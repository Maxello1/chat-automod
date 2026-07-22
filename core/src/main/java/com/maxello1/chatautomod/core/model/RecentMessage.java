package com.maxello1.chatautomod.core.model;

import java.time.Instant;
import java.util.Objects;

public record RecentMessage(String canonical, String deobfuscated, Instant timestamp) {
    public RecentMessage {
        canonical = Objects.requireNonNull(canonical, "canonical");
        deobfuscated = Objects.requireNonNull(deobfuscated, "deobfuscated");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
