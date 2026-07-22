package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.ModerationPlatform;

import java.util.Objects;

public final class PlatformActionExecutor {
    public void execute(ActionPlan plan, ModerationPlatform platform) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(platform, "platform");
        for (ModerationAction action : plan.actions()) {
            switch (action) {
                case ModerationAction.NotifyStaff alert -> platform.notifyStaff(alert);
                case ModerationAction.Warn warn -> platform.notifyPlayer(warn.playerId(), warn.message());
                case ModerationAction.Mute mute -> platform.mutePlayer(mute.playerId(), mute.until(), mute.reason());
                case ModerationAction.Kick kick -> platform.kickPlayer(kick.playerId(), kick.reason());
                case ModerationAction.ExecuteCommand command -> platform.executeServerCommand(command.command());
            }
        }
    }
}
