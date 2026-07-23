package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.action.ModerationAction;

import java.util.Objects;

final class ActionPlanExecutor {
    private final FabricRuntime runtime;
    private final FabricModerationPlatform platform;

    ActionPlanExecutor(FabricRuntime runtime, FabricModerationPlatform platform) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    void execute(ActionPlan plan) {
        for (ModerationAction action : plan.actions()) {
            try {
                switch (action) {
                    case ModerationAction.NotifyStaff alert -> platform.notifyStaff(alert);
                    case ModerationAction.Warn warning -> platform.notifyPlayer(
                            warning.playerId(),
                            warning.message());
                    case ModerationAction.Mute mute -> platform.applyAutomaticMute(mute);
                    case ModerationAction.Kick kick -> platform.kickPlayer(
                            kick.playerId(),
                            kick.reason());
                    case ModerationAction.ExecuteCommand command -> platform.executeServerCommand(
                            command.command());
                }
            } catch (RuntimeException exception) {
                runtime.logActionFailure(action.type(), exception);
            }
        }
    }
}
