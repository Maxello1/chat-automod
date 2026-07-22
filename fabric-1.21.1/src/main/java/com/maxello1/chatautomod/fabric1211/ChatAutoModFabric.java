package com.maxello1.chatautomod.fabric1211;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

public final class ChatAutoModFabric implements ModInitializer {
    public static final String MOD_ID = "chatautomod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Chat AutoMod");

    private static volatile BackportRuntime runtime;

    @Override
    public void onInitialize() {
        FabricPermissionService permissions = new FabricPermissionService(LOGGER, () -> {
            BackportRuntime current = runtime;
            return current == null ? null : current.server();
        });
        new FabricChatIngress(() -> runtime, LOGGER).register();
        AutoModCommands.register(permissions);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            runtime = new BackportRuntime(server, permissions, Clock.systemUTC());
            LOGGER.info("Chat AutoMod initialized for Minecraft 1.21.1.");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime = null);
    }
}
