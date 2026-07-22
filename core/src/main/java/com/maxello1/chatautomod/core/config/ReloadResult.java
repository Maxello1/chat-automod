package com.maxello1.chatautomod.core.config;

import java.util.List;

public record ReloadResult(boolean applied, List<ConfigProblem> problems) {
    public ReloadResult {
        problems = List.copyOf(problems);
    }
}
