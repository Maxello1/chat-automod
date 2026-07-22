package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.ConfigLoader;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.normalize.TextNormalizer;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static com.maxello1.chatautomod.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NormalizationDetectorTest {
    private static final Instant NOW = Instant.parse("2026-07-22T18:00:00Z");

    @Test
    void normalizesUnicodeLeetZeroWidthAndLinks() {
        var config = new ActiveConfig().current();
        TextNormalizer normalizer = new TextNormalizer(config.normalization());
        assertEquals("join", normalizer.normalize("j0in").deobfuscated());
        assertEquals("bad", normalizer.normalize("b\u200Bad").deobfuscated());
        assertEquals("hello world", normalizer.normalize("hello\n\tworld").canonical());
        assertEquals("a", normalizer.normalize("Α").deobfuscated());
        assertEquals("example.com", normalizer.normalize("ｅｘａｍｐｌｅ[.]com").linkNormalized());
        assertEquals("example.com", normalizer.normalize("e x a m p l e . c o m").linkNormalized());
        assertEquals("discord.gg/example", normalizer.normalize("discord dot gg / example").linkNormalized());
    }

    @Test
    void detectsLengthCapsRepeatedDuplicateRapidSimilarityAndFilter() {
        var length = config(root -> {
            JsonObject rule = rule(root, "message_length"); rule.addProperty("enabled", true); rule.addProperty("maximum_length", 5);
        });
        assertIds(length, "123456", PlayerModerationState.empty(PLAYER, "Tester", NOW), "flooding.message_length");

        var caps = config(root -> {
            JsonObject rule = rule(root, "caps"); rule.addProperty("enabled", true); rule.addProperty("minimum_letters", 4);
            rule.addProperty("maximum_uppercase_ratio", 0.75);
        });
        assertIds(caps, "LOUD TEXT", PlayerModerationState.empty(PLAYER, "Tester", NOW), "flooding.caps");
        assertTrue(preview(caps, "OK 123!!!", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());

        var repeated = config(root -> {
            JsonObject rule = rule(root, "repeated_characters"); rule.addProperty("enabled", true);
            rule.addProperty("maximum_repeated_letters", 3); rule.addProperty("maximum_repeated_symbols", 4);
        });
        assertIds(repeated, "nooooo", PlayerModerationState.empty(PLAYER, "Tester", NOW), "flooding.repeated_characters");

        var duplicate = config(root -> rule(root, "duplicate_spam").addProperty("enabled", true));
        PlayerModerationState duplicateState = stateWithMessages(List.of(new RecentMessage("hello there", "hello there", NOW.minusSeconds(2))));
        assertIds(duplicate, "hello there", duplicateState, "spam.duplicate");

        var rapid = config(root -> rule(root, "rapid_spam").addProperty("enabled", true));
        PlayerModerationState rapidState = new PlayerModerationState(0, PLAYER, "Tester", List.of(NOW.minusMillis(100)),
                List.of(), List.of(), java.util.Optional.empty(), List.of(), java.util.Optional.empty(), NOW.minusMillis(100));
        assertIds(rapid, "hello", rapidState, "spam.rapid");

        var similarity = config(root -> rule(root, "similarity_spam").addProperty("enabled", true));
        PlayerModerationState similarityState = stateWithMessages(List.of(new RecentMessage("join my server", "join my server", NOW.minusSeconds(2))));
        assertIds(similarity, "join my server please", similarityState, "spam.similarity");
        assertTrue(preview(similarity, "the weather is nice", NOW, similarityState).report().matches().isEmpty());

        var filter = config(root -> {
            JsonObject item = new JsonObject();
            item.addProperty("id", "filter.badword"); item.addProperty("enabled", true); item.addProperty("points", 3);
            item.addProperty("match_mode", "COMPACT");
            JsonArray terms = new JsonArray(); terms.add("badword"); item.add("terms", terms);
            JsonArray exceptions = new JsonArray(); item.add("exceptions", exceptions);
            JsonArray actions = new JsonArray(); actions.add("BLOCK"); item.add("actions", actions);
            JsonArray filters = new JsonArray(); filters.add(item); root.add("filters", filters);
        });
        assertIds(filter, "b 4 d w o r d", PlayerModerationState.empty(PLAYER, "Tester", NOW), "filter.badword");
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.com", "evil . com", "evil dot com", "evil[.]com", "8.8.8.8:25565", "discord dot gg / example"})
    void detectsAdvertisingVariants(String value) {
        var config = config(root -> rule(root, "advertising").addProperty("enabled", true));
        assertTrue(preview(config, value, NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches()
                .stream().anyMatch(match -> match.ruleId().startsWith("advertising.") && match.points() > 0));
    }

    @Test
    void advertisingAllowlistUsesDomainBoundariesAndRejectsInvalidIp() {
        var config = config(root -> rule(root, "advertising").addProperty("enabled", true));
        assertTrue(preview(config, "docs.example.org", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());
        assertFalse(preview(config, "fakeexample.org", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());
        assertFalse(preview(config, "example.org.evil.com", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());
        assertTrue(preview(config, "999.2.3.4", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());
        assertTrue(preview(config, "version 1.20.4", NOW, PlayerModerationState.empty(PLAYER, "Tester", NOW)).report().matches().isEmpty());
    }

    private void assertIds(com.maxello1.chatautomod.core.config.CompiledAutoModConfig config, String message,
            PlayerModerationState state, String expected) {
        assertTrue(preview(config, message, NOW, state).report().matches().stream().anyMatch(match -> match.ruleId().equals(expected)));
    }

    private PlayerModerationState stateWithMessages(List<RecentMessage> messages) {
        return new PlayerModerationState(0, PLAYER, "Tester", List.of(), messages, List.of(), java.util.Optional.empty(),
                List.of(), java.util.Optional.empty(), NOW.minusSeconds(2));
    }
}
