package com.maxello1.chatautomod.fabric1211.discord;

import java.util.Objects;

public record DiscordConfigProblem(String path, String message) {
    public DiscordConfigProblem {
        path = Objects.requireNonNull(path, "path");
        message = Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
