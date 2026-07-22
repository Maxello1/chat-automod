package com.maxello1.chatautomod.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AutoModConfig {
    @SerializedName("schema_version") public int schemaVersion = 1;
    public boolean enabled = true;
    public Permissions permissions = new Permissions();
    @SerializedName("staff_alerts") public StaffAlerts staffAlerts = new StaffAlerts();
    public Normalization normalization = new Normalization();
    public Rules rules = new Rules();
    public List<Filter> filters = new ArrayList<>();
    public Score score = new Score();
    public Logging logging = new Logging();
    public History history = new History();
    public State state = new State();
    public Mutes mutes = new Mutes();

    public static AutoModConfig defaults() {
        return new AutoModConfig();
    }

    public static JsonElement action(String name) {
        return new JsonPrimitive(name);
    }

    public static final class Permissions {
        @SerializedName("fallback_operator_level") public int fallbackOperatorLevel = 3;
    }

    public static final class StaffAlerts {
        @SerializedName("show_original") public boolean showOriginal = false;
        @SerializedName("mute_button_duration") public String muteButtonDuration = "5m";
    }

    public static class Normalization {
        @SerializedName("unicode_nfkc") public boolean unicodeNfkc = true;
        @SerializedName("remove_zero_width_characters") public boolean removeZeroWidthCharacters = true;
        @SerializedName("normalize_link_obfuscation") public boolean normalizeLinkObfuscation = true;
        @SerializedName("lookalike_substitutions") public Map<String, String> lookalikeSubstitutions = defaultLookalikes();
        @SerializedName("leet_substitutions") public Map<String, String> leetSubstitutions = defaultLeet();

        private static Map<String, String> defaultLeet() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("0", "o");
            result.put("3", "e");
            result.put("4", "a");
            result.put("5", "s");
            result.put("7", "t");
            result.put("@", "a");
            result.put("$", "s");
            return result;
        }

        private static Map<String, String> defaultLookalikes() {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("а", "a");
            result.put("е", "e");
            result.put("о", "o");
            result.put("р", "p");
            result.put("с", "c");
            result.put("х", "x");
            result.put("у", "y");
            result.put("і", "i");
            result.put("ј", "j");
            result.put("Α", "a");
            result.put("Β", "b");
            result.put("Ε", "e");
            result.put("Ι", "i");
            result.put("Κ", "k");
            result.put("Μ", "m");
            result.put("Ν", "n");
            result.put("Ο", "o");
            result.put("Ρ", "p");
            result.put("Τ", "t");
            result.put("Χ", "x");
            return result;
        }
    }

    public static class Rule {
        public boolean enabled = true;
        public int points = 1;
        public List<JsonElement> actions = new ArrayList<>(List.of(action("BLOCK")));
    }

    public static final class RapidSpam extends Rule {
        @SerializedName("minimum_interval_ms") public long minimumIntervalMs = 700;
        @SerializedName("window_seconds") public long windowSeconds = 8;
        @SerializedName("maximum_messages_in_window") public int maximumMessagesInWindow = 5;

        public RapidSpam() {
            actions = new ArrayList<>(List.of(action("BLOCK"), action("NOTIFY_STAFF")));
        }
    }

    public static final class DuplicateSpam extends Rule {
        @SerializedName("history_size") public int historySize = 8;
        @SerializedName("duplicate_window_seconds") public long duplicateWindowSeconds = 45;
        @SerializedName("minimum_length") public int minimumLength = 3;

        public DuplicateSpam() {
            points = 2;
            actions = new ArrayList<>(List.of(action("BLOCK"), action("NOTIFY_STAFF")));
        }
    }

    public static final class SimilaritySpam extends Rule {
        @SerializedName("history_size") public int historySize = 8;
        @SerializedName("window_seconds") public long windowSeconds = 45;
        @SerializedName("minimum_length") public int minimumLength = 8;
        @SerializedName("maximum_processed_length") public int maximumProcessedLength = 512;
        @SerializedName("similarity_threshold") public double similarityThreshold = 0.86;

        public SimilaritySpam() {
            points = 2;
            actions = new ArrayList<>(List.of(action("BLOCK"), action("NOTIFY_STAFF")));
        }
    }

    public static final class Caps extends Rule {
        @SerializedName("minimum_letters") public int minimumLetters = 8;
        @SerializedName("maximum_uppercase_ratio") public double maximumUppercaseRatio = 0.75;
    }

    public static final class RepeatedCharacters extends Rule {
        @SerializedName("maximum_repeated_letters") public int maximumRepeatedLetters = 6;
        @SerializedName("maximum_repeated_symbols") public int maximumRepeatedSymbols = 8;
    }

    public static final class MessageLength extends Rule {
        @SerializedName("maximum_length") public int maximumLength = 220;
    }

    public static final class Advertising extends Rule {
        @SerializedName("allowed_domains") public List<String> allowedDomains = new ArrayList<>(List.of("example.org"));

        public Advertising() {
            points = 5;
            actions = new ArrayList<>(List.of(action("BLOCK"), action("NOTIFY_STAFF")));
        }
    }

    public static final class Rules {
        @SerializedName("rapid_spam") public RapidSpam rapidSpam = new RapidSpam();
        @SerializedName("duplicate_spam") public DuplicateSpam duplicateSpam = new DuplicateSpam();
        @SerializedName("similarity_spam") public SimilaritySpam similaritySpam = new SimilaritySpam();
        public Caps caps = new Caps();
        @SerializedName("repeated_characters") public RepeatedCharacters repeatedCharacters = new RepeatedCharacters();
        @SerializedName("message_length") public MessageLength messageLength = new MessageLength();
        public Advertising advertising = new Advertising();
    }

    public static final class Filter extends Rule {
        public String id = "";
        @SerializedName("match_mode") public String matchMode = "WORD";
        public List<String> terms = new ArrayList<>();
        public List<String> exceptions = new ArrayList<>();
    }

    public static final class Score {
        @SerializedName("point_decay_minutes") public long pointDecayMinutes = 30;
        @SerializedName("maximum_points_per_message") public int maximumPointsPerMessage = 10;
        @SerializedName("threshold_mode") public String thresholdMode = "HIGHEST_CROSSED";
        public List<Threshold> thresholds = new ArrayList<>();
    }

    public static final class Threshold {
        public int points;
        public List<JsonElement> actions = new ArrayList<>();
    }

    public static final class Logging {
        public boolean enabled = true;
        @SerializedName("store_original_messages") public boolean storeOriginalMessages = false;
        @SerializedName("retention_days") public int retentionDays = 30;
    }

    public static final class History {
        @SerializedName("maximum_entries_per_player") public int maximumEntriesPerPlayer = 100;
        @SerializedName("retention_days") public int retentionDays = 30;
    }

    public static final class State {
        @SerializedName("inactive_player_minutes") public long inactivePlayerMinutes = 30;
        @SerializedName("maximum_tracked_players") public int maximumTrackedPlayers = 10_000;
        @SerializedName("maximum_score_entries_per_player") public int maximumScoreEntriesPerPlayer = 4096;
    }

    public static final class Mutes {
        @SerializedName("notification_cooldown") public String notificationCooldown = "5s";
        @SerializedName("maximum_duration") public String maximumDuration = "30d";
    }
}
