package com.maxello1.chatautomod.core.model;

import java.time.Instant;
import java.util.Objects;

public record ScoreEntry(int points, String ruleId, Instant createdAt, Instant expiresAt) {
    public ScoreEntry {
        if (points <= 0) {
            throw new IllegalArgumentException("points must be positive");
        }
        ruleId = Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
