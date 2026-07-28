package com.maxello1.chatautomod.fabric1211.discord;

import java.time.Duration;
import java.util.Optional;

public enum DiscordPunishment {
    MUTE_10_MINUTES("m10", "Mute 10m", Duration.ofMinutes(10)),
    MUTE_1_HOUR("m60", "Mute 1h", Duration.ofHours(1)),
    PERMANENT_BAN("ban", "Ban", null),
    DISMISS("dismiss", "Dismiss", null);

    private final String code;
    private final String label;
    private final Duration duration;

    DiscordPunishment(String code, String label, Duration duration) {
        this.code = code;
        this.label = label;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public Optional<Duration> duration() {
        return Optional.ofNullable(duration);
    }

    public String customId(String caseId) {
        return "cam:" + caseId + ":" + code;
    }

    public String resolutionLabel() {
        return switch (this) {
            case MUTE_10_MINUTES -> "Mute 10m";
            case MUTE_1_HOUR -> "Mute 1h";
            case PERMANENT_BAN -> "Permanent ban";
            case DISMISS -> "Dismissed";
        };
    }

    public static Optional<DiscordPunishment> fromCode(String code) {
        return java.util.Arrays.stream(values())
                .filter(action -> action.code.equals(code))
                .findFirst();
    }
}
