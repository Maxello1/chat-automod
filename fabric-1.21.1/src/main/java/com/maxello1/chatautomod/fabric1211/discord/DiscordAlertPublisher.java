package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class DiscordAlertPublisher {
    private final DiscordAlertFormatter formatter;
    private final Supplier<DiscordAlertDestination> destination;
    private final DiscordCaseStore caseStore;

    DiscordAlertPublisher(
            DiscordAlertFormatter formatter,
            Supplier<DiscordAlertDestination> destination
    ) {
        this(formatter, destination, new DiscordCaseStore());
    }

    DiscordAlertPublisher(
            DiscordAlertFormatter formatter,
            Supplier<DiscordAlertDestination> destination,
            DiscordCaseStore caseStore
    ) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
    }

    CompletableFuture<String> publish(
            ModerationAction.NotifyStaff alert,
            DiscordConfig config
    ) {
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(config, "config");
        try {
            DiscordAlertDestination currentDestination = destination.get();
            if (currentDestination == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Discord integration is not ready"));
            }
            boolean interactive = config.actions().anyEnabled()
                    && (!config.allowedRoleIds().isEmpty() || !config.allowedUserIds().isEmpty());
            DiscordModerationCase moderationCase = interactive
                    ? caseStore.create(alert, config.caseExpiry())
                    : null;
            CompletableFuture<String> sent = currentDestination.send(
                    formatter.format(
                            alert,
                            config,
                            moderationCase == null ? null : moderationCase.caseId()));
            if (moderationCase != null) {
                String caseId = moderationCase.caseId();
                sent.whenComplete((messageId, exception) -> {
                    if (exception == null) {
                        caseStore.attachMessageId(caseId, messageId);
                    } else {
                        caseStore.remove(caseId);
                    }
                });
            }
            return sent;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    CompletableFuture<String> publishTest() {
        try {
            DiscordAlertDestination currentDestination = destination.get();
            if (currentDestination == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Discord integration is not ready"));
            }
            return currentDestination.send(formatter.testMessage());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
