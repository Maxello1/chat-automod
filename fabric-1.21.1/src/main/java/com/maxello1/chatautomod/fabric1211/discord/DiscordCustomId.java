package com.maxello1.chatautomod.fabric1211.discord;

import java.util.Optional;

public record DiscordCustomId(String caseId, DiscordPunishment action) {
    public static Optional<DiscordCustomId> parse(String value) {
        if (value == null || !value.startsWith("cam:")) {
            return Optional.empty();
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3 || !"cam".equals(parts[0]) || !isValidCaseId(parts[1])) {
            return Optional.empty();
        }
        return DiscordPunishment.fromCode(parts[2])
                .map(action -> new DiscordCustomId(parts[1], action));
    }

    static boolean isValidCaseId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,64}");
    }
}
