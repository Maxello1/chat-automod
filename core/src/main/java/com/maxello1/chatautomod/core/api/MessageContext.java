package com.maxello1.chatautomod.core.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MessageContext(
        UUID playerId,
        String playerName,
        String rawMessage,
        MessageSource source,
        String commandPath,
        Instant timestamp,
        boolean testMode
) {
    public MessageContext {
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        rawMessage = Objects.requireNonNull(rawMessage, "rawMessage");
        source = Objects.requireNonNull(source, "source");
        commandPath = commandPath == null ? "" : commandPath;
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public MessageContext asTest() {
        return new MessageContext(playerId, playerName, rawMessage, MessageSource.TEST, commandPath, timestamp, true);
    }
}
