package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.fabric1211.discord.DiscordPunishment;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PunishmentRequest(
        String caseId,
        UUID playerId,
        String playerName,
        List<String> ruleIds,
        DiscordPunishment action,
        ExternalModerator moderator
) {
    public PunishmentRequest {
        caseId = Objects.requireNonNullElse(caseId, "");
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNullElse(playerName, playerId.toString());
        ruleIds = List.copyOf(ruleIds);
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(moderator, "moderator");
    }

    public String reason() {
        String rules = ruleIds.isEmpty() ? "unknown rule" : String.join(", ", ruleIds);
        return "Discord staff action for: " + rules;
    }
}
