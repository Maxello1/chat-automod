package com.maxello1.chatautomod.core.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ActiveConfig {
    private final ConfigLoader loader;
    private final ConfigurationBundleLoader bundleLoader;
    private final AtomicReference<CompiledAutoModConfig> current;

    public ActiveConfig() {
        this(new ConfigLoader());
    }

    public ActiveConfig(ConfigLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.bundleLoader = new ConfigurationBundleLoader(loader);
        ConfigLoadResult defaults = loader.load(loader.defaultJson());
        if (!defaults.valid()) {
            throw new IllegalStateException("default configuration is invalid: " + defaults.problems());
        }
        this.current = new AtomicReference<>(defaults.config().orElseThrow());
    }

    public ActiveConfig(CompiledAutoModConfig initial, ConfigLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.bundleLoader = new ConfigurationBundleLoader(loader);
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public CompiledAutoModConfig current() {
        return current.get();
    }

    public ReloadResult reload(String json) {
        return reload(json, 0, 0);
    }

    public ReloadResult reload(String json, int minimumTrackedPlayers,
            int minimumScoreEntriesPerPlayer) {
        return activate(loader.load(json), minimumTrackedPlayers,
                minimumScoreEntriesPerPlayer);
    }

    public ReloadResult reloadBundle(String mainJson, Map<String, String> packFiles,
            String exceptionsJson, int minimumTrackedPlayers,
            int minimumScoreEntriesPerPlayer) {
        return activate(bundleLoader.load(mainJson, packFiles, exceptionsJson),
                minimumTrackedPlayers, minimumScoreEntriesPerPlayer);
    }

    private ReloadResult activate(ConfigLoadResult result, int minimumTrackedPlayers,
            int minimumScoreEntriesPerPlayer) {
        if (minimumTrackedPlayers < 0 || minimumScoreEntriesPerPlayer < 0) {
            throw new IllegalArgumentException("reload minimums must not be negative");
        }
        if (!result.valid()) {
            return new ReloadResult(false, result.problems());
        }
        CompiledAutoModConfig candidate = result.config().orElseThrow();
        if (candidate.state().maximumTrackedPlayers() < minimumTrackedPlayers) {
            return new ReloadResult(false, List.of(new ConfigProblem(
                    "$.state.maximum_tracked_players",
                    "must be at least " + minimumTrackedPlayers
                            + " for current player state")));
        }
        if (candidate.state().maximumScoreEntriesPerPlayer()
                < minimumScoreEntriesPerPlayer) {
            return new ReloadResult(false, List.of(new ConfigProblem(
                    "$.state.maximum_score_entries_per_player",
                    "must be at least " + minimumScoreEntriesPerPlayer
                            + " for current active score state")));
        }
        current.set(candidate);
        return new ReloadResult(true, List.of());
    }

    public String defaultJson() {
        return loader.defaultJson();
    }

    public Map<String, String> defaultFilterPacks() {
        return DefaultFilterPacks.files();
    }

    public String defaultExceptionsJson() {
        return DefaultFilterPacks.exceptions();
    }
}
