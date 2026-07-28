package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAlertFormatterTest {
    private final DiscordAlertFormatter formatter = new DiscordAlertFormatter();

    @Test
    void includesOriginalMessageOnlyWhenBothPrivacyLayersPermitIt() {
        ModerationAction.NotifyStaff hiddenByCore = alert("");
        ModerationAction.NotifyStaff availableFromCore = alert("private text");

        DiscordAlertMessage hidden = formatter.format(hiddenByCore, config(true, ""));
        DiscordAlertMessage disabledByDiscord = formatter.format(availableFromCore, config(false, ""));
        DiscordAlertMessage visible = formatter.format(availableFromCore, config(true, ""));

        assertFalse(hasField(hidden, "Original message"));
        assertFalse(hasField(disabledByDiscord, "Original message"));
        assertTrue(hasField(visible, "Original message"));
    }

    @Test
    void sanitizesMarkdownControlsAndUserGeneratedMentions() {
        ModerationAction.NotifyStaff alert = new ModerationAction.NotifyStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "*Player* @everyone\n",
                java.util.List.of("rule_<@&123>"),
                MessageDecision.BLOCK,
                "`message` @here <@123>\u0000",
                5,
                9,
                Instant.parse("2026-07-28T10:15:30Z"));

        DiscordAlertMessage message = formatter.format(alert, config(true, "456"));
        MessageCreateData data = JdaDiscordMessageFactory.create(message);
        MessageEmbed embed = data.getEmbeds().getFirst();
        String combined = embed.getFields().stream()
                .map(MessageEmbed.Field::getValue)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(combined.contains("\\*Player\\* @\u200Beveryone"));
        assertTrue(combined.contains("\\`message\\` @\u200Bhere"));
        assertFalse(combined.contains("\u0000"));
        assertTrue(data.getAllowedMentions().isEmpty());
        assertEquals(Set.of("456"), data.getMentionedRoles());
        assertEquals("<@&456>", data.getContent());
    }

    @Test
    void truncatesLongFieldsWithinDiscordLimits() {
        String original = "😀".repeat(2_000);

        DiscordAlertMessage message = formatter.format(alert(original), config(true, ""));
        String value = message.fields().stream()
                .filter(field -> field.name().equals("Original message"))
                .findFirst()
                .orElseThrow()
                .value();

        assertEquals(1_024, value.codePointCount(0, value.length()));
        assertTrue(value.endsWith("…"));
    }

    @Test
    void addsOnlyEnabledPunishmentButtonsWithOpaqueCaseIds() {
        DiscordConfig base = config(false, "");
        DiscordConfig configured = new DiscordConfig(
                base.schemaVersion(),
                base.enabled(),
                base.tokenEnvironmentVariable(),
                base.guildId(),
                base.alertChannelId(),
                base.allowedRoleIds(),
                base.allowedUserIds(),
                base.mentionRoleId(),
                base.caseExpiry(),
                base.includeOriginalMessage(),
                new DiscordConfig.Actions(true, false, true));

        DiscordAlertMessage message = formatter.format(alert(""), configured, "CaseId_12345678");

        assertEquals(
                java.util.List.of(
                        "cam:CaseId_12345678:m10",
                        "cam:CaseId_12345678:ban",
                        "cam:CaseId_12345678:dismiss"),
                message.buttons().stream().map(DiscordAlertMessage.Button::customId).toList());
        assertTrue(message.buttons().stream().noneMatch(button ->
                button.customId().contains("11111111-1111-1111-1111-111111111111")));
    }

    private static boolean hasField(DiscordAlertMessage message, String name) {
        return message.fields().stream().anyMatch(field -> field.name().equals(name));
    }

    private static ModerationAction.NotifyStaff alert(String originalMessage) {
        return new ModerationAction.NotifyStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Player",
                java.util.List.of("advertising"),
                MessageDecision.BLOCK,
                originalMessage,
                5,
                9,
                Instant.parse("2026-07-28T10:15:30Z"));
    }

    private static DiscordConfig config(boolean includeOriginalMessage, String mentionRoleId) {
        DiscordConfig defaults = DiscordConfig.defaults();
        return new DiscordConfig(
                defaults.schemaVersion(),
                true,
                defaults.tokenEnvironmentVariable(),
                "123",
                "124",
                Set.of(),
                Set.of("125"),
                mentionRoleId,
                defaults.caseExpiry(),
                includeOriginalMessage,
                defaults.actions());
    }
}
