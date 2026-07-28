package com.maxello1.chatautomod.fabric1211.discord;

import java.time.Duration;
import java.util.Set;

public record DiscordConfig(
        int schemaVersion,
        boolean enabled,
        String tokenEnvironmentVariable,
        String botToken,
        String guildId,
        String alertChannelId,
        Set<String> allowedRoleIds,
        Set<String> allowedUserIds,
        String mentionRoleId,
        Duration caseExpiry,
        boolean includeOriginalMessage,
        Actions actions
) {
    public DiscordConfig {
        tokenEnvironmentVariable = tokenEnvironmentVariable == null ? "" : tokenEnvironmentVariable;
        botToken = botToken == null ? "" : botToken;
        guildId = guildId == null ? "" : guildId;
        alertChannelId = alertChannelId == null ? "" : alertChannelId;
        allowedRoleIds = Set.copyOf(allowedRoleIds);
        allowedUserIds = Set.copyOf(allowedUserIds);
        mentionRoleId = mentionRoleId == null ? "" : mentionRoleId;
    }

    public static DiscordConfig defaults() {
        return new DiscordConfig(
                1,
                false,
                "CHATAUTOMOD_DISCORD_TOKEN",
                "",
                "",
                "",
                Set.of(),
                Set.of(),
                "",
                Duration.ofHours(24),
                false,
                new Actions(true, true, true));
    }

    public DiscordConfig disabled() {
        return new DiscordConfig(
                schemaVersion,
                false,
                tokenEnvironmentVariable,
                botToken,
                guildId,
                alertChannelId,
                allowedRoleIds,
                allowedUserIds,
                mentionRoleId,
                caseExpiry,
                includeOriginalMessage,
                actions);
    }

    @Override
    public String toString() {
        return "DiscordConfig[schemaVersion=" + schemaVersion
                + ", enabled=" + enabled
                + ", tokenEnvironmentVariable=" + tokenEnvironmentVariable
                + ", botToken=" + (botToken.isEmpty() ? "<empty>" : "<redacted>")
                + ", guildId=" + guildId
                + ", alertChannelId=" + alertChannelId
                + ", allowedRoleIds=" + allowedRoleIds
                + ", allowedUserIds=" + allowedUserIds
                + ", mentionRoleId=" + mentionRoleId
                + ", caseExpiry=" + caseExpiry
                + ", includeOriginalMessage=" + includeOriginalMessage
                + ", actions=" + actions + "]";
    }

    public record Actions(
            boolean mute10Minutes,
            boolean mute1Hour,
            boolean permanentBan
    ) {
        public boolean anyEnabled() {
            return mute10Minutes || mute1Hour || permanentBan;
        }

        public boolean isEnabled(DiscordPunishment punishment) {
            return switch (punishment) {
                case MUTE_10_MINUTES -> mute10Minutes;
                case MUTE_1_HOUR -> mute1Hour;
                case PERMANENT_BAN -> permanentBan;
                case DISMISS -> true;
            };
        }
    }
}
