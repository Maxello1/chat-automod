package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.List;

public final class CapsDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.FLOODING; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().caps();
        if (!rule.common().enabled()) return List.of();
        int letters = 0;
        int uppercase = 0;
        for (int cp : context.rawMessage().codePoints().toArray()) {
            if (!Character.isLetter(cp)) continue;
            letters++;
            if (Character.isUpperCase(cp)) uppercase++;
        }
        if (letters < rule.minimumLetters()) return List.of();
        double ratio = (double) uppercase / letters;
        if (ratio <= rule.maximumUppercaseRatio()) return List.of();
        return List.of(new RuleMatch("flooding.caps", category(), rule.common().points(),
                "uppercase ratio " + ratio, uppercase + "/" + letters, rule.common().effect()));
    }
}
