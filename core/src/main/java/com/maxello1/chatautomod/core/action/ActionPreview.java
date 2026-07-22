package com.maxello1.chatautomod.core.action;

import com.maxello1.chatautomod.core.api.MessageDecision;

import java.util.List;

public record ActionPreview(MessageDecision decision, List<ModerationAction> actions) {
    public ActionPreview {
        actions = List.copyOf(actions);
    }
}
