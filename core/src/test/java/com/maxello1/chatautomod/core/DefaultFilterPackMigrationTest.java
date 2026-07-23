package com.maxello1.chatautomod.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.DefaultFilterPackMigration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFilterPackMigrationTest {
    @Test
    void addsOnlyMissingTermsAndPreservesAdministratorChanges() {
        Map<String, String> oldPacks = oldPackFiles();
        JsonObject racistRule = rule(oldPacks.get("racist-slurs"), "racism.nword-family");
        racistRule.getAsJsonArray("terms").add("administrator custom term");
        racistRule.addProperty("points", 37);
        racistRule.addProperty("custom_metadata", "preserved");
        JsonArray actions = racistRule.getAsJsonArray("actions").deepCopy();
        oldPacks = replaceRule(oldPacks, "racist-slurs", racistRule);

        var result = DefaultFilterPackMigration.migrate(oldPacks);

        assertTrue(result.successful(), () -> result.problems().toString());
        assertEquals(java.util.Set.of("racist-slurs", "antisemitism-extremism"),
                result.changedPacks());
        JsonObject migratedRacist = rule(result.packFiles().get("racist-slurs"),
                "racism.nword-family");
        assertEquals(37, migratedRacist.get("points").getAsInt());
        assertEquals("preserved", migratedRacist.get("custom_metadata").getAsString());
        assertEquals(actions, migratedRacist.getAsJsonArray("actions"));
        assertTerms(migratedRacist, "administrator custom term", "nigger", "nigga", "nibba");
        assertTerms(rule(result.packFiles().get("antisemitism-extremism"),
                "extremism.severe-antisemitism"), "hate jew", "hate jews");
    }

    @Test
    void migrationIsIdempotentAndDoesNotRestoreAfterItsMarkerExists() {
        var first = DefaultFilterPackMigration.migrate(oldPackFiles());
        assertTrue(first.successful());
        var second = DefaultFilterPackMigration.migrate(first.packFiles());
        assertTrue(second.successful());
        assertTrue(second.changedPacks().isEmpty());
        assertEquals(first.packFiles(), second.packFiles());

        String state = DefaultFilterPackMigration.markApplied(null);
        assertTrue(DefaultFilterPackMigration.appliedMigrationIds(state)
                .contains(DefaultFilterPackMigration.MIGRATION_ID));

        Map<String, String> administratorEdited = new LinkedHashMap<>(second.packFiles());
        JsonObject racist = root(administratorEdited.get("racist-slurs"));
        JsonObject rule = findRule(racist, "racism.nword-family");
        JsonArray retained = new JsonArray();
        rule.getAsJsonArray("terms").forEach(term -> {
            if (!"nigger".equals(term.getAsString())) {
                retained.add(term.deepCopy());
            }
        });
        rule.add("terms", retained);
        administratorEdited.put("racist-slurs", racist.toString());

        assertTrue(DefaultFilterPackMigration.appliedMigrationIds(state)
                .contains(DefaultFilterPackMigration.MIGRATION_ID));
        assertFalse(terms(findRule(root(administratorEdited.get("racist-slurs")),
                "racism.nword-family")).contains("nigger"));
    }

    @Test
    void invalidInputDoesNotProducePartialOutputOrCompletionState() {
        Map<String, String> packs = oldPackFiles();
        packs.put("antisemitism-extremism", "{ broken");

        var invalidJson = DefaultFilterPackMigration.migrate(packs);

        assertFalse(invalidJson.successful());
        assertTrue(invalidJson.changedPacks().isEmpty());
        assertEquals(packs, invalidJson.packFiles());
        assertTrue(invalidJson.problems().stream().anyMatch(problem ->
                problem.path().equals("antisemitism-extremism.json:$")));

        Map<String, String> missingRulePacks = oldPackFiles();
        JsonObject antisemitism = root(missingRulePacks.get("antisemitism-extremism"));
        antisemitism.add("rules", new JsonArray());
        missingRulePacks.put("antisemitism-extremism", antisemitism.toString());
        var missingRule = DefaultFilterPackMigration.migrate(missingRulePacks);
        assertFalse(missingRule.successful());
        assertTrue(missingRule.problems().stream().anyMatch(problem ->
                problem.path().equals("antisemitism-extremism.json:$.rules")
                        && problem.message().contains("extremism.severe-antisemitism")));

        assertThrows(IllegalArgumentException.class,
                () -> DefaultFilterPackMigration.appliedMigrationIds("{ broken"));
    }

    private static Map<String, String> oldPackFiles() {
        Map<String, String> packs = new LinkedHashMap<>(new ActiveConfig().defaultFilterPacks());
        packs = removeTerms(packs, "racist-slurs", "racism.nword-family",
                List.of("nigger", "nigga", "nibba"));
        return removeTerms(packs, "antisemitism-extremism", "extremism.severe-antisemitism",
                List.of("hate jew", "hate jews"));
    }

    private static Map<String, String> removeTerms(Map<String, String> packs, String pack,
            String ruleId, List<String> removed) {
        JsonObject root = root(packs.get(pack));
        JsonObject rule = findRule(root, ruleId);
        JsonArray retained = new JsonArray();
        rule.getAsJsonArray("terms").forEach(term -> {
            if (!removed.contains(term.getAsString())) {
                retained.add(term.deepCopy());
            }
        });
        rule.add("terms", retained);
        Map<String, String> result = new LinkedHashMap<>(packs);
        result.put(pack, root.toString());
        return result;
    }

    private static Map<String, String> replaceRule(Map<String, String> packs, String pack,
            JsonObject replacement) {
        JsonObject root = root(packs.get(pack));
        JsonArray rules = root.getAsJsonArray("rules");
        for (int index = 0; index < rules.size(); index++) {
            if (replacement.get("id").getAsString()
                    .equals(rules.get(index).getAsJsonObject().get("id").getAsString())) {
                rules.set(index, replacement);
                break;
            }
        }
        Map<String, String> result = new LinkedHashMap<>(packs);
        result.put(pack, root.toString());
        return result;
    }

    private static JsonObject rule(String json, String ruleId) {
        return findRule(root(json), ruleId);
    }

    private static JsonObject root(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
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

    private static List<String> terms(JsonObject rule) {
        return java.util.stream.StreamSupport.stream(
                        rule.getAsJsonArray("terms").spliterator(), false)
                .map(value -> value.getAsString())
                .toList();
    }

    private static void assertTerms(JsonObject rule, String... required) {
        List<String> terms = terms(rule);
        for (String term : required) {
            assertEquals(1, terms.stream().filter(term::equals).count(),
                    () -> term + " in " + terms);
        }
    }
}
