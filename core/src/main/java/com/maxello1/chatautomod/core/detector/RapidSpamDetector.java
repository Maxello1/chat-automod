package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class RapidSpamDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.SPAM; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().rapidSpam();
        if (!rule.common().enabled()) return List.of();
        Instant now = context.timestamp();
        String evidence = null;
        if (!state.recentMessageTimes().isEmpty()) {
            Instant last = state.recentMessageTimes().get(state.recentMessageTimes().size() - 1);
            Duration elapsed = Duration.between(last, now);
            if (!elapsed.isNegative() && elapsed.compareTo(rule.minimumInterval()) < 0) evidence = "minimum interval";
        }
        Instant cutoff = now.minus(rule.window());
        long count = state.recentMessageTimes().stream().filter(time -> !time.isBefore(cutoff)).count();
        if (count >= rule.maximumMessages()) evidence = evidence == null ? "sliding window" : evidence + " and sliding window";
        if (evidence == null) return List.of();
        return List.of(new RuleMatch("spam.rapid", category(), rule.common().points(), evidence,
                Long.toString(count + 1), rule.common().effect()));
    }
}
