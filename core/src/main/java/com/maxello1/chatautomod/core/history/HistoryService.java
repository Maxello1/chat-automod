package com.maxello1.chatautomod.core.history;

import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import com.maxello1.chatautomod.core.state.PlayerStateStore;
import com.maxello1.chatautomod.core.state.StateClearScope;
import com.maxello1.chatautomod.core.state.StateUpdate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class HistoryService {
    private final PlayerStateStore states;
    private final Clock clock;

    public HistoryService(PlayerStateStore states, Clock clock) {
        this.states = Objects.requireNonNull(states, "states");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HistoryPage<ViolationRecord> violations(UUID playerId, String playerName, int page, int pageSize) {
        if (page < 1) throw new IllegalArgumentException("page must be at least 1");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page size must be between 1 and 100");
        List<ViolationRecord> newestFirst = new ArrayList<>(states.snapshot(playerId, playerName, clock.instant()).violations());
        java.util.Collections.reverse(newestFirst);
        int total = newestFirst.size();
        int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        int from = Math.min(total, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);
        return new HistoryPage<>(newestFirst.subList(from, to), page, pageSize, total, totalPages);
    }

    public boolean clearViolationsAndScore(UUID playerId, String playerName) {
        return clearSelected(playerId, playerName, true, true, false);
    }

    public boolean clear(UUID playerId, String playerName, StateClearScope scope) {
        Objects.requireNonNull(scope, "scope");
        return switch (scope) {
            case SCORE -> clearScore(playerId, playerName);
            case HISTORY -> clearHistory(playerId, playerName);
            case SPAM -> clearSpam(playerId, playerName);
            case ALL -> clearAll(playerId, playerName);
        };
    }

    public boolean clearScore(UUID playerId, String playerName) {
        return clearSelected(playerId, playerName, true, false, false);
    }

    public boolean clearHistory(UUID playerId, String playerName) {
        return clearSelected(playerId, playerName, false, true, false);
    }

    public boolean clearSpam(UUID playerId, String playerName) {
        return clearSelected(playerId, playerName, false, false, true);
    }

    public boolean clearAll(UUID playerId, String playerName) {
        return clearSelected(playerId, playerName, true, true, true);
    }

    public ViolationRecord recordManualUnmute(UUID playerId, String playerName,
            UUID moderatorId, com.maxello1.chatautomod.core.model.MuteKind previousKind,
            int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        Instant now = clock.instant();
        return states.transact(playerId, playerName, now, current -> {
            List<ViolationRecord> records = new ArrayList<>(current.violations());
            int score = com.maxello1.chatautomod.core.score.ScoreMath.sumActive(
                    current.scoreEntries(), now);
            ViolationRecord record = new ViolationRecord(UUID.randomUUID(), now,
                    playerId, playerName, List.of("manual.unmute"),
                    List.of(com.maxello1.chatautomod.core.model.RuleCategory.SECURITY),
                    com.maxello1.chatautomod.core.model.Severity.LOW,
                    com.maxello1.chatautomod.core.api.MessageDecision.ALLOW,
                    0, score, List.of(), Optional.ofNullable(previousKind), Optional.empty());
            records.add(record);
            if (records.size() > maximumEntries) {
                records = new ArrayList<>(records.subList(
                        records.size() - maximumEntries, records.size()));
            }
            PlayerModerationState updated = new PlayerModerationState(
                    current.revision() + 1, current.playerId(), current.lastKnownName(),
                    current.recentMessageTimes(), current.recentMessages(),
                    current.scoreEntries(), current.crossedThresholds(), current.mute(),
                    records, current.lastMuteNotificationAt(), now);
            return new StateUpdate<>(updated, record);
        });
    }

    private boolean clearSelected(UUID playerId, String playerName, boolean score, boolean history, boolean spam) {
        if (!states.contains(playerId)) return false;
        Instant now = clock.instant();
        return states.transact(playerId, playerName, now, current -> {
            boolean changed = (score && (!current.scoreEntries().isEmpty() || !current.crossedThresholds().isEmpty()))
                    || (history && !current.violations().isEmpty())
                    || (spam && (!current.recentMessageTimes().isEmpty() || !current.recentMessages().isEmpty()));
            if (!changed) return new StateUpdate<>(current, false);
            PlayerModerationState cleared = new PlayerModerationState(current.revision() + 1, current.playerId(),
                    current.lastKnownName(), spam ? List.of() : current.recentMessageTimes(),
                    spam ? List.of() : current.recentMessages(), score ? List.of() : current.scoreEntries(),
                    score ? java.util.Set.of() : current.crossedThresholds(), current.mute(),
                    history ? List.of() : current.violations(), current.lastMuteNotificationAt(), now);
            return new StateUpdate<>(cleared, true);
        });
    }
}
