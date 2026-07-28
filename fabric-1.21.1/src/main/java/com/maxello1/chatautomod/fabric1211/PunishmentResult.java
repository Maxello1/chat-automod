package com.maxello1.chatautomod.fabric1211;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record PunishmentResult(
        boolean success,
        String safeSummary,
        boolean retrySafe,
        Instant muteUntil
) {
    public PunishmentResult {
        safeSummary = Objects.requireNonNullElse(safeSummary, "Punishment failed.");
    }

    public static PunishmentResult success(String summary) {
        return new PunishmentResult(true, summary, false, null);
    }

    public static PunishmentResult muted(String summary, Instant until) {
        return new PunishmentResult(true, summary, false, until);
    }

    public static PunishmentResult failure(String summary, boolean retrySafe) {
        return new PunishmentResult(false, summary, retrySafe, null);
    }

    public Optional<Instant> muteExpiry() {
        return Optional.ofNullable(muteUntil);
    }
}
