package com.maxello1.chatautomod.fabric1211;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.DefaultFilterPackMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FabricFilterPackMigratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesThroughBackupsRecordsCompletionAndThenHonorsAdministratorEdits() throws Exception {
        Path config = temporaryDirectory.resolve("config");
        Path filters = config.resolve("filters");
        Files.createDirectories(filters);
        Map<String, String> oldPacks = oldPackFiles();
        writeTargets(filters, oldPacks);

        migrator(config).apply(filters);

        Path state = config.resolve(".filter-pack-migrations.json");
        assertTrue(DefaultFilterPackMigration.appliedMigrationIds(
                        Files.readString(state, StandardCharsets.UTF_8))
                .contains(DefaultFilterPackMigration.MIGRATION_ID));
        for (String pack : List.of("racist-slurs", "antisemitism-extremism")) {
            assertTrue(Files.isRegularFile(filters.resolve(pack + ".json."
                    + DefaultFilterPackMigration.MIGRATION_ID + ".bak")));
        }
        assertEquals(1, countTerm(filters.resolve("racist-slurs.json"),
                "racism.nword-family", "nigger"));

        removeTerm(filters.resolve("racist-slurs.json"),
                "racism.nword-family", "nigger");
        migrator(config).apply(filters);
        assertEquals(0, countTerm(filters.resolve("racist-slurs.json"),
                "racism.nword-family", "nigger"));
    }

    @Test
    void invalidTargetIsNotOverwrittenAndDoesNotCreateMigrationState() throws Exception {
        Path config = temporaryDirectory.resolve("config");
        Path filters = config.resolve("filters");
        Files.createDirectories(filters);
        Map<String, String> oldPacks = oldPackFiles();
        String racistBefore = oldPacks.get("racist-slurs");
        Files.writeString(filters.resolve("racist-slurs.json"), racistBefore);
        Files.writeString(filters.resolve("antisemitism-extremism.json"), "{ broken");

        migrator(config).apply(filters);

        assertEquals(racistBefore, Files.readString(filters.resolve("racist-slurs.json")));
        assertEquals("{ broken", Files.readString(filters.resolve("antisemitism-extremism.json")));
        assertTrue(Files.notExists(config.resolve(".filter-pack-migrations.json")));
        assertTrue(Files.notExists(filters.resolve("racist-slurs.json."
                + DefaultFilterPackMigration.MIGRATION_ID + ".bak")));
    }

    @Test
    void failedStateWriteNeverClaimsMigrationCompletion() throws Exception {
        Path config = temporaryDirectory.resolve("config");
        Path filters = config.resolve("filters");
        Files.createDirectories(filters);
        writeTargets(filters, oldPackFiles());
        Path blockedState = config.resolve(".filter-pack-migrations.json");
        Files.createDirectories(blockedState);
        Files.writeString(blockedState.resolve("keep"), "blocks replacement");

        migrator(config).apply(filters);

        assertTrue(Files.isDirectory(blockedState));
        assertTrue(Files.isRegularFile(filters.resolve("racist-slurs.json."
                + DefaultFilterPackMigration.MIGRATION_ID + ".bak")));
        assertEquals(1, countTerm(filters.resolve("racist-slurs.json"),
                "racism.nword-family", "nigger"));
    }

    private static FabricFilterPackMigrator migrator(Path config) {
        return new FabricFilterPackMigrator(
                config, LoggerFactory.getLogger(FabricFilterPackMigratorTest.class));
    }

    private static void writeTargets(Path filters, Map<String, String> packs) throws Exception {
        for (String pack : List.of("racist-slurs", "antisemitism-extremism")) {
            Files.writeString(filters.resolve(pack + ".json"), packs.get(pack));
        }
    }

    private static Map<String, String> oldPackFiles() {
        Map<String, String> packs = new LinkedHashMap<>(new ActiveConfig().defaultFilterPacks());
        removeTerms(packs, "racist-slurs", "racism.nword-family",
                List.of("nigger", "nigga", "nibba"));
        removeTerms(packs, "antisemitism-extremism", "extremism.severe-antisemitism",
                List.of("hate jew", "hate jews"));
        return packs;
    }

    private static void removeTerms(Map<String, String> packs, String pack,
            String ruleId, List<String> removed) {
        JsonObject root = JsonParser.parseString(packs.get(pack)).getAsJsonObject();
        JsonObject rule = findRule(root, ruleId);
        JsonArray retained = new JsonArray();
        rule.getAsJsonArray("terms").forEach(term -> {
            if (!removed.contains(term.getAsString())) {
                retained.add(term.deepCopy());
            }
        });
        rule.add("terms", retained);
        packs.put(pack, root.toString());
    }

    private static void removeTerm(Path file, String ruleId, String removed) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject rule = findRule(root, ruleId);
        JsonArray retained = new JsonArray();
        rule.getAsJsonArray("terms").forEach(term -> {
            if (!removed.equals(term.getAsString())) {
                retained.add(term.deepCopy());
            }
        });
        rule.add("terms", retained);
        Files.writeString(file, root.toString());
    }

    private static long countTerm(Path file, String ruleId, String term) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return java.util.stream.StreamSupport.stream(
                        findRule(root, ruleId).getAsJsonArray("terms").spliterator(), false)
                .filter(value -> term.equals(value.getAsString()))
                .count();
    }

    private static JsonObject findRule(JsonObject root, String ruleId) {
        for (var value : root.getAsJsonArray("rules")) {
            JsonObject rule = value.getAsJsonObject();
            if (ruleId.equals(rule.get("id").getAsString())) {
                return rule;
            }
        }
        throw new AssertionError("missing rule " + ruleId);
    }
}
