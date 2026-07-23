package com.maxello1.chatautomod.core.model;

import java.util.Objects;

public record PreventedMatch(String ruleId, String exception) {
    public PreventedMatch {
        ruleId = Objects.requireNonNull(ruleId, "ruleId");
        exception = Objects.requireNonNull(exception, "exception");
    }
}
