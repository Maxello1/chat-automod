package com.maxello1.chatautomod.core.score;

public record ScoreContribution(String ruleId, int points) {
    public ScoreContribution {
        if (points < 0) throw new IllegalArgumentException("points must not be negative");
    }
}
