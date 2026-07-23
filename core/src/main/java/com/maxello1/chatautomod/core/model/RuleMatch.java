package com.maxello1.chatautomod.core.model;

import com.maxello1.chatautomod.core.action.RuleEffect;

import java.util.Objects;

public record RuleMatch(
        String ruleId,
        RuleCategory category,
        Severity severity,
        int points,
        String evidence,
        String matchedValue,
        RuleEffect effect
) {
    public RuleMatch {
        ruleId = Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        severity = Objects.requireNonNull(severity, "severity");
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative");
        }
        evidence = Objects.requireNonNullElse(evidence, "");
        matchedValue = Objects.requireNonNullElse(matchedValue, "");
        effect = Objects.requireNonNull(effect, "effect");
    }

    public RuleMatch(String ruleId, RuleCategory category, int points, String evidence,
            String matchedValue, RuleEffect effect) {
        this(ruleId, category, defaultSeverity(category), points, evidence, matchedValue, effect);
    }

    private static Severity defaultSeverity(RuleCategory category) {
        return switch (category) {
            case SECURITY -> Severity.HIGH;
            default -> Severity.MODERATE;
        };
    }
}
