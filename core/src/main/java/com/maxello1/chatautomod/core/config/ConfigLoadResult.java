package com.maxello1.chatautomod.core.config;

import java.util.List;
import java.util.Optional;

public record ConfigLoadResult(Optional<CompiledAutoModConfig> config, List<ConfigProblem> problems) {
    public ConfigLoadResult {
        config = config == null ? Optional.empty() : config;
        problems = List.copyOf(problems);
    }

    public static ConfigLoadResult success(CompiledAutoModConfig config) {
        return new ConfigLoadResult(Optional.of(config), List.of());
    }

    public static ConfigLoadResult failure(List<ConfigProblem> problems) {
        return new ConfigLoadResult(Optional.empty(), problems);
    }

    public boolean valid() {
        return config.isPresent() && problems.isEmpty();
    }
}
