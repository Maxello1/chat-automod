package com.maxello1.chatautomod.fabric1211.discord;

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class JdaDiscordAlertDestination implements DiscordAlertDestination {
    private final TextChannel channel;

    JdaDiscordAlertDestination(TextChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public CompletableFuture<String> send(DiscordAlertMessage message) {
        CompletableFuture<String> result = new CompletableFuture<>();
        channel.sendMessage(JdaDiscordMessageFactory.create(message)).queue(
                sent -> result.complete(sent.getId()),
                result::completeExceptionally);
        return result;
    }
}
