package com.maxello1.chatautomod.fabric1211;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatAutoModFabric implements ModInitializer {
    public static final String MOD_ID = "chatautomod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        FabricRuntime runtime = new FabricRuntime(LOGGER);
        new FabricChatIngress(runtime).register();
        new AutoModCommands(runtime).register();
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::startServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(runtime::stopServer);
        LOGGER.info("Chat AutoMod initialized for Minecraft 1.21.1");
    }
}
