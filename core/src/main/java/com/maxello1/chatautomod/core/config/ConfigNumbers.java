package com.maxello1.chatautomod.core.config;

import java.util.List;

final class ConfigNumbers {
    private ConfigNumbers() {}

    static void validate(long value, long minimum, long maximum, String path, List<ConfigProblem> problems) {
        if (value < minimum || value > maximum) {
            problems.add(new ConfigProblem(path, "must be between " + minimum + " and " + maximum));
        }
    }

    static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
