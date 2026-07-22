package com.maxello1.chatautomod.core.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpacedLabelCollapser {
    private SpacedLabelCollapser() {}

    static String collapse(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group().replace(" ", "")));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
