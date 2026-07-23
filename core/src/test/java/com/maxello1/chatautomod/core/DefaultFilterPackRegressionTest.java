package com.maxello1.chatautomod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.model.MuteKind;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.maxello1.chatautomod.core.CoreTestSupport.PLAYER;
import static com.maxello1.chatautomod.core.CoreTestSupport.context;
import static com.maxello1.chatautomod.core.CoreTestSupport.preview;
import static org.junit.jupiter.api.Assertions.*;

class DefaultFilterPackRegressionTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final CompiledAutoModConfig DEFAULTS = defaultBundle();

    @Test
    void everyDefaultTermHandlesCaseSeparatorsSpacingAndRepeatedCharacters() {
        for (DefaultTerm entry : defaultTerms()) {
            for (String message : List.of(
                    entry.term(),
                    entry.term().toUpperCase(Locale.ROOT),
                    entry.term() + "!",
                    spaced(entry.term()),
                    repeatFirst(entry.term()))) {
                assertRule(message, entry.ruleId());
            }
        }
    }

    @Test
    void severeMatcherKeepsRequiredFalsePositivesAndIdentityPhrasesAllowed() {
        for (String allowed : List.of(
                "night", "nights", "bigger", "trigger", "Nigeria",
                "Nigerian", "Niger", "assignment", "signal", "original",
                "I am gay", "gay rights", "gay marriage", "the character is gay")) {
            var report = preview(DEFAULTS, allowed, NOW,
                    PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
            assertTrue(report.matches().stream().noneMatch(match ->
                            match.category() == RuleCategory.RACISM
                                    || match.category() == RuleCategory.IDENTITY_HARASSMENT),
                    () -> "unexpected filtered-content match for: " + allowed
                            + " -> " + report.matches());
        }
    }

    @Test
    void severeDefaultsPermanentlyMuteWhileContextualDefaultsDoNot() {
        var severe = preview(DEFAULTS, "ni66a", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
        assertEquals(MessageDecision.BLOCK, severe.decision());
        assertEquals(20, severe.pointsAdded());
        List<ModerationAction.Mute> severeMutes = severe.actionsThatWouldRun().stream()
                .filter(ModerationAction.Mute.class::isInstance)
                .map(ModerationAction.Mute.class::cast)
                .toList();
        assertEquals(1, severeMutes.size());
        assertEquals(MuteKind.PERMANENT, severeMutes.getFirst().kind());

        var contextual = preview(DEFAULTS, "gypsy", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
        assertEquals(8, contextual.pointsAdded());
        assertTrue(contextual.actionsThatWouldRun().stream()
                .noneMatch(ModerationAction.Mute.class::isInstance));
    }

    @Test
    void exceptionsOnlyCoverTheMatchingOccurrence() {
        CompiledAutoModConfig config = CoreTestSupport.config(root -> {
            JsonObject filter = new JsonObject();
            filter.addProperty("id", "filter.bad");
            filter.addProperty("category", "FILTERED_CONTENT");
            filter.addProperty("severity", "MODERATE");
            filter.addProperty("match_mode", "NORMALIZED_WORD");
            filter.addProperty("points", 2);
            filter.add("terms", strings("bad"));
            filter.add("patterns", new JsonArray());
            filter.add("exceptions", strings("not bad"));
            filter.add("actions", strings("BLOCK"));
            JsonArray filters = new JsonArray();
            filters.add(filter);
            root.add("filters", filters);
        });

        var prevented = preview(config, "not bad", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
        assertTrue(prevented.matches().isEmpty());
        assertEquals("not bad", prevented.preventedMatches().getFirst().exception());

        var independent = preview(config, "not bad but bad", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
        assertTrue(independent.matches().stream()
                .anyMatch(match -> match.ruleId().equals("filter.bad")));
    }

    @Test
    void bundleFailuresStayAtomicAndLegacyFilterModesKeepTheirBehavior() {
        ActiveConfig active = new ActiveConfig();
        CompiledAutoModConfig before = active.current();
        JsonObject invalid = JsonParser.parseString(active.defaultJson()).getAsJsonObject();
        invalid.add("filters", new JsonObject());
        var failed = active.reloadBundle(new Gson().toJson(invalid),
                active.defaultFilterPacks(), active.defaultExceptionsJson(), 0, 0);
        assertFalse(failed.applied());
        assertSame(before, active.current());
        assertTrue(failed.problems().stream()
                .anyMatch(problem -> problem.path().contains("$.filters")));

        CompiledAutoModConfig legacy = CoreTestSupport.config(root -> {
            JsonObject filter = new JsonObject();
            filter.addProperty("id", "legacy.gag");
            filter.addProperty("match_mode", "WORD");
            filter.addProperty("points", 1);
            filter.add("terms", strings("gag"));
            filter.add("exceptions", new JsonArray());
            filter.add("actions", strings("BLOCK"));
            JsonArray filters = new JsonArray();
            filters.add(filter);
            root.add("filters", filters);
        });
        assertTrue(preview(legacy, "gag!", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report()
                .matches().stream().anyMatch(match -> match.ruleId().equals("legacy.gag")));
        assertTrue(preview(legacy, "6a6", NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report()
                .matches().stream().noneMatch(match -> match.ruleId().equals("legacy.gag")));
    }

    @Test
    void previewIsSideEffectFreeAndPersistenceOutputIsTargetSpecific() {
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(
                new ActiveConfig(DEFAULTS, new ConfigLoader()), states);
        var result = service.preview(context("ni66a", NOW), BypassProfile.NONE);
        assertEquals(MessageDecision.BLOCK, result.report().decision());
        assertTrue(states.snapshots().isEmpty());

        assertEquals(1, new PersistenceCodec().snapshot(List.of(), NOW, DEFAULTS).schemaVersion());
        assertEquals(2, new PersistenceCodec(2).snapshot(List.of(), NOW, DEFAULTS).schemaVersion());
        assertFalse(DEFAULTS.permissions().operatorsBypassModeration());
        assertEquals(3, DEFAULTS.permissions().commandFallbackOperatorLevel());
        assertEquals(4, DEFAULTS.permissions().bypassFallbackOperatorLevel());
    }

    private static CompiledAutoModConfig defaultBundle() {
        ActiveConfig active = new ActiveConfig();
        var result = active.reloadBundle(active.defaultJson(), active.defaultFilterPacks(),
                active.defaultExceptionsJson(), 0, 0);
        assertTrue(result.applied(), () -> result.problems().toString());
        return active.current();
    }

    private static List<DefaultTerm> defaultTerms() {
        ActiveConfig active = new ActiveConfig();
        List<DefaultTerm> result = new ArrayList<>();
        active.defaultFilterPacks().values().forEach(json -> {
            JsonArray rules = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("rules");
            for (var element : rules) {
                JsonObject rule = element.getAsJsonObject();
                String ruleId = rule.get("id").getAsString();
                for (var term : rule.getAsJsonArray("terms")) {
                    result.add(new DefaultTerm(ruleId, term.getAsString()));
                }
            }
        });
        return List.copyOf(result);
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static String spaced(String value) {
        return value.codePoints()
                .filter(Character::isLetterOrDigit)
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String repeatFirst(String value) {
        int end = value.offsetByCodePoints(0, 1);
        return value.substring(0, end).repeat(4) + value.substring(end);
    }

    private static void assertRule(String message, String ruleId) {
        var report = preview(DEFAULTS, message, NOW,
                PlayerModerationState.empty(PLAYER, "Tester", NOW)).report();
        assertTrue(report.matches().stream().anyMatch(match -> match.ruleId().equals(ruleId)),
                () -> "expected " + ruleId + " for: " + message + " -> " + report.matches());
    }

    private record DefaultTerm(String ruleId, String term) {}
}
