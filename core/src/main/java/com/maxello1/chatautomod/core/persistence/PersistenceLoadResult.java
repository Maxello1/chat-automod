package com.maxello1.chatautomod.core.persistence;

import com.maxello1.chatautomod.core.config.ConfigProblem;
import com.maxello1.chatautomod.core.state.PlayerModerationState;

import java.util.List;

public record PersistenceLoadResult(List<PlayerModerationState> states, List<ConfigProblem> problems) {
    public PersistenceLoadResult {
        states = List.copyOf(states);
        problems = List.copyOf(problems);
    }

    public boolean valid() { return problems.isEmpty(); }
}
