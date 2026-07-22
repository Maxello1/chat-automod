package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageSource;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.score.ScoreCalculator;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.maxello1.chatautomod.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class RequiredEdgeCasesTest {
    private static final Instant NOW = Instant.parse("2026-07-22T21:00:00Z");

    @Test
    void spamWindowsShortMessagesAndPlayersStayIsolated() {
        CompiledAutoModConfig rapid = config(root -> rule(root, "rapid_spam").addProperty("enabled", true));
        assertTrue(preview(rapid, "normal conversation", NOW, empty()).report().matches().isEmpty());
        PlayerModerationState oldTime = state(List.of(NOW.minusSeconds(30)), List.of());
        assertTrue(preview(rapid, "still normal", NOW, oldTime).report().matches().isEmpty());

        InMemoryPlayerStateStore store = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(new ActiveConfig(rapid, new ConfigLoader()), store);
        service.evaluateLive(contextFor(PLAYER, "first", NOW), BypassProfile.NONE);
        UUID other = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertTrue(service.evaluateLive(contextFor(other, "first", NOW.plusMillis(100)), BypassProfile.NONE)
                .report().matches().isEmpty());

        CompiledAutoModConfig duplicate = config(root -> rule(root, "duplicate_spam").addProperty("enabled", true));
        PlayerModerationState expiredDuplicate = state(List.of(), List.of(new RecentMessage("hello", "hello", NOW.minusSeconds(46))));
        assertTrue(preview(duplicate, "hello", NOW, expiredDuplicate).report().matches().isEmpty());
        PlayerModerationState shortDuplicate = state(List.of(), List.of(new RecentMessage("ok", "ok", NOW.minusSeconds(1))));
        assertTrue(preview(duplicate, "ok", NOW, shortDuplicate).report().matches().isEmpty());

        CompiledAutoModConfig both = config(root -> {
            rule(root, "duplicate_spam").addProperty("enabled", true);
            rule(root, "similarity_spam").addProperty("enabled", true);
        });
        PlayerModerationState exact = state(List.of(), List.of(new RecentMessage("repeat this", "repeat this", NOW.minusSeconds(1))));
        var exactMatches = preview(both, "repeat this", NOW, exact).report().matches();
        assertTrue(exactMatches.stream().anyMatch(match -> match.ruleId().equals("spam.duplicate")));
        assertFalse(exactMatches.stream().anyMatch(match -> match.ruleId().equals("spam.similarity")));
        PlayerModerationState shortSimilar = state(List.of(), List.of(new RecentMessage("ok", "ok!", NOW.minusSeconds(1))));
        assertTrue(preview(both, "ok?", NOW, shortSimilar).report().matches().isEmpty());
    }

    @Test
    void filterModesBoundariesExceptionsAndDeduplicationWork() {
        CompiledAutoModConfig filters = config(root -> {
            JsonArray values = new JsonArray();
            values.add(filter("filter.word", "WORD", "cat", null, 2));
            values.add(filter("filter.phrase", "PHRASE", "bad phrase", null, 1));
            values.add(filter("filter.substring", "SUBSTRING", "evil", null, 1));
            values.add(filter("filter.exception", "WORD", "bad", "not bad", 1));
            root.add("filters", values);
        });
        assertTrue(preview(filters, "concatenate", NOW, empty()).report().matches().isEmpty());
        var repeated = preview(filters, "CAT cat", NOW, empty()).report();
        assertEquals(List.of("filter.word"), repeated.matches().stream().map(match -> match.ruleId()).toList());
        assertEquals(2, repeated.pointsAdded());
        assertTrue(preview(filters, "that BAD PHRASE here", NOW, empty()).report().matches().stream()
                .anyMatch(match -> match.ruleId().equals("filter.phrase")));
        assertTrue(preview(filters, "devilish", NOW, empty()).report().matches().stream()
                .anyMatch(match -> match.ruleId().equals("filter.substring")));
        assertFalse(preview(filters, "not bad", NOW, empty()).report().matches().stream()
                .anyMatch(match -> match.ruleId().equals("filter.exception")));
    }

    @Test
    void codePointLimitsAndSupplementaryRunsAreCountedCorrectly() {
        CompiledAutoModConfig length = config(root -> {
            JsonObject rule = rule(root, "message_length"); rule.addProperty("enabled", true); rule.addProperty("maximum_length", 1);
        });
        assertTrue(preview(length, "😀", NOW, empty()).report().matches().isEmpty());
        assertFalse(preview(length, "😀😀", NOW, empty()).report().matches().isEmpty());

        CompiledAutoModConfig repeated = config(root -> {
            JsonObject rule = rule(root, "repeated_characters"); rule.addProperty("enabled", true);
            rule.addProperty("maximum_repeated_letters", 2); rule.addProperty("maximum_repeated_symbols", 2);
        });
        assertTrue(preview(repeated, "😀😀", NOW, empty()).report().matches().isEmpty());
        assertFalse(preview(repeated, "😀😀😀", NOW, empty()).report().matches().isEmpty());
    }

    @Test
    void validatesPortsPunycodeExactExpiryAndBypasses() {
        CompiledAutoModConfig advertising = config(root -> rule(root, "advertising").addProperty("enabled", true));
        assertFalse(preview(advertising, "8.8.8.8:65535", NOW, empty()).report().matches().isEmpty());
        assertTrue(preview(advertising, "8.8.8.8:0", NOW, empty()).report().matches().isEmpty());
        assertTrue(preview(advertising, "8.8.8.8:65536", NOW, empty()).report().matches().isEmpty());
        assertFalse(preview(advertising, "xn--e1afmkfd.xn--p1ai", NOW, empty()).report().matches().isEmpty());

        CompiledAutoModConfig defaults = new ActiveConfig().current();
        ScoreEntry expired = new ScoreEntry(3, "test", NOW.minusSeconds(60), NOW);
        var score = new ScoreCalculator().calculate(NOW, List.of(expired), List.of(), defaults.score(), 10);
        assertEquals(0, score.pointsBefore());

        CompiledAutoModConfig caps = config(root -> {
            JsonObject rule = rule(root, "caps"); rule.addProperty("enabled", true); rule.addProperty("minimum_letters", 4);
        });
        ModerationService service = new ModerationService(new ActiveConfig(caps, new ConfigLoader()), new InMemoryPlayerStateStore());
        assertTrue(service.preview(context("LOUD TEXT", NOW), empty(),
                new BypassProfile(false, Set.of(RuleCategory.FLOODING))).report().matches().isEmpty());
        assertTrue(service.preview(context("LOUD TEXT", NOW), empty(), BypassProfile.ALL).report().matches().isEmpty());
    }

    @Test
    void muteCooldownBlocksWithoutAddingPoints() {
        CompiledAutoModConfig compiled = config(root -> { });
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        new MuteService(states, Clock.fixed(NOW, ZoneOffset.UTC)).mute(PLAYER, "Tester", Duration.ofMinutes(5),
                compiled.mutes().maximumDuration(), "Muted", "manual", compiled.state().maximumTrackedPlayers());
        ModerationService service = new ModerationService(new ActiveConfig(compiled, new ConfigLoader()), states);
        var first = service.evaluateLive(context("message", NOW.plusSeconds(1)), BypassProfile.NONE);
        var second = service.evaluateLive(context("message", NOW.plusSeconds(2)), BypassProfile.NONE);
        assertEquals(0, first.report().pointsAdded());
        assertEquals(List.of(ActionType.WARN), first.actionPlan().actions().stream().map(action -> action.type()).toList());
        assertEquals(0, second.report().pointsAdded());
        assertTrue(second.actionPlan().actions().isEmpty());
    }

    @Test
    void configAndPersistenceFailuresAreReportedAndPrivacyCanBeTightened() {
        JsonObject invalid = defaultJsonObject();
        rule(invalid, "caps").addProperty("points", -1);
        invalid.getAsJsonObject("mutes").addProperty("maximum_duration", "0d");
        JsonArray thresholds = new JsonArray(); thresholds.add(threshold(2)); thresholds.add(threshold(2));
        invalid.getAsJsonObject("score").add("thresholds", thresholds);
        var invalidResult = new ConfigLoader().load(invalid.toString());
        assertFalse(invalidResult.valid());
        assertTrue(invalidResult.problems().stream().anyMatch(problem -> problem.path().contains("points")));
        assertTrue(invalidResult.problems().stream().anyMatch(problem -> problem.path().contains("maximum_duration")));
        assertTrue(invalidResult.problems().stream().anyMatch(problem -> problem.message().contains("strictly increasing")));

        CompiledAutoModConfig storing = config(root -> {
            JsonObject caps = rule(root, "caps"); caps.addProperty("enabled", true); caps.addProperty("minimum_letters", 4);
            root.getAsJsonObject("logging").addProperty("store_original_messages", true);
        });
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        new ModerationService(new ActiveConfig(storing, new ConfigLoader()), states)
                .evaluateLive(context("PRIVATE LOUD MESSAGE", NOW), BypassProfile.NONE);
        CompiledAutoModConfig privateNow = config(root -> root.getAsJsonObject("logging").addProperty("store_original_messages", false));
        PersistenceCodec codec = new PersistenceCodec();
        String json = codec.encode(states.snapshots(), NOW.plusSeconds(1), privateNow);
        assertFalse(json.contains("PRIVATE LOUD MESSAGE"));
        var loaded = codec.decode(json, NOW.plusSeconds(1), privateNow);
        assertTrue(loaded.valid());
        assertTrue(loaded.states().get(0).violations().get(0).originalMessage().isEmpty());
        assertFalse(codec.decode("{", NOW, privateNow).valid());
        assertFalse(codec.decode("{\"schemaVersion\":1,\"savedAt\":\"2026-07-22T00:00:00Z\",\"players\":null}", NOW, privateNow).valid());
    }

    private PlayerModerationState empty() {
        return PlayerModerationState.empty(PLAYER, "Tester", NOW);
    }

    private PlayerModerationState state(List<Instant> times, List<RecentMessage> messages) {
        return new PlayerModerationState(0, PLAYER, "Tester", times, messages, List.of(), Optional.empty(),
                List.of(), Optional.empty(), NOW.minusSeconds(1));
    }

    private MessageContext contextFor(UUID id, String message, Instant timestamp) {
        return new MessageContext(id, "Tester", message, MessageSource.PUBLIC_CHAT, "", timestamp, false);
    }

    private JsonObject filter(String id, String mode, String term, String exception, int points) {
        JsonObject filter = new JsonObject(); filter.addProperty("id", id); filter.addProperty("match_mode", mode);
        filter.addProperty("points", points); JsonArray terms = new JsonArray(); terms.add(term); filter.add("terms", terms);
        JsonArray exceptions = new JsonArray(); if (exception != null) exceptions.add(exception); filter.add("exceptions", exceptions);
        JsonArray actions = new JsonArray(); actions.add("BLOCK"); filter.add("actions", actions); return filter;
    }

    private JsonObject threshold(int points) {
        JsonObject threshold = new JsonObject(); threshold.addProperty("points", points); threshold.add("actions", new JsonArray());
        return threshold;
    }
}
