package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.List;

public final class MessageLengthDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.FLOODING; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().messageLength();
        if (!rule.common().enabled()) return List.of();
        int length = context.rawMessage().codePointCount(0, context.rawMessage().length());
        if (length <= rule.maximumLength()) return List.of();
        return List.of(new RuleMatch("flooding.message_length", category(), rule.common().points(),
                "message has " + length + " code points", Integer.toString(length), rule.common().effect()));
    }
}
