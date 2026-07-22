package com.maxello1.chatautomod.core.action;

import java.util.List;

public record RuleEffect(boolean blockMessage, List<ConfiguredAction> directActions) {
    public RuleEffect {
        directActions = directActions == null ? List.of() : List.copyOf(directActions);
    }
}
