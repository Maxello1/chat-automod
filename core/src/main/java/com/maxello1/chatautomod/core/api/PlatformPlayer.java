package com.maxello1.chatautomod.core.api;

import java.util.Objects;
import java.util.UUID;

public record PlatformPlayer(UUID playerId, String playerName) {
    public PlatformPlayer {
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
    }
}
