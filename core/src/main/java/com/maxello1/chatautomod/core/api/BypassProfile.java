package com.maxello1.chatautomod.core.api;

import com.maxello1.chatautomod.core.model.RuleCategory;

import java.util.EnumSet;
import java.util.Set;

public record BypassProfile(boolean all, Set<RuleCategory> categories) {
    public static final BypassProfile NONE = new BypassProfile(false, Set.of());
    public static final BypassProfile ALL = new BypassProfile(true, Set.of());

    public BypassProfile {
        categories = categories == null || categories.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(categories));
    }

    public boolean bypasses(RuleCategory category) {
        return all || categories.contains(category);
    }
}
