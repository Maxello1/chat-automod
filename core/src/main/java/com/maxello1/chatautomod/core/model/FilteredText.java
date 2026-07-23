package com.maxello1.chatautomod.core.model;

import java.util.List;
import java.util.Objects;

public record FilteredText(String normalized, String compact, List<String> tokens) {
    public FilteredText {
        normalized = Objects.requireNonNull(normalized, "normalized");
        compact = Objects.requireNonNull(compact, "compact");
        tokens = List.copyOf(tokens);
    }
}
