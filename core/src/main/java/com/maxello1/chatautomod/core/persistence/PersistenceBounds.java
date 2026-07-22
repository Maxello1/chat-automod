package com.maxello1.chatautomod.core.persistence;

import java.util.List;

final class PersistenceBounds {
    private PersistenceBounds() {}

    static PersistentSnapshot checkedSnapshot(int schemaVersion, String savedAt,
            List<PersistentSnapshot.PersistentPlayer> players, int maximumTrackedPlayers,
            int maximumScoreEntriesPerPlayer, int maximumViolationsPerPlayer) {
        if (players.size() > maximumTrackedPlayers) {
            throw new IllegalStateException("persistent state exceeds maximum tracked players");
        }
        for (PersistentSnapshot.PersistentPlayer player : players) {
            if (player.scoreEntries().size() > maximumScoreEntriesPerPlayer) {
                throw new IllegalStateException("persistent state exceeds maximum score entries per player");
            }
            if (player.violations().size() > maximumViolationsPerPlayer) {
                throw new IllegalStateException("persistent state exceeds maximum violations per player");
            }
        }
        return new PersistentSnapshot(schemaVersion, savedAt, players);
    }
}
