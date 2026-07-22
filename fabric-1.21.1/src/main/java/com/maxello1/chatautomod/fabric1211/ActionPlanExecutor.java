package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MinecraftPlatformAdapter;

public final class ActionPlanExecutor {
    private final MinecraftPlatformAdapter platform;

    public ActionPlanExecutor(MinecraftPlatformAdapter platform) {
        this.platform = platform;
    }

    public void execute(ActionPlan plan) {
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
