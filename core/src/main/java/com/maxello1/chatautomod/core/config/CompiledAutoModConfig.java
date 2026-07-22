package com.maxello1.chatautomod.core.config;

import com.maxello1.chatautomod.core.action.ConfiguredAction;
import com.maxello1.chatautomod.core.action.RuleEffect;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record CompiledAutoModConfig(
        boolean enabled,
        Permissions permissions,
        StaffAlerts staffAlerts,
        Normalization normalization,
        Rules rules,
        List<CompiledFilter> filters,
        Score score,
        Logging logging,
        History history,
        State state,
        Mutes mutes
) {
    public CompiledAutoModConfig {
        filters = List.copyOf(filters);
    }

    public record Permissions(int fallbackOperatorLevel) {}
    public record StaffAlerts(boolean showOriginal, Duration muteButtonDuration) {}

    public record Normalization(
            boolean unicodeNfkc,
            boolean removeZeroWidthCharacters,
            boolean normalizeLinkObfuscation,
            Map<Integer, String> lookalikeSubstitutions,
            Map<Integer, String> leetSubstitutions
    ) {
        public Normalization {
            lookalikeSubstitutions = Map.copyOf(lookalikeSubstitutions);
            leetSubstitutions = Map.copyOf(leetSubstitutions);
        }
    }

    public record RuleSettings(boolean enabled, int points, RuleEffect effect) {}

    public record RapidSpam(RuleSettings common, Duration minimumInterval, Duration window, int maximumMessages) {}
    public record DuplicateSpam(RuleSettings common, int historySize, Duration window, int minimumLength) {}
    public record SimilaritySpam(RuleSettings common, int historySize, Duration window, int minimumLength,
                                 int maximumProcessedLength, double threshold) {}
    public record Caps(RuleSettings common, int minimumLetters, double maximumUppercaseRatio) {}
    public record RepeatedCharacters(RuleSettings common, int maximumLetters, int maximumSymbols) {}
    public record MessageLength(RuleSettings common, int maximumLength) {}
    public record Advertising(RuleSettings common, Set<String> allowedDomains) {
        public Advertising { allowedDomains = Set.copyOf(allowedDomains); }
    }
    public record Rules(RapidSpam rapidSpam, DuplicateSpam duplicateSpam, SimilaritySpam similaritySpam,
                        Caps caps, RepeatedCharacters repeatedCharacters, MessageLength messageLength,
                        Advertising advertising) {}

    public record CompiledFilter(String id, FilterMatchMode mode, List<String> terms, List<String> exceptions,
                                 RuleSettings common) {
        public CompiledFilter {
            terms = List.copyOf(terms);
            exceptions = List.copyOf(exceptions);
        }
    }

    public record Threshold(int points, List<ConfiguredAction> actions) {
        public Threshold { actions = List.copyOf(actions); }
    }

    public record Score(Duration pointDecay, int maximumPointsPerMessage, List<Threshold> thresholds) {
        public Score { thresholds = List.copyOf(thresholds); }
    }
    public record Logging(boolean enabled, boolean storeOriginalMessages, int retentionDays) {}
    public record History(int maximumEntriesPerPlayer, int retentionDays) {}
    public record State(Duration inactivePlayerTime, int maximumTrackedPlayers, int maximumScoreEntriesPerPlayer) {}
    public record Mutes(Duration notificationCooldown, Duration maximumDuration) {}
}
