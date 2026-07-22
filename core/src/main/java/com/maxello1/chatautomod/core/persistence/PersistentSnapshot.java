package com.maxello1.chatautomod.core.persistence;

import java.util.List;

public record PersistentSnapshot(int schemaVersion, String savedAt, List<PersistentPlayer> players) {
    public PersistentSnapshot {
        players = List.copyOf(players);
    }

    public record PersistentPlayer(
            String playerUuid,
            String lastKnownName,
            PersistentMute mute,
            List<PersistentScoreEntry> scoreEntries,
            List<PersistentViolation> violations
    ) {
        public PersistentPlayer {
            scoreEntries = List.copyOf(scoreEntries);
            violations = List.copyOf(violations);
        }
    }

    public record PersistentMute(String mutedUntil, String reason, String source) {}
    public record PersistentScoreEntry(int points, String ruleId, String createdAt, String expiresAt) {}
    public record PersistentViolation(
            String eventId,
            String timestamp,
            String playerUuid,
            String playerName,
            List<String> ruleIds,
            String decision,
            int pointsAdded,
            int scoreAfter,
            List<String> actions,
            String originalMessage
    ) {
        public PersistentViolation {
            ruleIds = List.copyOf(ruleIds);
            actions = List.copyOf(actions);
        }
    }
}
