package com.maxello1.chatautomod.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MuteState(
        MuteKind kind,
        Instant mutedAt,
        Instant mutedUntil,
        String reason,
        String source,
        String ruleId,
        UUID moderatorId
) {
    public MuteState {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mutedAt, "mutedAt");
        if (kind == MuteKind.TEMPORARY) {
            Objects.requireNonNull(mutedUntil, "mutedUntil");
            if (!mutedUntil.isAfter(mutedAt)) {
                throw new IllegalArgumentException("temporary mute must end after it begins");
            }
        } else if (mutedUntil != null) {
            throw new IllegalArgumentException("permanent mute must not have an end time");
        }
        reason = Objects.requireNonNullElse(reason, "Muted by Chat AutoMod");
        source = Objects.requireNonNullElse(source, "automatic");
        ruleId = Objects.requireNonNullElse(ruleId, "");
    }

    public MuteState(Instant mutedUntil, String reason, String source) {
        this(MuteKind.TEMPORARY, Instant.MIN, mutedUntil, reason, source, "", null);
    }

    public static MuteState temporary(Instant mutedAt, Instant mutedUntil, String reason, String source,
            String ruleId, UUID moderatorId) {
        return new MuteState(MuteKind.TEMPORARY, mutedAt, mutedUntil, reason, source, ruleId, moderatorId);
    }

    public static MuteState permanent(Instant mutedAt, String reason, String source,
            String ruleId, UUID moderatorId) {
        return new MuteState(MuteKind.PERMANENT, mutedAt, null, reason, source, ruleId, moderatorId);
    }

    public boolean isActiveAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return kind == MuteKind.PERMANENT || mutedUntil.isAfter(instant);
    }

    public boolean activeAt(Instant instant) {
        return isActiveAt(instant);
    }
}
