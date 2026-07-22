package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.action.CommandTemplate;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.api.MessageSource;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.detector.SimilarityCalculator;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.NormalizationFlag;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.normalize.TextNormalizer;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.persistence.PersistentSnapshot;
import com.maxello1.chatautomod.core.score.ScoreMath;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.maxello1.chatautomod.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreHardeningTest {
    private static final Instant NOW = Instant.parse("2026-07-22T22:00:00Z");

    @Test
    void rejectsUnsafeConfigurationAndKeepsReloadAtomic() {
        String zeroWidth = Character.toString(0x200B);
        JsonObject root = disabledRulesJson();
        JsonArray filters = new JsonArray();
        filters.add(filter("spam.rapid", "WORD", "word"));
        filters.add(filter("filter.empty", "SUBSTRING", zeroWidth));
        root.add("filters", filters);
        JsonObject similarity = rule(root, "similarity_spam");
        similarity.addProperty("history_size", 257);
        similarity.addProperty("maximum_processed_length", 4097);
        similarity.addProperty("minimum_length", 4098);
        root.getAsJsonObject("mutes").addProperty("maximum_duration", "3651d");

        var loaded = new ConfigLoader().load(root.toString());
        assertFalse(loaded.valid());
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.message().contains("built-in rule ID")));
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.message().contains("normalize to an empty")));
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.path().endsWith("history_size")));
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.path().endsWith("maximum_processed_length")));
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.message().contains("maximum_processed_length")));
        assertTrue(loaded.problems().stream().anyMatch(problem -> problem.path().endsWith("maximum_duration")));

        ActiveConfig active = new ActiveConfig();
        CompiledAutoModConfig before = active.current();
        assertFalse(active.reload(root.toString()).applied());
        assertSame(before, active.current());

        JsonObject smaller = disabledRulesJson();
        smaller.getAsJsonObject("state").addProperty("maximum_tracked_players", 1);
        smaller.getAsJsonObject("state").addProperty("maximum_score_entries_per_player", 1);
        assertFalse(active.reload(smaller.toString(), 2, 0).applied());
        assertTrue(active.reload(smaller.toString(), 2, 0).problems().stream()
                .anyMatch(problem -> problem.path().endsWith("maximum_tracked_players")));
        assertFalse(active.reload(smaller.toString(), 0, 2).applied());
        assertTrue(active.reload(smaller.toString(), 0, 2).problems().stream()
                .anyMatch(problem -> problem.path().endsWith("maximum_score_entries_per_player")));

        String lineSeparator = Character.toString(0x2028);
        assertThrows(IllegalArgumentException.class, () -> CommandTemplate.compile("say hello" + lineSeparator + "stop"));
        assertDoesNotThrow(() -> CommandTemplate.compile("say hello world"));
    }

    @Test
    void pruningAndCapacityPreserveOnlyCurrentDurableState() {
        InMemoryPlayerStateStore runtimeOnly = new InMemoryPlayerStateStore();
        UUID staleId = new UUID(0, 1);
        runtimeOnly.restore(List.of(new PlayerModerationState(0, staleId, "Stale",
                List.of(NOW.minusSeconds(7200)), List.of(new RecentMessage("old", "old", NOW.minusSeconds(7200))),
                List.of(), Optional.empty(), List.of(), Optional.of(NOW.minusSeconds(7200)), NOW.minusSeconds(7200))));
        runtimeOnly.pruneInactive(NOW, Duration.ofHours(1), Duration.ofDays(30), 8);
        assertTrue(runtimeOnly.snapshots().isEmpty());

        InMemoryPlayerStateStore durable = new InMemoryPlayerStateStore();
        UUID durableId = new UUID(0, 2);
        ScoreEntry activeScore = new ScoreEntry(5, "test.active", NOW.minusSeconds(10), NOW.plusSeconds(3600));
        durable.restore(List.of(new PlayerModerationState(0, durableId, "Durable",
                List.of(NOW.minusSeconds(7200)), List.of(new RecentMessage("old", "old", NOW.minusSeconds(7200))),
                List.of(activeScore), Optional.of(new MuteState(NOW.plusSeconds(1800), "Muted", "test")),
                List.of(), Optional.of(NOW.minusSeconds(7200)), NOW.minusSeconds(7200))));
        durable.pruneInactive(NOW, Duration.ofHours(1), Duration.ofDays(30), 8);
        PlayerModerationState cleaned = durable.snapshots().iterator().next();
        assertTrue(cleaned.recentMessageTimes().isEmpty());
        assertTrue(cleaned.recentMessages().isEmpty());
        assertTrue(cleaned.lastMuteNotificationAt().isEmpty());
        assertEquals(List.of(activeScore), cleaned.scoreEntries());
        assertTrue(cleaned.mute().isPresent());

        InMemoryPlayerStateStore expiredHistory = new InMemoryPlayerStateStore();
        UUID expiredId = new UUID(0, 3);
        expiredHistory.restore(List.of(new PlayerModerationState(0, expiredId, "Expired", List.of(), List.of(),
                List.of(), Optional.empty(), List.of(violation(expiredId, NOW.minus(Duration.ofDays(31)), Optional.empty())),
                Optional.empty(), NOW.minus(Duration.ofDays(31)))));
        expiredHistory.pruneInactive(NOW, Duration.ofHours(1), Duration.ofDays(30), 8);
        assertTrue(expiredHistory.snapshots().isEmpty());

        int capacity = 8;
        InMemoryPlayerStateStore full = new InMemoryPlayerStateStore();
        List<PlayerModerationState> occupied = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            UUID id = new UUID(1, i + 1);
            occupied.add(new PlayerModerationState(0, id, "Player" + i, List.of(), List.of(),
                    List.of(new ScoreEntry(1, "test.capacity", NOW.minusSeconds(1), NOW.plusSeconds(3600))),
                    Optional.empty(), List.of(), Optional.empty(), NOW));
        }
        full.restore(occupied);
        CompiledAutoModConfig capped = config(json ->
                json.getAsJsonObject("state").addProperty("maximum_tracked_players", capacity));
        ModerationService service = new ModerationService(new ActiveConfig(capped, new ConfigLoader()), full);
        UUID newcomer = new UUID(2, 1);
        MessageContext incoming = new MessageContext(newcomer, "Newcomer", "hello", MessageSource.PUBLIC_CHAT, "", NOW, false);
        var blocked = service.evaluateLive(incoming, BypassProfile.NONE);
        assertEquals(MessageDecision.BLOCK, blocked.report().decision());
        assertEquals(0, blocked.report().pointsAdded());
        assertTrue(blocked.report().matches().stream().anyMatch(match -> match.ruleId().equals("security.state_capacity")));
        ModerationAction.NotifyStaff alert = (ModerationAction.NotifyStaff) blocked.actionPlan().actions().get(0);
        assertEquals(MessageDecision.BLOCK, alert.decision());
        assertEquals(capacity, full.snapshots().size());
        assertFalse(full.contains(newcomer));

        UUID bypassedId = new UUID(2, 2);
        MessageContext bypassed = new MessageContext(bypassedId, "Bypassed", "hello", MessageSource.PUBLIC_CHAT, "", NOW, false);
        assertEquals(MessageDecision.ALLOW, service.evaluateLive(bypassed, BypassProfile.ALL).report().decision());
        assertFalse(full.contains(bypassedId));

        CompiledAutoModConfig disabled = config(json -> {
            json.addProperty("enabled", false);
            json.getAsJsonObject("state").addProperty("maximum_tracked_players", capacity);
        });
        UUID disabledId = new UUID(2, 3);
        MessageContext disabledMessage = new MessageContext(disabledId, "Disabled", "hello", MessageSource.PUBLIC_CHAT, "", NOW, false);
        assertEquals(MessageDecision.ALLOW,
                new ModerationService(new ActiveConfig(disabled, new ConfigLoader()), full)
                        .evaluateLive(disabledMessage, BypassProfile.NONE).report().decision());
        assertFalse(full.contains(disabledId));

        MuteService mutes = new MuteService(full, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(MuteService.MuteCapacityException.class, () -> mutes.mute(new UUID(2, 4), "Muted",
                Duration.ofMinutes(1), capped.mutes().maximumDuration(), "Reason", "manual", capacity));
        assertThrows(IllegalArgumentException.class, () -> new MuteService(new InMemoryPlayerStateStore(),
                Clock.fixed(Instant.MAX.minusSeconds(1), ZoneOffset.UTC)).mute(new UUID(2, 5), "Boundary",
                Duration.ofSeconds(2), Duration.ofDays(3650), "Reason", "manual", 1));
    }

    @Test
    void advertisingUsesRecognizedDomainsWithoutFileOrVersionFalsePositives() {
        CompiledAutoModConfig advertising = config(root -> rule(root, "advertising").addProperty("enabled", true));
        for (String value : List.of("config.json", "Example.java", "version 1.2.3", "version 1.2.3.4.5", "decimal 3.14")) {
            assertTrue(preview(advertising, value, NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW))
                    .report().matches().isEmpty(), value);
        }
        for (String value : List.of("example.ch", "example.jp", "example.se", "example.lol",
                "play.subdomain.example.de", "xn--e1afmkfd.xn--p1ai")) {
            assertTrue(preview(advertising, value, NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW))
                    .report().matches().stream().anyMatch(match -> match.points() > 0), value);
        }
    }

    @Test
    void malformedUnicodeIsRemovedAndStoredMessagesStaySingleLine() {
        TextNormalizer normalizer = new TextNormalizer(new ActiveConfig().current().normalization());
        String malformed = "ok" + new String(new char[]{(char) 0xD800}) + "bad";
        var normalized = assertDoesNotThrow(() -> normalizer.normalize(malformed));
        assertEquals("okbad", normalized.canonical());
        assertTrue(normalized.flags().contains(NormalizationFlag.CONTROL_REMOVED));

        String lineSeparator = Character.toString(0x2028);
        String paragraphSeparator = Character.toString(0x2029);
        CompiledAutoModConfig storing = config(root -> {
            JsonObject caps = rule(root, "caps");
            caps.addProperty("enabled", true);
            caps.addProperty("minimum_letters", 4);
            JsonArray actions = new JsonArray();
            actions.add("NOTIFY_STAFF");
            caps.add("actions", actions);
            root.getAsJsonObject("logging").addProperty("store_original_messages", true);
        });
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        var evaluation = new ModerationService(new ActiveConfig(storing, new ConfigLoader()), states)
                .evaluateLive(context("LOUD" + lineSeparator + "MESSAGE" + paragraphSeparator + "NOW", NOW), BypassProfile.NONE);
        assertEquals(MessageDecision.ALLOW, evaluation.report().decision());
        ModerationAction.NotifyStaff alert = evaluation.actionPlan().actions().stream()
                .filter(ModerationAction.NotifyStaff.class::isInstance)
                .map(ModerationAction.NotifyStaff.class::cast).findFirst().orElseThrow();
        assertEquals(MessageDecision.ALLOW, alert.decision());
        assertEquals("LOUD MESSAGE NOW", states.snapshots().iterator().next().violations().get(0).originalMessage().orElseThrow());
    }

    @Test
    void persistenceBoundsRoundTripAndScoreTotalsSaturate() {
        CompiledAutoModConfig bounded = config(root -> {
            root.getAsJsonObject("state").addProperty("maximum_tracked_players", 1);
            root.getAsJsonObject("state").addProperty("maximum_score_entries_per_player", 2);
            root.getAsJsonObject("history").addProperty("maximum_entries_per_player", 2);
            root.getAsJsonObject("logging").addProperty("store_original_messages", true);
        });
        ScoreEntry maximum = new ScoreEntry(Integer.MAX_VALUE, "test.maximum", NOW.minusSeconds(2), NOW.plusSeconds(3600));
        ScoreEntry one = new ScoreEntry(1, "test.one", NOW.minusSeconds(1), NOW.plusSeconds(3600));
        ViolationRecord first = violation(PLAYER, NOW.minusSeconds(2), Optional.of("first"));
        ViolationRecord second = violation(PLAYER, NOW.minusSeconds(1), Optional.of("second"));
        PlayerModerationState atBounds = new PlayerModerationState(0, PLAYER, "Tester", List.of(), List.of(),
                List.of(maximum, one), Optional.empty(), List.of(first, second), Optional.empty(), NOW);

        PersistenceCodec codec = new PersistenceCodec();
        String encoded = codec.encode(List.of(atBounds), NOW, bounded);
        var decoded = codec.decode(encoded, NOW, bounded);
        assertTrue(decoded.valid(), () -> decoded.problems().toString());
        assertEquals(2, decoded.states().get(0).scoreEntries().size());
        assertEquals(2, decoded.states().get(0).violations().size());
        assertEquals(Integer.MAX_VALUE, ScoreMath.sumActive(decoded.states().get(0).scoreEntries(), NOW));

        PlayerModerationState tooManyScores = new PlayerModerationState(0, PLAYER, "Tester", List.of(), List.of(),
                List.of(maximum, one, new ScoreEntry(1, "test.extra", NOW, NOW.plusSeconds(3600))),
                Optional.empty(), List.of(), Optional.empty(), NOW);
        assertThrows(IllegalStateException.class, () -> codec.encode(List.of(tooManyScores), NOW, bounded));

        List<PersistentSnapshot.PersistentViolation> threeViolations = List.of(
                persistentViolation(1), persistentViolation(2), persistentViolation(3));
        PersistentSnapshot oversizedHistory = new PersistentSnapshot(PersistenceCodec.SCHEMA_VERSION, NOW.toString(),
                List.of(new PersistentSnapshot.PersistentPlayer(PLAYER.toString(), "Tester", null, List.of(), threeViolations)));
        assertFalse(codec.decode(codec.encode(oversizedHistory), NOW, bounded).valid());

        String longOriginal = "x".repeat(1_100_000);
        PlayerModerationState largeValid = new PlayerModerationState(0, PLAYER, "Tester", List.of(), List.of(),
                List.of(), Optional.empty(), List.of(violation(PLAYER, NOW, Optional.of(longOriginal))),
                Optional.empty(), NOW);
        String largeJson = codec.encode(List.of(largeValid), NOW, bounded);
        assertTrue(largeJson.length() > 1_048_576);
        assertTrue(codec.decode(largeJson, NOW, bounded).valid());
    }

    @Test
    void similarityRequiresAReasonableLengthRatioBeforeContainment() {
        SimilarityCalculator calculator = new SimilarityCalculator();
        assertTrue(calculator.similar("join my server", "join my server please", 0.85));
        assertFalse(calculator.similar("join my server",
                "join my server followed by a completely unrelated and substantially longer paragraph", 0.85));
    }

    @Test
    void pacedDeobfuscationEquivalentMessagesRemainSimilaritySpam() {
        CompiledAutoModConfig compiled = config(root -> {
            rule(root, "duplicate_spam").addProperty("enabled", true);
            rule(root, "similarity_spam").addProperty("enabled", true);
        });
        PlayerModerationState prior = new PlayerModerationState(0, PLAYER, "Tester", List.of(),
                List.of(new RecentMessage("please join now", "please join now", NOW.minusSeconds(10))),
                List.of(), Optional.empty(), List.of(), Optional.empty(), NOW.minusSeconds(10));

        var report = preview(compiled, "please j0in now", NOW, prior).report();
        assertTrue(report.matches().stream().anyMatch(match -> match.ruleId().equals("spam.similarity")));
        assertFalse(report.matches().stream().anyMatch(match -> match.ruleId().equals("spam.duplicate")));
    }

    @Test
    void restoredNameOnlyStateYieldsCapacityToANewPlayer() {
        UUID restoredId = new UUID(9, 1);
        UUID newcomerId = new UUID(9, 2);
        CompiledAutoModConfig capped = config(root ->
                root.getAsJsonObject("state").addProperty("maximum_tracked_players", 1));
        PlayerModerationState nameOnly = PlayerModerationState.empty(restoredId, "Remembered",
                NOW.minus(Duration.ofDays(1)));
        PersistenceCodec codec = new PersistenceCodec();
        String json = codec.encode(List.of(nameOnly), NOW.minusSeconds(1), capped);
        var decoded = codec.decode(json, NOW, capped);
        assertTrue(decoded.valid(), () -> decoded.problems().toString());

        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        states.restore(decoded.states());
        MessageContext incoming = new MessageContext(newcomerId, "Newcomer", "hello",
                MessageSource.PUBLIC_CHAT, "", NOW.plusSeconds(1), false);
        var result = new ModerationService(new ActiveConfig(capped, new ConfigLoader()), states)
                .evaluateLive(incoming, BypassProfile.NONE);

        assertEquals(MessageDecision.ALLOW, result.report().decision());
        assertFalse(states.contains(restoredId));
        assertTrue(states.contains(newcomerId));
        assertEquals(1, states.snapshots().size());
    }

    @Test
    void rejectsStringEncodedBooleanAndNumberScalarsAtTheirPaths() {
        JsonObject root = disabledRulesJson();
        root.addProperty("schema_version", "1");
        root.addProperty("enabled", "false");
        root.getAsJsonObject("logging").addProperty("store_original_messages", "true");
        rule(root, "rapid_spam").addProperty("points", "5");
        root.getAsJsonObject("state").addProperty("maximum_tracked_players", "10");

        var result = new ConfigLoader().load(root.toString());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.schema_version") && problem.message().contains("JSON number")));
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.enabled") && problem.message().contains("JSON boolean")));
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.logging.store_original_messages")
                        && problem.message().contains("JSON boolean")));
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.rules.rapid_spam.points") && problem.message().contains("JSON number")));
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.state.maximum_tracked_players") && problem.message().contains("JSON number")));
    }

    private JsonObject filter(String id, String mode, String term) {
        JsonObject filter = new JsonObject();
        filter.addProperty("id", id);
        filter.addProperty("match_mode", mode);
        JsonArray terms = new JsonArray();
        terms.add(term);
        filter.add("terms", terms);
        filter.add("exceptions", new JsonArray());
        filter.add("actions", new JsonArray());
        return filter;
    }

    private ViolationRecord violation(UUID playerId, Instant timestamp, Optional<String> original) {
        return new ViolationRecord(UUID.nameUUIDFromBytes(("event-" + timestamp).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                timestamp, playerId, "Tester", List.of("test.rule"), MessageDecision.BLOCK, 1, 1,
                List.of(ActionType.WARN), original);
    }

    private PersistentSnapshot.PersistentViolation persistentViolation(int index) {
        return new PersistentSnapshot.PersistentViolation(new UUID(3, index).toString(), NOW.toString(),
                PLAYER.toString(), "Tester", List.of("test.rule"), MessageDecision.BLOCK.name(), 1, 1,
                List.of(ActionType.WARN.name()), null);
    }
}
