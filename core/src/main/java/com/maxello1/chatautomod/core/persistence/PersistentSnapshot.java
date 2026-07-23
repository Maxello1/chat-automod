package com.maxello1.chatautomod.core.persistence;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public record PersistentSnapshot(
        @SerializedName(value = "schema_version", alternate = "schemaVersion") int schemaVersion,
        String savedAt,
        List<PersistentPlayer> players
) {
    public PersistentSnapshot {
        players = List.copyOf(players);
    }

    public record PersistentPlayer(
            String playerUuid,
            String lastKnownName,
            PersistentMute mute,
            List<PersistentScoreEntry> scoreEntries,
            List<Integer> crossedThresholds,
            List<PersistentViolation> violations
    ) {
        public PersistentPlayer {
            scoreEntries = List.copyOf(scoreEntries);
            crossedThresholds = crossedThresholds == null ? List.of() : List.copyOf(crossedThresholds);
            violations = List.copyOf(violations);
        }

        public PersistentPlayer(String playerUuid, String lastKnownName, PersistentMute mute,
                List<PersistentScoreEntry> scoreEntries, List<PersistentViolation> violations) {
            this(playerUuid, lastKnownName, mute, scoreEntries, List.of(), violations);
        }
    }

    public record PersistentMute(
            String kind,
            String mutedAt,
            String mutedUntil,
            String reason,
            String source,
            String ruleId,
            String moderatorId
    ) {
        public PersistentMute(String mutedUntil, String reason, String source) {
            this(null, null, mutedUntil, reason, source, null, null);
        }
    }
    public record PersistentScoreEntry(int points, String ruleId, String createdAt, String expiresAt) {}
    public record PersistentViolation(
            String eventId,
            String timestamp,
            String playerUuid,
            String playerName,
            List<String> ruleIds,
            List<String> categories,
            String severity,
            String decision,
            int pointsAdded,
            int scoreAfter,
            List<String> actions,
            String muteKind,
            String originalMessage
    ) {
        public PersistentViolation {
            ruleIds = List.copyOf(ruleIds);
            categories = categories == null ? List.of() : List.copyOf(categories);
            actions = List.copyOf(actions);
        }

        public PersistentViolation(String eventId, String timestamp, String playerUuid, String playerName,
                List<String> ruleIds, String decision, int pointsAdded, int scoreAfter,
                List<String> actions, String originalMessage) {
            this(eventId, timestamp, playerUuid, playerName, ruleIds, List.of(), null, decision,
                    pointsAdded, scoreAfter, actions, null, originalMessage);
        }
    }
}
