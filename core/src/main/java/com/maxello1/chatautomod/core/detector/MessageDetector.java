package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.List;

public interface MessageDetector {
    RuleCategory category();
    List<RuleMatch> detect(MessageContext context, NormalizedMessage message,
                           PlayerModerationState state, CompiledAutoModConfig config);
}
