package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.maxello1.chatautomod.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineStateTest {
    private static final Instant NOW = Instant.parse("2026-07-22T19:00:00Z");

    @Test
    void dryRunHasNoStateSideEffects() {
        var compiled = capsConfig(2, 10, new JsonArray());
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(new ActiveConfig(compiled, new ConfigLoader()), states);
        var result = service.preview(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        assertEquals(MessageDecision.BLOCK, result.report().decision());
        assertEquals(2, result.report().pointsAdded());
        assertTrue(states.snapshots().isEmpty());
    }

    @Test
    void pointsExpireExactlyAndThresholdCanBeRecrossed() {
        JsonArray thresholds = new JsonArray();
        thresholds.add(threshold(2, actionObject("WARN", "message", "Stop.")));
        var compiled = capsConfig(2, 10, thresholds);
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(new ActiveConfig(compiled, new ConfigLoader()), states);

        var first = service.evaluateLive(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        assertTrue(first.report().thresholdCrossing().isPresent());
        var second = service.evaluateLive(context("MORE SHOUTING", NOW.plusSeconds(1)), BypassProfile.NONE);
        assertTrue(second.report().thresholdCrossing().isEmpty());
        var recross = service.evaluateLive(context("LOUD AGAIN", NOW.plusSeconds(61)), BypassProfile.NONE);
        assertEquals(0, recross.report().pointsBefore());
        assertTrue(recross.report().thresholdCrossing().isPresent());
    }

    @Test
    void highestCrossedRunsOnlyHighestThresholdAndMuteBlocksLaterChat() {
        JsonArray thresholds = new JsonArray();
        thresholds.add(threshold(2, actionObject("WARN", "message", "Stop.")));
        thresholds.add(threshold(5, actionObject("MUTE", "duration", "5m")));
        thresholds.add(threshold(10, actionObject("KICK", "reason", "Repeated violations")));
        var highest = capsConfig(12, 20, thresholds);
        ModerationService highestService = new ModerationService(new ActiveConfig(highest, new ConfigLoader()), new InMemoryPlayerStateStore());
        var result = highestService.evaluateLive(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        assertEquals(10, result.report().thresholdCrossing().orElseThrow().points());
        assertEquals(java.util.List.of(ActionType.KICK), result.actionPlan().actions().stream().map(a -> a.type()).toList());

        JsonArray muteThreshold = new JsonArray();
        muteThreshold.add(threshold(1, actionObject("MUTE", "duration", "5m")));
        var muteConfig = capsConfig(1, 10, muteThreshold);
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService muteService = new ModerationService(new ActiveConfig(muteConfig, new ConfigLoader()), states);
        muteService.evaluateLive(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        var blocked = muteService.evaluateLive(context("ordinary message", NOW.plusSeconds(1)), BypassProfile.NONE);
        assertEquals(MessageDecision.BLOCK, blocked.report().decision());
        assertTrue(blocked.report().matches().isEmpty());
        assertEquals(1, blocked.report().pointsAfter());
    }

    @Test
    void pointCapAndRuntimeHistoriesRemainBoundedPerPlayer() {
        var compiled = capsConfig(12, 3, new JsonArray());
        InMemoryPlayerStateStore states = new InMemoryPlayerStateStore();
        ModerationService service = new ModerationService(new ActiveConfig(compiled, new ConfigLoader()), states);
        var result = service.evaluateLive(context("LOUD MESSAGE", NOW), BypassProfile.NONE);
        assertEquals(3, result.report().pointsAdded());
        for (int i = 1; i <= 20; i++) {
            service.evaluateLive(context("message " + i, NOW.plusSeconds(i * 2L)), BypassProfile.NONE);
        }
        var state = states.snapshot(PLAYER, "Tester", NOW.plusSeconds(50));
        assertTrue(state.recentMessageTimes().size() <= compiled.rules().rapidSpam().maximumMessages());
        assertTrue(state.recentMessages().size() <= Math.max(compiled.rules().duplicateSpam().historySize(),
                compiled.rules().similaritySpam().historySize()));
    }

    private com.maxello1.chatautomod.core.config.CompiledAutoModConfig capsConfig(int points, int cap, JsonArray thresholds) {
        return config(root -> {
            JsonObject caps = rule(root, "caps"); caps.addProperty("enabled", true); caps.addProperty("points", points);
            caps.addProperty("minimum_letters", 4); caps.addProperty("maximum_uppercase_ratio", 0.75);
            JsonObject score = root.getAsJsonObject("score"); score.addProperty("point_decay_minutes", 1);
            score.addProperty("maximum_points_per_message", cap); score.add("thresholds", thresholds);
        });
    }

    private JsonObject threshold(int points, JsonObject action) {
        JsonObject threshold = new JsonObject(); threshold.addProperty("points", points);
        JsonArray actions = new JsonArray(); actions.add(action); threshold.add("actions", actions); return threshold;
    }

    private JsonObject actionObject(String type, String field, String value) {
        JsonObject action = new JsonObject(); action.addProperty("type", type); action.addProperty(field, value); return action;
    }
}
