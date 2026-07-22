package com.maxello1.chatautomod.core.history;

import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import com.maxello1.chatautomod.core.state.PlayerStateStore;
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
        Instant now = clock.instant();
        return states.transact(playerId, playerName, now, current -> {
            boolean changed = !current.violations().isEmpty() || !current.scoreEntries().isEmpty();
            PlayerModerationState cleared = new PlayerModerationState(current.revision() + 1, current.playerId(),
                    current.lastKnownName(), current.recentMessageTimes(), current.recentMessages(), List.of(),
                    current.mute(), List.of(), current.lastMuteNotificationAt(), now);
            return new StateUpdate<>(cleared, changed);
        });
    }
}
