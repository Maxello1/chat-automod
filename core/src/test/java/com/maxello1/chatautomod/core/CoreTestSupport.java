package com.maxello1.chatautomod.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageSource;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.ConfigLoadResult;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.engine.PreviewEvaluation;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreTestSupport {
    static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Gson GSON = new Gson();

    private CoreTestSupport() {}

    static JsonObject defaultJsonObject() {
        return JsonParser.parseString(new ConfigLoader().defaultJson()).getAsJsonObject();
    }

    static JsonObject disabledRulesJson() {
        JsonObject root = defaultJsonObject();
        JsonObject rules = root.getAsJsonObject("rules");
        rules.entrySet().forEach(entry -> entry.getValue().getAsJsonObject().addProperty("enabled", false));
        root.add("filters", new JsonArray());
        root.getAsJsonObject("score").add("thresholds", new JsonArray());
        return root;
    }

    static CompiledAutoModConfig config(Consumer<JsonObject> mutation) {
        JsonObject root = disabledRulesJson();
        mutation.accept(root);
        ConfigLoadResult result = new ConfigLoader().load(GSON.toJson(root));
        assertTrue(result.valid(), () -> "invalid test config: " + result.problems());
        return result.config().orElseThrow();
    }

    static JsonObject rule(JsonObject root, String name) {
        return root.getAsJsonObject("rules").getAsJsonObject(name);
    }

    static MessageContext context(String message, Instant timestamp) {
        return new MessageContext(PLAYER, "Tester", message, MessageSource.PUBLIC_CHAT, "", timestamp, false);
    }

    static PreviewEvaluation preview(CompiledAutoModConfig config, String message, Instant timestamp,
            PlayerModerationState state) {
        ModerationService service = new ModerationService(new ActiveConfig(config, new ConfigLoader()),
                new InMemoryPlayerStateStore());
        return service.preview(context(message, timestamp), state, BypassProfile.NONE);
    }
}
