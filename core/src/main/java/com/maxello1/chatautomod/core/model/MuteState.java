package com.maxello1.chatautomod.core.model;

import java.time.Instant;
import java.util.Objects;

public record MuteState(Instant mutedUntil, String reason, String source) {
    public MuteState {
        Objects.requireNonNull(mutedUntil, "mutedUntil");
        reason = Objects.requireNonNullElse(reason, "Muted by Chat AutoMod");
        source = Objects.requireNonNullElse(source, "automatic");
    }

    public boolean activeAt(Instant instant) {
        return mutedUntil.isAfter(instant);
    }
}
