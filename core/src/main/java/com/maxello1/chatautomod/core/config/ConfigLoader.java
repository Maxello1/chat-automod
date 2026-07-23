package com.maxello1.chatautomod.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.action.CommandTemplate;
import com.maxello1.chatautomod.core.action.ConfiguredAction;
import com.maxello1.chatautomod.core.action.RuleEffect;
import com.maxello1.chatautomod.core.detector.BuiltInFilterMatchers;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.Severity;
import com.maxello1.chatautomod.core.normalize.FilterTextNormalizer;

import com.maxello1.chatautomod.core.normalize.TextNormalizer;

import java.net.IDN;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_RULE_POINTS = 100_000;
    private static final int MAX_SCORE_CAP = 1_000_000;
    private static final int MAX_THRESHOLD = 1_000_000_000;
    private static final long MAX_WINDOW_SECONDS = 2_592_000;
    private static final long MAX_DECAY_MINUTES = 5_256_000;
    private static final long MAX_INACTIVE_MINUTES = 525_600;
    private static final int MAX_HISTORY_ENTRIES = 10_000;
    private static final int MAX_TRACKED_PLAYERS = 1_000_000;
    private static final int MAX_SCORE_ENTRIES = 100_000;
    private static final int MAX_SIMILARITY_HISTORY = 256;
    private static final int MAX_SIMILARITY_LENGTH = 4_096;
    private static final Duration MAX_MUTE_DURATION = Duration.ofDays(3_650);
    private static final Set<String> RESERVED_RULE_IDS = Set.of(
            "spam.rapid", "spam.duplicate", "spam.similarity", "flooding.caps",
            "flooding.repeated_characters", "flooding.message_length", "advertising.discord_invite",
            "advertising.ip", "advertising.domain", "advertising.domain_obfuscation", "security.state_capacity");

    public String defaultJson() {
        return GSON.toJson(AutoModConfig.defaults());
    }

    public ConfigLoadResult load(String json) {
        List<ConfigProblem> problems = new ArrayList<>();
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return ConfigLoadResult.failure(List.of(new ConfigProblem("$", "configuration must be a JSON object")));
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            return ConfigLoadResult.failure(List.of(new ConfigProblem("$", "invalid JSON: " + exception.getMessage())));
        }

        validateUnknownFields(root, problems);
        validateScalarTypes(root, problems);
        AutoModConfig raw;
        try {
            raw = GSON.fromJson(root, AutoModConfig.class);
        } catch (RuntimeException exception) {
            problems.add(new ConfigProblem("$", "could not decode configuration: " + exception.getMessage()));
            return ConfigLoadResult.failure(problems);
        }
        CompiledAutoModConfig compiled;
        try {
            compiled = compile(raw, problems);
        } catch (RuntimeException exception) {
            problems.add(new ConfigProblem("$", "configuration values could not be compiled safely: " + exception.getMessage()));
            return ConfigLoadResult.failure(problems);
        }
        return problems.isEmpty() ? ConfigLoadResult.success(compiled) : ConfigLoadResult.failure(problems);
    }

    private CompiledAutoModConfig compile(AutoModConfig raw, List<ConfigProblem> problems) {
        if (raw == null) {
            problems.add(new ConfigProblem("$", "configuration is required"));
            return null;
        }
        if (raw.schemaVersion != 1) {
            problems.add(new ConfigProblem("$.schema_version", "only schema version 1 is supported"));
        }
        require(raw.permissions, "$.permissions", problems);
        require(raw.staffAlerts, "$.staff_alerts", problems);
        require(raw.normalization, "$.normalization", problems);
        require(raw.rules, "$.rules", problems);
        require(raw.filterPacks, "$.filter_packs", problems);
        require(raw.score, "$.score", problems);
        require(raw.logging, "$.logging", problems);
        require(raw.history, "$.history", problems);
        require(raw.state, "$.state", problems);
        require(raw.mutes, "$.mutes", problems);
        if (raw.permissions == null || raw.staffAlerts == null || raw.normalization == null || raw.rules == null || raw.score == null || raw.logging == null
                || raw.history == null || raw.state == null || raw.mutes == null || raw.filterPacks == null) {
            return null;
        }

        Duration maximumMute = parseDuration(raw.mutes.maximumDuration, "$.mutes.maximum_duration", MAX_MUTE_DURATION, problems);
        Duration muteCooldown = parseDuration(raw.mutes.notificationCooldown, "$.mutes.notification_cooldown", maximumMute, problems);
        if (maximumMute == null) maximumMute = Duration.ofDays(30);
        if (muteCooldown == null) muteCooldown = Duration.ofSeconds(5);

        Duration muteButtonDuration = parseDuration(raw.staffAlerts.muteButtonDuration,
                "$.staff_alerts.mute_button_duration", maximumMute, problems);
        if (muteButtonDuration == null) muteButtonDuration = Duration.ofMinutes(5);
        validateOperatorLevel(raw.permissions.commandFallbackOperatorLevel,
                "$.permissions.command_fallback_operator_level", problems);
        validateOperatorLevel(raw.permissions.staffFallbackOperatorLevel,
                "$.permissions.staff_fallback_operator_level", problems);
        validateOperatorLevel(raw.permissions.bypassFallbackOperatorLevel,
                "$.permissions.bypass_fallback_operator_level", problems);
        if (raw.permissions.legacyFallbackOperatorLevel != null) {
            validateOperatorLevel(raw.permissions.legacyFallbackOperatorLevel,
                    "$.permissions.fallback_operator_level", problems);
        }

        Map<Integer, String> lookalikes = compileSubstitutions(raw.normalization.lookalikeSubstitutions,
                "$.normalization.lookalike_substitutions", problems);
        Map<Integer, String> leet = compileSubstitutions(raw.normalization.leetSubstitutions,
                "$.normalization.leet_substitutions", problems);
        Map<Integer, String> filterLeet = compileSubstitutions(raw.normalization.filterLeetSubstitutions,
                "$.normalization.filter_leet_substitutions", problems);
        var normalization = new CompiledAutoModConfig.Normalization(raw.normalization.unicodeNfkc,
                raw.normalization.removeZeroWidthCharacters, raw.normalization.normalizeLinkObfuscation,
                lookalikes, leet, filterLeet);

        AutoModConfig.Rules rr = raw.rules;
        require(rr.rapidSpam, "$.rules.rapid_spam", problems);
        require(rr.duplicateSpam, "$.rules.duplicate_spam", problems);
        require(rr.similaritySpam, "$.rules.similarity_spam", problems);
        require(rr.caps, "$.rules.caps", problems);
        require(rr.repeatedCharacters, "$.rules.repeated_characters", problems);
        require(rr.messageLength, "$.rules.message_length", problems);
        require(rr.advertising, "$.rules.advertising", problems);
        if (rr.rapidSpam == null || rr.duplicateSpam == null || rr.similaritySpam == null || rr.caps == null
                || rr.repeatedCharacters == null || rr.messageLength == null || rr.advertising == null) {
            return null;
        }

        ConfigNumbers.validate(rr.rapidSpam.minimumIntervalMs, 1, 86_400_000, "$.rules.rapid_spam.minimum_interval_ms", problems);
        ConfigNumbers.validate(rr.rapidSpam.windowSeconds, 1, MAX_WINDOW_SECONDS, "$.rules.rapid_spam.window_seconds", problems);
        ConfigNumbers.validate(rr.rapidSpam.maximumMessagesInWindow, 1, MAX_HISTORY_ENTRIES, "$.rules.rapid_spam.maximum_messages_in_window", problems);
        ConfigNumbers.validate(rr.duplicateSpam.historySize, 1, MAX_HISTORY_ENTRIES, "$.rules.duplicate_spam.history_size", problems);
        ConfigNumbers.validate(rr.duplicateSpam.duplicateWindowSeconds, 1, MAX_WINDOW_SECONDS, "$.rules.duplicate_spam.duplicate_window_seconds", problems);
        ConfigNumbers.validate(rr.duplicateSpam.minimumLength, 0, 1_000_000, "$.rules.duplicate_spam.minimum_length", problems);
        ConfigNumbers.validate(rr.similaritySpam.historySize, 1, MAX_SIMILARITY_HISTORY, "$.rules.similarity_spam.history_size", problems);
        ConfigNumbers.validate(rr.similaritySpam.windowSeconds, 1, MAX_WINDOW_SECONDS, "$.rules.similarity_spam.window_seconds", problems);
        ConfigNumbers.validate(rr.similaritySpam.minimumLength, 1, 1_000_000, "$.rules.similarity_spam.minimum_length", problems);
        ConfigNumbers.validate(rr.similaritySpam.maximumProcessedLength, 1, MAX_SIMILARITY_LENGTH, "$.rules.similarity_spam.maximum_processed_length", problems);
        if (rr.similaritySpam.minimumLength > rr.similaritySpam.maximumProcessedLength) {
            problems.add(new ConfigProblem("$.rules.similarity_spam.minimum_length", "must not exceed maximum_processed_length"));
        }
        validateRatio(rr.similaritySpam.similarityThreshold, "$.rules.similarity_spam.similarity_threshold", problems);
        ConfigNumbers.validate(rr.caps.minimumLetters, 1, 1_000_000, "$.rules.caps.minimum_letters", problems);
        validateRatio(rr.caps.maximumUppercaseRatio, "$.rules.caps.maximum_uppercase_ratio", problems);
        ConfigNumbers.validate(rr.repeatedCharacters.maximumRepeatedLetters, 1, 1_000_000, "$.rules.repeated_characters.maximum_repeated_letters", problems);
        ConfigNumbers.validate(rr.repeatedCharacters.maximumRepeatedSymbols, 1, 1_000_000, "$.rules.repeated_characters.maximum_repeated_symbols", problems);
        ConfigNumbers.validate(rr.messageLength.maximumLength, 1, 1_000_000, "$.rules.message_length.maximum_length", problems);

        Set<String> allowedDomains = compileDomains(rr.advertising.allowedDomains, "$.rules.advertising.allowed_domains", problems);
        var rules = new CompiledAutoModConfig.Rules(
                new CompiledAutoModConfig.RapidSpam(rule(rr.rapidSpam, "$.rules.rapid_spam", maximumMute, problems),
                        Duration.ofMillis(ConfigNumbers.clamp(rr.rapidSpam.minimumIntervalMs, 1, 86_400_000)),
                        Duration.ofSeconds(ConfigNumbers.clamp(rr.rapidSpam.windowSeconds, 1, MAX_WINDOW_SECONDS)), ConfigNumbers.clamp(rr.rapidSpam.maximumMessagesInWindow, 1, MAX_HISTORY_ENTRIES)),
                new CompiledAutoModConfig.DuplicateSpam(rule(rr.duplicateSpam, "$.rules.duplicate_spam", maximumMute, problems),
                        ConfigNumbers.clamp(rr.duplicateSpam.historySize, 1, MAX_HISTORY_ENTRIES), Duration.ofSeconds(ConfigNumbers.clamp(rr.duplicateSpam.duplicateWindowSeconds, 1, MAX_WINDOW_SECONDS)),
                        ConfigNumbers.clamp(rr.duplicateSpam.minimumLength, 0, 1_000_000)),
                new CompiledAutoModConfig.SimilaritySpam(rule(rr.similaritySpam, "$.rules.similarity_spam", maximumMute, problems),
                        ConfigNumbers.clamp(rr.similaritySpam.historySize, 1, MAX_SIMILARITY_HISTORY), Duration.ofSeconds(ConfigNumbers.clamp(rr.similaritySpam.windowSeconds, 1, MAX_WINDOW_SECONDS)),
                        ConfigNumbers.clamp(rr.similaritySpam.minimumLength, 1, 1_000_000), ConfigNumbers.clamp(rr.similaritySpam.maximumProcessedLength, 1, MAX_SIMILARITY_LENGTH),
                        clampRatio(rr.similaritySpam.similarityThreshold)),
                new CompiledAutoModConfig.Caps(rule(rr.caps, "$.rules.caps", maximumMute, problems),
                        ConfigNumbers.clamp(rr.caps.minimumLetters, 1, 1_000_000), clampRatio(rr.caps.maximumUppercaseRatio)),
                new CompiledAutoModConfig.RepeatedCharacters(rule(rr.repeatedCharacters, "$.rules.repeated_characters", maximumMute, problems),
                        ConfigNumbers.clamp(rr.repeatedCharacters.maximumRepeatedLetters, 1, 1_000_000), ConfigNumbers.clamp(rr.repeatedCharacters.maximumRepeatedSymbols, 1, 1_000_000)),
                new CompiledAutoModConfig.MessageLength(rule(rr.messageLength, "$.rules.message_length", maximumMute, problems),
                        ConfigNumbers.clamp(rr.messageLength.maximumLength, 1, 1_000_000)),
                new CompiledAutoModConfig.Advertising(rule(rr.advertising, "$.rules.advertising", maximumMute, problems), allowedDomains)
        );

        FilterTextNormalizer filterNormalizer = new FilterTextNormalizer(normalization);
        TextNormalizer legacyNormalizer = new TextNormalizer(normalization);
        List<CompiledAutoModConfig.CompiledFilter> filters = compileFilters(
                raw.filters, filterNormalizer, legacyNormalizer, maximumMute, problems);

        String filterPackDirectory = compileFilterPackDirectory(
                raw.filterPacks.directory, problems);
        List<String> activeFilterPacks = compileActiveFilterPacks(
                raw.filterPacks.active, problems);

        ConfigNumbers.validate(raw.score.pointDecayMinutes, 1, MAX_DECAY_MINUTES, "$.score.point_decay_minutes", problems);
        ConfigNumbers.validate(raw.score.maximumPointsPerMessage, 1, MAX_SCORE_CAP, "$.score.maximum_points_per_message", problems);
        if (!"HIGHEST_CROSSED".equalsIgnoreCase(raw.score.thresholdMode)) {
            problems.add(new ConfigProblem("$.score.threshold_mode", "only HIGHEST_CROSSED is supported"));
        }
        List<CompiledAutoModConfig.Threshold> thresholds = compileThresholds(raw.score.thresholds, maximumMute, problems);
        var score = new CompiledAutoModConfig.Score(Duration.ofMinutes(ConfigNumbers.clamp(raw.score.pointDecayMinutes, 1, MAX_DECAY_MINUTES)),
                ConfigNumbers.clamp(raw.score.maximumPointsPerMessage, 1, MAX_SCORE_CAP), thresholds);

        ConfigNumbers.validate(raw.logging.retentionDays, 1, 3650, "$.logging.retention_days", problems);
        ConfigNumbers.validate(raw.history.maximumEntriesPerPlayer, 1, MAX_HISTORY_ENTRIES, "$.history.maximum_entries_per_player", problems);
        ConfigNumbers.validate(raw.history.retentionDays, 1, 3650, "$.history.retention_days", problems);
        ConfigNumbers.validate(raw.state.inactivePlayerMinutes, 1, MAX_INACTIVE_MINUTES, "$.state.inactive_player_minutes", problems);
        ConfigNumbers.validate(raw.state.maximumTrackedPlayers, 1, MAX_TRACKED_PLAYERS, "$.state.maximum_tracked_players", problems);
        ConfigNumbers.validate(raw.state.maximumScoreEntriesPerPlayer, 1, MAX_SCORE_ENTRIES, "$.state.maximum_score_entries_per_player", problems);

        return new CompiledAutoModConfig(raw.enabled,
                new CompiledAutoModConfig.Permissions(
                        clampedOperatorLevel(configuredCommandFallback(raw.permissions)),
                        clampedOperatorLevel(raw.permissions.staffFallbackOperatorLevel),
                        raw.permissions.operatorsBypassModeration,
                        clampedOperatorLevel(raw.permissions.bypassFallbackOperatorLevel)),
                new CompiledAutoModConfig.StaffAlerts(raw.staffAlerts.showOriginal, muteButtonDuration),
                normalization, rules, filters,
                new CompiledAutoModConfig.FilterPacks(raw.filterPacks.enabled,
                        filterPackDirectory, activeFilterPacks), score,
                new CompiledAutoModConfig.Logging(raw.logging.enabled, raw.logging.storeOriginalMessages,
                        ConfigNumbers.clamp(raw.logging.retentionDays, 1, 3650)),
                new CompiledAutoModConfig.History(ConfigNumbers.clamp(raw.history.maximumEntriesPerPlayer, 1, MAX_HISTORY_ENTRIES),
                        ConfigNumbers.clamp(raw.history.retentionDays, 1, 3650)),
                new CompiledAutoModConfig.State(Duration.ofMinutes(ConfigNumbers.clamp(raw.state.inactivePlayerMinutes, 1, MAX_INACTIVE_MINUTES)),
                        ConfigNumbers.clamp(raw.state.maximumTrackedPlayers, 1, MAX_TRACKED_PLAYERS), ConfigNumbers.clamp(raw.state.maximumScoreEntriesPerPlayer, 1, MAX_SCORE_ENTRIES)),
                new CompiledAutoModConfig.Mutes(muteCooldown, maximumMute));
    }

    private List<CompiledAutoModConfig.CompiledFilter> compileFilters(List<AutoModConfig.Filter> rawFilters,
            FilterTextNormalizer filterNormalizer, TextNormalizer legacyNormalizer,
            Duration maximumMute, List<ConfigProblem> problems) {
        if (rawFilters == null) {
            problems.add(new ConfigProblem("$.filters", "must not be null"));
            return List.of();
        }
        List<CompiledAutoModConfig.CompiledFilter> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rawFilters.size(); index++) {
            AutoModConfig.Filter filter = rawFilters.get(index);
            String path = "$.filters[" + index + "]";
            if (filter == null) {
                problems.add(new ConfigProblem(path, "must not be null"));
                continue;
            }
            String id = filter.id == null ? "" : filter.id;
            if (!RULE_ID.matcher(id).matches()) {
                problems.add(new ConfigProblem(path + ".id", "must match " + RULE_ID.pattern()));
            } else if (RESERVED_RULE_IDS.contains(id)) {
                problems.add(new ConfigProblem(path + ".id", "collides with a built-in rule ID"));
            } else if (!ids.add(id)) {
                problems.add(new ConfigProblem(path + ".id", "duplicate filter ID"));
            }

            FilterMatchMode mode = parseEnum(FilterMatchMode.class, filter.matchMode,
                    FilterMatchMode.NORMALIZED_WORD, path + ".match_mode", problems);
            RuleCategory category = parseEnum(RuleCategory.class, filter.category,
                    RuleCategory.FILTERED_CONTENT, path + ".category", problems);
            Severity severity = parseEnum(Severity.class, filter.severity,
                    Severity.MODERATE, path + ".severity", problems);
            List<String> rawTerms = filter.terms == null ? List.of() : filter.terms;
            List<String> rawPatterns = filter.patterns == null ? List.of() : filter.patterns;
            if (rawTerms.isEmpty() && rawPatterns.isEmpty()) {
                problems.add(new ConfigProblem(path, "must contain at least one term or built-in pattern"));
            }
            if (severity == Severity.SEVERE && (mode == FilterMatchMode.SUBSTRING
                    || mode == FilterMatchMode.COMPACT
                    || mode == FilterMatchMode.COMPACT_PHRASE)) {
                problems.add(new ConfigProblem(path + ".match_mode",
                        "unrestricted substring matching is not allowed for severe rules"));
            }
            List<String> terms = compileFilterValues(rawTerms, mode,
                    filterNormalizer, legacyNormalizer,
                    path + ".terms", problems);
            List<String> exceptions = compileFilterValues(
                    filter.exceptions == null ? List.of() : filter.exceptions,
                    mode, filterNormalizer, legacyNormalizer,
                    path + ".exceptions", problems);
            List<String> patterns = compileBuiltInPatterns(rawPatterns, path + ".patterns", problems);
            CompiledAutoModConfig.RuleSettings settings = rule(filter, path, maximumMute, problems);
            boolean permanentMute = settings.effect().directActions().stream()
                    .anyMatch(action -> action.type() == ActionType.MUTE && action.duration() == null);
            boolean notifiesStaff = settings.effect().directActions().stream()
                    .anyMatch(action -> action.type() == ActionType.NOTIFY_STAFF);
            if (permanentMute && !notifiesStaff) {
                problems.add(new ConfigProblem(path + ".actions",
                        "permanent-mute rules must include NOTIFY_STAFF"));
            }
            result.add(new CompiledAutoModConfig.CompiledFilter(
                    id.isEmpty() ? "invalid" : id, category, severity, mode,
                    terms, patterns, exceptions, settings));
        }
        return List.copyOf(result);
    }

    private List<String> compileFilterValues(List<String> values, FilterMatchMode mode,
            FilterTextNormalizer filterNormalizer, TextNormalizer legacyNormalizer,
            String path, List<ConfigProblem> problems) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            String itemPath = path + "[" + index + "]";
            if (value == null || value.isBlank()) {
                problems.add(new ConfigProblem(itemPath, "must not be blank"));
                continue;
            }
            if (value.codePoints().anyMatch(Character::isISOControl)) {
                problems.add(new ConfigProblem(itemPath, "must not contain control characters"));
                continue;
            }
            int length = value.codePointCount(0, value.length());
            if (length > 256) {
                problems.add(new ConfigProblem(itemPath, "must contain at most 256 characters"));
                continue;
            }
            String compiled;
            if (isLegacyFilterMode(mode)) {
                var normalized = legacyNormalizer.normalize(value);
                compiled = mode == FilterMatchMode.COMPACT
                        ? normalized.compact() : normalized.deobfuscated();
            } else {
                var normalized = filterNormalizer.normalize(value);
                compiled = switch (mode) {
                    case COMPACT_WORD, COMPACT_PHRASE -> normalized.compact();
                    default -> normalized.normalized();
                };
            }
            if (compiled.isEmpty()) {
                problems.add(new ConfigProblem(itemPath, "must not normalize to an empty value"));
                continue;
            }
            if ((mode == FilterMatchMode.COMPACT || mode == FilterMatchMode.COMPACT_WORD
                    || mode == FilterMatchMode.COMPACT_PHRASE)
                    && compiled.codePointCount(0, compiled.length()) < 4) {
                problems.add(new ConfigProblem(itemPath,
                        "compact terms must contain at least four characters"));
            }
            result.add(compiled);
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private List<String> compileBuiltInPatterns(List<String> values, String path,
            List<ConfigProblem> problems) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            if (value == null || !BuiltInFilterMatchers.supported(value)) {
                problems.add(new ConfigProblem(path + "[" + index + "]",
                        "unsupported built-in pattern"));
            } else {
                result.add(value);
            }
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private String compileFilterPackDirectory(String value,
            List<ConfigProblem> problems) {
        if (value == null || value.isBlank()) {
            problems.add(new ConfigProblem("$.filter_packs.directory",
                    "must not be blank"));
            return "filters";
        }
        if (value.length() > 256
                || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}(?:/[a-zA-Z0-9][a-zA-Z0-9._-]{0,63})*")) {
            problems.add(new ConfigProblem("$.filter_packs.directory",
                    "must be a safe relative directory using letters, digits, '.', '_', '-' and '/'"));
            return "filters";
        }
        return value;
    }

    private List<String> compileActiveFilterPacks(List<String> values,
            List<ConfigProblem> problems) {
        if (values == null) {
            problems.add(new ConfigProblem("$.filter_packs.active",
                    "must be an array"));
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String value = values.get(index);
            String path = "$.filter_packs.active[" + index + "]";
            if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                problems.add(new ConfigProblem(path, "must be a valid filter-pack ID"));
            } else if (!seen.add(value)) {
                problems.add(new ConfigProblem(path, "duplicate pack ID"));
            } else {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isLegacyFilterMode(FilterMatchMode mode) {
        return mode == FilterMatchMode.WORD
                || mode == FilterMatchMode.PHRASE
                || mode == FilterMatchMode.SUBSTRING
                || mode == FilterMatchMode.COMPACT;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback,
            String path, List<ConfigProblem> problems) {
        try {
            return Enum.valueOf(type, String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            problems.add(new ConfigProblem(path, "unsupported value: " + raw));
            return fallback;
        }
    }

    private List<CompiledAutoModConfig.Threshold> compileThresholds(List<AutoModConfig.Threshold> rawThresholds,
            Duration maximumMute, List<ConfigProblem> problems) {
        if (rawThresholds == null) {
            problems.add(new ConfigProblem("$.score.thresholds", "must not be null"));
            return List.of();
        }
        List<CompiledAutoModConfig.Threshold> result = new ArrayList<>();
        int previous = 0;
        for (int i = 0; i < rawThresholds.size(); i++) {
            AutoModConfig.Threshold threshold = rawThresholds.get(i);
            String path = "$.score.thresholds[" + i + "]";
            if (threshold == null) {
                problems.add(new ConfigProblem(path, "must not be null"));
                continue;
            }
            ConfigNumbers.validate(threshold.points, 1, MAX_THRESHOLD, path + ".points", problems);
            if (threshold.points <= previous) {
                problems.add(new ConfigProblem(path + ".points", "threshold points must be positive and strictly increasing"));
            }
            previous = Math.max(previous, ConfigNumbers.clamp(threshold.points, 1, MAX_THRESHOLD));
            ParsedActions parsed = parseActions(threshold.actions, path + ".actions", maximumMute, false, problems);
            result.add(new CompiledAutoModConfig.Threshold(ConfigNumbers.clamp(threshold.points, 1, MAX_THRESHOLD), parsed.actions));
        }
        return result;
    }

    private CompiledAutoModConfig.RuleSettings rule(AutoModConfig.Rule raw, String path, Duration maximumMute,
            List<ConfigProblem> problems) {
        ConfigNumbers.validate(raw.points, 0, MAX_RULE_POINTS, path + ".points", problems);
        ParsedActions actions = parseActions(raw.actions, path + ".actions", maximumMute, true, problems);
        return new CompiledAutoModConfig.RuleSettings(raw.enabled, ConfigNumbers.clamp(raw.points, 0, MAX_RULE_POINTS),
                new RuleEffect(actions.block, actions.actions));
    }

    private ParsedActions parseActions(List<JsonElement> elements, String path, Duration maximumMute,
            boolean allowBlock, List<ConfigProblem> problems) {
        if (elements == null) {
            problems.add(new ConfigProblem(path, "must not be null"));
            return new ParsedActions(false, List.of());
        }
        boolean block = false;
        List<ConfiguredAction> actions = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            JsonElement element = elements.get(i);
            String itemPath = path + "[" + i + "]";
            if (element == null || element.isJsonNull()) {
                problems.add(new ConfigProblem(itemPath, "action must not be null"));
                continue;
            }
            String typeValue;
            JsonObject object = null;
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                typeValue = element.getAsString();
            } else if (element.isJsonObject()) {
                object = element.getAsJsonObject();
                checkFields(object, itemPath, Set.of("type", "message", "duration", "reason", "command"), problems);
                typeValue = stringField(object, "type");
            } else {
                problems.add(new ConfigProblem(itemPath, "action must be a string or object"));
                continue;
            }
            if (typeValue == null) {
                problems.add(new ConfigProblem(itemPath + ".type", "action type is required"));
                continue;
            }
            String type = typeValue.toUpperCase(Locale.ROOT);
            try {
                switch (type) {
                    case "BLOCK" -> {
                        if (!allowBlock) problems.add(new ConfigProblem(itemPath, "BLOCK is only valid on rules"));
                        else block = true;
                    }
                    case "NOTIFY_STAFF" -> actions.add(ConfiguredAction.notifyStaff());
                    case "WARN" -> actions.add(new ConfiguredAction(ActionType.WARN,
                            valueOr(object, "message", "Please follow the server chat rules."), null, "", null));
                    case "MUTE" -> {
                        String durationValue = valueOr(object, "duration", null);
                        String reason = valueOr(object, "reason", "Chat rule violation");
                        if ("permanent".equalsIgnoreCase(durationValue)) {
                            actions.add(new ConfiguredAction(ActionType.MUTE, "", null, reason, null));
                        } else {
                            Duration duration = parseDuration(durationValue,
                                    itemPath + ".duration", maximumMute, problems);
                            if (duration != null) {
                                actions.add(new ConfiguredAction(ActionType.MUTE, "", duration, reason, null));
                            }
                        }
                    }
                    case "KICK" -> actions.add(new ConfiguredAction(ActionType.KICK, "", null,
                            valueOr(object, "reason", "Repeated chat violations."), null));
                    case "EXECUTE_COMMAND" -> {
                        String command = valueOr(object, "command", null);
                        try {
                            actions.add(new ConfiguredAction(ActionType.EXECUTE_COMMAND, "", null, "",
                                    CommandTemplate.compile(command)));
                        } catch (IllegalArgumentException exception) {
                            problems.add(new ConfigProblem(itemPath + ".command", exception.getMessage()));
                        }
                    }
                    default -> problems.add(new ConfigProblem(itemPath, "unknown action type: " + typeValue));
                }
            } catch (RuntimeException exception) {
                problems.add(new ConfigProblem(itemPath, exception.getMessage()));
            }
        }
        return new ParsedActions(block, List.copyOf(actions));
    }

    private record ParsedActions(boolean block, List<ConfiguredAction> actions) {}

    private Map<Integer, String> compileSubstitutions(Map<String, String> values, String path,
            List<ConfigProblem> problems) {
        if (values == null) {
            problems.add(new ConfigProblem(path, "must not be null"));
            return Map.of();
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = key == null ? null : key.toLowerCase(Locale.ROOT);
            if (normalizedKey == null || normalizedKey.codePointCount(0, normalizedKey.length()) != 1) {
                problems.add(new ConfigProblem(path, "substitution keys must be one Unicode code point"));
            } else if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 4) {
                problems.add(new ConfigProblem(path + "." + key, "substitution values must contain one to four code points"));
            } else {
                result.put(normalizedKey.codePointAt(0), value.toLowerCase(Locale.ROOT));
            }
        });
        return result;
    }

    private Set<String> compileDomains(List<String> values, String path, List<ConfigProblem> problems) {
        if (values == null) {
            problems.add(new ConfigProblem(path, "must not be null"));
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < values.size(); i++) {
            try {
                String value = values.get(i);
                if (value == null || value.contains(":") || value.contains("/") || value.contains("*")) {
                    throw new IllegalArgumentException("must be a bare domain without scheme, port, path, or wildcard");
                }
                String ascii = IDN.toASCII(value.toLowerCase(Locale.ROOT), IDN.USE_STD3_ASCII_RULES);
                if (!ascii.contains(".") || ascii.startsWith(".") || ascii.endsWith(".")) {
                    throw new IllegalArgumentException("must be a valid domain name");
                }
                result.add(ascii);
            } catch (RuntimeException exception) {
                problems.add(new ConfigProblem(path + "[" + i + "]", exception.getMessage()));
            }
        }
        return result;
    }

    private Duration parseDuration(String value, String path, Duration maximum, List<ConfigProblem> problems) {
        try {
            return maximum == null ? DurationParser.parse(value) : DurationParser.parse(value, maximum);
        } catch (IllegalArgumentException exception) {
            problems.add(new ConfigProblem(path, exception.getMessage()));
            return null;
        }
    }

    private void validateScalarTypes(JsonObject root, List<ConfigProblem> problems) {
        numberField(root, "schema_version", "$.schema_version", problems);
        booleanField(root, "enabled", "$.enabled", problems);
        object(root, "permissions").ifPresent(value -> {
            numberField(value, "fallback_operator_level",
                    "$.permissions.fallback_operator_level", problems);
            numberField(value, "command_fallback_operator_level",
                    "$.permissions.command_fallback_operator_level", problems);
            numberField(value, "staff_fallback_operator_level",
                    "$.permissions.staff_fallback_operator_level", problems);
            booleanField(value, "operators_bypass_moderation",
                    "$.permissions.operators_bypass_moderation", problems);
            numberField(value, "bypass_fallback_operator_level",
                    "$.permissions.bypass_fallback_operator_level", problems);
        });
        object(root, "staff_alerts").ifPresent(value ->
                booleanField(value, "show_original", "$.staff_alerts.show_original", problems));
        object(root, "normalization").ifPresent(value -> {
            booleanField(value, "unicode_nfkc", "$.normalization.unicode_nfkc", problems);
            booleanField(value, "remove_zero_width_characters",
                    "$.normalization.remove_zero_width_characters", problems);
            booleanField(value, "normalize_link_obfuscation",
                    "$.normalization.normalize_link_obfuscation", problems);
        });
        object(root, "filter_packs").ifPresent(value -> {
            booleanField(value, "enabled", "$.filter_packs.enabled", problems);
            stringField(value, "directory", "$.filter_packs.directory", problems);
            JsonElement active = value.get("active");
            if (active != null && !active.isJsonArray()) {
                problems.add(new ConfigProblem("$.filter_packs.active",
                        "must be a JSON array"));
            } else if (active != null) {
                JsonArray entries = active.getAsJsonArray();
                for (int index = 0; index < entries.size(); index++) {
                    JsonElement entry = entries.get(index);
                    if (!entry.isJsonPrimitive()
                            || !entry.getAsJsonPrimitive().isString()) {
                        problems.add(new ConfigProblem("$.filter_packs.active["
                                + index + "]", "must be a JSON string"));
                    }
                }
            }
        });
        object(root, "rules").ifPresent(rules -> {
            ruleScalarTypes(rules, "rapid_spam",
                    List.of("minimum_interval_ms", "window_seconds", "maximum_messages_in_window"), problems);
            ruleScalarTypes(rules, "duplicate_spam",
                    List.of("history_size", "duplicate_window_seconds", "minimum_length"), problems);
            ruleScalarTypes(rules, "similarity_spam",
                    List.of("history_size", "window_seconds", "minimum_length",
                            "maximum_processed_length", "similarity_threshold"), problems);
            ruleScalarTypes(rules, "caps", List.of("minimum_letters", "maximum_uppercase_ratio"), problems);
            ruleScalarTypes(rules, "repeated_characters",
                    List.of("maximum_repeated_letters", "maximum_repeated_symbols"), problems);
            ruleScalarTypes(rules, "message_length", List.of("maximum_length"), problems);
            ruleScalarTypes(rules, "advertising", List.of(), problems);
        });
        array(root, "filters").ifPresent(filters -> {
            for (int i = 0; i < filters.size(); i++) {
                if (!filters.get(i).isJsonObject()) continue;
                JsonObject filter = filters.get(i).getAsJsonObject();
                String path = "$.filters[" + i + "]";
                booleanField(filter, "enabled", path + ".enabled", problems);
                numberField(filter, "points", path + ".points", problems);
            }
        });
        object(root, "score").ifPresent(score -> {
            numberField(score, "point_decay_minutes", "$.score.point_decay_minutes", problems);
            numberField(score, "maximum_points_per_message", "$.score.maximum_points_per_message", problems);
            array(score, "thresholds").ifPresent(thresholds -> {
                for (int i = 0; i < thresholds.size(); i++) {
                    if (thresholds.get(i).isJsonObject()) {
                        numberField(thresholds.get(i).getAsJsonObject(), "points",
                                "$.score.thresholds[" + i + "].points", problems);
                    }
                }
            });
        });
        object(root, "logging").ifPresent(logging -> {
            booleanField(logging, "enabled", "$.logging.enabled", problems);
            booleanField(logging, "store_original_messages", "$.logging.store_original_messages", problems);
            numberField(logging, "retention_days", "$.logging.retention_days", problems);
        });
        object(root, "history").ifPresent(history -> {
            numberField(history, "maximum_entries_per_player",
                    "$.history.maximum_entries_per_player", problems);
            numberField(history, "retention_days", "$.history.retention_days", problems);
        });
        object(root, "state").ifPresent(state -> {
            numberField(state, "inactive_player_minutes", "$.state.inactive_player_minutes", problems);
            numberField(state, "maximum_tracked_players", "$.state.maximum_tracked_players", problems);
            numberField(state, "maximum_score_entries_per_player",
                    "$.state.maximum_score_entries_per_player", problems);
        });
    }

    private void ruleScalarTypes(JsonObject rules, String name, List<String> numericFields,
            List<ConfigProblem> problems) {
        object(rules, name).ifPresent(rule -> {
            String path = "$.rules." + name;
            booleanField(rule, "enabled", path + ".enabled", problems);
            numberField(rule, "points", path + ".points", problems);
            numericFields.forEach(field -> numberField(rule, field, path + "." + field, problems));
        });
    }

    private static void booleanField(JsonObject object, String name, String path,
            List<ConfigProblem> problems) {
        JsonElement value = object.get(name);
        if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())) {
            problems.add(new ConfigProblem(path, "must be a JSON boolean"));
        }
    }

    private static void numberField(JsonObject object, String name, String path,
            List<ConfigProblem> problems) {
        JsonElement value = object.get(name);
        if (value != null && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())) {
            problems.add(new ConfigProblem(path, "must be a JSON number"));
        }
    }

    private static void stringField(JsonObject object, String name, String path,
            List<ConfigProblem> problems) {
        JsonElement value = object.get(name);
        if (value != null && (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString())) {
            problems.add(new ConfigProblem(path, "must be a JSON string"));
        }
    }

    private void validateUnknownFields(JsonObject root, List<ConfigProblem> problems) {
        checkFields(root, "$", Set.of("schema_version", "enabled", "permissions", "staff_alerts", "normalization", "rules", "filters", "filter_packs", "score",
                "logging", "history", "state", "mutes"), problems);
        object(root, "permissions").ifPresent(o -> checkFields(o, "$.permissions", Set.of(
                "fallback_operator_level", "command_fallback_operator_level", "staff_fallback_operator_level",
                "operators_bypass_moderation", "bypass_fallback_operator_level"), problems));
        object(root, "staff_alerts").ifPresent(o -> checkFields(o, "$.staff_alerts", Set.of("show_original", "mute_button_duration"), problems));
        object(root, "normalization").ifPresent(o -> checkFields(o, "$.normalization", Set.of("unicode_nfkc",
                "remove_zero_width_characters", "normalize_link_obfuscation", "lookalike_substitutions",
                "leet_substitutions", "filter_leet_substitutions"), problems));
        object(root, "rules").ifPresent(rules -> {
            checkFields(rules, "$.rules", Set.of("rapid_spam", "duplicate_spam", "similarity_spam", "caps",
                    "repeated_characters", "message_length", "advertising"), problems);
            checkRule(rules, "rapid_spam", Set.of("minimum_interval_ms", "window_seconds", "maximum_messages_in_window"), problems);
            checkRule(rules, "duplicate_spam", Set.of("history_size", "duplicate_window_seconds", "minimum_length"), problems);
            checkRule(rules, "similarity_spam", Set.of("history_size", "window_seconds", "minimum_length",
                    "maximum_processed_length", "similarity_threshold"), problems);
            checkRule(rules, "caps", Set.of("minimum_letters", "maximum_uppercase_ratio"), problems);
            checkRule(rules, "repeated_characters", Set.of("maximum_repeated_letters", "maximum_repeated_symbols"), problems);
            checkRule(rules, "message_length", Set.of("maximum_length"), problems);
            checkRule(rules, "advertising", Set.of("allowed_domains"), problems);
        });
        array(root, "filters").ifPresent(a -> {
            for (int i = 0; i < a.size(); i++) if (a.get(i).isJsonObject()) checkFields(a.get(i).getAsJsonObject(),
                    "$.filters[" + i + "]", Set.of("id", "category", "severity", "enabled", "points",
                            "actions", "match_mode", "terms", "patterns", "exceptions"), problems);
        });
        object(root, "filter_packs").ifPresent(o -> checkFields(o, "$.filter_packs", Set.of("enabled", "directory", "active"), problems));
        object(root, "score").ifPresent(o -> {
            checkFields(o, "$.score", Set.of("point_decay_minutes", "maximum_points_per_message", "threshold_mode", "thresholds"), problems);
            array(o, "thresholds").ifPresent(a -> {
                for (int i = 0; i < a.size(); i++) if (a.get(i).isJsonObject()) checkFields(a.get(i).getAsJsonObject(),
                        "$.score.thresholds[" + i + "]", Set.of("points", "actions"), problems);
            });
        });
        object(root, "logging").ifPresent(o -> checkFields(o, "$.logging", Set.of("enabled", "store_original_messages", "retention_days"), problems));
        object(root, "history").ifPresent(o -> checkFields(o, "$.history", Set.of("maximum_entries_per_player", "retention_days"), problems));
        object(root, "state").ifPresent(o -> checkFields(o, "$.state", Set.of("inactive_player_minutes", "maximum_tracked_players", "maximum_score_entries_per_player"), problems));
        object(root, "mutes").ifPresent(o -> checkFields(o, "$.mutes", Set.of("notification_cooldown", "maximum_duration"), problems));
    }

    private void checkRule(JsonObject rules, String name, Set<String> extra, List<ConfigProblem> problems) {
        object(rules, name).ifPresent(o -> {
            Set<String> allowed = new HashSet<>(Set.of("enabled", "points", "actions"));
            allowed.addAll(extra);
            checkFields(o, "$.rules." + name, allowed, problems);
        });
    }

    private static void checkFields(JsonObject object, String path, Set<String> allowed, List<ConfigProblem> problems) {
        object.keySet().stream().filter(key -> !allowed.contains(key))
                .forEach(key -> problems.add(new ConfigProblem(path + "." + key, "unknown property")));
    }

    private static java.util.Optional<JsonObject> object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? java.util.Optional.of(value.getAsJsonObject()) : java.util.Optional.empty();
    }

    private static java.util.Optional<JsonArray> array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? java.util.Optional.of(value.getAsJsonArray()) : java.util.Optional.empty();
    }

    private static String stringField(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) return null;
        return object.get(key).getAsString();
    }

    private static String valueOr(JsonObject object, String key, String fallback) {
        String value = stringField(object, key);
        return value == null ? fallback : value;
    }

    private static void require(Object value, String path, List<ConfigProblem> problems) {
        if (value == null) problems.add(new ConfigProblem(path, "is required"));
    }

    private static void validateOperatorLevel(int value, String path,
            List<ConfigProblem> problems) {
        if (value < 0 || value > 4) {
            problems.add(new ConfigProblem(path, "must be between 0 and 4"));
        }
    }

    private static int clampedOperatorLevel(int value) {
        return Math.max(0, Math.min(4, value));
    }

    private static int configuredCommandFallback(AutoModConfig.Permissions permissions) {
        return permissions.legacyFallbackOperatorLevel == null
                ? permissions.commandFallbackOperatorLevel
                : permissions.legacyFallbackOperatorLevel;
    }

    private static void validatePositive(long value, String path, List<ConfigProblem> problems) {
        if (value <= 0) problems.add(new ConfigProblem(path, "must be positive"));
    }

    private static void validateNonNegative(long value, String path, List<ConfigProblem> problems) {
        if (value < 0) problems.add(new ConfigProblem(path, "must not be negative"));
    }

    private static void validateRatio(double value, String path, List<ConfigProblem> problems) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) problems.add(new ConfigProblem(path, "must be greater than 0 and at most 1"));
    }

    private static double clampRatio(double value) {
        return Double.isFinite(value) ? Math.max(Double.MIN_NORMAL, Math.min(1.0, value)) : 1.0;
    }
}
