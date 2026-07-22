package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.MessageDecision;

import java.util.List;

public record ActionPlan(MessageDecision decision, List<ModerationAction> actions) {
    public ActionPlan {
        actions = List.copyOf(actions);
    }
}
