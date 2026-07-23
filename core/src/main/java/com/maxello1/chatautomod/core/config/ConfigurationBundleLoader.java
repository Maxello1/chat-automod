package com.maxello1.chatautomod.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigurationBundleLoader {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern FILTER_PATH = Pattern.compile("^\\$\\.filters\\[(\\d+)](.*)$");
    private final ConfigLoader configLoader;

    public ConfigurationBundleLoader() {
        this(new ConfigLoader());
    }

    public ConfigurationBundleLoader(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public ConfigLoadResult load(String mainJson, Map<String, String> packFiles,
            String exceptionsJson) {
        List<ConfigProblem> problems = new ArrayList<>();
        JsonObject main = parseObject(mainJson, "automod.json", problems);
        if (main == null) {
            return ConfigLoadResult.failure(problems);
        }
        JsonObject candidate = main.deepCopy();
        JsonArray mergedFilters = new JsonArray();
        if (candidate.has("filters")) {
            if (candidate.get("filters").isJsonArray()) {
                mergedFilters = candidate.getAsJsonArray("filters").deepCopy();
            } else {
                problems.add(new ConfigProblem("automod.json $.filters",
                        "must be an array"));
            }
        }
        List<RuleSource> sources = new ArrayList<>();
        for (int index = 0; index < mergedFilters.size(); index++) {
            sources.add(new RuleSource("automod.json", "$.filters[" + index + "]",
                    mergedFilters.get(index).isJsonObject()
                            ? mergedFilters.get(index).getAsJsonObject() : null));
        }

        JsonObject settings = main.has("filter_packs") && main.get("filter_packs").isJsonObject()
                ? main.getAsJsonObject("filter_packs") : null;
        boolean enabled = settings == null || !settings.has("enabled")
                || booleanValue(settings.get("enabled"), true);
        Set<String> packIds = new HashSet<>();
        Set<String> ruleIds = collectInlineRuleIds(mergedFilters);
        if (enabled) {
            List<String> active = readActivePacks(settings, problems);
            for (String packId : active) {
                loadPack(packId, packFiles == null ? Map.of() : packFiles,
                        packIds, ruleIds, mergedFilters, sources, problems);
            }
            applyExceptions(exceptionsJson, ruleIds, sources, problems);
        }
        candidate.add("filters", mergedFilters);
        ConfigLoadResult compiled = configLoader.load(GSON.toJson(candidate));
        if (!compiled.valid()) {
            problems.addAll(compiled.problems().stream()
                    .map(problem -> qualify(problem, sources))
                    .toList());
        }
        return problems.isEmpty() ? compiled : ConfigLoadResult.failure(problems);
    }

    private void loadPack(String packId, Map<String, String> packFiles,
            Set<String> packIds, Set<String> ruleIds, JsonArray filters,
            List<RuleSource> sources, List<ConfigProblem> problems) {
        if (!PACK_ID.matcher(packId).matches()) {
            problems.add(new ConfigProblem("automod.json $.filter_packs.active",
                    "invalid pack ID: " + packId));
            return;
        }
        String file = "filters/" + packId + ".json";
        String json = packFiles.get(packId);
        if (json == null) {
            problems.add(new ConfigProblem(file, "active filter pack is missing"));
            return;
        }
        JsonObject root = parseObject(json, file, problems);
        if (root == null) {
            return;
        }
        checkFields(root, Set.of("schema_version", "id", "rules"), file + " $", problems);
        if (integerValue(root.get("schema_version"), -1) != 1) {
            problems.add(new ConfigProblem(file + " $.schema_version",
                    "only schema version 1 is supported"));
        }
        String declaredId = stringValue(root.get("id"));
        if (!packId.equals(declaredId)) {
            problems.add(new ConfigProblem(file + " $.id",
                    "must match active pack ID " + packId));
        }
        if (!packIds.add(declaredId == null ? packId : declaredId)) {
            problems.add(new ConfigProblem(file + " $.id", "duplicate pack ID"));
        }
        JsonArray rules = root.has("rules") && root.get("rules").isJsonArray()
                ? root.getAsJsonArray("rules") : null;
        if (rules == null) {
            problems.add(new ConfigProblem(file + " $.rules", "must be an array"));
            return;
        }
        for (int index = 0; index < rules.size(); index++) {
            String rulePath = "$.rules[" + index + "]";
            if (!rules.get(index).isJsonObject()) {
                problems.add(new ConfigProblem(file + " " + rulePath,
                        "must be an object"));
                continue;
            }
            JsonObject rule = rules.get(index).getAsJsonObject().deepCopy();
            checkFields(rule, Set.of("id", "category", "severity", "match_mode",
                    "terms", "patterns", "exceptions", "points", "actions", "enabled"),
                    file + " " + rulePath, problems);
            checkRuleFieldTypes(rule, file + " " + rulePath, problems);
            String ruleId = stringValue(rule.get("id"));
            if (ruleId == null || !ruleIds.add(ruleId)) {
                problems.add(new ConfigProblem(file + " " + rulePath + ".id",
                        ruleId == null ? "is required" : "duplicate rule ID"));
            }
            filters.add(rule);
            sources.add(new RuleSource(file, rulePath, rule));
        }
    }

    private void applyExceptions(String json, Set<String> ruleIds,
            List<RuleSource> sources, List<ConfigProblem> problems) {
        String file = "filters/exceptions.json";
        JsonObject root = parseObject(json, file, problems);
        if (root == null) {
            return;
        }
        checkFields(root, Set.of("schema_version", "id", "global_exceptions"),
                file + " $", problems);
        if (integerValue(root.get("schema_version"), -1) != 1) {
            problems.add(new ConfigProblem(file + " $.schema_version",
                    "only schema version 1 is supported"));
        }
        JsonArray entries = root.has("global_exceptions")
                && root.get("global_exceptions").isJsonArray()
                ? root.getAsJsonArray("global_exceptions") : null;
        if (entries == null) {
            problems.add(new ConfigProblem(file + " $.global_exceptions",
                    "must be an array"));
            return;
        }
        Map<String, JsonObject> byId = new HashMap<>();
        for (RuleSource source : sources) {
            if (source.rule() != null) {
                String id = stringValue(source.rule().get("id"));
                if (id != null) {
                    byId.put(id, source.rule());
                }
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            String path = "$.global_exceptions[" + index + "]";
            if (!entries.get(index).isJsonObject()) {
                problems.add(new ConfigProblem(file + " " + path, "must be an object"));
                continue;
            }
            JsonObject entry = entries.get(index).getAsJsonObject();
            checkFields(entry, Set.of("term", "applies_to"), file + " " + path, problems);
            String term = stringValue(entry.get("term"));
            JsonArray targets = entry.has("applies_to") && entry.get("applies_to").isJsonArray()
                    ? entry.getAsJsonArray("applies_to") : null;
            if (term == null || term.isBlank()) {
                problems.add(new ConfigProblem(file + " " + path + ".term",
                        "must not be blank"));
            }
            if (targets == null || targets.isEmpty()) {
                problems.add(new ConfigProblem(file + " " + path + ".applies_to",
                        "must contain explicit rule IDs"));
                continue;
            }
            for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
                String target = stringValue(targets.get(targetIndex));
                if (target == null || !ruleIds.contains(target)) {
                    problems.add(new ConfigProblem(file + " " + path + ".applies_to["
                            + targetIndex + "]", "unknown target rule ID"));
                    continue;
                }
                JsonObject rule = byId.get(target);
                JsonArray exceptions = rule.has("exceptions") && rule.get("exceptions").isJsonArray()
                        ? rule.getAsJsonArray("exceptions") : new JsonArray();
                exceptions.add(term);
                rule.add("exceptions", exceptions);
            }
        }
    }

    private List<String> readActivePacks(JsonObject settings,
            List<ConfigProblem> problems) {
        if (settings == null || !settings.has("active")
                || !settings.get("active").isJsonArray()) {
            problems.add(new ConfigProblem("automod.json $.filter_packs.active",
                    "must be an array"));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray values = settings.getAsJsonArray("active");
        for (int index = 0; index < values.size(); index++) {
            String value = stringValue(values.get(index));
            if (value == null || !seen.add(value)) {
                problems.add(new ConfigProblem("automod.json $.filter_packs.active["
                        + index + "]", value == null ? "must be a string" : "duplicate pack ID"));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private Set<String> collectInlineRuleIds(JsonArray filters) {
        Set<String> result = new HashSet<>();
        for (JsonElement element : filters) {
            if (element.isJsonObject()) {
                String id = stringValue(element.getAsJsonObject().get("id"));
                if (id != null) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    private ConfigProblem qualify(ConfigProblem problem, List<RuleSource> sources) {
        Matcher matcher = FILTER_PATH.matcher(problem.path());
        if (matcher.matches()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 0 && index < sources.size()) {
                RuleSource source = sources.get(index);
                return new ConfigProblem(source.file() + " " + source.path()
                        + matcher.group(2), problem.message());
            }
        }
        return new ConfigProblem("automod.json " + problem.path(), problem.message());
    }

    private JsonObject parseObject(String json, String file,
            List<ConfigProblem> problems) {
        if (json == null) {
            problems.add(new ConfigProblem(file, "file is missing"));
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                problems.add(new ConfigProblem(file + " $", "must be a JSON object"));
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            problems.add(new ConfigProblem(file + " $",
                    "invalid JSON: " + exception.getMessage()));
            return null;
        }
    }

    private void checkFields(JsonObject object, Set<String> allowed,
            String path, List<ConfigProblem> problems) {
        object.keySet().stream().filter(key -> !allowed.contains(key))
                .forEach(key -> problems.add(new ConfigProblem(path + "." + key,
                        "unknown property")));
    }

    private void checkRuleFieldTypes(JsonObject rule, String path,
            List<ConfigProblem> problems) {
        for (String field : List.of("id", "category", "severity", "match_mode")) {
            JsonElement value = rule.get(field);
            if (value != null && (!value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString())) {
                problems.add(new ConfigProblem(path + "." + field,
                        "must be a JSON string"));
            }
        }
        for (String field : List.of("terms", "patterns", "exceptions", "actions")) {
            JsonElement value = rule.get(field);
            if (value != null && !value.isJsonArray()) {
                problems.add(new ConfigProblem(path + "." + field,
                        "must be a JSON array"));
            }
        }
        JsonElement points = rule.get("points");
        if (points != null && (!points.isJsonPrimitive()
                || !points.getAsJsonPrimitive().isNumber())) {
            problems.add(new ConfigProblem(path + ".points",
                    "must be a JSON number"));
        }
        JsonElement enabled = rule.get("enabled");
        if (enabled != null && (!enabled.isJsonPrimitive()
                || !enabled.getAsJsonPrimitive().isBoolean())) {
            problems.add(new ConfigProblem(path + ".enabled",
                    "must be a JSON boolean"));
        }
    }

    private static boolean booleanValue(JsonElement value, boolean fallback) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : fallback;
    }

    private static int integerValue(JsonElement value, int fallback) {
        try {
            return value == null ? fallback : value.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private record RuleSource(String file, String path, JsonObject rule) {
    }
}
