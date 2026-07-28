package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.fabric1211.discord.DiscordPunishment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record DiscordPunishmentAuditEvent(
        String caseId,
        UUID playerId,
        String playerName,
        DiscordPunishment action,
        Duration duration,
        String reason,
        List<String> ruleIds,
        ExternalModerator moderator,
        Instant timestamp,
        boolean success,
        String resultSummary
) {
    DiscordPunishmentAuditEvent {
        caseId = Objects.requireNonNullElse(caseId, "");
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNullElse(playerName, playerId.toString());
        Objects.requireNonNull(action, "action");
        reason = Objects.requireNonNullElse(reason, "Discord staff action");
        ruleIds = List.copyOf(ruleIds);
        Objects.requireNonNull(moderator, "moderator");
        Objects.requireNonNull(timestamp, "timestamp");
        resultSummary = Objects.requireNonNullElse(resultSummary, "");
    }
}
