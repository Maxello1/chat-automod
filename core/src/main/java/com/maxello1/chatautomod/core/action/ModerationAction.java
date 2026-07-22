package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.MessageDecision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public sealed interface ModerationAction {
    ActionType type();

    record NotifyStaff(
            UUID playerId,
            String playerName,
            List<String> ruleIds,
            MessageDecision decision,
            String originalMessage,
            int pointsAdded,
            int scoreAfter,
            Instant timestamp
    ) implements ModerationAction {
        public NotifyStaff {
            Objects.requireNonNull(playerId, "playerId");
            playerName = Objects.requireNonNull(playerName, "playerName");
            ruleIds = List.copyOf(ruleIds);
            Objects.requireNonNull(decision, "decision");
            originalMessage = Objects.requireNonNullElse(originalMessage, "");
            Objects.requireNonNull(timestamp, "timestamp");
        }

        @Override public ActionType type() { return ActionType.NOTIFY_STAFF; }
    }

    record Warn(UUID playerId, String message) implements ModerationAction {
        @Override public ActionType type() { return ActionType.WARN; }
    }

    record Mute(UUID playerId, Instant until, String reason, String source) implements ModerationAction {
        @Override public ActionType type() { return ActionType.MUTE; }
    }

    record Kick(UUID playerId, String reason) implements ModerationAction {
        @Override public ActionType type() { return ActionType.KICK; }
    }

    record ExecuteCommand(String command) implements ModerationAction {
        @Override public ActionType type() { return ActionType.EXECUTE_COMMAND; }
    }
}
