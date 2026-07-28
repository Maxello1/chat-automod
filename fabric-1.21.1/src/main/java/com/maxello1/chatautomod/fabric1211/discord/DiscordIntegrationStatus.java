package com.maxello1.chatautomod.fabric1211.discord;

import java.util.Objects;

public record DiscordIntegrationStatus(
        boolean configured,
        boolean enabled,
        Connection connection,
        boolean guildConfigured,
        boolean channelConfigured,
        int authorisedRoleCount,
        int authorisedUserCount,
        int openCaseCount,
        String lastSafeError
) {
    public DiscordIntegrationStatus {
        Objects.requireNonNull(connection, "connection");
        lastSafeError = lastSafeError == null ? "" : lastSafeError;
    }

    public enum Connection {
        DISABLED,
        STARTING,
        READY,
        FAILED,
        STOPPED
    }
}
