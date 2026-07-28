package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordInteractionRouterTest {
    private static final Instant NOW = Instant.parse("2026-07-28T10:15:30Z");
    private DiscordCaseStore cases;
    private DiscordInteractionRouter router;
    private DiscordModerationCase moderationCase;

    @BeforeEach
    void setUp() {
        cases = new DiscordCaseStore(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "CaseId_12345678");
        router = new DiscordInteractionRouter(cases, new DiscordAuthorisationService());
        moderationCase = cases.create(alert(), Duration.ofHours(1));
        cases.attachMessageId(moderationCase.caseId(), "message-1");
    }

    @Test
    void claimsAuthorisedActionAfterAllContextChecks() {
        DiscordInteractionRouter.RouteResult result = router.route(
                request("user-1", List.of(), DiscordPunishment.MUTE_10_MINUTES),
                config(new DiscordConfig.Actions(true, true, true)));

        assertEquals(DiscordInteractionRouter.Outcome.CLAIMED, result.outcome());
        assertEquals(DiscordCaseStatus.PROCESSING, result.moderationCase().orElseThrow().status());
    }

    @Test
    void rejectsUnauthorisedUserBeforeCaseDetailsAreReturned() {
        DiscordInteractionRouter.RouteResult result = router.route(
                request("other-user", List.of("other-role"), DiscordPunishment.MUTE_10_MINUTES),
                config(new DiscordConfig.Actions(true, true, true)));

        assertEquals(DiscordInteractionRouter.Outcome.UNAUTHORISED, result.outcome());
        assertEquals(java.util.Optional.empty(), result.moderationCase());
    }

    @Test
    void rejectsActionDisabledInCurrentConfiguration() {
        DiscordInteractionRouter.RouteResult result = router.route(
                request("user-1", List.of(), DiscordPunishment.PERMANENT_BAN),
                config(new DiscordConfig.Actions(true, true, false)));

        assertEquals(DiscordInteractionRouter.Outcome.ACTION_DISABLED, result.outcome());
        assertEquals(DiscordCaseStatus.OPEN, cases.get(moderationCase.caseId()).orElseThrow().status());
    }

    @Test
    void rejectsReplayedCustomIdFromDifferentMessage() {
        DiscordInteractionRouter.Request request = new DiscordInteractionRouter.Request(
                true,
                "1",
                "2",
                DiscordPunishment.MUTE_10_MINUTES.customId(moderationCase.caseId()),
                "user-1",
                List.of(),
                "Moderator",
                "different-message");

        DiscordInteractionRouter.RouteResult result = router.route(
                request,
                config(new DiscordConfig.Actions(true, true, true)));

        assertEquals(DiscordInteractionRouter.Outcome.MESSAGE_MISMATCH, result.outcome());
    }

    private DiscordInteractionRouter.Request request(
            String userId,
            List<String> roles,
            DiscordPunishment action
    ) {
        return new DiscordInteractionRouter.Request(
                true,
                "1",
                "2",
                action.customId(moderationCase.caseId()),
                userId,
                roles,
                "Moderator",
                "message-1");
    }

    private static DiscordConfig config(DiscordConfig.Actions actions) {
        return new DiscordConfig(
                1,
                true,
                "TOKEN",
                "",
                "1",
                "2",
                Set.of("role-1"),
                Set.of("user-1"),
                "",
                Duration.ofHours(24),
                false,
                actions);
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
                NOW);
    }
}
