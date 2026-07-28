package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscordAlertPublisherTest {
    @Test
    void publishesThroughInjectedDestinationWithoutDiscordConnection() {
        AtomicReference<DiscordAlertMessage> sent = new AtomicReference<>();
        DiscordAlertDestination destination = message -> {
            sent.set(message);
            return CompletableFuture.completedFuture("message-id");
        };
        DiscordAlertPublisher publisher = new DiscordAlertPublisher(
                new DiscordAlertFormatter(),
                () -> destination);

        String messageId = publisher.publish(alert(), DiscordConfig.defaults()).join();

        assertEquals("message-id", messageId);
        assertEquals("Chat AutoMod staff alert", sent.get().title());
    }

    @Test
    void publishingAfterShutdownFailsSafelyWithoutThrowingOnCallerThread() {
        AtomicReference<DiscordAlertDestination> destination = new AtomicReference<>();
        DiscordAlertPublisher publisher = new DiscordAlertPublisher(
                new DiscordAlertFormatter(),
                destination::get);

        CompletableFuture<String> result = publisher.publish(alert(), DiscordConfig.defaults());

        assertThrows(CompletionException.class, result::join);
    }

    private static ModerationAction.NotifyStaff alert() {
        return new ModerationAction.NotifyStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Player",
                List.of("advertising"),
                MessageDecision.BLOCK,
                "",
                5,
                9,
                Instant.parse("2026-07-28T10:15:30Z"));
    }
}
