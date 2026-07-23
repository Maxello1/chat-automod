package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.model.MuteKind;

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

    record Mute(UUID playerId, MuteKind kind, Instant mutedAt, Instant until,
            String reason, String source, String ruleId) implements ModerationAction {
        public Mute {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(mutedAt, "mutedAt");
            if (kind == MuteKind.TEMPORARY) {
                Objects.requireNonNull(until, "until");
            } else if (until != null) {
                throw new IllegalArgumentException("permanent mute must not have an end");
            }
            reason = Objects.requireNonNullElse(reason, "Chat rule violation");
            source = Objects.requireNonNullElse(source, "automatic");
            ruleId = Objects.requireNonNullElse(ruleId, "");
        }

        public Mute(UUID playerId, Instant until, String reason, String source) {
            this(playerId, MuteKind.TEMPORARY, Instant.MIN, until, reason, source, "");
        }

        @Override public ActionType type() { return ActionType.MUTE; }
    }

    record Kick(UUID playerId, String reason) implements ModerationAction {
        @Override public ActionType type() { return ActionType.KICK; }
    }

    record ExecuteCommand(String command) implements ModerationAction {
        @Override public ActionType type() { return ActionType.EXECUTE_COMMAND; }
    }
}
