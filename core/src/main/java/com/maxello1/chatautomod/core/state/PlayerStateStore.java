package com.maxello1.chatautomod.core.state;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface PlayerStateStore {
    PlayerModerationState snapshot(UUID playerId, String playerName, Instant now);
    boolean contains(UUID playerId);
    <T> T transact(UUID playerId, String playerName, Instant now,
                   Function<PlayerModerationState, StateUpdate<T>> transaction);
    <T> Optional<T> transactIfCapacity(UUID playerId, String playerName, Instant now, int maximumTrackedPlayers,
                                       Function<PlayerModerationState, StateUpdate<T>> transaction);
    Collection<PlayerModerationState> snapshots();
    void restore(Collection<PlayerModerationState> states);
    void remove(UUID playerId);
    void pruneInactive(Instant now, java.time.Duration inactiveTime, java.time.Duration historyRetention,
                       int maximumTrackedPlayers);
}
