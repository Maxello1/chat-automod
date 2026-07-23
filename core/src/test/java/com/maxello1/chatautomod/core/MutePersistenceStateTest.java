package com.maxello1.chatautomod.core;

import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.history.HistoryService;
import com.maxello1.chatautomod.core.model.MuteKind;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.Severity;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import com.maxello1.chatautomod.core.state.StateClearScope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MutePersistenceStateTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODERATOR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void permanentMuteRemainsActiveAndCannotBeDowngradedByTemporaryMute() {
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        MuteService service = new MuteService(states, Clock.fixed(NOW, ZoneOffset.UTC));

        MuteState permanent = service.mutePermanent(PLAYER, "Tester", "Severe violation", "manual",
                "filter.severe", MODERATOR, 100);
        assertEquals(MuteKind.PERMANENT, permanent.kind());
        assertEquals(NOW, permanent.mutedAt());
        assertNull(permanent.mutedUntil());
        assertEquals("filter.severe", permanent.ruleId());
        assertEquals(MODERATOR, permanent.moderatorId());
        assertTrue(permanent.isActiveAt(Instant.MAX));

        MuteState unchanged = service.muteTemporary(PLAYER, "Tester", Duration.ofMinutes(5),
                Duration.ofDays(30), "Shorter mute", "manual", "", MODERATOR, 100);
        assertSame(permanent, unchanged);
        assertEquals(MuteKind.PERMANENT, service.activeMute(PLAYER, "Tester").orElseThrow().kind());

        assertTrue(service.unmute(PLAYER, "Tester"));
        assertTrue(service.activeMute(PLAYER, "Tester").isEmpty());

        MuteState temporary = service.muteTemporary(PLAYER, "Tester", Duration.ofMinutes(5),
                Duration.ofDays(30), "Temporary", "manual", "rule.temp", MODERATOR, 100);
        assertEquals(MuteKind.TEMPORARY, temporary.kind());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), temporary.mutedUntil());
        assertFalse(temporary.isActiveAt(temporary.mutedUntil()));
    }

    @Test
    void schemaTwoRoundTripsPermanentMutesAndMigratesSchemaOneTemporaryMutes() {
        var config = new ActiveConfig().current();
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        MuteService service = new MuteService(states, Clock.fixed(NOW, ZoneOffset.UTC));
        service.mutePermanent(PLAYER, "Tester", "Permanent", "manual", "rule.severe", MODERATOR, 100);

        PersistenceCodec codec = new PersistenceCodec(2);
        String encoded = codec.encode(states.snapshots(), NOW, config);
        assertTrue(encoded.contains("\"schema_version\": 2"));
        assertTrue(encoded.contains("\"kind\": \"PERMANENT\""));

        var permanentLoad = codec.decode(encoded, NOW.plus(Duration.ofDays(3650)), config);
        assertTrue(permanentLoad.valid(), () -> permanentLoad.problems().toString());
        MuteState restoredPermanent = permanentLoad.states().get(0).mute().orElseThrow();
        assertEquals(MuteKind.PERMANENT, restoredPermanent.kind());
        assertNull(restoredPermanent.mutedUntil());
        assertEquals(MODERATOR, restoredPermanent.moderatorId());

        String legacy = """
                {
                  "schemaVersion": 1,
                  "savedAt": "2026-07-23T12:00:00Z",
                  "players": [{
                    "playerUuid": "11111111-1111-1111-1111-111111111111",
                    "lastKnownName": "Tester",
                    "mute": {
                      "mutedUntil": "2026-07-23T12:30:00Z",
                      "reason": "Legacy mute",
                      "source": "manual"
                    },
                    "scoreEntries": [],
                    "violations": []
                  }]
                }
                """;
        var legacyLoad = codec.decode(legacy, NOW.plusSeconds(1), config);
        assertTrue(legacyLoad.valid(), () -> legacyLoad.problems().toString());
        MuteState migrated = legacyLoad.states().get(0).mute().orElseThrow();
        assertEquals(MuteKind.TEMPORARY, migrated.kind());
        assertEquals(NOW, migrated.mutedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), migrated.mutedUntil());
        assertTrue(codec.encode(legacyLoad.states(), NOW.plusSeconds(1), config)
                .contains("\"kind\": \"TEMPORARY\""));
    }

    @Test
    void persistenceKeepsCrossedThresholdsAndScopedClearsNeverRemoveMute() {
        var config = new ActiveConfig().current();
        MuteState mute = MuteState.permanent(NOW, "Permanent", "manual", "rule.severe", MODERATOR);
        ScoreEntry score = new ScoreEntry(5, "rule.score", NOW, NOW.plus(Duration.ofHours(1)));
        ViolationRecord violation = new ViolationRecord(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                NOW, PLAYER, "Tester", List.of("rule.score"), List.of(RuleCategory.SECURITY), Severity.SEVERE,
                MessageDecision.BLOCK, 5, 5, List.of(ActionType.MUTE), Optional.of(MuteKind.PERMANENT),
                Optional.empty());
        PlayerModerationState populated = new PlayerModerationState(0, PLAYER, "Tester",
                List.of(NOW), List.of(new RecentMessage("message", "message", NOW)),
                List.of(score), Set.of(5, 10), Optional.of(mute), List.of(violation), Optional.empty(), NOW);

        PersistenceCodec codec = new PersistenceCodec();
        var loaded = codec.decode(codec.encode(List.of(populated), NOW, config), NOW.plusSeconds(1), config);
        assertTrue(loaded.valid(), () -> loaded.problems().toString());
        assertEquals(Set.of(5, 10), loaded.states().get(0).crossedThresholds());
        ViolationRecord restoredViolation = loaded.states().get(0).violations().get(0);
        assertEquals(List.of(RuleCategory.SECURITY), restoredViolation.categories());
        assertEquals(Severity.SEVERE, restoredViolation.severity());
        assertEquals(Optional.of(MuteKind.PERMANENT), restoredViolation.muteKind());

        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        HistoryService history = new HistoryService(states, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
        states.restore(List.of(populated));

        assertTrue(history.clear(PLAYER, "Tester", StateClearScope.SCORE));
        PlayerModerationState afterScore = states.snapshot(PLAYER, "Tester", NOW);
        assertTrue(afterScore.scoreEntries().isEmpty());
        assertTrue(afterScore.crossedThresholds().isEmpty());
        assertFalse(afterScore.violations().isEmpty());
        assertFalse(afterScore.recentMessages().isEmpty());
        assertEquals(MuteKind.PERMANENT, afterScore.mute().orElseThrow().kind());

        assertTrue(history.clearHistory(PLAYER, "Tester"));
        assertTrue(states.snapshot(PLAYER, "Tester", NOW).violations().isEmpty());
        assertTrue(history.clearSpam(PLAYER, "Tester"));
        assertTrue(states.snapshot(PLAYER, "Tester", NOW).recentMessages().isEmpty());
        assertEquals(MuteKind.PERMANENT, states.snapshot(PLAYER, "Tester", NOW).mute().orElseThrow().kind());

        states.restore(List.of(populated));
        assertTrue(history.clearAll(PLAYER, "Tester"));
        PlayerModerationState afterAll = states.snapshot(PLAYER, "Tester", NOW);
        assertTrue(afterAll.scoreEntries().isEmpty());
        assertTrue(afterAll.crossedThresholds().isEmpty());
        assertTrue(afterAll.violations().isEmpty());
        assertTrue(afterAll.recentMessageTimes().isEmpty());
        assertTrue(afterAll.recentMessages().isEmpty());
        assertTrue(afterAll.mute().isPresent());
    }

    @Test
    void muteKindAndEndTimeInvariantsAreEnforced() {
        assertThrows(NullPointerException.class,
                () -> new MuteState(MuteKind.TEMPORARY, NOW, null, "reason", "manual", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MuteState(MuteKind.PERMANENT, NOW, NOW.plusSeconds(1), "reason", "manual", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MuteState(MuteKind.TEMPORARY, NOW, NOW, "reason", "manual", "", null));
    }
}
