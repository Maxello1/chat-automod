package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.fabric1211.discord.DiscordPunishment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricAuditLoggerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsDiscordActionWithoutOriginalMessageOrToken() throws Exception {
        Instant now = Instant.now();
        FabricAuditLogger logger = new FabricAuditLogger(
                temporaryDirectory,
                LoggerFactory.getLogger(FabricAuditLoggerTest.class),
                30);
        logger.appendDiscordAction(
                new DiscordPunishmentAuditEvent(
                        "CaseId_12345678",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "Player",
                        DiscordPunishment.MUTE_10_MINUTES,
                        Duration.ofMinutes(10),
                        "Discord staff action for: advertising",
                        List.of("advertising"),
                        new ExternalModerator("discord", "123", "Moderator"),
                        now,
                        true,
                        "Muted Player for 10 minutes."),
                30);
        logger.close();

        Path file = temporaryDirectory.resolve("logs/automod-"
                + now.atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl");
        String written = Files.readString(file);

        assertTrue(written.contains("\"event_type\":\"discord_moderation_action\""));
        assertTrue(written.contains("\"case_id\":\"CaseId_12345678\""));
        assertTrue(written.contains("\"moderator_id\":\"123\""));
        assertFalse(written.contains("original_message"));
        assertFalse(written.toLowerCase(java.util.Locale.ROOT).contains("token"));
    }
}
