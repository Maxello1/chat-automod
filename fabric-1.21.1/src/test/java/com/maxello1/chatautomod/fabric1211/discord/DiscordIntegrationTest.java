package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.fabric1211.PunishmentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledIntegrationStartsAndStopsWithoutDiscordConnection() {
        Path config = temporaryDirectory.resolve("discord.json");
        DiscordIntegration integration = new DiscordIntegration(
                LoggerFactory.getLogger(DiscordIntegrationTest.class),
                config,
                new DiscordConfigLoader(name -> null),
                request -> CompletableFuture.completedFuture(
                        PunishmentResult.failure("not used", true)));

        integration.start();
        integration.publishAlert(alert());

        assertEquals(DiscordIntegrationStatus.Connection.DISABLED,
                integration.status().connection());
        assertTrue(integration.status().configured());
        assertFalse(integration.publishTestAlert());
        integration.stop();
        assertEquals(DiscordIntegrationStatus.Connection.STOPPED,
                integration.status().connection());
    }

    @Test
    void missingTokenFailsOnlyDiscordStartupWithoutPersistingSecret() throws Exception {
        Path config = temporaryDirectory.resolve("discord.json");
        Files.writeString(config, """
                {
                  "enabled": true,
                  "token_environment_variable": "MISSING_TOKEN",
                  "guild_id": "1",
                  "alert_channel_id": "2",
                  "allowed_user_ids": ["3"]
                }
                """);
        DiscordIntegration integration = new DiscordIntegration(
                LoggerFactory.getLogger(DiscordIntegrationTest.class),
                config,
                new DiscordConfigLoader(name -> null),
                request -> CompletableFuture.completedFuture(
                        PunishmentResult.failure("not used", true)));

        integration.start();

        assertEquals(DiscordIntegrationStatus.Connection.FAILED,
                integration.status().connection());
        assertTrue(integration.status().enabled());
        assertFalse(integration.status().configured());
        assertFalse(Files.readString(config).contains("secret"));
        integration.stop();
    }

    private static ModerationAction.NotifyStaff alert() {
        return new ModerationAction.NotifyStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Player",
                List.of("advertising"),
                MessageDecision.BLOCK,
                "",
                5,
                9,
                Instant.parse("2026-07-28T10:15:30Z"));
    }
}
