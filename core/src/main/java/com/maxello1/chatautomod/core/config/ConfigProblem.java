package com.maxello1.chatautomod.core.config;

public record ConfigProblem(String path, String message) {
    @Override
    public String toString() {
        return path + ": " + message;
    }
}
