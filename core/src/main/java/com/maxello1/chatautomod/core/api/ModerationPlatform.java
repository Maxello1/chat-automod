package com.maxello1.chatautomod.core.api;

import com.maxello1.chatautomod.core.action.ModerationAction;

import java.time.Instant;
import java.util.UUID;

public interface ModerationPlatform {
    void notifyPlayer(UUID playerId, String message);
    void notifyStaff(ModerationAction.NotifyStaff alert);
    void mutePlayer(UUID playerId, Instant until, String reason);
    void kickPlayer(UUID playerId, String reason);
    void executeServerCommand(String command);
}
