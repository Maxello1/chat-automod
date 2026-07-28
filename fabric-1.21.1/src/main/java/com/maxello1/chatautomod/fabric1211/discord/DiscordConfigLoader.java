package com.maxello1.chatautomod.fabric1211.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.maxello1.chatautomod.core.config.DurationParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class DiscordConfigLoader {
    private static final Duration MINIMUM_CASE_EXPIRY = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_CASE_EXPIRY = Duration.ofDays(7);
    private static final String DEFAULT_JSON = """
            {
              "schema_version": 1,
              "enabled": false,
              "token_environment_variable": "CHATAUTOMOD_DISCORD_TOKEN",
              "guild_id": "",
              "alert_channel_id": "",
              "allowed_role_ids": [],
              "allowed_user_ids": [],
              "mention_role_id": "",
              "case_expiry": "24h",
              "include_original_message": false,
              "actions": {
                "mute_10_minutes": true,
                "mute_1_hour": true,
                "permanent_ban": true
              }
            }
            """;

    private final Function<String, String> environment;

    public DiscordConfigLoader() {
        this(System::getenv);
    }

    public DiscordConfigLoader(Function<String, String> environment) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
    }

    public String defaultJson() {
        return DEFAULT_JSON;
    }

    public LoadResult load(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                Files.writeString(
                        path,
                        DEFAULT_JSON,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return LoadResult.failed("$", "could not read Discord configuration ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    public LoadResult parse(String json) {
        List<DiscordConfigProblem> problems = new ArrayList<>();
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return LoadResult.failed("$", "must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            return LoadResult.failed("$", "invalid JSON");
        }

        DiscordConfig defaults = DiscordConfig.defaults();
        int schemaVersion = integer(root, "schema_version", defaults.schemaVersion(), problems);
        boolean enabled = bool(root, "enabled", defaults.enabled(), problems);
        String tokenEnvironmentVariable = string(
                root,
                "token_environment_variable",
                defaults.tokenEnvironmentVariable(),
                problems);
        String guildId = string(root, "guild_id", defaults.guildId(), problems);
        String alertChannelId = string(root, "alert_channel_id", defaults.alertChannelId(), problems);
        Set<String> allowedRoleIds = snowflakeList(root, "allowed_role_ids", problems);
        Set<String> allowedUserIds = snowflakeList(root, "allowed_user_ids", problems);
        String mentionRoleId = string(root, "mention_role_id", defaults.mentionRoleId(), problems);
        String caseExpiryText = string(root, "case_expiry", "24h", problems);
        boolean includeOriginalMessage = bool(
                root,
                "include_original_message",
                defaults.includeOriginalMessage(),
                problems);

        DiscordConfig.Actions defaultActions = defaults.actions();
        JsonObject actions = object(root, "actions", problems);
        DiscordConfig.Actions configuredActions = actions == null
                ? defaultActions
                : new DiscordConfig.Actions(
                        bool(actions, "mute_10_minutes", defaultActions.mute10Minutes(), problems, "$.actions"),
                        bool(actions, "mute_1_hour", defaultActions.mute1Hour(), problems, "$.actions"),
                        bool(actions, "permanent_ban", defaultActions.permanentBan(), problems, "$.actions"));

        Duration caseExpiry = defaults.caseExpiry();
        try {
            caseExpiry = DurationParser.parse(caseExpiryText, MAXIMUM_CASE_EXPIRY);
            if (caseExpiry.compareTo(MINIMUM_CASE_EXPIRY) < 0) {
                problems.add(new DiscordConfigProblem(
                        "$.case_expiry",
                        "must be between 5 minutes and 7 days"));
            }
        } catch (IllegalArgumentException exception) {
            problems.add(new DiscordConfigProblem(
                    "$.case_expiry",
                    "must be a duration between 5 minutes and 7 days"));
        }

        if (schemaVersion != 1) {
            problems.add(new DiscordConfigProblem("$.schema_version", "must equal 1"));
        }
        validateOptionalSnowflake(guildId, "$.guild_id", problems);
        validateOptionalSnowflake(alertChannelId, "$.alert_channel_id", problems);
        validateOptionalSnowflake(mentionRoleId, "$.mention_role_id", problems);
        if (!tokenEnvironmentVariable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            problems.add(new DiscordConfigProblem(
                    "$.token_environment_variable",
                    "must be a valid environment-variable name"));
        }
        if (enabled) {
            requireSnowflake(guildId, "$.guild_id", problems);
            requireSnowflake(alertChannelId, "$.alert_channel_id", problems);
            if (configuredActions.anyEnabled() && allowedRoleIds.isEmpty() && allowedUserIds.isEmpty()) {
                problems.add(new DiscordConfigProblem(
                        "$.allowed_role_ids",
                        "at least one allowed role or user is required while punishment actions are enabled"));
            }
            String tokenValue = environment.apply(tokenEnvironmentVariable);
            if (tokenValue == null || tokenValue.isBlank()) {
                problems.add(new DiscordConfigProblem(
                        "$.token_environment_variable",
                        "configured token environment variable is missing or empty"));
            }
        }

        DiscordConfig parsedConfig = new DiscordConfig(
                schemaVersion,
                enabled,
                tokenEnvironmentVariable,
                guildId,
                alertChannelId,
                allowedRoleIds,
                allowedUserIds,
                mentionRoleId,
                caseExpiry,
                includeOriginalMessage,
                configuredActions);
        boolean valid = problems.isEmpty();
        return new LoadResult(
                valid ? parsedConfig : parsedConfig.disabled(),
                enabled,
                valid,
                problems);
    }

    Optional<String> token(DiscordConfig config) {
        String value = environment.apply(config.tokenEnvironmentVariable());
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static JsonObject object(
            JsonObject parent,
            String name,
            List<DiscordConfigProblem> problems
    ) {
        JsonElement value = parent.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            problems.add(new DiscordConfigProblem("$." + name, "must be an object"));
            return null;
        }
        return value.getAsJsonObject();
    }

    private static int integer(
            JsonObject object,
            String name,
            int fallback,
            List<DiscordConfigProblem> problems
    ) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException exception) {
            problems.add(new DiscordConfigProblem("$." + name, "must be an integer"));
            return fallback;
        }
    }

    private static boolean bool(
            JsonObject object,
            String name,
            boolean fallback,
            List<DiscordConfigProblem> problems
    ) {
        return bool(object, name, fallback, problems, "$" );
    }

    private static boolean bool(
            JsonObject object,
            String name,
            boolean fallback,
            List<DiscordConfigProblem> problems,
            String parentPath
    ) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            problems.add(new DiscordConfigProblem(parentPath + "." + name, "must be a boolean"));
            return fallback;
        }
        return value.getAsBoolean();
    }

    private static String string(
            JsonObject object,
            String name,
            String fallback,
            List<DiscordConfigProblem> problems
    ) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            problems.add(new DiscordConfigProblem("$." + name, "must be a string"));
            return fallback;
        }
        return value.getAsString().strip();
    }

    private static Set<String> snowflakeList(
            JsonObject object,
            String name,
            List<DiscordConfigProblem> problems
    ) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return Set.of();
        }
        if (!value.isJsonArray()) {
            problems.add(new DiscordConfigProblem("$." + name, "must be an array"));
            return Set.of();
        }
        JsonArray values = value.getAsJsonArray();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            JsonElement entry = values.get(index);
            String path = "$." + name + "[" + index + "]";
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                problems.add(new DiscordConfigProblem(path, "must be an unsigned decimal Discord ID"));
                continue;
            }
            String id = entry.getAsString().strip();
            if (!isSnowflake(id)) {
                problems.add(new DiscordConfigProblem(path, "must be an unsigned decimal Discord ID"));
                continue;
            }
            result.add(id);
        }
        return result;
    }

    private static void validateOptionalSnowflake(
            String value,
            String path,
            List<DiscordConfigProblem> problems
    ) {
        if (!value.isEmpty() && !isSnowflake(value)) {
            problems.add(new DiscordConfigProblem(path, "must be an unsigned decimal Discord ID"));
        }
    }

    private static void requireSnowflake(
            String value,
            String path,
            List<DiscordConfigProblem> problems
    ) {
        if (value.isEmpty()) {
            problems.add(new DiscordConfigProblem(path, "is required when Discord integration is enabled"));
        }
    }

    static boolean isSnowflake(String value) {
        if (value == null || value.isEmpty() || value.length() > 20
                || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Long.parseUnsignedLong(value) != 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public record LoadResult(
            DiscordConfig config,
            boolean requestedEnabled,
            boolean configured,
            List<DiscordConfigProblem> problems
    ) {
        public LoadResult {
            problems = List.copyOf(problems);
        }

        static LoadResult failed(String path, String message) {
            return new LoadResult(
                    DiscordConfig.defaults(),
                    false,
                    false,
                    List.of(new DiscordConfigProblem(path, message)));
        }
    }
}
