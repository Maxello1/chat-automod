package com.maxello1.chatautomod.core.state;

import com.maxello1.chatautomod.core.model.MuteKind;
import com.maxello1.chatautomod.core.model.MuteState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MuteService {
    private final PlayerStateStore states;
    private final Clock clock;

    public MuteService(PlayerStateStore states, Clock clock) {
        this.states = states;
        this.clock = clock;
    }

    public MuteState mute(UUID playerId, String playerName, Duration duration, Duration maximum,
            String reason, String source, int maximumTrackedPlayers) {
        return muteTemporary(playerId, playerName, duration, maximum, reason, source, "", null,
                maximumTrackedPlayers);
    }

    public MuteState muteTemporary(UUID playerId, String playerName, Duration duration, Duration maximum,
            String reason, String source, String ruleId, UUID moderatorId, int maximumTrackedPlayers) {
        if (maximumTrackedPlayers < 1) throw new IllegalArgumentException("maximumTrackedPlayers must be positive");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(maximum, "maximum");
        if (duration.isZero() || duration.isNegative() || duration.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("mute duration must be positive and at most " + maximum);
        }
        Instant now = clock.instant();
        Instant requested;
        try {
            requested = now.plus(duration);
        } catch (java.time.DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("mute end is outside the supported timestamp range", exception);
        }
        return states.transactIfCapacity(playerId, playerName, now, maximumTrackedPlayers,
                current -> {
            Optional<MuteState> active = current.mute().filter(mute -> mute.isActiveAt(now));
            if (active.filter(mute -> mute.kind() == MuteKind.PERMANENT
                    || mute.mutedUntil().compareTo(requested) >= 0).isPresent()) {
                return new StateUpdate<>(current, active.orElseThrow());
            }
            MuteState mute = MuteState.temporary(now, requested, reason, source, ruleId, moderatorId);
            return new StateUpdate<>(current.withMute(Optional.of(mute), now), mute);
        }).orElseThrow(() -> new MuteCapacityException("maximum tracked players reached"));
    }

    public MuteState mutePermanent(UUID playerId, String playerName, String reason, String source,
            String ruleId, UUID moderatorId, int maximumTrackedPlayers) {
        if (maximumTrackedPlayers < 1) throw new IllegalArgumentException("maximumTrackedPlayers must be positive");
        Instant now = clock.instant();
        MuteState mute = MuteState.permanent(now, reason, source, ruleId, moderatorId);
        return states.transactIfCapacity(playerId, playerName, now, maximumTrackedPlayers,
                current -> new StateUpdate<>(current.withMute(Optional.of(mute), now), mute))
                .orElseThrow(() -> new MuteCapacityException("maximum tracked players reached"));
    }

    public boolean unmute(UUID playerId, String playerName) {
        if (!states.contains(playerId)) return false;
        Instant now = clock.instant();
        return states.transact(playerId, playerName, now, current -> {
            boolean existed = current.mute().isPresent();
            return new StateUpdate<>(current.withMute(Optional.empty(), now), existed);
        });
    }

    public Optional<MuteState> activeMute(UUID playerId, String playerName) {
        Instant now = clock.instant();
        return states.snapshot(playerId, playerName, now).mute().filter(mute -> mute.isActiveAt(now));
    }

    public static final class MuteCapacityException extends IllegalArgumentException {
        public MuteCapacityException(String message) { super(message); }
    }
}
