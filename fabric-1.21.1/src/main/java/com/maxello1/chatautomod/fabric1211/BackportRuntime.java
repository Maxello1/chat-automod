package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import net.minecraft.server.MinecraftServer;

import java.time.Clock;

final class BackportRuntime {
    private final MinecraftServer server;
    private final Clock clock;
    private final FabricModerationPlatform platform;
    private final ActionPlanExecutor actionExecutor;
    private final ModerationService moderation;

    BackportRuntime(MinecraftServer server, FabricPermissionService permissions, Clock clock) {
        this.server = server;
        this.clock = clock;
        this.platform = new FabricModerationPlatform(server, permissions);
        this.actionExecutor = new ActionPlanExecutor(platform);
        this.moderation = new ModerationService(new ActiveConfig(), new InMemoryPlayerStateStore());
    }

    public ActionPlan evaluate(MessageContext context, BypassProfile bypass) {
        return moderation.evaluateLive(context, bypass).actionPlan();
    }

    MinecraftServer server() {
        return server;
    }

    Clock clock() {
        return clock;
    }

    FabricModerationPlatform platform() {
        return platform;
    }

    void execute(ActionPlan plan) {
        actionExecutor.execute(plan);
    }
}
