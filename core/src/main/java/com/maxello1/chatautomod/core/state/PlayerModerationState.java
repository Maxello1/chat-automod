package com.maxello1.chatautomod.core.state;

import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.ViolationRecord;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PlayerModerationState(
        long revision,
        UUID playerId,
        String lastKnownName,
        List<Instant> recentMessageTimes,
        List<RecentMessage> recentMessages,
        List<ScoreEntry> scoreEntries,
        Optional<MuteState> mute,
        List<ViolationRecord> violations,
        Optional<Instant> lastMuteNotificationAt,
        Instant lastActivityAt
) {
    public PlayerModerationState {
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        Objects.requireNonNull(playerId, "playerId");
        lastKnownName = Objects.requireNonNullElse(lastKnownName, "");
        recentMessageTimes = List.copyOf(recentMessageTimes);
        recentMessages = List.copyOf(recentMessages);
        scoreEntries = List.copyOf(scoreEntries);
        mute = mute == null ? Optional.empty() : mute;
        violations = List.copyOf(violations);
        lastMuteNotificationAt = lastMuteNotificationAt == null ? Optional.empty() : lastMuteNotificationAt;
        Objects.requireNonNull(lastActivityAt, "lastActivityAt");
    }

    public static PlayerModerationState empty(UUID playerId, String name, Instant now) {
        return new PlayerModerationState(0, playerId, name, List.of(), List.of(), List.of(), Optional.empty(),
                List.of(), Optional.empty(), now);
    }

    public PlayerModerationState withMute(Optional<MuteState> newMute, Instant now) {
        return new PlayerModerationState(revision + 1, playerId, lastKnownName, recentMessageTimes, recentMessages,
                scoreEntries, newMute, violations, lastMuteNotificationAt, now);
    }

    public boolean hasDurableData(Instant now) {
        return mute.filter(value -> value.activeAt(now)).isPresent()
                || scoreEntries.stream().anyMatch(value -> value.expiresAt().isAfter(now))
                || !violations.isEmpty();
    }
}
