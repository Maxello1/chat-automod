package com.maxello1.chatautomod.core.model;

import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.api.MessageDecision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ViolationRecord(
        UUID eventId,
        Instant timestamp,
        UUID playerId,
        String playerName,
        List<String> ruleIds,
        MessageDecision decision,
        int pointsAdded,
        int scoreAfter,
        List<ActionType> actions,
        Optional<String> originalMessage
) {
    public ViolationRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        ruleIds = List.copyOf(ruleIds);
        Objects.requireNonNull(decision, "decision");
        actions = List.copyOf(actions);
        originalMessage = originalMessage == null ? Optional.empty() : originalMessage;
    }
}
