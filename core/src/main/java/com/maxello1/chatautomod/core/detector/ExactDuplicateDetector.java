package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RecentMessage;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.time.Instant;
import java.util.List;

public final class ExactDuplicateDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.DUPLICATE; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().duplicateSpam();
        if (!rule.common().enabled() || message.canonical().codePointCount(0, message.canonical().length()) < rule.minimumLength()) {
            return List.of();
        }
        boolean duplicate = false;
        for (int i = state.recentMessages().size() - 1; i >= 0 && !duplicate; i--) {
            duplicate = claims(context, message, state, config, state.recentMessages().get(i), i);
        }
        if (!duplicate) return List.of();
        return List.of(new RuleMatch("spam.duplicate", category(), rule.common().points(),
                "exact canonical duplicate", message.canonical(), rule.common().effect()));
    }

    static boolean claims(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config, RecentMessage previous, int index) {
        var rule = config.rules().duplicateSpam();
        if (!rule.common().enabled()
                || message.canonical().codePointCount(0, message.canonical().length()) < rule.minimumLength()
                || index < Math.max(0, state.recentMessages().size() - rule.historySize())) {
            return false;
        }
        Instant cutoff = context.timestamp().minus(rule.window());
        return !previous.timestamp().isBefore(cutoff) && previous.canonical().equals(message.canonical());
    }
}
