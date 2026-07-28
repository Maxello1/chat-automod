package com.maxello1.chatautomod.fabric1211;

import java.util.Objects;

public record ExternalModerator(
        String source,
        String id,
        String displayName
) {
    public ExternalModerator {
        source = Objects.requireNonNullElse(source, "external");
        id = Objects.requireNonNullElse(id, "unknown");
        displayName = Objects.requireNonNullElse(displayName, "Unknown moderator");
    }
}
