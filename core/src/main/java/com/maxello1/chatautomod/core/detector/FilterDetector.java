package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.config.FilterMatchMode;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.ArrayList;
import java.util.List;

public final class FilterDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.FILTERED_CONTENT; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        List<RuleMatch> matches = new ArrayList<>();
        for (var filter : config.filters()) {
            if (!filter.common().enabled()) continue;
            String haystack = filter.mode() == FilterMatchMode.COMPACT ? message.compact() : message.deobfuscated();
            if (filter.exceptions().stream().anyMatch(exception -> contains(haystack, exception, filter.mode()))) continue;
            String term = filter.terms().stream().filter(candidate -> contains(haystack, candidate, filter.mode())).findFirst().orElse(null);
            if (term != null) matches.add(new RuleMatch(filter.id(), category(), filter.common().points(),
                    "filtered " + filter.mode().name().toLowerCase(), term, filter.common().effect()));
        }
        return matches;
    }

    private boolean contains(String haystack, String needle, FilterMatchMode mode) {
        int from = 0;
        while (true) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return false;
            if (mode == FilterMatchMode.SUBSTRING || mode == FilterMatchMode.COMPACT) return true;
            int end = index + needle.length();
            boolean left = index == 0 || !Character.isLetterOrDigit(haystack.codePointBefore(index));
            boolean right = end == haystack.length() || !Character.isLetterOrDigit(haystack.codePointAt(end));
            if (left && right) return true;
            from = index + 1;
        }
    }
}
