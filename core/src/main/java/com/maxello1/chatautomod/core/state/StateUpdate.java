package com.maxello1.chatautomod.core.state;

import java.util.Objects;

public record StateUpdate<T>(PlayerModerationState state, T result) {
    public StateUpdate {
        Objects.requireNonNull(state, "state");
    }
}
