package com.maxello1.chatautomod.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultFilterPackMigration {
    public static final String MIGRATION_ID = "2026-07-severe-canonical-terms-v1";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final List<Target> TARGETS = List.of(
            new Target("racist-slurs", "racism.nword-family",
                    List.of("nigger", "nigga", "nibba")),
            new Target("antisemitism-extremism", "extremism.severe-antisemitism",
                    List.of("hate jew", "hate jews")));

    private DefaultFilterPackMigration() {}

    public static DefaultFilterPackMigrationResult migrate(Map<String, String> packFiles) {
        Objects.requireNonNull(packFiles, "packFiles");
        Map<String, String> migrated = new LinkedHashMap<>(packFiles);
        Set<String> changed = new LinkedHashSet<>();
        List<ConfigProblem> problems = new ArrayList<>();

        for (Target target : TARGETS) {
            String json = packFiles.get(target.packName());
            if (json == null) {
                problems.add(problem(target, "$", "required filter pack is missing"));
                continue;
            }
            JsonObject root;
            try {
                JsonElement parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()) {
                    problems.add(problem(target, "$", "must be a JSON object"));
                    continue;
                }
                root = parsed.getAsJsonObject();
            } catch (JsonParseException | IllegalStateException exception) {
                problems.add(problem(target, "$", "invalid JSON: " + safeMessage(exception)));
                continue;
            }

            JsonElement rulesValue = root.get("rules");
            if (rulesValue == null || !rulesValue.isJsonArray()) {
                problems.add(problem(target, "$.rules", "must be an array"));
                continue;
            }
            JsonArray rules = rulesValue.getAsJsonArray();
            JsonObject matchingRule = null;
            int matchingIndex = -1;
            for (int index = 0; index < rules.size(); index++) {
                JsonElement ruleValue = rules.get(index);
                if (!ruleValue.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = ruleValue.getAsJsonObject();
                JsonElement idValue = candidate.get("id");
                if (idValue != null && idValue.isJsonPrimitive()
                        && idValue.getAsJsonPrimitive().isString()
                        && target.ruleId().equals(idValue.getAsString())) {
                    matchingRule = candidate;
                    matchingIndex = index;
                    break;
                }
            }
            if (matchingRule == null) {
                problems.add(problem(target, "$.rules",
                        "expected rule " + target.ruleId() + " was not found"));
                continue;
            }
            JsonElement termsValue = matchingRule.get("terms");
            String termsPath = "$.rules[" + matchingIndex + "].terms";
            if (termsValue == null || !termsValue.isJsonArray()) {
                problems.add(problem(target, termsPath, "must be an array"));
                continue;
            }

            JsonArray terms = termsValue.getAsJsonArray();
            Set<String> existing = new LinkedHashSet<>();
            for (JsonElement term : terms) {
                if (term.isJsonPrimitive() && term.getAsJsonPrimitive().isString()) {
                    existing.add(term.getAsString());
                }
            }
            boolean packChanged = false;
            for (String required : target.requiredTerms()) {
                if (existing.add(required)) {
                    terms.add(required);
                    packChanged = true;
                }
            }
            if (packChanged) {
                migrated.put(target.packName(), GSON.toJson(root) + System.lineSeparator());
                changed.add(target.packName());
            }
        }

        if (!problems.isEmpty()) {
            return new DefaultFilterPackMigrationResult(
                    false, packFiles, Set.of(), problems);
        }
        return new DefaultFilterPackMigrationResult(true, migrated, changed, List.of());
    }

    public static Set<String> appliedMigrationIds(String stateJson) {
        JsonObject root = parseState(stateJson);
        JsonElement appliedValue = root.get("applied");
        if (appliedValue == null || !appliedValue.isJsonArray()) {
            throw new IllegalArgumentException("$.applied: must be an array");
        }
        Set<String> applied = new LinkedHashSet<>();
        JsonArray values = appliedValue.getAsJsonArray();
        for (int index = 0; index < values.size(); index++) {
            JsonElement value = values.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("$.applied[" + index + "]: must be a string");
            }
            applied.add(value.getAsString());
        }
        return Set.copyOf(applied);
    }

    public static String markApplied(String existingStateJson) {
        JsonObject root;
        if (existingStateJson == null) {
            root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.add("applied", new JsonArray());
        } else {
            root = parseState(existingStateJson);
            appliedMigrationIds(existingStateJson);
        }
        JsonArray applied = root.getAsJsonArray("applied");
        boolean alreadyPresent = false;
        for (JsonElement value : applied) {
            alreadyPresent |= MIGRATION_ID.equals(value.getAsString());
        }
        if (!alreadyPresent) {
            applied.add(MIGRATION_ID);
        }
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static JsonObject parseState(String stateJson) {
        Objects.requireNonNull(stateJson, "stateJson");
        try {
            JsonElement parsed = JsonParser.parseString(stateJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("$: must be a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement schemaValue = root.get("schema_version");
            if (schemaValue == null || !schemaValue.isJsonPrimitive()
                    || !schemaValue.getAsJsonPrimitive().isNumber()
                    || schemaValue.getAsInt() != 1) {
                throw new IllegalArgumentException("$.schema_version: must be 1");
            }
            return root;
        } catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
            throw new IllegalArgumentException("$: invalid JSON: " + safeMessage(exception), exception);
        }
    }

    private static ConfigProblem problem(Target target, String jsonPath, String message) {
        return new ConfigProblem(target.packName() + ".json:" + jsonPath, message);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record Target(String packName, String ruleId, List<String> requiredTerms) {}
}
