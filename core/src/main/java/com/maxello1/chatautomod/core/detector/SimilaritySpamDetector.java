package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Instant;
import java.util.List;

public final class SimilaritySpamDetector implements MessageDetector {
    private final SimilarityCalculator calculator = new SimilarityCalculator();
    @Override public RuleCategory category() { return RuleCategory.SIMILARITY; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().similaritySpam();
        String current = truncate(message.deobfuscated(), rule.maximumProcessedLength());
        if (!rule.common().enabled() || current.codePointCount(0, current.length()) < rule.minimumLength()) return List.of();
        Instant cutoff = context.timestamp().minus(rule.window());
        int start = Math.max(0, state.recentMessages().size() - rule.historySize());
        for (int i = state.recentMessages().size() - 1; i >= start; i--) {
            var previous = state.recentMessages().get(i);
            if (previous.timestamp().isBefore(cutoff)) continue;
            String candidate = truncate(previous.deobfuscated(), rule.maximumProcessedLength());
            if (candidate.equals(current) && ExactDuplicateDetector.claims(context, message, state, config, previous, i)) continue;
            if (calculator.similar(current, candidate, rule.threshold())) {
                return List.of(new RuleMatch("spam.similarity", category(), rule.common().points(),
                        "similar to a recent message", candidate, rule.common().effect()));
            }
        }
        return List.of();
    }

    private static String truncate(String value, int maximumCodePoints) {
        if (value.codePointCount(0, value.length()) <= maximumCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }
}
