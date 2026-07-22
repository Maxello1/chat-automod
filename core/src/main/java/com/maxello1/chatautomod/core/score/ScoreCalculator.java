package com.maxello1.chatautomod.core.score;

import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.ScoreEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ScoreCalculator {
    public ScoreTransition calculate(Instant now, List<ScoreEntry> existing, List<ScoreContribution> contributions,
            CompiledAutoModConfig.Score config, int maximumEntries) {
        List<ScoreEntry> entries = new ArrayList<>();
        long beforeTotal = 0;
        for (ScoreEntry entry : existing) {
            if (entry.expiresAt().isAfter(now)) {
                entries.add(entry);
                beforeTotal = Math.min(Integer.MAX_VALUE, beforeTotal + entry.points());
            }
        }
        int before = (int) beforeTotal;
        int remaining = config.maximumPointsPerMessage();
        int added = 0;
        for (ScoreContribution contribution : contributions) {
            int awarded = Math.min(Math.max(0, contribution.points()), remaining);
            if (awarded == 0) continue;
            if (entries.size() >= maximumEntries) {
                throw new StateCapacityException("score entry capacity reached");
            }
            entries.add(new ScoreEntry(awarded, contribution.ruleId(), now, now.plus(config.pointDecay())));
            added = ScoreMath.saturatingAdd(added, awarded);
            remaining -= awarded;
            if (remaining == 0) break;
        }
        int after = ScoreMath.saturatingAdd(before, added);
        CompiledAutoModConfig.Threshold crossed = null;
        for (CompiledAutoModConfig.Threshold threshold : config.thresholds()) {
            if (before < threshold.points() && after >= threshold.points()) crossed = threshold;
        }
        Optional<ThresholdCrossing> crossing = crossed == null ? Optional.empty()
                : Optional.of(new ThresholdCrossing(crossed.points(), crossed));
        return new ScoreTransition(entries, before, added, after, crossing);
    }

    public static final class StateCapacityException extends RuntimeException {
        public StateCapacityException(String message) { super(message); }
    }
}
