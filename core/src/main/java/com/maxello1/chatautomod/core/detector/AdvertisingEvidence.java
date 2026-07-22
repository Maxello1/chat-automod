package com.maxello1.chatautomod.core.detector;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

final class AdvertisingEvidence {
    private AdvertisingEvidence() {}
    private static final Set<String> COUNTRY_TLDS = java.util.Arrays.stream(Locale.getISOCountries())
            .map(code -> code.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());

    static boolean recognizedTld(String host, Set<String> commonTlds) {
        String tld = host.substring(host.lastIndexOf('.') + 1);
        return tld.startsWith("xn--") || commonTlds.contains(tld) || COUNTRY_TLDS.contains(tld);
    }

    static boolean strongUrlEvidence(String value, Matcher matcher, String host) {
        int prefixStart = Math.max(0, matcher.start() - 8);
        String prefix = value.substring(prefixStart, matcher.start()).toLowerCase(Locale.ROOT);
        if (prefix.endsWith("http://") || prefix.endsWith("https://") || host.startsWith("www.")) return true;
        if (matcher.end() >= value.length()) return false;
        char next = value.charAt(matcher.end());
        return next == '/' || next == ':';
    }
}
