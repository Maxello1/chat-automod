package com.maxello1.chatautomod.fabric262;

import com.maxello1.chatautomod.core.api.ChatIngress;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

import java.util.Objects;

final class FabricChatIngress implements ChatIngress {
    private final FabricRuntime runtime;

    FabricChatIngress(FabricRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String signedContent = message.signedContent();
            return runtime.evaluatePublicChat(sender, signedContent);
        });
    }
}
