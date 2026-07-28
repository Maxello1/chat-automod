package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class DiscordCaseStore {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, DiscordModerationCase> cases = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<String> caseIdGenerator;

    public DiscordCaseStore() {
        this(Clock.systemUTC(), DiscordCaseStore::randomCaseId);
    }

    DiscordCaseStore(Clock clock, Supplier<String> caseIdGenerator) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.caseIdGenerator = java.util.Objects.requireNonNull(caseIdGenerator, "caseIdGenerator");
    }

    public DiscordModerationCase create(
            ModerationAction.NotifyStaff alert,
            Duration expiry
    ) {
        pruneExpired();
        Instant expiresAt;
        try {
            expiresAt = alert.timestamp().plus(expiry);
        } catch (DateTimeException | ArithmeticException exception) {
            expiresAt = Instant.MAX;
        }
        while (true) {
            String caseId = caseIdGenerator.get();
            if (!DiscordCustomId.isValidCaseId(caseId)) {
                throw new IllegalStateException("case ID generator returned an invalid value");
            }
            DiscordModerationCase created = new DiscordModerationCase(
                    caseId,
                    alert.playerId(),
                    alert.playerName(),
                    alert.ruleIds(),
                    alert.timestamp(),
                    "",
                    expiresAt,
                    DiscordCaseStatus.OPEN,
                    null,
                    "",
                    "",
                    null);
            if (cases.putIfAbsent(caseId, created) == null) {
                return created;
            }
        }
    }

    public Optional<DiscordModerationCase> get(String caseId) {
        expire(caseId);
        return Optional.ofNullable(cases.get(caseId));
    }

    public void attachMessageId(String caseId, String messageId) {
        cases.computeIfPresent(caseId, (ignored, current) ->
                current.status() == DiscordCaseStatus.OPEN
                        ? current.withMessageId(messageId)
                        : current);
    }

    public void remove(String caseId) {
        cases.remove(caseId);
    }

    public ClaimResult claim(
            String caseId,
            String moderatorId,
            String moderatorDisplayName,
            DiscordPunishment action
    ) {
        Instant now = clock.instant();
        AtomicReference<ClaimOutcome> outcome = new AtomicReference<>(ClaimOutcome.UNKNOWN);
        AtomicReference<DiscordModerationCase> result = new AtomicReference<>();
        cases.compute(caseId, (ignored, current) -> {
            if (current == null) {
                return null;
            }
            if (!current.expiresAt().isAfter(now)
                    && current.status() == DiscordCaseStatus.OPEN) {
                DiscordModerationCase expired = current.completed(DiscordCaseStatus.EXPIRED, now);
                outcome.set(ClaimOutcome.EXPIRED);
                result.set(expired);
                return expired;
            }
            if (current.status() != DiscordCaseStatus.OPEN) {
                outcome.set(current.status() == DiscordCaseStatus.EXPIRED
                        ? ClaimOutcome.EXPIRED
                        : ClaimOutcome.NOT_OPEN);
                result.set(current);
                return current;
            }
            DiscordModerationCase claimed = current.claimed(action, moderatorId, moderatorDisplayName);
            outcome.set(ClaimOutcome.CLAIMED);
            result.set(claimed);
            return claimed;
        });
        return new ClaimResult(outcome.get(), Optional.ofNullable(result.get()));
    }

    public Optional<DiscordModerationCase> complete(String caseId, boolean dismissed) {
        AtomicReference<DiscordModerationCase> result = new AtomicReference<>();
        cases.computeIfPresent(caseId, (ignored, current) -> {
            if (current.status() != DiscordCaseStatus.PROCESSING) {
                result.set(current);
                return current;
            }
            DiscordModerationCase completed = current.completed(
                    dismissed ? DiscordCaseStatus.DISMISSED : DiscordCaseStatus.RESOLVED,
                    clock.instant());
            result.set(completed);
            return completed;
        });
        return Optional.ofNullable(result.get());
    }

    public Optional<DiscordModerationCase> fail(String caseId, boolean retrySafe) {
        AtomicReference<DiscordModerationCase> result = new AtomicReference<>();
        cases.computeIfPresent(caseId, (ignored, current) -> {
            if (current.status() != DiscordCaseStatus.PROCESSING) {
                result.set(current);
                return current;
            }
            DiscordModerationCase failed = retrySafe
                    ? current.reopened()
                    : current.completed(DiscordCaseStatus.FAILED, clock.instant());
            result.set(failed);
            return failed;
        });
        return Optional.ofNullable(result.get());
    }

    public int openCount() {
        pruneExpired();
        return (int) cases.values().stream()
                .filter(value -> value.status() == DiscordCaseStatus.OPEN
                        || value.status() == DiscordCaseStatus.PROCESSING)
                .count();
    }

    public void clear() {
        cases.clear();
    }

    private void expire(String caseId) {
        Instant now = clock.instant();
        cases.computeIfPresent(caseId, (ignored, current) ->
                current.status() == DiscordCaseStatus.OPEN && !current.expiresAt().isAfter(now)
                        ? current.completed(DiscordCaseStatus.EXPIRED, now)
                        : current);
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        cases.replaceAll((ignored, current) ->
                current.status() == DiscordCaseStatus.OPEN && !current.expiresAt().isAfter(now)
                        ? current.completed(DiscordCaseStatus.EXPIRED, now)
                        : current);
        cases.entrySet().removeIf(entry -> {
            DiscordModerationCase value = entry.getValue();
            if (value.status() == DiscordCaseStatus.OPEN
                    || value.status() == DiscordCaseStatus.PROCESSING) {
                return false;
            }
            Instant terminalAt = value.resolutionTimestamp() == null
                    ? value.expiresAt()
                    : value.resolutionTimestamp();
            return terminalAt.isBefore(now.minus(Duration.ofDays(7)));
        });
    }

    private static String randomCaseId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public enum ClaimOutcome {
        CLAIMED,
        UNKNOWN,
        EXPIRED,
        NOT_OPEN
    }

    public record ClaimResult(
            ClaimOutcome outcome,
            Optional<DiscordModerationCase> moderationCase
    ) {}
}
