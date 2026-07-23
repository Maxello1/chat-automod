package com.maxello1.chatautomod.fabric1211;

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
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) ->
                runtime.evaluatePublicChat(sender, message.signedContent()));
    }
}
