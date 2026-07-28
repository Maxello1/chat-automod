package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.fabric1211.ExternalModerator;
import com.maxello1.chatautomod.fabric1211.PunishmentRequest;
import com.maxello1.chatautomod.fabric1211.PunishmentResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class DiscordInteractionListener extends ListenerAdapter {
    private final Supplier<DiscordIntegrationStatus> status;
    private final Supplier<DiscordConfig> config;
    private final DiscordInteractionRouter router;
    private final DiscordCaseStore caseStore;
    private final DiscordPunishmentHandler punishmentHandler;
    private final Logger logger;

    DiscordInteractionListener(
            Supplier<DiscordIntegrationStatus> status,
            Supplier<DiscordConfig> config,
            DiscordInteractionRouter router,
            DiscordCaseStore caseStore,
            DiscordPunishmentHandler punishmentHandler,
            Logger logger
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.config = Objects.requireNonNull(config, "config");
        this.router = Objects.requireNonNull(router, "router");
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.punishmentHandler = Objects.requireNonNull(punishmentHandler, "punishmentHandler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Member member = event.getMember();
        String guildId = event.isFromGuild() ? event.getGuild().getId() : "";
        String displayName = member == null
                ? event.getUser().getEffectiveName()
                : member.getEffectiveName();
        List<String> roleIds = member == null
                ? List.of()
                : member.getRoles().stream().map(role -> role.getId()).toList();
        DiscordIntegrationStatus currentStatus = status.get();
        DiscordInteractionRouter.RouteResult result = router.route(
                new DiscordInteractionRouter.Request(
                        currentStatus.connection() == DiscordIntegrationStatus.Connection.READY,
                        guildId,
                        event.getChannel().getId(),
                        event.getComponentId(),
                        event.getUser().getId(),
                        roleIds,
                        displayName,
                        event.getMessageId()),
                config.get());

        if (result.outcome() == DiscordInteractionRouter.Outcome.IGNORED) {
            return;
        }
        if (result.outcome() != DiscordInteractionRouter.Outcome.CLAIMED) {
            deny(event, responseFor(result.outcome()));
            return;
        }

        DiscordModerationCase moderationCase = result.moderationCase().orElseThrow();
        DiscordPunishment action = result.customId().action();
        ExternalModerator moderator = new ExternalModerator(
                "discord",
                event.getUser().getId(),
                displayName);
        PunishmentRequest request = new PunishmentRequest(
                moderationCase.caseId(),
                moderationCase.playerId(),
                moderationCase.playerName(),
                moderationCase.ruleIds(),
                action,
                moderator);

        event.deferReply().setEphemeral(true).queue(
                hook -> execute(event.getMessage(), hook, request),
                failure -> {
                    caseStore.fail(moderationCase.caseId(), true);
                    logger.warn("Could not acknowledge a Discord moderation interaction ({})",
                            failure.getClass().getSimpleName());
                });
    }

    private void execute(Message alertMessage, InteractionHook hook, PunishmentRequest request) {
        java.util.concurrent.CompletableFuture<PunishmentResult> future;
        try {
            future = punishmentHandler.execute(request);
        } catch (RuntimeException exception) {
            future = java.util.concurrent.CompletableFuture.failedFuture(exception);
        }
        future.whenComplete((result, exception) -> {
            PunishmentResult completed = exception == null
                    ? result
                    : PunishmentResult.failure("The moderation action failed safely.", false);
            if (exception != null) {
                Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                        ? exception.getCause()
                        : exception;
                logger.warn("Discord moderation action failed ({})", cause.getClass().getSimpleName());
            }
            if (completed.success()) {
                boolean dismissed = request.action() == DiscordPunishment.DISMISS;
                caseStore.complete(request.caseId(), dismissed).ifPresentOrElse(resolved -> {
                    resolveAlertMessage(alertMessage, resolved);
                    hook.editOriginal(completed.safeSummary()).queue();
                }, () -> hook.editOriginal(
                        "The action completed, but this case is no longer available after server shutdown.")
                        .queue());
            } else {
                DiscordModerationCase failed = caseStore.fail(request.caseId(), completed.retrySafe())
                        .orElse(null);
                if (failed != null && failed.status() == DiscordCaseStatus.FAILED) {
                    resolveAlertMessage(alertMessage, failed);
                }
                hook.editOriginal("Action failed: " + completed.safeSummary()).queue();
            }
        });
    }

    private void resolveAlertMessage(Message message, DiscordModerationCase moderationCase) {
        if (message.getEmbeds().isEmpty()) {
            message.editMessageComponents(disabledRows(message)).queue();
            return;
        }
        EmbedBuilder embed = new EmbedBuilder(message.getEmbeds().getFirst())
                .addField(
                        moderationCase.status() == DiscordCaseStatus.FAILED ? "Failed" : "Resolved",
                        moderationCase.selectedAction().resolutionLabel(),
                        true)
                .addField(
                        "Moderator",
                        DiscordAlertFormatter.sanitize(
                                moderationCase.moderatorDisplayName() + " ("
                                        + moderationCase.moderatorUserId() + ")",
                                1_024),
                        true)
                .addField(
                        "Resolution time",
                        Objects.requireNonNullElse(
                                moderationCase.resolutionTimestamp(),
                                Instant.now()).toString(),
                        false);
        MessageEditBuilder edit = new MessageEditBuilder()
                .setEmbeds(List.of(embed.build()))
                .setComponents(disabledRows(message));
        message.editMessage(edit.build()).queue(
                ignored -> {},
                failure -> logger.warn("Could not update a resolved Discord alert ({})",
                        failure.getClass().getSimpleName()));
    }

    private static List<ActionRow> disabledRows(Message message) {
        return message.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .map(ActionRow.class::cast)
                .map(ActionRow::asDisabled)
                .toList();
    }

    private static void deny(ButtonInteractionEvent event, String response) {
        event.reply(response)
                .setEphemeral(true)
                .setAllowedMentions(List.of())
                .queue();
    }

    private static String responseFor(DiscordInteractionRouter.Outcome outcome) {
        return switch (outcome) {
            case NOT_READY -> "Discord moderation is temporarily unavailable.";
            case WRONG_GUILD, WRONG_CHANNEL, MALFORMED, MESSAGE_MISMATCH ->
                    "This moderation action is not valid here.";
            case UNAUTHORISED -> "You are not authorised to use Chat AutoMod actions.";
            case ACTION_DISABLED -> "That moderation action is disabled.";
            case UNKNOWN_CASE, EXPIRED ->
                    "This case has expired or the Minecraft server restarted.";
            case NOT_OPEN -> "This case has already been handled.";
            case CLAIMED, IGNORED -> "This moderation action is unavailable.";
        };
    }
}
