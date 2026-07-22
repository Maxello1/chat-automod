package com.maxello1.chatautomod.core.normalize;

import java.util.Set;
import java.util.regex.Pattern;

final class LinkObfuscationNormalizer {
    private static final String LABEL = "([\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?)";
    private static final Pattern BRACKET_DOT = Pattern.compile("(?iu)" + LABEL
            + "\\s*(?:\\[\\s*\\.\\s*]|\\(\\s*\\.\\s*\\))\\s*" + LABEL);
    private static final Pattern SPACED_PERIOD = Pattern.compile("(?iu)" + LABEL + "\\s+\\.\\s+" + LABEL);
    private static final Set<String> COMMON_TLDS = Set.of("com", "net", "org", "gg", "io", "co", "uk", "us", "de",
            "dev", "app", "me", "tv", "info", "biz", "xyz", "online", "site", "store", "fr", "nl", "ru", "ca", "au",
            "lol", "top", "world", "fun", "icu", "link", "space", "digital", "agency", "studio", "solutions", "services", "social", "today", "news", "wiki");
    private static final Pattern DOT_WORD = Pattern.compile("(?iu)" + LABEL
            + "\\s*(?:\\[\\s*dot\\s*]|\\(\\s*dot\\s*\\)|\\s+dot\\s+)\\s*" + LABEL);
    private static final Pattern SPACED_LABEL = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:[\\p{L}\\p{N}]\\s+){2,}[\\p{L}\\p{N}](?![\\p{L}\\p{N}])");

    private LinkObfuscationNormalizer() {}

    static Result normalize(String input) {
        String value = SpacedLabelCollapser.collapse(input, SPACED_LABEL);
        value = value.replaceAll("\\s*/\\s*", "/");
        boolean changed = !value.equals(input);
        for (int i = 0; i < 4; i++) {
            String next = BRACKET_DOT.matcher(value).replaceAll("$1.$2");
            next = SPACED_PERIOD.matcher(next).replaceAll("$1.$2");
            var matcher = DOT_WORD.matcher(next);
            StringBuffer buffer = new StringBuffer();
            boolean wordChanged = false;
            while (matcher.find()) {
                String right = matcher.group(2);
                if (COMMON_TLDS.contains(right.toLowerCase())) {
                    matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + "." + right));
                    wordChanged = true;
                }
            }
            matcher.appendTail(buffer);
            next = buffer.toString();
            if (next.equals(value)) break;
            changed = true;
            value = next;
            if (wordChanged) changed = true;
        }
        return new Result(value, changed);
    }

    record Result(String value, boolean changed) {}
}
