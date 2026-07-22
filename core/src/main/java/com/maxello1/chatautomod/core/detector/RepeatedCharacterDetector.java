package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.config.CompiledAutoModConfig;
import com.maxello1.chatautomod.core.model.NormalizedMessage;
import com.maxello1.chatautomod.core.model.RuleCategory;
import com.maxello1.chatautomod.core.model.RuleMatch;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.List;

public final class RepeatedCharacterDetector implements MessageDetector {
    @Override public RuleCategory category() { return RuleCategory.FLOODING; }

    @Override
    public List<RuleMatch> detect(MessageContext context, NormalizedMessage message, PlayerModerationState state,
            CompiledAutoModConfig config) {
        var rule = config.rules().repeatedCharacters();
        if (!rule.common().enabled()) return List.of();
        int previous = -1;
        int run = 0;
        for (int cp : context.rawMessage().codePoints().toArray()) {
            if (cp == previous) run++;
            else { previous = cp; run = 1; }
            int maximum = Character.isLetter(cp) ? rule.maximumLetters() : rule.maximumSymbols();
            if (!Character.isWhitespace(cp) && run > maximum) {
                return List.of(new RuleMatch("flooding.repeated_characters", category(), rule.common().points(),
                        "repeated code point run of " + run, new String(Character.toChars(cp)), rule.common().effect()));
            }
        }
        return List.of();
    }
}
