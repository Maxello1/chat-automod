package com.maxello1.chatautomod.core.api;

import java.util.Objects;
import java.util.UUID;

public record StaffRecipient(UUID playerId, String playerName, boolean inspectEnabled) {
    public StaffRecipient {
        Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
    }
}
