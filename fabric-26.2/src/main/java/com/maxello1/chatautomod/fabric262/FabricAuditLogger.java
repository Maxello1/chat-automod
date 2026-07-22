package com.maxello1.chatautomod.fabric262;

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
    private static final Envelope STOP = new Envelope(null, false, 1, true);

    private final Path logDirectory;
    private final Logger logger;
    private final int initialRetentionDays;
    private final ArrayBlockingQueue<Envelope> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writerThread;

    FabricAuditLogger(Path worldDataDirectory, Logger logger, int initialRetentionDays) {
        this.logDirectory = Objects.requireNonNull(worldDataDirectory, "worldDataDirectory").resolve("logs");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.initialRetentionDays = Math.max(1, initialRetentionDays);
        this.writerThread = Thread.ofPlatform()
                .name("Chat AutoMod Log Writer")
                .daemon(true)
                .unstarted(this::writerLoop);
        this.writerThread.start();
    }

    void append(ViolationRecord record, boolean includeOriginalMessage, int retentionDays) {
        if (!open.get()) {
            return;
        }
        Envelope envelope = new Envelope(
                immutableCopy(record),
                includeOriginalMessage,
                Math.max(1, retentionDays),
                false);
        if (!queue.offer(envelope)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                logger.warn("Chat AutoMod log queue is full; {} audit entries have been dropped", count);
            }
        }
    }

    private void writerLoop() {
        LocalDate lastCleanup = null;
        try {
            Files.createDirectories(logDirectory);
            lastCleanup = LocalDate.now(ZoneOffset.UTC);
            deleteExpiredLogsQuietly(lastCleanup, initialRetentionDays);
            while (true) {
                Envelope envelope = queue.take();
                if (envelope.stop()) {
                    break;
                }
                LocalDate date = envelope.record().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.equals(lastCleanup)) {
                    deleteExpiredLogsQuietly(date, envelope.retentionDays());
                    lastCleanup = date;
                }
                try {
                    write(envelope, date);
                } catch (IOException exception) {
                    logger.error("Could not write a Chat AutoMod audit entry", exception);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            logger.error("Could not initialize Chat AutoMod audit logging", exception);
        } catch (RuntimeException exception) {
            logger.error("Chat AutoMod audit logging stopped unexpectedly", exception);
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
        String encoded = encode(envelope.record(), envelope.includeOriginalMessage());
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
        json.addProperty("decision", record.decision().name());
        json.addProperty("points_added", record.pointsAdded());
        json.addProperty("score_after", record.scoreAfter());
        JsonArray actions = new JsonArray();
        record.actions().forEach(action -> actions.add(action.name()));
        json.add("actions", actions);
        if (includeOriginalMessage) {
            record.originalMessage().map(FabricAuditLogger::sanitize)
                    .ifPresent(message -> json.addProperty("original_message", message));
        }
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

    private static ViolationRecord immutableCopy(ViolationRecord record) {
        return new ViolationRecord(
                record.eventId(),
                record.timestamp(),
                record.playerId(),
                sanitize(record.playerName()),
                record.ruleIds().stream().map(FabricAuditLogger::sanitize).toList(),
                record.decision(),
                record.pointsAdded(),
                record.scoreAfter(),
                record.actions(),
                record.originalMessage().map(FabricAuditLogger::sanitize));
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .forEach(sanitized::appendCodePoint);
        return sanitized.toString();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
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
            boolean includeOriginalMessage,
            int retentionDays,
            boolean stop
    ) {}
}
