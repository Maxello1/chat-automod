package com.maxello1.chatautomod.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern FORMAT = Pattern.compile("([0-9]+)([smhdwSMHDW])");

    private DurationParser() {}

    public static Duration parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("duration is required");
        }
        Matcher matcher = FORMAT.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("duration must use s, m, h, d, or w");
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("duration is too large", exception);
        }
        if (amount == 0) {
            throw new IllegalArgumentException("duration must be positive");
        }
        try {
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
                default -> throw new IllegalArgumentException("unsupported duration unit");
            };
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("duration is too large", exception);
        }
    }

    public static Duration parse(String value, Duration maximum) {
        Duration result = parse(value);
        if (result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("duration exceeds maximum of " + maximum);
        }
        return result;
    }
}
