package com.maxello1.chatautomod.fabric1211.discord;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

public final class DiscordInteractionRouter {
    private final DiscordCaseStore caseStore;
    private final DiscordAuthorisationService authorisation;

    public DiscordInteractionRouter(
            DiscordCaseStore caseStore,
            DiscordAuthorisationService authorisation
    ) {
        this.caseStore = Objects.requireNonNull(caseStore, "caseStore");
        this.authorisation = Objects.requireNonNull(authorisation, "authorisation");
    }

    public RouteResult route(Request request, DiscordConfig config) {
        if (!request.ready()) {
            return RouteResult.of(Outcome.NOT_READY);
        }
        if (!config.guildId().equals(request.guildId())) {
            return RouteResult.of(Outcome.WRONG_GUILD);
        }
        if (!config.alertChannelId().equals(request.channelId())) {
            return RouteResult.of(Outcome.WRONG_CHANNEL);
        }
        if (request.customId() == null || !request.customId().startsWith("cam:")) {
            return RouteResult.of(Outcome.IGNORED);
        }
        Optional<DiscordCustomId> parsed = DiscordCustomId.parse(request.customId());
        if (parsed.isEmpty()) {
            return RouteResult.of(Outcome.MALFORMED);
        }
        if (!authorisation.isAuthorised(request.userId(), request.roleIds(), config)) {
            return RouteResult.of(Outcome.UNAUTHORISED);
        }
        DiscordCustomId customId = parsed.orElseThrow();
        if (!config.actions().isEnabled(customId.action())) {
            return new RouteResult(Outcome.ACTION_DISABLED, customId, Optional.empty());
        }
        Optional<DiscordModerationCase> found = caseStore.get(customId.caseId());
        if (found.isEmpty()) {
            return new RouteResult(Outcome.UNKNOWN_CASE, customId, Optional.empty());
        }
        DiscordModerationCase moderationCase = found.orElseThrow();
        if (moderationCase.status() == DiscordCaseStatus.EXPIRED) {
            return new RouteResult(Outcome.EXPIRED, customId, found);
        }
        if (moderationCase.status() != DiscordCaseStatus.OPEN) {
            return new RouteResult(Outcome.NOT_OPEN, customId, found);
        }
        if (moderationCase.discordMessageId().isEmpty()
                || !moderationCase.discordMessageId().equals(request.messageId())) {
            return new RouteResult(Outcome.MESSAGE_MISMATCH, customId, found);
        }
        DiscordCaseStore.ClaimResult claim = caseStore.claim(
                customId.caseId(),
                request.userId(),
                request.displayName(),
                customId.action());
        Outcome outcome = switch (claim.outcome()) {
            case CLAIMED -> Outcome.CLAIMED;
            case UNKNOWN -> Outcome.UNKNOWN_CASE;
            case EXPIRED -> Outcome.EXPIRED;
            case NOT_OPEN -> Outcome.NOT_OPEN;
        };
        return new RouteResult(outcome, customId, claim.moderationCase());
    }

    public enum Outcome {
        CLAIMED,
        IGNORED,
        NOT_READY,
        WRONG_GUILD,
        WRONG_CHANNEL,
        MALFORMED,
        UNAUTHORISED,
        ACTION_DISABLED,
        UNKNOWN_CASE,
        EXPIRED,
        NOT_OPEN,
        MESSAGE_MISMATCH
    }

    public record Request(
            boolean ready,
            String guildId,
            String channelId,
            String customId,
            String userId,
            Collection<String> roleIds,
            String displayName,
            String messageId
    ) {
        public Request {
            guildId = Objects.requireNonNullElse(guildId, "");
            channelId = Objects.requireNonNullElse(channelId, "");
            userId = Objects.requireNonNullElse(userId, "");
            roleIds = roleIds == null ? java.util.List.of() : java.util.List.copyOf(roleIds);
            displayName = Objects.requireNonNullElse(displayName, "Discord moderator");
            messageId = Objects.requireNonNullElse(messageId, "");
        }
    }

    public record RouteResult(
            Outcome outcome,
            DiscordCustomId customId,
            Optional<DiscordModerationCase> moderationCase
    ) {
        static RouteResult of(Outcome outcome) {
            return new RouteResult(outcome, null, Optional.empty());
        }
    }
}
