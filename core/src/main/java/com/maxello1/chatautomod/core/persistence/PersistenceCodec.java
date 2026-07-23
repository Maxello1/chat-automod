package com.maxello1.chatautomod.core.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.ConfigProblem;
import com.maxello1.chatautomod.core.model.MuteKind;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.Severity;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PersistenceCodec {
    public static final int SCHEMA_VERSION = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final int outputSchemaVersion;

    public PersistenceCodec() {
        this(1);
    }

    public PersistenceCodec(int outputSchemaVersion) {
        if (outputSchemaVersion < 1 || outputSchemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("output schema version must be 1 or " + SCHEMA_VERSION);
        }
        this.outputSchemaVersion = outputSchemaVersion;
    }

    public PersistentSnapshot snapshot(Collection<PlayerModerationState> states, Instant now,
            CompiledAutoModConfig config) {
        int schemaVersion = outputSchemaVersion == 1 && states.stream()
                .flatMap(state -> state.mute().stream())
                .anyMatch(mute -> mute.kind() == MuteKind.PERMANENT)
                ? SCHEMA_VERSION : outputSchemaVersion;
        Instant historyCutoff = now.minus(Duration.ofDays(config.history().retentionDays()));
        List<PersistentSnapshot.PersistentPlayer> players = new ArrayList<>();
        states.stream().sorted(java.util.Comparator.comparing(state -> state.playerId().toString())).forEach(state -> {
            List<PersistentSnapshot.PersistentScoreEntry> scores = state.scoreEntries().stream()
                    .filter(entry -> entry.expiresAt().isAfter(now))
                    .map(entry -> new PersistentSnapshot.PersistentScoreEntry(entry.points(), entry.ruleId(),
                            entry.createdAt().toString(), entry.expiresAt().toString())).toList();
            if (scores.size() > config.state().maximumScoreEntriesPerPlayer()) {
                throw new IllegalStateException("persistent score entries exceed the configured per-player maximum");
            }
            List<Integer> crossedThresholds = state.crossedThresholds().stream().sorted().toList();
            PersistentSnapshot.PersistentMute mute = state.mute().filter(value -> value.isActiveAt(now))
                    .map(value -> schemaVersion == 1
                            ? new PersistentSnapshot.PersistentMute(
                                    value.mutedUntil().toString(), value.reason(), value.source())
                            : new PersistentSnapshot.PersistentMute(value.kind().name(),
                                    value.mutedAt().toString(),
                                    value.mutedUntil() == null ? null : value.mutedUntil().toString(),
                                    value.reason(), value.source(), value.ruleId(),
                                    value.moderatorId() == null ? null : value.moderatorId().toString()))
                    .orElse(null);
            List<PersistentSnapshot.PersistentViolation> violations = state.violations().stream()
                    .filter(record -> !record.timestamp().isBefore(historyCutoff))
                    .map(record -> persistentViolation(record, config.logging().storeOriginalMessages())).toList();
            if (violations.size() > config.history().maximumEntriesPerPlayer()) {
                violations = List.copyOf(violations.subList(
                        violations.size() - config.history().maximumEntriesPerPlayer(), violations.size()));
            }
            if (mute != null || !scores.isEmpty() || !crossedThresholds.isEmpty()
                    || !violations.isEmpty() || !state.lastKnownName().isBlank()) {
                players.add(new PersistentSnapshot.PersistentPlayer(state.playerId().toString(), state.lastKnownName(),
                        mute, scores, crossedThresholds, violations));
            }
        });
        return PersistenceBounds.checkedSnapshot(schemaVersion, now.toString(), players,
                config.state().maximumTrackedPlayers(), config.state().maximumScoreEntriesPerPlayer(), config.history().maximumEntriesPerPlayer());
    }

    public String encode(PersistentSnapshot snapshot) {
        JsonObject root = GSON.toJsonTree(snapshot).getAsJsonObject();
        if (snapshot.schemaVersion() == 1) {
            JsonElement version = root.remove("schema_version");
            root.add("schemaVersion", version);
        }
        return GSON.toJson(root);
    }

    public String encode(Collection<PlayerModerationState> states, Instant now, CompiledAutoModConfig config) {
        return encode(snapshot(states, now, config));
    }

    public PersistenceLoadResult decode(String json, Instant now, CompiledAutoModConfig config) {
        List<ConfigProblem> problems = new ArrayList<>();
        if (json == null) return new PersistenceLoadResult(List.of(), List.of(new ConfigProblem("$", "state is required")));
        PersistentSnapshot snapshot;
        try {
            snapshot = GSON.fromJson(json, PersistentSnapshot.class);
        } catch (RuntimeException exception) {
            return new PersistenceLoadResult(List.of(), List.of(new ConfigProblem("$", "invalid state JSON: " + exception.getMessage())));
        }
        if (snapshot == null) return new PersistenceLoadResult(List.of(), List.of(new ConfigProblem("$", "state is empty")));
        if (snapshot.schemaVersion() < 1 || snapshot.schemaVersion() > SCHEMA_VERSION) problems.add(new ConfigProblem("$.schema_version", "unsupported state schema"));
        if (snapshot.players() == null) problems.add(new ConfigProblem("$.players", "players are required"));
        else if (snapshot.players().size() > config.state().maximumTrackedPlayers()) problems.add(new ConfigProblem("$.players", "too many persisted players"));
        if (!problems.isEmpty()) return new PersistenceLoadResult(List.of(), problems);

        List<PlayerModerationState> states = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Instant legacyMutedAt = parseLegacyMutedAt(snapshot.savedAt());
        for (int i = 0; i < snapshot.players().size(); i++) {
            String path = "$.players[" + i + "]";
            try {
                PersistentSnapshot.PersistentPlayer player = snapshot.players().get(i);
                UUID playerId = UUID.fromString(player.playerUuid());
                if (!seen.add(playerId)) throw new IllegalArgumentException("duplicate player UUID");
                List<ScoreEntry> scores = decodeScores(player.scoreEntries(), now, config, path, problems);
                Set<Integer> crossedThresholds = decodeCrossedThresholds(player.crossedThresholds(), path, problems);
                Optional<MuteState> mute = decodeMute(player.mute(), snapshot.schemaVersion(), legacyMutedAt, now, path, problems);
                List<ViolationRecord> violations = decodeViolations(player.violations(), now, config, path, problems);
                states.add(new PlayerModerationState(0, playerId, player.lastKnownName(), List.of(), List.of(), scores,
                        crossedThresholds, mute, violations, Optional.empty(), now));
            } catch (RuntimeException exception) {
                problems.add(new ConfigProblem(path, exception.getMessage()));
            }
        }
        return problems.isEmpty() ? new PersistenceLoadResult(states, List.of())
                : new PersistenceLoadResult(List.of(), problems);
    }

    private List<ScoreEntry> decodeScores(List<PersistentSnapshot.PersistentScoreEntry> values, Instant now,
            CompiledAutoModConfig config, String path, List<ConfigProblem> problems) {
        if (values == null) throw new IllegalArgumentException("scoreEntries are required");
        if (values.size() > config.state().maximumScoreEntriesPerPlayer()) throw new IllegalArgumentException("too many score entries");
        List<ScoreEntry> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            try {
                var value = values.get(i);
                ScoreEntry entry = new ScoreEntry(value.points(), value.ruleId(), Instant.parse(value.createdAt()),
                        Instant.parse(value.expiresAt()));
                if (entry.expiresAt().isAfter(now)) result.add(entry);
            } catch (RuntimeException exception) {
                problems.add(new ConfigProblem(path + ".scoreEntries[" + i + "]", exception.getMessage()));
            }
        }
        return result;
    }

    private Set<Integer> decodeCrossedThresholds(List<Integer> values, String path,
            List<ConfigProblem> problems) {
        if (values == null) return Set.of();
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            Integer points = values.get(i);
            if (points == null || points < 0) {
                problems.add(new ConfigProblem(path + ".crossedThresholds[" + i + "]",
                        "threshold points must not be negative"));
            } else {
                result.add(points);
            }
        }
        return Set.copyOf(result);
    }

    private Optional<MuteState> decodeMute(PersistentSnapshot.PersistentMute value, int schemaVersion,
            Instant legacyMutedAt, Instant now, String path, List<ConfigProblem> problems) {
        if (value == null) return Optional.empty();
        try {
            MuteKind kind = schemaVersion == 1 ? MuteKind.TEMPORARY : MuteKind.valueOf(value.kind());
            Instant mutedAt = schemaVersion == 1 ? legacyMutedAt : Instant.parse(value.mutedAt());
            Instant mutedUntil = value.mutedUntil() == null ? null : Instant.parse(value.mutedUntil());
            if (schemaVersion == 1 && mutedUntil != null && !mutedUntil.isAfter(mutedAt)) {
                mutedAt = Instant.MIN;
            }
            UUID moderatorId = value.moderatorId() == null || value.moderatorId().isBlank()
                    ? null : UUID.fromString(value.moderatorId());
            MuteState mute = new MuteState(kind, mutedAt, mutedUntil, value.reason(), value.source(),
                    value.ruleId(), moderatorId);
            return mute.isActiveAt(now) ? Optional.of(mute) : Optional.empty();
        } catch (RuntimeException exception) {
            problems.add(new ConfigProblem(path + ".mute", exception.getMessage()));
            return Optional.empty();
        }
    }

    private Instant parseLegacyMutedAt(String savedAt) {
        if (savedAt == null) return Instant.MIN;
        try {
            return Instant.parse(savedAt);
        } catch (RuntimeException exception) {
            return Instant.MIN;
        }
    }

    private List<ViolationRecord> decodeViolations(List<PersistentSnapshot.PersistentViolation> values, Instant now,
            CompiledAutoModConfig config, String path, List<ConfigProblem> problems) {
        if (values == null) throw new IllegalArgumentException("violations are required");
        if (values.size() > config.history().maximumEntriesPerPlayer()) throw new IllegalArgumentException("too many violations");
        Instant cutoff = now.minus(Duration.ofDays(config.history().retentionDays()));
        List<ViolationRecord> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            try {
                var value = values.get(i);
                Instant timestamp = Instant.parse(value.timestamp());
                if (timestamp.isBefore(cutoff)) continue;
                List<RuleCategory> categories = value.categories().stream().map(RuleCategory::valueOf).toList();
                Severity severity = value.severity() == null ? Severity.MODERATE : Severity.valueOf(value.severity());
                Optional<MuteKind> muteKind = value.muteKind() == null
                        ? Optional.empty() : Optional.of(MuteKind.valueOf(value.muteKind()));
                result.add(new ViolationRecord(UUID.fromString(value.eventId()), timestamp,
                        UUID.fromString(value.playerUuid()), value.playerName(), value.ruleIds(), categories, severity,
                        MessageDecision.valueOf(value.decision()), value.pointsAdded(), value.scoreAfter(),
                        value.actions().stream().map(ActionType::valueOf).toList(), muteKind,
                        config.logging().storeOriginalMessages() ? Optional.ofNullable(value.originalMessage()) : Optional.empty()));
            } catch (RuntimeException exception) {
                problems.add(new ConfigProblem(path + ".violations[" + i + "]", exception.getMessage()));
            }
        }
        return result;
    }

    private PersistentSnapshot.PersistentViolation persistentViolation(ViolationRecord value, boolean storeOriginalMessages) {
        return new PersistentSnapshot.PersistentViolation(value.eventId().toString(), value.timestamp().toString(),
                value.playerId().toString(), value.playerName(), value.ruleIds(),
                value.categories().stream().map(Enum::name).toList(), value.severity().name(), value.decision().name(),
                value.pointsAdded(), value.scoreAfter(), value.actions().stream().map(Enum::name).toList(),
                value.muteKind().map(Enum::name).orElse(null),
                storeOriginalMessages ? value.originalMessage().orElse(null) : null);
    }
}
