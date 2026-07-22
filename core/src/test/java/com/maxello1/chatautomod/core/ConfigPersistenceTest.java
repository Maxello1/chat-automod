package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.persistence.AtomicJsonFileStore;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.maxello1.chatautomod.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T20:00:00Z");

    @Test
    void defaultConfigIsValidAndFailedReloadIsAtomic() {
        ConfigLoader loader = new ConfigLoader();
        ActiveConfig active = new ActiveConfig(loader);
        var before = active.current();
        JsonObject broken = defaultJsonObject();
        broken.addProperty("unknown_setting", true);
        broken.getAsJsonObject("permissions").addProperty("fallback_operator_level", 9);
        broken.getAsJsonObject("rules").getAsJsonObject("similarity_spam").addProperty("similarity_threshold", 1.5);
        broken.getAsJsonObject("mutes").addProperty("notification_cooldown", "0m");
        var result = active.reload(broken.toString());
        assertFalse(result.applied());
        assertSame(before, active.current());
        assertTrue(result.problems().size() >= 4);
        assertEquals(3, active.current().permissions().fallbackOperatorLevel());
        assertFalse(active.current().staffAlerts().showOriginal());
    }

    @Test
    void rejectsDuplicateFiltersUnknownRulesAndUnsafeCommands() {
        JsonObject root = disabledRulesJson();
        root.getAsJsonObject("rules").add("invented_rule", new JsonObject());
        JsonArray filters = new JsonArray();
        filters.add(filter("same")); filters.add(filter("same")); root.add("filters", filters);
        JsonObject threshold = new JsonObject(); threshold.addProperty("points", 2);
        JsonObject action = new JsonObject(); action.addProperty("type", "EXECUTE_COMMAND");
        action.addProperty("command", "ban {message}");
        JsonArray actions = new JsonArray(); actions.add(action); threshold.add("actions", actions);
        JsonArray thresholds = new JsonArray(); thresholds.add(threshold); root.getAsJsonObject("score").add("thresholds", thresholds);
        var result = new ConfigLoader().load(root.toString());
        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().contains("invented_rule")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.message().contains("duplicate filter")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.message().contains("placeholder")));
    }

    @Test
    void persistenceRoundTripOmitsRuntimeBuffersAndPrivateOriginals(@TempDir Path tempDir) throws Exception {
        var compiled = config(root -> {
            JsonObject caps = rule(root, "caps"); caps.addProperty("enabled", true); caps.addProperty("points", 2);
            caps.addProperty("minimum_letters", 4); caps.addProperty("maximum_uppercase_ratio", 0.75);
        });
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(new ActiveConfig(compiled, new ConfigLoader()), states);
        service.evaluateLive(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        new MuteService(states, Clock.fixed(NOW, ZoneOffset.UTC)).mute(PLAYER, "Tester", Duration.ofMinutes(5),
                compiled.mutes().maximumDuration(), "Manual test", "manual", compiled.state().maximumTrackedPlayers());

        PersistenceCodec codec = new PersistenceCodec();
        String json = codec.encode(states.snapshots(), NOW, compiled);
        var loaded = codec.decode(json, NOW.plusSeconds(1), compiled);
        assertTrue(loaded.valid(), () -> loaded.problems().toString());
        var restored = loaded.states().get(0);
        assertTrue(restored.recentMessages().isEmpty());
        assertTrue(restored.recentMessageTimes().isEmpty());
        assertTrue(restored.mute().isPresent());
        assertFalse(restored.scoreEntries().isEmpty());
        assertTrue(restored.violations().get(0).originalMessage().isEmpty());

        Path file = tempDir.resolve("state.json");
        AtomicJsonFileStore files = new AtomicJsonFileStore();
        files.write(file, json);
        files.write(file, "{\"new\":true}");
        assertEquals("{\"new\":true}", files.readWithBackup(file));
        assertTrue(Files.exists(tempDir.resolve("state.json.bak")));
    }

    private JsonObject filter(String id) {
        JsonObject filter = new JsonObject(); filter.addProperty("id", id); filter.addProperty("match_mode", "WORD");
        JsonArray terms = new JsonArray(); terms.add("word"); filter.add("terms", terms);
        filter.add("exceptions", new JsonArray()); filter.add("actions", new JsonArray()); return filter;
    }
}
