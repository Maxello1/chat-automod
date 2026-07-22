package com.maxello1.chatautomod.core.state;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class InMemoryPlayerStateStore implements PlayerStateStore {
    private final ConcurrentHashMap<UUID, PlayerModerationState> states = new ConcurrentHashMap<>();

    @Override
    public PlayerModerationState snapshot(UUID playerId, String playerName, Instant now) {
        return states.getOrDefault(playerId, PlayerModerationState.empty(playerId, playerName, now));
    }

    @Override
    public boolean contains(UUID playerId) {
        return states.containsKey(playerId);
    }

    @Override
    public <T> T transact(UUID playerId, String playerName, Instant now,
            Function<PlayerModerationState, StateUpdate<T>> transaction) {
        AtomicReference<T> result = new AtomicReference<>();
        states.compute(playerId, (id, current) -> {
            PlayerModerationState base = current == null ? PlayerModerationState.empty(id, playerName, now) : current;
            StateUpdate<T> update = transaction.apply(base);
            if (!update.state().playerId().equals(id)) throw new IllegalStateException("state player ID changed during transaction");
            result.set(update.result());
            return update.state();
        });
        return result.get();
    }

    @Override
    public <T> Optional<T> transactIfCapacity(UUID playerId, String playerName, Instant now, int maximumTrackedPlayers,
            Function<PlayerModerationState, StateUpdate<T>> transaction) {
        synchronized (states) {
            if (!states.containsKey(playerId) && states.size() >= maximumTrackedPlayers) {
                states.values().stream()
                        .filter(state -> !state.hasDurableData(now))
                        .min(Comparator.comparing(PlayerModerationState::lastActivityAt)
                                .thenComparing(state -> state.playerId().toString()))
                        .ifPresent(candidate -> states.remove(candidate.playerId(), candidate));
                if (states.size() >= maximumTrackedPlayers) return Optional.empty();
            }
            return Optional.ofNullable(transact(playerId, playerName, now, transaction));
        }
    }

    @Override
    public Collection<PlayerModerationState> snapshots() {
        return List.copyOf(states.values());
    }

    @Override
    public void restore(Collection<PlayerModerationState> restored) {
        for (PlayerModerationState state : restored) states.put(state.playerId(), state);
    }

    @Override
    public void remove(UUID playerId) {
        states.remove(playerId);
    }

    @Override
    public void pruneInactive(Instant now, Duration inactiveTime, Duration historyRetention,
            int maximumTrackedPlayers) {
        Instant cutoff = now.minus(inactiveTime);
        Instant historyCutoff = now.minus(historyRetention);
        synchronized (states) {
            states.forEach((playerId, state) -> {
                var activeScores = state.scoreEntries().stream()
                        .filter(entry -> entry.expiresAt().isAfter(now)).toList();
                var activeMute = state.mute().filter(mute -> mute.activeAt(now));
                var retainedViolations = state.violations().stream()
                        .filter(violation -> !violation.timestamp().isBefore(historyCutoff)).toList();
                boolean inactive = state.lastActivityAt().isBefore(cutoff);
                boolean changed = activeScores.size() != state.scoreEntries().size()
                        || !activeMute.equals(state.mute())
                        || retainedViolations.size() != state.violations().size()
                        || (inactive && (!state.recentMessageTimes().isEmpty()
                                || !state.recentMessages().isEmpty()
                                || state.lastMuteNotificationAt().isPresent()));
                PlayerModerationState cleaned = changed
                        ? new PlayerModerationState(state.revision() + 1, state.playerId(), state.lastKnownName(),
                                inactive ? List.of() : state.recentMessageTimes(),
                                inactive ? List.of() : state.recentMessages(),
                                activeScores, activeMute, retainedViolations,
                                inactive ? Optional.empty() : state.lastMuteNotificationAt(), state.lastActivityAt())
                        : state;
                if (inactive && !cleaned.hasDurableData(now)) {
                    states.remove(playerId, state);
                } else if (changed) {
                    states.replace(playerId, state, cleaned);
                }
            });
            if (states.size() <= maximumTrackedPlayers) return;
            List<PlayerModerationState> candidates = new ArrayList<>(states.values());
            candidates.sort(Comparator.comparing(PlayerModerationState::lastActivityAt));
            for (PlayerModerationState candidate : candidates) {
                if (states.size() <= maximumTrackedPlayers) break;
                if (!candidate.hasDurableData(now)) states.remove(candidate.playerId(), candidate);
            }
        }
    }
}
