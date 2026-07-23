package com.maxello1.chatautomod.core.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record DefaultFilterPackMigrationResult(
        boolean successful,
        Map<String, String> packFiles,
        Set<String> changedPacks,
        List<ConfigProblem> problems
) {
    public DefaultFilterPackMigrationResult {
        packFiles = Map.copyOf(packFiles);
        changedPacks = Set.copyOf(changedPacks);
        problems = List.copyOf(problems);
    }
}
