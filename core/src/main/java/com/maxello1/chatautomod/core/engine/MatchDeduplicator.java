package com.maxello1.chatautomod.core.engine;

import com.maxello1.chatautomod.core.model.RuleMatch;

import java.util.LinkedHashMap;
import java.util.List;

public final class MatchDeduplicator {
    public List<RuleMatch> deduplicate(List<RuleMatch> matches) {
        LinkedHashMap<String, RuleMatch> result = new LinkedHashMap<>();
        for (RuleMatch match : matches) result.putIfAbsent(match.ruleId(), match);
        return List.copyOf(result.values());
    }
}
