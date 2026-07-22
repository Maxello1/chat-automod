package com.maxello1.chatautomod.core.score;

import com.maxello1.chatautomod.core.model.ScoreEntry;

import java.time.Instant;
import java.util.List;

public final class ScoreMath {
    private ScoreMath() {}

    public static int sumActive(List<ScoreEntry> entries, Instant now) {
        long total = 0;
        for (ScoreEntry entry : entries) {
            if (!entry.expiresAt().isAfter(now)) continue;
            total += entry.points();
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    public static int saturatingAdd(int left, int right) {
        return (int) Math.min(Integer.MAX_VALUE, (long) left + right);
    }
}
