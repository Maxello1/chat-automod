package com.maxello1.chatautomod.core.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record NormalizedMessage(
        String original,
        String canonical,
        String deobfuscated,
        String linkNormalized,
        String compact,
        String repeatedCharactersCollapsed,
        Set<NormalizationFlag> flags
) {
    public NormalizedMessage {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(deobfuscated, "deobfuscated");
        Objects.requireNonNull(linkNormalized, "linkNormalized");
        Objects.requireNonNull(compact, "compact");
        Objects.requireNonNull(repeatedCharactersCollapsed, "repeatedCharactersCollapsed");
        flags = flags == null || flags.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(flags));
    }
}
