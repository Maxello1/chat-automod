package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DiscordAlertFormatter {
    private static final int ALERT_COLOR = 0xE67E22;
    private static final int TEST_COLOR = 0x3498DB;

    DiscordAlertMessage format(ModerationAction.NotifyStaff alert, DiscordConfig config) {
        return format(alert, config, null);
    }

    DiscordAlertMessage format(
            ModerationAction.NotifyStaff alert,
            DiscordConfig config,
            String caseId
    ) {
        List<DiscordAlertMessage.Field> fields = new ArrayList<>();
        fields.add(field("Player", alert.playerName(), true, 256));
        fields.add(field("Player UUID", alert.playerId().toString(), true, 128));
        fields.add(field(
                "Matched rules",
                alert.ruleIds().isEmpty() ? "unknown" : String.join(", ", alert.ruleIds()),
                false,
                1_024));
        fields.add(field("Decision", alert.decision().name().toLowerCase(java.util.Locale.ROOT), true, 64));
        fields.add(field("Points added", Integer.toString(alert.pointsAdded()), true, 64));
        fields.add(field("Current score", Integer.toString(alert.scoreAfter()), true, 64));
        if (config.includeOriginalMessage() && !alert.originalMessage().isEmpty()) {
            fields.add(field("Original message", alert.originalMessage(), false, 1_024));
        }

        String mentionRoleId = config.mentionRoleId();
        String content = mentionRoleId.isEmpty() ? "" : "<@&" + mentionRoleId + ">";
        Set<String> allowedRoleMentions = mentionRoleId.isEmpty()
                ? Set.of()
                : Set.of(mentionRoleId);
        List<DiscordAlertMessage.Button> buttons = caseId == null
                ? List.of()
                : buttons(caseId, config.actions());
        return new DiscordAlertMessage(
                content,
                "Chat AutoMod staff alert",
                "A configured moderation rule produced a staff notification.",
                fields,
                buttons,
                alert.timestamp(),
                ALERT_COLOR,
                allowedRoleMentions);
    }

    DiscordAlertMessage testMessage() {
        return new DiscordAlertMessage(
                "",
                "Chat AutoMod Discord integration test",
                "Server connection is working.",
                List.of(),
                List.of(),
                java.time.Instant.now(),
                TEST_COLOR,
                Set.of());
    }

    private static List<DiscordAlertMessage.Button> buttons(
            String caseId,
            DiscordConfig.Actions actions
    ) {
        List<DiscordAlertMessage.Button> result = new ArrayList<>();
        if (actions.mute10Minutes()) {
            result.add(button(caseId, DiscordPunishment.MUTE_10_MINUTES,
                    DiscordAlertMessage.Button.Style.PRIMARY));
        }
        if (actions.mute1Hour()) {
            result.add(button(caseId, DiscordPunishment.MUTE_1_HOUR,
                    DiscordAlertMessage.Button.Style.PRIMARY));
        }
        if (actions.permanentBan()) {
            result.add(button(caseId, DiscordPunishment.PERMANENT_BAN,
                    DiscordAlertMessage.Button.Style.DANGER));
        }
        result.add(button(caseId, DiscordPunishment.DISMISS,
                DiscordAlertMessage.Button.Style.SECONDARY));
        return List.copyOf(result);
    }

    private static DiscordAlertMessage.Button button(
            String caseId,
            DiscordPunishment action,
            DiscordAlertMessage.Button.Style style
    ) {
        return new DiscordAlertMessage.Button(action.customId(caseId), action.label(), style);
    }

    private static DiscordAlertMessage.Field field(
            String name,
            String value,
            boolean inline,
            int maximumCodePoints
    ) {
        return new DiscordAlertMessage.Field(
                sanitize(name, 256),
                sanitize(value, maximumCodePoints),
                inline);
    }

    static String sanitize(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && (codePoint < 0xD800 || codePoint > 0xDFFF)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .forEach(codePoint -> {
                    if (codePoint == '@') {
                        cleaned.append('@').append('\u200B');
                    } else if (codePoint == '\\' || codePoint == '*' || codePoint == '_'
                            || codePoint == '~' || codePoint == '`' || codePoint == '|'
                            || codePoint == '>') {
                        cleaned.append('\\').appendCodePoint(codePoint);
                    } else {
                        cleaned.appendCodePoint(codePoint);
                    }
                });
        String result = cleaned.toString();
        int count = result.codePointCount(0, result.length());
        if (count <= maximumCodePoints) {
            return result.isEmpty() ? "unknown" : result;
        }
        int retained = Math.max(0, maximumCodePoints - 1);
        int end = result.offsetByCodePoints(0, retained);
        return result.substring(0, end) + "…";
    }
}
