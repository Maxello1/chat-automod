package com.maxello1.chatautomod.fabric1211.discord;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DiscordModerationCase(
        String caseId,
        UUID playerId,
        String playerName,
        List<String> ruleIds,
        Instant alertTimestamp,
        String discordMessageId,
        Instant expiresAt,
        DiscordCaseStatus status,
        DiscordPunishment selectedAction,
        String moderatorUserId,
        String moderatorDisplayName,
        Instant resolutionTimestamp
) {
    public DiscordModerationCase {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNullElse(playerName, playerId.toString());
        ruleIds = List.copyOf(ruleIds);
        Objects.requireNonNull(alertTimestamp, "alertTimestamp");
        discordMessageId = Objects.requireNonNullElse(discordMessageId, "");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        moderatorUserId = Objects.requireNonNullElse(moderatorUserId, "");
        moderatorDisplayName = Objects.requireNonNullElse(moderatorDisplayName, "");
    }

    DiscordModerationCase withMessageId(String messageId) {
        return copy(discordMessageId(messageId), status, selectedAction,
                moderatorUserId, moderatorDisplayName, resolutionTimestamp);
    }

    DiscordModerationCase claimed(
            DiscordPunishment action,
            String moderatorId,
            String moderatorName
    ) {
        return copy(discordMessageId, DiscordCaseStatus.PROCESSING, action,
                moderatorId, moderatorName, null);
    }

    DiscordModerationCase completed(DiscordCaseStatus completedStatus, Instant timestamp) {
        return copy(discordMessageId, completedStatus, selectedAction,
                moderatorUserId, moderatorDisplayName, timestamp);
    }

    DiscordModerationCase reopened() {
        return copy(discordMessageId, DiscordCaseStatus.OPEN, null, "", "", null);
    }

    private DiscordModerationCase copy(
            String messageId,
            DiscordCaseStatus newStatus,
            DiscordPunishment action,
            String moderatorId,
            String moderatorName,
            Instant resolvedAt
    ) {
        return new DiscordModerationCase(
                caseId,
                playerId,
                playerName,
                ruleIds,
                alertTimestamp,
                messageId,
                expiresAt,
                newStatus,
                action,
                moderatorId,
                moderatorName,
                resolvedAt);
    }

    private static String discordMessageId(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
