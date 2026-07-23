package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.config.DefaultFilterPackMigration;
import com.maxello1.chatautomod.core.config.DefaultFilterPackMigrationResult;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class FabricFilterPackMigrator {
    private static final List<String> TARGET_PACKS = List.of(
            "racist-slurs", "antisemitism-extremism");
    private static final String STATE_FILE_NAME = ".filter-pack-migrations.json";

    private final Path configDirectory;
    private final Path stateFile;
    private final Logger logger;

    FabricFilterPackMigrator(Path configDirectory, Logger logger) {
        this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory");
        this.stateFile = configDirectory.resolve(STATE_FILE_NAME);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void apply(Path filtersDirectory) {
        Objects.requireNonNull(filtersDirectory, "filtersDirectory");
        String stateJson;
        try {
            stateJson = Files.isRegularFile(stateFile)
                    ? Files.readString(stateFile, StandardCharsets.UTF_8)
                    : null;
            if (stateJson != null && DefaultFilterPackMigration.appliedMigrationIds(stateJson)
                    .contains(DefaultFilterPackMigration.MIGRATION_ID)) {
                return;
            }
        } catch (IOException | IllegalArgumentException exception) {
            logger.error("Could not read Chat AutoMod filter-pack migration state {}: {}",
                    stateFile, safeMessage(exception));
            return;
        }

        Map<String, String> packFiles = new LinkedHashMap<>();
        for (String packName : TARGET_PACKS) {
            Path file = filtersDirectory.resolve(packName + ".json");
            try {
                packFiles.put(packName, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                logger.error("Could not read Chat AutoMod migration target {}: {}",
                        file, safeMessage(exception));
                return;
            }
        }

        DefaultFilterPackMigrationResult result = DefaultFilterPackMigration.migrate(packFiles);
        if (!result.successful()) {
            logger.error("Chat AutoMod filter-pack migration {} was not applied",
                    DefaultFilterPackMigration.MIGRATION_ID);
            result.problems().forEach(problem -> logger.error("- {}", problem));
            return;
        }

        try {
            for (String packName : TARGET_PACKS) {
                if (result.changedPacks().contains(packName)) {
                    writeMigratedPack(
                            filtersDirectory.resolve(packName + ".json"),
                            result.packFiles().get(packName));
                }
            }
            writeState(DefaultFilterPackMigration.markApplied(stateJson));
            logger.info("Applied Chat AutoMod filter-pack migration {}",
                    DefaultFilterPackMigration.MIGRATION_ID);
        } catch (IOException | RuntimeException exception) {
            logger.error("Chat AutoMod filter-pack migration {} was not recorded; "
                            + "previous files remain available as backups: {}",
                    DefaultFilterPackMigration.MIGRATION_ID, safeMessage(exception));
        }
    }

    private void writeMigratedPack(Path target, String content) throws IOException {
        String fileName = target.getFileName().toString();
        Path temporary = target.resolveSibling(fileName + ".migration.tmp");
        Path backup = target.resolveSibling(fileName + "."
                + DefaultFilterPackMigration.MIGRATION_ID + ".bak");
        writeTemporary(temporary, content);
        if (Files.notExists(backup)) {
            Files.copy(target, backup);
        }
        replace(temporary, target);
    }

    private void writeState(String content) throws IOException {
        Files.createDirectories(configDirectory);
        Path temporary = configDirectory.resolve(STATE_FILE_NAME + ".tmp");
        Path backup = configDirectory.resolve(STATE_FILE_NAME + ".bak");
        writeTemporary(temporary, content);
        if (Files.isRegularFile(stateFile)) {
            Files.copy(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        replace(temporary, stateFile);
    }

    private static void writeTemporary(Path temporary, String content) throws IOException {
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
