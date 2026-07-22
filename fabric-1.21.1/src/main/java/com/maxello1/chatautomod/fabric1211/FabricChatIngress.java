package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.action.ActionPlan;
import com.maxello1.chatautomod.core.api.ChatIngress;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.api.MessageSource;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class FabricChatIngress implements ChatIngress {
    private final Supplier<BackportRuntime> runtimeSupplier;
    private final Logger logger;

    public FabricChatIngress(Supplier<BackportRuntime> runtimeSupplier, Logger logger) {
        this.runtimeSupplier = Objects.requireNonNull(runtimeSupplier, "runtimeSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
            BackportRuntime runtime = runtimeSupplier.get();
            if (runtime == null) {
                return true;
            }

            Instant now = runtime.clock().instant();
            if (runtime.platform().blockMutedMessage(sender, now)) {
                return false;
            }

            MessageContext context = new MessageContext(
                    sender.getUUID(),
                    sender.getGameProfile().getName(),
                    message.signedContent(),
                    MessageSource.PUBLIC_CHAT,
                    "",
                    now,
                    false
            );

            ActionPlan plan;
            try {
                plan = runtime.evaluate(context, runtime.platform().bypassProfile(sender));
            } catch (RuntimeException exception) {
                logger.error("Chat moderation evaluation failed for {}. The original message will be allowed.", sender.getGameProfile().getName(), exception);
                return true;
            }

            try {
                runtime.execute(plan);
            } catch (RuntimeException exception) {
                logger.error("Chat moderation actions failed for {}. The message decision will still be enforced.", sender.getGameProfile().getName(), exception);
            }
            return plan.decision() == MessageDecision.ALLOW;
        });
    }
}
