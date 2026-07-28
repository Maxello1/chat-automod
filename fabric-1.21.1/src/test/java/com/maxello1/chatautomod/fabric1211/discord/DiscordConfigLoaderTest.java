package com.maxello1.chatautomod.fabric1211.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordConfigLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesSafeDisabledDefaultWithoutTokenValue() throws Exception {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> "secret-token-value");
        Path path = temporaryDirectory.resolve("chatautomod/discord.json");

        DiscordConfigLoader.LoadResult result = loader.load(path);

        assertTrue(result.configured());
        assertFalse(result.config().enabled());
        assertEquals(Duration.ofHours(24), result.config().caseExpiry());
        String written = Files.readString(path);
        assertTrue(written.contains("CHATAUTOMOD_DISCORD_TOKEN"));
        assertFalse(written.contains("secret-token-value"));
    }

    @Test
    void parsesEnabledConfigurationAndReadsTokenOnlyFromEnvironment() {
        Map<String, String> environment = Map.of("CAM_TOKEN", "token-from-environment");
        DiscordConfigLoader loader = new DiscordConfigLoader(environment::get);

        DiscordConfigLoader.LoadResult result = loader.parse(enabledJson(
                "\"allowed_user_ids\": [\"18446744073709551615\"]"));

        assertTrue(result.configured());
        assertTrue(result.config().enabled());
        assertEquals("123456789012345678", result.config().guildId());
        assertEquals("token-from-environment", loader.token(result.config()).orElseThrow());
        assertEquals(1, result.config().allowedUserIds().size());
    }

    @Test
    void missingTokenDisablesOnlyDiscordConfiguration() {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> null);

        DiscordConfigLoader.LoadResult result = loader.parse(enabledJson(
                "\"allowed_role_ids\": [\"123456789012345680\"]"));

        assertTrue(result.requestedEnabled());
        assertFalse(result.configured());
        assertFalse(result.config().enabled());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.path().equals("$.token_environment_variable")));
        assertTrue(result.problems().stream().noneMatch(problem ->
                problem.message().contains("token-from-environment")));
    }

    @Test
    void rejectsInvalidGuildChannelRoleAndUserIds() {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> "token");
        String json = """
                {
                  "enabled": true,
                  "guild_id": "-1",
                  "alert_channel_id": "abc",
                  "allowed_role_ids": ["0"],
                  "allowed_user_ids": ["18446744073709551616"]
                }
                """;

        DiscordConfigLoader.LoadResult result = loader.parse(json);

        assertFalse(result.configured());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().equals("$.guild_id")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().equals("$.alert_channel_id")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().startsWith("$.allowed_role_ids")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.path().startsWith("$.allowed_user_ids")));
    }

    @Test
    void requiresAuthorisationWhenPunishmentActionsAreEnabled() {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> "token");

        DiscordConfigLoader.LoadResult result = loader.parse(enabledJson(""));

        assertFalse(result.configured());
        assertTrue(result.problems().stream().anyMatch(problem ->
                problem.message().contains("allowed role or user")));
    }

    @Test
    void acceptsAlertOnlyConfigurationWithoutAuthorisedModerators() {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> "token");
        String actions = """
                "actions": {
                  "mute_10_minutes": false,
                  "mute_1_hour": false,
                  "permanent_ban": false
                }
                """;

        DiscordConfigLoader.LoadResult result = loader.parse(enabledJson(actions));

        assertTrue(result.configured());
        assertTrue(result.config().enabled());
    }

    @Test
    void boundsCaseExpiry() {
        DiscordConfigLoader loader = new DiscordConfigLoader(name -> "token");

        DiscordConfigLoader.LoadResult shortResult = loader.parse(
                enabledJson("\"case_expiry\": \"4m\", \"allowed_user_ids\": [\"1\"]"));
        DiscordConfigLoader.LoadResult longResult = loader.parse(
                enabledJson("\"case_expiry\": \"8d\", \"allowed_user_ids\": [\"1\"]"));

        assertFalse(shortResult.configured());
        assertFalse(longResult.configured());
    }

    private static String enabledJson(String additionalFields) {
        String suffix = additionalFields == null || additionalFields.isBlank()
                ? ""
                : ",\n" + additionalFields;
        return """
                {
                  "schema_version": 1,
                  "enabled": true,
                  "token_environment_variable": "CAM_TOKEN",
                  "guild_id": "123456789012345678",
                  "alert_channel_id": "123456789012345679"%s
                }
                """.formatted(suffix);
    }
}
