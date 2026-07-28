package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.MessageDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordCaseStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-28T10:15:30Z");

    @Test
    void createsCaseAndAttachesDiscordMessageId() {
        DiscordCaseStore store = store();

        DiscordModerationCase created = store.create(alert(NOW), Duration.ofHours(24));
        store.attachMessageId(created.caseId(), "456");

        DiscordModerationCase stored = store.get(created.caseId()).orElseThrow();
        assertEquals(DiscordCaseStatus.OPEN, stored.status());
        assertEquals("456", stored.discordMessageId());
        assertEquals(NOW.plus(Duration.ofHours(24)), stored.expiresAt());
    }

    @Test
    void marksExpiredCaseAndRejectsClaim() {
        DiscordCaseStore store = store();
        DiscordModerationCase created = store.create(alert(NOW.minus(Duration.ofHours(2))), Duration.ofHours(1));

        DiscordCaseStore.ClaimResult result = store.claim(
                created.caseId(),
                "moderator",
                "Mod",
                DiscordPunishment.MUTE_10_MINUTES);

        assertEquals(DiscordCaseStore.ClaimOutcome.EXPIRED, result.outcome());
        assertEquals(DiscordCaseStatus.EXPIRED, result.moderationCase().orElseThrow().status());
        assertEquals(0, store.openCount());
    }

    @Test
    void allowsOnlyOneConcurrentClaim() throws Exception {
        DiscordCaseStore store = store();
        DiscordModerationCase created = store.create(alert(NOW), Duration.ofHours(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAfterSignal(
                    store, created.caseId(), "one", DiscordPunishment.MUTE_10_MINUTES, ready, go));
            var second = executor.submit(() -> claimAfterSignal(
                    store, created.caseId(), "two", DiscordPunishment.PERMANENT_BAN, ready, go));
            ready.await();
            go.countDown();

            List<DiscordCaseStore.ClaimOutcome> outcomes = List.of(first.get(), second.get());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == DiscordCaseStore.ClaimOutcome.CLAIMED)
                    .count());
            assertEquals(1, outcomes.stream()
                    .filter(outcome -> outcome == DiscordCaseStore.ClaimOutcome.NOT_OPEN)
                    .count());
        }
    }

    @Test
    void reopensOnlyRetrySafeFailures() {
        DiscordCaseStore store = store();
        DiscordModerationCase created = store.create(alert(NOW), Duration.ofHours(1));
        store.claim(created.caseId(), "one", "Mod", DiscordPunishment.MUTE_10_MINUTES);

        DiscordModerationCase reopened = store.fail(created.caseId(), true).orElseThrow();

        assertEquals(DiscordCaseStatus.OPEN, reopened.status());
        assertTrue(reopened.moderatorUserId().isEmpty());
    }

    private static DiscordCaseStore.ClaimOutcome claimAfterSignal(
            DiscordCaseStore store,
            String caseId,
            String moderator,
            DiscordPunishment action,
            CountDownLatch ready,
            CountDownLatch go
    ) throws InterruptedException {
        ready.countDown();
        go.await();
        return store.claim(caseId, moderator, moderator, action).outcome();
    }

    private static DiscordCaseStore store() {
        return new DiscordCaseStore(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "CaseId_12345678");
    }

    private static ModerationAction.NotifyStaff alert(Instant timestamp) {
        return new ModerationAction.NotifyStaff(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Player",
                List.of("advertising"),
                MessageDecision.BLOCK,
                "",
                5,
                9,
                timestamp);
    }
}
