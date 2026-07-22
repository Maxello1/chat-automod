package com.maxello1.chatautomod.fabric262;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class FabricSnapshotStore implements AutoCloseable {
    private static final long SAVE_DEBOUNCE_MILLIS = 2_000;

    private final Path directory;
    private final Path stateFile;
    private final Path backupFile;
    private final Logger logger;
    private final ScheduledExecutorService writer;
    private final Object lock = new Object();

    private java.util.function.Supplier<String> pendingSnapshot;
    private ScheduledFuture<?> scheduledSave;
    private volatile boolean primaryKnownValid;
    private boolean closed;

    FabricSnapshotStore(Path worldDataDirectory, Logger logger) {
        this.directory = Objects.requireNonNull(worldDataDirectory, "worldDataDirectory");
        this.stateFile = directory.resolve("state.json");
        this.backupFile = directory.resolve("state.json.bak");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform()
                .name("Chat AutoMod State Writer")
                .daemon(true)
                .unstarted(runnable));
    }

    List<StoredSnapshot> loadCandidates() {
        List<StoredSnapshot> candidates = new ArrayList<>(2);
        read(stateFile, "primary").ifPresent(candidates::add);
        read(backupFile, "backup").ifPresent(candidates::add);
        return List.copyOf(candidates);
    }

    void markPrimaryValid() {
        primaryKnownValid = true;
    }

    void markPrimaryInvalid() {
        primaryKnownValid = false;
    }

    private java.util.Optional<StoredSnapshot> read(Path file, String description) {
        if (!Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new StoredSnapshot(
                    description,
                    Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            logger.error("Could not read Chat AutoMod {} state from {}", description, file, exception);
            return java.util.Optional.empty();
        }
    }

    void scheduleSave(java.util.function.Supplier<String> snapshotSupplier) {
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        synchronized (lock) {
            if (closed) {
                return;
            }
            pendingSnapshot = snapshotSupplier;
            if (scheduledSave == null || scheduledSave.isDone()) {
                scheduledSave = writer.schedule(this::drainPending, SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void drainPending() {
        java.util.function.Supplier<String> snapshotSupplier;
        synchronized (lock) {
            snapshotSupplier = pendingSnapshot;
            pendingSnapshot = null;
            scheduledSave = null;
        }
        if (snapshotSupplier != null) {
            try {
                writeAtomically(snapshotSupplier.get());
            } catch (IOException | RuntimeException exception) {
                logger.error("Could not persist Chat AutoMod state", exception);
            }
        }
        synchronized (lock) {
            if (!closed && pendingSnapshot != null && scheduledSave == null) {
                scheduledSave = writer.schedule(this::drainPending, SAVE_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void writeAtomically(String encodedSnapshot) throws IOException {
        Files.createDirectories(directory);
        Path temporary = directory.resolve("state.json.tmp");
        Path backupTemporary = directory.resolve("state.json.bak.tmp");
        Files.writeString(
                temporary,
                encodedSnapshot,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);

        if (primaryKnownValid && Files.isRegularFile(stateFile)) {
            Files.copy(stateFile, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
            replace(backupTemporary, backupFile);
        }
        replace(temporary, stateFile);
        primaryKnownValid = true;
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void closeWithFinalSnapshot(String encodedSnapshot) {
        Objects.requireNonNull(encodedSnapshot, "encodedSnapshot");
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pendingSnapshot = null;
            if (scheduledSave != null) {
                scheduledSave.cancel(false);
                scheduledSave = null;
            }
        }
        try {
            writer.submit(() -> {
                try {
                    writeAtomically(encodedSnapshot);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            logger.error("Could not write final Chat AutoMod state", exception.getCause());
        } catch (java.util.concurrent.TimeoutException exception) {
            logger.error("Timed out while writing final Chat AutoMod state", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                    writer.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writer.shutdownNow();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (scheduledSave != null) {
                scheduledSave.cancel(false);
            }
        }
        writer.shutdownNow();
    }

    record StoredSnapshot(String description, String json) {}
}
