package com.maxello1.chatautomod.core.action;

import java.time.Duration;
import java.util.Objects;

public record ConfiguredAction(
        ActionType type,
        String message,
        Duration duration,
        String reason,
        CommandTemplate command
) {
    public ConfiguredAction {
        Objects.requireNonNull(type, "type");
        message = message == null ? "" : message;
        reason = reason == null ? "" : reason;
    }

    public static ConfiguredAction notifyStaff() {
        return new ConfiguredAction(ActionType.NOTIFY_STAFF, "", null, "", null);
    }
}
