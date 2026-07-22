package com.maxello1.chatautomod.core.score;

import com.maxello1.chatautomod.core.model.ScoreEntry;

import java.util.List;
import java.util.Optional;

public record ScoreTransition(
        List<ScoreEntry> entries,
        int pointsBefore,
        int pointsAdded,
        int pointsAfter,
        Optional<ThresholdCrossing> crossing
) {
    public ScoreTransition {
        entries = List.copyOf(entries);
        crossing = crossing == null ? Optional.empty() : crossing;
    }
}
