package com.maxello1.chatautomod.fabric1211;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class FabricAuditLogger implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 2_048;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Envelope STOP = new Envelope(null, null, false, 1, true);

    private final Path logDirectory;
    private final Logger logger;
    private final ArrayBlockingQueue<Envelope> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writerThread;

    FabricAuditLogger(Path worldDataDirectory, Logger logger, int initialRetentionDays) throws IOException {
        this.logDirectory = Objects.requireNonNull(worldDataDirectory, "worldDataDirectory").resolve("logs");
        this.logger = Objects.requireNonNull(logger, "logger");
        int retentionDays = Math.max(1, initialRetentionDays);
        Files.createDirectories(logDirectory);
        deleteExpiredLogs(LocalDate.now(ZoneOffset.UTC), retentionDays);
        this.writerThread = Thread.ofPlatform()
                .name("Chat AutoMod Log Writer")
                .daemon(true)
                .unstarted(this::writerLoop);
        this.writerThread.start();
    }

    void append(ViolationRecord record, boolean includeOriginalMessage, int retentionDays) {
        Envelope envelope = new Envelope(
                Objects.requireNonNull(record, "record"),
                null,
                includeOriginalMessage,
                Math.max(1, retentionDays),
                false);
        offer(envelope);
    }

    void appendDiscordAction(DiscordPunishmentAuditEvent event, int retentionDays) {
        Envelope envelope = new Envelope(
                null,
                Objects.requireNonNull(event, "event"),
                false,
                Math.max(1, retentionDays),
                false);
        offer(envelope);
    }

    private void offer(Envelope envelope) {
        boolean offered;
        synchronized (lifecycleLock) {
            if (!accepting.get()) {
                return;
            }
            offered = queue.offer(envelope);
        }
        if (!offered) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                logger.warn("Chat AutoMod log queue is full; {} audit entries have been dropped", count);
            }
        }
    }

    void stopAccepting() {
        synchronized (lifecycleLock) {
            accepting.set(false);
        }
    }

    private void writerLoop() {
        LocalDate lastCleanup = LocalDate.now(ZoneOffset.UTC);
        try {
            while (true) {
                Envelope envelope = queue.take();
                if (envelope.stop()) {
                    break;
                }
                LocalDate currentDate = LocalDate.now(ZoneOffset.UTC);
                if (!currentDate.equals(lastCleanup)) {
                    deleteExpiredLogsQuietly(currentDate, envelope.retentionDays());
                    lastCleanup = currentDate;
                }
                LocalDate date = envelope.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
                try {
                    write(envelope, date);
                } catch (IOException exception) {
                    logger.error("Could not write a Chat AutoMod audit entry", exception);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            logger.error("Chat AutoMod audit logging stopped unexpectedly", exception);
        } finally {
            accepting.set(false);
        }
    }

    private void deleteExpiredLogsQuietly(LocalDate currentDate, int retentionDays) {
        try {
            deleteExpiredLogs(currentDate, retentionDays);
        } catch (IOException exception) {
            logger.warn("Could not delete expired Chat AutoMod audit logs", exception);
        }
    }

    private void write(Envelope envelope, LocalDate date) throws IOException {
        Path destination = logDirectory.resolve("automod-" + date + ".jsonl");
        String encoded = envelope.discordEvent() == null
                ? encode(envelope.record(), envelope.includeOriginalMessage())
                : encode(envelope.discordEvent());
        Files.writeString(
                destination,
                encoded + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String encode(ViolationRecord record, boolean includeOriginalMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", record.timestamp().toString());
        json.addProperty("event_id", record.eventId().toString());
        json.addProperty("player_uuid", record.playerId().toString());
        json.addProperty("player_name", sanitize(record.playerName()));
        JsonArray ruleIds = new JsonArray();
        record.ruleIds().forEach(rule -> ruleIds.add(sanitize(rule)));
        json.add("rule_ids", ruleIds);
        JsonArray categories = new JsonArray();
        record.categories().forEach(category -> categories.add(category.name()));
        json.add("categories", categories);
        json.addProperty("severity", record.severity().name());
        json.addProperty("decision", record.decision().name());
        json.addProperty("points_added", record.pointsAdded());
        json.addProperty("score_after", record.scoreAfter());
        JsonArray actions = new JsonArray();
        record.actions().forEach(action -> actions.add(action.name()));
        json.add("actions", actions);
        record.muteKind().ifPresent(kind ->
                json.addProperty("mute_kind", kind.name()));
        if (includeOriginalMessage) {
            record.originalMessage().map(FabricAuditLogger::sanitize)
                    .ifPresent(message -> json.addProperty("original_message", message));
        }
        return GSON.toJson(json);
    }

    private static String encode(DiscordPunishmentAuditEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", event.timestamp().toString());
        json.addProperty("event_type", "discord_moderation_action");
        json.addProperty("case_id", sanitize(event.caseId()));
        json.addProperty("player_uuid", event.playerId().toString());
        json.addProperty("player_name", sanitize(event.playerName()));
        json.addProperty("action", event.action().name());
        if (event.duration() != null) {
            json.addProperty("duration_seconds", event.duration().toSeconds());
        }
        json.addProperty("reason", sanitize(event.reason()));
        JsonArray rules = new JsonArray();
        event.ruleIds().forEach(rule -> rules.add(sanitize(rule)));
        json.add("rule_ids", rules);
        json.addProperty("moderator_source", sanitize(event.moderator().source()));
        json.addProperty("moderator_id", sanitize(event.moderator().id()));
        json.addProperty("moderator_display_name", sanitize(event.moderator().displayName()));
        json.addProperty("success", event.success());
        json.addProperty("result", sanitize(event.resultSummary()));
        return GSON.toJson(json);
    }

    private void deleteExpiredLogs(LocalDate currentDate, int retentionDays) throws IOException {
        LocalDate cutoff = currentDate.minusDays(Math.max(1, retentionDays));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(logDirectory, "automod-*.jsonl")) {
            for (Path file : files) {
                Optional<LocalDate> date = dateFromFile(file.getFileName().toString());
                if (date.isPresent() && date.orElseThrow().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static Optional<LocalDate> dateFromFile(String fileName) {
        if (!fileName.startsWith("automod-") || !fileName.endsWith(".jsonl")) {
            return Optional.empty();
        }
        String date = fileName.substring("automod-".length(), fileName.length() - ".jsonl".length());
        try {
            return Optional.of(LocalDate.parse(date));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && (codePoint < 0xD800 || codePoint > 0xDFFF)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .forEach(sanitized::appendCodePoint);
        return sanitized.toString();
    }

    @Override
    public void close() {
        stopAccepting();
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!queue.offer(STOP, 5, TimeUnit.SECONDS)) {
                logger.warn("Chat AutoMod log writer did not accept its shutdown marker; {} entries remain", queue.size());
                writerThread.interrupt();
            }
            writerThread.join(TimeUnit.SECONDS.toMillis(10));
            if (writerThread.isAlive()) {
                logger.warn("Chat AutoMod log writer did not stop within ten seconds");
                writerThread.interrupt();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writerThread.interrupt();
        }
    }

    private record Envelope(
            ViolationRecord record,
            DiscordPunishmentAuditEvent discordEvent,
            boolean includeOriginalMessage,
            int retentionDays,
            boolean stop
    ) {
        Instant timestamp() {
            return discordEvent == null ? record.timestamp() : discordEvent.timestamp();
        }
    }
}
