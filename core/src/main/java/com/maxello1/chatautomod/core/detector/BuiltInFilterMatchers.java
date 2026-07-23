package com.maxello1.chatautomod.core.detector;

import com.maxello1.chatautomod.core.model.FilteredText;

import java.util.Set;
import java.util.regex.Pattern;

public final class BuiltInFilterMatchers {
    public static final String NWORD_FAMILY = "builtin:nword-family";
    private static final Set<String> SUPPORTED = Set.of(NWORD_FAMILY);
    private static final Pattern NWORD_TOKEN = Pattern.compile(
            "(?:n+i+g{2}(?:a|ah|as|er|ers|e|uh)|n+i+b{2}(?:a|ah|er|ers))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private BuiltInFilterMatchers() {
    }

    public static boolean supported(String id) {
        return SUPPORTED.contains(id);
    }

    public static String find(String id, FilteredText text) {
        if (!NWORD_FAMILY.equals(id)) {
            throw new IllegalArgumentException("unsupported built-in pattern: " + id);
        }
        for (String token : text.tokens()) {
            if (NWORD_TOKEN.matcher(token).matches()) {
                return token;
            }
        }
        for (int start = 0; start < text.tokens().size(); start++) {
            StringBuilder joined = new StringBuilder();
            for (int end = start; end < text.tokens().size() && end < start + 10; end++) {
                String token = text.tokens().get(end);
                if (token.codePointCount(0, token.length()) > 16) {
                    break;
                }
                joined.append(token);
                if (NWORD_TOKEN.matcher(joined).matches()) {
                    return String.join(" ", text.tokens().subList(start, end + 1));
                }
                if (joined.length() > 16) {
                    break;
                }
            }
        }
        return null;
    }
}
