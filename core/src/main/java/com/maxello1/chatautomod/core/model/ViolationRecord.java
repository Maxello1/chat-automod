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
        List<RuleCategory> categories,
        Severity severity,
        MessageDecision decision,
        int pointsAdded,
        int scoreAfter,
        List<ActionType> actions,
        Optional<MuteKind> muteKind,
        Optional<String> originalMessage
) {
    public ViolationRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        ruleIds = List.copyOf(ruleIds);
        categories = List.copyOf(categories);
        severity = Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(decision, "decision");
        actions = List.copyOf(actions);
        muteKind = muteKind == null ? Optional.empty() : muteKind;
        originalMessage = originalMessage == null ? Optional.empty() : originalMessage;
    }

    public ViolationRecord(UUID eventId, Instant timestamp, UUID playerId,
            String playerName, List<String> ruleIds, MessageDecision decision,
            int pointsAdded, int scoreAfter, List<ActionType> actions,
            Optional<String> originalMessage) {
        this(eventId, timestamp, playerId, playerName, ruleIds, List.of(),
                Severity.MODERATE, decision, pointsAdded, scoreAfter, actions,
                Optional.empty(), originalMessage);
    }
}
