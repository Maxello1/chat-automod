package com.maxello1.chatautomod.fabric1211;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.maxello1.chatautomod.core.action.ActionType;
import com.maxello1.chatautomod.core.action.ModerationAction;
import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.MessageContext;
import com.maxello1.chatautomod.core.api.MessageDecision;
import com.maxello1.chatautomod.core.api.MessageSource;
import com.maxello1.chatautomod.core.config.ActiveConfig;
import com.maxello1.chatautomod.core.config.ConfigProblem;
import com.maxello1.chatautomod.core.config.DurationParser;
import com.maxello1.chatautomod.core.config.ReloadResult;
import com.maxello1.chatautomod.core.engine.LiveEvaluation;
import com.maxello1.chatautomod.core.engine.ModerationService;
import com.maxello1.chatautomod.core.engine.PreviewEvaluation;
import com.maxello1.chatautomod.core.history.HistoryService;
import com.maxello1.chatautomod.core.model.MuteKind;
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.persistence.PersistenceLoadResult;
import com.maxello1.chatautomod.core.persistence.PersistentSnapshot;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
import com.maxello1.chatautomod.core.state.StateClearScope;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

final class FabricRuntime {
    private static final int PAGE_SIZE = 6;
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);

    private final Logger logger;
    private final Clock clock;
    private final ActiveConfig configs;
    private final InMemoryPlayerStateStore states;
    private final ModerationService moderation;
    private final MuteService mutes;
    private final HistoryService history;
    private final PersistenceCodec persistenceCodec;
    private final FabricPermissionService permissions;
    private final FabricModerationPlatform platform;
    private final ActionPlanExecutor actionExecutor;
    private final Set<UUID> inspectors = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastMuteNotices = new ConcurrentHashMap<>();
    private final AtomicLong evaluations = new AtomicLong();
    private final Path configDirectory;
    private final Path configFile;
    private final Path filtersDirectory;

    private volatile MinecraftServer server;
    private volatile Path worldDataDirectory;
    private volatile FabricSnapshotStore snapshotStore;
    private volatile FabricAuditLogger auditLogger;
    private volatile boolean ready;

    FabricRuntime(Logger logger) {
        this(logger, Clock.systemUTC());
    }

    FabricRuntime(Logger logger, Clock clock) {
        this.logger = java.util.Objects.requireNonNull(logger, "logger");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.configs = new ActiveConfig();
        ReloadResult safeDefaults = configs.reloadBundle(
                configs.defaultJson(), configs.defaultFilterPacks(),
                configs.defaultExceptionsJson(), 0, 0);
        if (!safeDefaults.applied()) {
            throw new IllegalStateException("built-in filter configuration is invalid: "
                    + safeDefaults.problems());
        }
        this.states = new InMemoryPlayerStateStore();
        this.moderation = new ModerationService(configs, states);
        this.mutes = new MuteService(states, clock);
        this.history = new HistoryService(states, clock);
        this.persistenceCodec = new PersistenceCodec(2);
        this.permissions = new FabricPermissionService(logger, () -> server);
        this.platform = new FabricModerationPlatform(this);
        this.actionExecutor = new ActionPlanExecutor(this, platform);
        this.configDirectory = FabricLoader.getInstance().getConfigDir().resolve("chatautomod");
        this.configFile = configDirectory.resolve("automod.json");
        this.filtersDirectory = configDirectory.resolve("filters");
        loadInitialConfiguration();
    }

    private void loadInitialConfiguration() {
        try {
            createMissingConfigurationFiles();
            ReloadResult result = reloadBundle(0, 0);
            if (!result.applied()) {
                logger.error("Chat AutoMod configuration is invalid; safe defaults remain active");
                result.problems().forEach(problem -> logger.error("- {}", problem));
            }
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not initialize Chat AutoMod configuration; safe defaults remain active", exception);
        }
    }

    private void createMissingConfigurationFiles() throws IOException {
        Files.createDirectories(configDirectory);
        writeDefaultIfMissing(configFile, configs.defaultJson());
        String mainJson = Files.readString(configFile, StandardCharsets.UTF_8);
        Path activeFiltersDirectory = configuredFiltersDirectory(mainJson);
        Files.createDirectories(activeFiltersDirectory);
        for (Map.Entry<String, String> entry : configs.defaultFilterPacks().entrySet()) {
            String basename = safePackBasename(entry.getKey());
            writeDefaultIfMissing(activeFiltersDirectory.resolve(basename + ".json"), entry.getValue());
        }
        writeDefaultIfMissing(activeFiltersDirectory.resolve("exceptions.json"),
                configs.defaultExceptionsJson());
        new FabricFilterPackMigrator(configDirectory, logger).apply(activeFiltersDirectory);
    }

    private static void writeDefaultIfMissing(Path path, String content) throws IOException {
        if (Files.notExists(path)) {
            Files.writeString(
                    path,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        }
    }

    private ReloadResult reloadBundle(int minimumTrackedPlayers, int minimumScoreEntries) throws IOException {
        String mainJson = Files.readString(configFile, StandardCharsets.UTF_8);
        Path activeFiltersDirectory = configuredFiltersDirectory(mainJson);
        Path activeExceptionsFile = activeFiltersDirectory.resolve("exceptions.json");
        String exceptionsJson = Files.readString(activeExceptionsFile, StandardCharsets.UTF_8);
        Map<String, String> packs = readFilterPacks(activeFiltersDirectory, activeExceptionsFile);
        return configs.reloadBundle(
                mainJson,
                packs,
                exceptionsJson,
                minimumTrackedPlayers,
                minimumScoreEntries);
    }

    private Map<String, String> readFilterPacks(Path activeFiltersDirectory,
            Path activeExceptionsFile) throws IOException {
        Map<String, String> packs = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(activeFiltersDirectory)) {
            List<Path> sorted = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().equals(activeExceptionsFile.getFileName()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : sorted) {
                String fileName = file.getFileName().toString();
                String basename = fileName.substring(0, fileName.length() - ".json".length());
                packs.put(safePackBasename(basename), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(packs);
    }

    private Path configuredFiltersDirectory(String mainJson) {
        try {
            JsonElement parsed = JsonParser.parseString(mainJson);
            if (!parsed.isJsonObject()) {
                return filtersDirectory;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement settingsValue = root.get("filter_packs");
            if (settingsValue == null || !settingsValue.isJsonObject()) {
                return filtersDirectory;
            }
            JsonElement directoryValue = settingsValue.getAsJsonObject().get("directory");
            if (directoryValue == null || !directoryValue.isJsonPrimitive()
                    || !directoryValue.getAsJsonPrimitive().isString()) {
                return filtersDirectory;
            }
            String directory = directoryValue.getAsString();
            if (directory.length() > 256
                    || !directory.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}(?:/[a-zA-Z0-9][a-zA-Z0-9._-]{0,63})*")) {
                return filtersDirectory;
            }
            Path resolved = configDirectory;
            for (String segment : directory.split("/")) {
                resolved = resolved.resolve(segment);
            }
            resolved = resolved.normalize();
            return resolved.startsWith(configDirectory.normalize())
                    ? resolved : filtersDirectory;
        } catch (RuntimeException exception) {
            return filtersDirectory;
        }
    }

    private static String safePackBasename(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid filter-pack filename: " + value);
        }
        return value;
    }

    void startServer(MinecraftServer startedServer) {
        ready = false;
        server = startedServer;
        Path dataDirectory = startedServer.getWorldPath(LevelResource.ROOT).resolve("chatautomod");
        try {
            Files.createDirectories(dataDirectory);
            FabricSnapshotStore store = new FabricSnapshotStore(dataDirectory, logger, persistenceCodec);
            FabricAuditLogger logs = new FabricAuditLogger(
                    dataDirectory,
                    logger,
                    configs.current().logging().retentionDays());
            worldDataDirectory = dataDirectory;
            snapshotStore = store;
            auditLogger = logs;
            restoreState(store);
            Instant now = clock.instant();
            var config = configs.current();
            states.pruneInactive(
                    now,
                    config.state().inactivePlayerTime(),
                    Duration.ofDays(config.history().retentionDays()),
                    config.state().maximumTrackedPlayers());
            ready = true;
            scheduleSnapshot();
            logger.info("Chat AutoMod started with {} restored player records", states.snapshots().size());
        } catch (IOException | RuntimeException exception) {
            ready = false;
            logger.error("Chat AutoMod could not initialize world persistence; moderation will remain inactive", exception);
            closeRuntimeWriters();
            worldDataDirectory = null;
            server = null;
        }
    }

    private void restoreState(FabricSnapshotStore store) {
        Instant now = clock.instant();
        for (FabricSnapshotStore.StoredSnapshot candidate : store.loadCandidates()) {
            PersistenceLoadResult decoded = persistenceCodec.decode(candidate.json(), now, configs.current());
            if (decoded.valid()) {
                states.restore(decoded.states());
                if ("primary".equals(candidate.description())) {
                    store.markPrimaryValid();
                } else {
                    store.markPrimaryInvalid();
                    logger.warn("Recovered Chat AutoMod state from the backup file");
                }
                return;
            }
            if ("primary".equals(candidate.description())) {
                store.markPrimaryInvalid();
            }
            logger.error("Ignored invalid Chat AutoMod {} state", candidate.description());
            decoded.problems().forEach(problem -> logger.error("- {}", problem));
        }
    }

    void stopServer(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        ready = false;
        FabricSnapshotStore store = snapshotStore;
        FabricAuditLogger logs = auditLogger;
        if (logs != null) {
            logs.stopAccepting();
        }

        PersistentSnapshot finalSnapshot = null;
        try {
            finalSnapshot = createSnapshot();
        } catch (RuntimeException exception) {
            logger.error("Could not encode the final Chat AutoMod state snapshot ({})",
                    exception.getClass().getName());
        }

        try {
            if (store != null) {
                if (finalSnapshot != null) {
                    store.closeWithFinalSnapshot(finalSnapshot);
                } else {
                    store.close();
                }
            }
        } catch (RuntimeException exception) {
            logger.error("Could not stop the Chat AutoMod state writer ({})", exception.getClass().getName());
        }

        try {
            if (logs != null) {
                logs.close();
            }
        } catch (RuntimeException exception) {
            logger.error("Could not stop the Chat AutoMod log writer ({})", exception.getClass().getName());
        } finally {
            states.snapshots().forEach(state -> states.remove(state.playerId()));
            inspectors.clear();
            lastMuteNotices.clear();
            snapshotStore = null;
            auditLogger = null;
            worldDataDirectory = null;
            server = null;
        }
    }

    private void closeRuntimeWriters() {
        FabricAuditLogger logs = auditLogger;
        if (logs != null) {
            logs.stopAccepting();
            logs.close();
        }
        FabricSnapshotStore store = snapshotStore;
        if (store != null) {
            store.close();
        }
        snapshotStore = null;
        auditLogger = null;
    }

    boolean evaluatePublicChat(ServerPlayer player, String signedContent) {
        if (!ready || server == null) {
            return true;
        }

        Instant now = clock.instant();
        Optional<MuteState> activeMute = mutes.activeMute(
                player.getUUID(),
                player.getGameProfile().getName());
        if (activeMute.isPresent()) {
            notifyMutedPlayer(player, activeMute.orElseThrow(), now);
            return false;
        }
        lastMuteNotices.remove(player.getUUID());

        MessageContext context;
        LiveEvaluation result;
        try {
            context = new MessageContext(
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    signedContent,
                    MessageSource.PUBLIC_CHAT,
                    "",
                    now,
                    false);
            var permissionConfig = configs.current().permissions();
            BypassProfile bypass = permissions.bypassProfile(
                    player,
                    permissionConfig.operatorsBypassModeration(),
                    permissionConfig.bypassFallbackOperatorLevel());
            result = moderation.evaluateLive(context, bypass);
        } catch (RuntimeException exception) {
            logChatPathFailure("Chat AutoMod could not evaluate a message from " + player.getUUID(), exception);
            return true;
        }

        MessageDecision decision = result.actionPlan().decision();
        actionExecutor.execute(result.actionPlan());
        try {
            afterLiveEvaluation(context, result);
        } catch (RuntimeException exception) {
            logChatPathFailure(
                    "Chat AutoMod could not persist evaluation side effects for " + player.getUUID(),
                    exception);
        }

        if ((evaluations.incrementAndGet() & 255L) == 0L) {
            try {
                var config = configs.current();
                states.pruneInactive(
                        now,
                        config.state().inactivePlayerTime(),
                        Duration.ofDays(config.history().retentionDays()),
                        config.state().maximumTrackedPlayers());
                scheduleSnapshot();
            } catch (RuntimeException exception) {
                logChatPathFailure("Chat AutoMod could not prune inactive moderation state", exception);
            }
        }
        return decision == MessageDecision.ALLOW;
    }

    private void notifyMutedPlayer(ServerPlayer player, MuteState mute, Instant now) {
        Duration cooldown = configs.current().mutes().notificationCooldown();
        Instant previousNotice = lastMuteNotices.get(player.getUUID());
        if (previousNotice != null && previousNotice.plus(cooldown).isAfter(now)) {
            return;
        }
        lastMuteNotices.put(player.getUUID(), now);
        if (mute.kind() == MuteKind.PERMANENT) {
            player.sendSystemMessage(Component.literal("You are permanently muted. Reason: "
                    + FabricModerationPlatform.safeText(mute.reason())));
            return;
        }
        Duration remaining = Duration.between(now, mute.mutedUntil());
        player.sendSystemMessage(Component.literal("You are muted for another "
                + FabricModerationPlatform.compactDuration(remaining)
                + ". Reason: " + FabricModerationPlatform.safeText(mute.reason())));
    }

    void logActionFailure(ActionType actionType, RuntimeException exception) {
        logChatPathFailure("Chat AutoMod could not execute " + actionType + " action", exception);
    }

    private void logChatPathFailure(String summary, RuntimeException exception) {
        boolean includeDetails;
        try {
            includeDetails = configs.current().logging().storeOriginalMessages();
        } catch (RuntimeException ignored) {
            includeDetails = false;
        }
        if (includeDetails) {
            logger.error(summary, exception);
        } else {
            logger.error("{} ({})", summary, exception.getClass().getName());
        }
    }

    private void afterLiveEvaluation(MessageContext context, LiveEvaluation evaluation) {
        if (evaluation.report().matches().isEmpty()) {
            return;
        }
        PlayerModerationState state = states.snapshot(
                context.playerId(),
                context.playerName(),
                context.timestamp());
        if (!state.violations().isEmpty()) {
            ViolationRecord record = state.violations().getLast();
            var logging = configs.current().logging();
            FabricAuditLogger logs = auditLogger;
            if (logs != null && logging.enabled()) {
                logs.append(record, logging.storeOriginalMessages(), logging.retentionDays());
            }
        }
        scheduleSnapshot();
    }

    private PersistentSnapshot createSnapshot() {
        return persistenceCodec.snapshot(states.snapshots(), clock.instant(), configs.current());
    }

    private void scheduleSnapshot() {
        FabricSnapshotStore store = snapshotStore;
        if (store == null) {
            return;
        }
        try {
            store.scheduleSave(createSnapshot());
        } catch (RuntimeException exception) {
            logger.error("Could not prepare a Chat AutoMod state snapshot ({})", exception.getClass().getName());
        }
    }

    boolean mayUse(CommandSourceStack source, String permission) {
        int fallback = commandFallbackOperatorLevel();
        return permissions.hasPermission(source, permission, fallback)
                || (!FabricPermissionService.ADMIN.equals(permission)
                && permissions.hasPermission(source, FabricPermissionService.ADMIN, fallback));
    }

    private boolean requirePermission(CommandSourceStack source, String permission) {
        if (mayUse(source, permission)) {
            return true;
        }
        source.sendFailure(Component.literal("You do not have permission to use this command."));
        return false;
    }

    int showCommandSummary(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Chat AutoMod " + version())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("Runtime ready: " + yesNo(ready)), false);
        source.sendSuccess(() -> Component.literal("Moderation enabled: " + yesNo(configs.current().enabled())), false);
        source.sendSuccess(() -> Component.literal("Active filter rules: " + configs.current().filters().size()), false);
        source.sendSuccess(() -> Component.literal("Tracked players: " + states.snapshots().size()), false);
        source.sendSuccess(() -> Component.literal(
                "Commands: reload, test, history, violations, clear, mute, unmute, inspect, permissions")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    int reloadConfiguration(CommandSourceStack source) {
        if (!requirePermission(source, FabricPermissionService.RELOAD)) {
            return 0;
        }
        try {
            createMissingConfigurationFiles();
            boolean storedOriginalMessages = configs.current().logging().storeOriginalMessages();
            Instant now = clock.instant();
            List<PlayerModerationState> currentStates = List.copyOf(states.snapshots());
            int minimumScoreEntries = currentStates.stream()
                    .mapToInt(state -> (int) state.scoreEntries().stream()
                            .filter(entry -> entry.expiresAt().isAfter(now))
                            .count())
                    .max()
                    .orElse(0);
            ReloadResult result = reloadBundle(currentStates.size(), minimumScoreEntries);
            if (result.applied()) {
                if (storedOriginalMessages
                        && !configs.current().logging().storeOriginalMessages()) {
                    FabricSnapshotStore store = snapshotStore;
                    if (store != null) {
                        store.purgeBackupOnNextWrite();
                    }
                }
                source.sendSuccess(() -> Component.literal("Chat AutoMod configuration reloaded.")
                        .withStyle(ChatFormatting.GREEN), true);
                scheduleSnapshot();
                return 1;
            }
            source.sendFailure(Component.literal("Chat AutoMod configuration was not changed."));
            for (ConfigProblem problem : result.problems()) {
                source.sendFailure(Component.literal("- " + problem));
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            source.sendFailure(Component.literal("Could not reload Chat AutoMod configuration: "
                    + FabricModerationPlatform.safeText(exception.getMessage())));
            return 0;
        }
    }

    int testMessage(CommandSourceStack source, String rawMessage) {
        if (!requirePermission(source, FabricPermissionService.TEST)) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        UUID playerId = player == null ? CONSOLE_UUID : player.getUUID();
        String playerName = player == null ? "Server" : player.getGameProfile().getName();
        MessageContext context = new MessageContext(
                playerId,
                playerName,
                rawMessage,
                MessageSource.TEST,
                "automod test",
                clock.instant(),
                true);
        PlayerModerationState detached = states.snapshot(playerId, playerName, context.timestamp());
        detached = new PlayerModerationState(
                detached.revision(), detached.playerId(), detached.lastKnownName(),
                detached.recentMessageTimes(), detached.recentMessages(),
                detached.scoreEntries(), detached.crossedThresholds(), Optional.empty(),
                detached.violations(), Optional.empty(), detached.lastActivityAt());
        PreviewEvaluation preview = moderation.preview(context, detached, BypassProfile.NONE);
        var report = preview.report();

        source.sendSuccess(() -> Component.literal("Chat AutoMod test result")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.literal("Original: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(boundedText(rawMessage, 512)).withStyle(ChatFormatting.WHITE)), false);
        report.normalizedMessage().ifPresent(normalized -> {
            source.sendSuccess(() -> Component.literal("Canonical: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(boundedText(normalized.canonical(), 512))
                            .withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Deobfuscated: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(boundedText(normalized.deobfuscated(), 512))
                            .withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Compact: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(boundedText(normalized.compact(), 512))
                            .withStyle(ChatFormatting.WHITE)), false);
        });

        if (report.matches().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Matched: none"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Matched:"), false);
            report.matches().stream().limit(20).forEach(match ->
                    source.sendSuccess(() -> Component.literal("- " + match.ruleId()
                            + " | " + match.category()
                            + " | " + match.severity()
                            + " | +" + match.points()), false));
        }
        if (!report.preventedMatches().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Ignored:"), false);
            report.preventedMatches().stream().limit(20).forEach(prevented ->
                    source.sendSuccess(() -> Component.literal("- " + prevented.ruleId()
                            + " | configured exception \"" + boundedText(prevented.exception(), 160) + "\""), false));
        }

        source.sendSuccess(() -> Component.literal("Decision: " + report.decision())
                .withStyle(report.decision() == MessageDecision.BLOCK
                        ? ChatFormatting.RED
                        : ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Points: +" + report.pointsAdded()
                + " (would become " + report.pointsAfter() + ")"), false);
        if (report.actionsThatWouldRun().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Predicted actions: none"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Predicted actions:"), false);
            report.actionsThatWouldRun().stream().limit(20).forEach(action ->
                    source.sendSuccess(() -> Component.literal("- "
                            + describePreviewAction(action, context.timestamp())), false));
        }

        BypassProfile liveBypass = player == null ? BypassProfile.NONE : liveBypass(player);
        source.sendSuccess(() -> Component.literal("Live-chat bypass: " + describeBypass(liveBypass))
                .withStyle(liveBypass.all() || !liveBypass.categories().isEmpty()
                        ? ChatFormatting.YELLOW
                        : ChatFormatting.GREEN), false);
        return 1;
    }

    int showPlayerRecords(CommandSourceStack source, String targetText, int page, boolean activeViolations) {
        if (!requirePermission(source, FabricPermissionService.HISTORY)) {
            return 0;
        }
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        Instant now = clock.instant();
        PlayerModerationState state = states.snapshot(player.playerId(), player.playerName(), now);
        return activeViolations
                ? showActiveState(source, player, state, page, now)
                : showHistory(source, player, state, page);
    }

    private int showHistory(
            CommandSourceStack source,
            TargetPlayer player,
            PlayerModerationState state,
            int requestedPage
    ) {
        List<ViolationRecord> records = new ArrayList<>(state.violations());
        records.sort(Comparator.comparing(ViolationRecord::timestamp).reversed());
        Page<ViolationRecord> result = page(records, requestedPage);
        source.sendSuccess(() -> Component.literal("AutoMod history for " + player.playerName()
                + " - page " + result.page() + "/" + result.totalPages())
                .withStyle(ChatFormatting.GOLD), false);
        if (result.values().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No recorded violations."), false);
        }
        boolean showOriginal = configs.current().logging().storeOriginalMessages();
        for (ViolationRecord record : result.values()) {
            String categories = record.categories().isEmpty()
                    ? "unknown category"
                    : String.join(", ", record.categories().stream().map(Enum::name).toList());
            String mute = record.muteKind().map(kind -> " | mute " + kind).orElse("");
            source.sendSuccess(() -> Component.literal(record.timestamp() + " | "
                    + String.join(", ", record.ruleIds()) + " | " + categories + " | " + record.severity()
                    + " | +" + record.pointsAdded() + " -> " + record.scoreAfter()
                    + " | " + record.decision() + " | actions "
                    + String.join(", ", record.actions().stream().map(Enum::name).toList()) + mute), false);
            if (showOriginal) {
                record.originalMessage().ifPresent(message ->
                        source.sendSuccess(() -> Component.literal("  Message: "
                                + boundedText(message, 512)).withStyle(ChatFormatting.DARK_GRAY), false));
            }
        }
        return 1;
    }

    private int showActiveState(
            CommandSourceStack source,
            TargetPlayer player,
            PlayerModerationState state,
            int requestedPage,
            Instant now
    ) {
        List<ScoreEntry> scores = state.scoreEntries().stream()
                .filter(entry -> entry.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(ScoreEntry::createdAt).reversed())
                .toList();
        Page<ScoreEntry> result = page(scores, requestedPage);
        long total = scores.stream().mapToLong(ScoreEntry::points).sum();
        source.sendSuccess(() -> Component.literal("Active violations for " + player.playerName()
                + " (score " + total + ") - page " + result.page() + "/" + result.totalPages())
                .withStyle(ChatFormatting.GOLD), false);
        if (result.values().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active violation points."), false);
        }
        for (ScoreEntry entry : result.values()) {
            source.sendSuccess(() -> Component.literal(entry.ruleId() + " | +" + entry.points()
                    + " | expires " + entry.expiresAt()), false);
        }
        String mute = state.mute().filter(value -> value.isActiveAt(now))
                .map(FabricRuntime::describeMute)
                .orElse("none");
        source.sendSuccess(() -> Component.literal("Mute: " + mute), false);
        String thresholds = state.crossedThresholds().isEmpty()
                ? "none"
                : state.crossedThresholds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Crossed thresholds: " + thresholds), false);
        source.sendSuccess(() -> Component.literal("Retained history entries: " + state.violations().size()), false);
        return 1;
    }

    int clearPlayer(CommandSourceStack source, String targetText, StateClearScope scope) {
        if (!requirePermission(source, FabricPermissionService.CLEAR)) {
            return 0;
        }
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        boolean changed = history.clear(player.playerId(), player.playerName(), scope);
        if (changed) {
            scheduleSnapshot();
        }
        source.sendSuccess(() -> Component.literal((changed ? "Cleared " : "No ")
                + scope.name().toLowerCase(Locale.ROOT) + " moderation data "
                + (changed ? "for " : "found for ") + player.playerName() + ".")
                .withStyle(changed ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return changed ? 1 : 0;
    }

    int mutePlayer(CommandSourceStack source, String targetText, String durationText, String reason) {
        if (!requirePermission(source, FabricPermissionService.MUTE)) {
            return 0;
        }
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        UUID moderatorId = Optional.ofNullable(source.getPlayer()).map(ServerPlayer::getUUID).orElse(null);
        String safeReason = safeReason(reason);
        try {
            var config = configs.current();
            MuteState mute;
            if ("permanent".equalsIgnoreCase(durationText)) {
                mute = mutes.mutePermanent(
                        player.playerId(),
                        player.playerName(),
                        safeReason,
                        "manual",
                        "manual",
                        moderatorId,
                        config.state().maximumTrackedPlayers());
            } else {
                Duration duration = DurationParser.parse(durationText, config.mutes().maximumDuration());
                mute = mutes.muteTemporary(
                        player.playerId(),
                        player.playerName(),
                        duration,
                        config.mutes().maximumDuration(),
                        safeReason,
                        "manual",
                        "manual",
                        moderatorId,
                        config.state().maximumTrackedPlayers());
            }
            scheduleSnapshot();
            if (mute.kind() == MuteKind.PERMANENT) {
                platform.notifyPlayer(player.playerId(), "You have been permanently muted. Reason: " + mute.reason());
                source.sendSuccess(() -> Component.literal("Permanently muted " + player.playerName() + ".")
                        .withStyle(ChatFormatting.GREEN), true);
            } else {
                platform.notifyPlayer(player.playerId(), "You have been muted until " + mute.mutedUntil()
                        + ". Reason: " + mute.reason());
                source.sendSuccess(() -> Component.literal("Muted " + player.playerName() + " until "
                        + mute.mutedUntil() + ".").withStyle(ChatFormatting.GREEN), true);
            }
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(FabricModerationPlatform.safeText(exception.getMessage())));
            return 0;
        }
    }

    int unmutePlayer(CommandSourceStack source, String targetText) {
        if (!requirePermission(source, FabricPermissionService.MUTE)) {
            return 0;
        }
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        Optional<MuteState> previousMute = states.snapshot(
                        player.playerId(),
                        player.playerName(),
                        clock.instant())
                .mute()
                .filter(mute -> mute.isActiveAt(clock.instant()));
        boolean existed = previousMute.isPresent()
                && mutes.unmute(player.playerId(), player.playerName());
        if (existed) {
            UUID moderatorId = Optional.ofNullable(source.getPlayer())
                    .map(ServerPlayer::getUUID)
                    .orElse(null);
            ViolationRecord record = history.recordManualUnmute(
                    player.playerId(),
                    player.playerName(),
                    moderatorId,
                    previousMute.orElseThrow().kind(),
                    configs.current().history().maximumEntriesPerPlayer());
            var logging = configs.current().logging();
            FabricAuditLogger logs = auditLogger;
            if (logs != null && logging.enabled()) {
                logs.append(record, logging.storeOriginalMessages(), logging.retentionDays());
            }
            scheduleSnapshot();
            platform.notifyPlayer(player.playerId(), "Your Chat AutoMod mute has been removed.");
        }
        source.sendSuccess(() -> Component.literal((existed ? "Unmuted " : "No active mute for ")
                + player.playerName() + ".")
                .withStyle(existed ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return existed ? 1 : 0;
    }

    int setInspect(CommandSourceStack source, Boolean requested) {
        if (!requirePermission(source, FabricPermissionService.INSPECT)) {
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Inspect mode can only be used by a player."));
            return 0;
        }
        boolean enabled = requested == null ? !inspectors.contains(player.getUUID()) : requested;
        if (enabled) {
            inspectors.add(player.getUUID());
        } else {
            inspectors.remove(player.getUUID());
        }
        source.sendSuccess(() -> Component.literal("Detailed AutoMod alerts "
                + (enabled ? "enabled." : "disabled."))
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    int showPermissions(CommandSourceStack source, String targetText) {
        if (!requirePermission(source, FabricPermissionService.PERMISSIONS)) {
            return 0;
        }
        Optional<ServerPlayer> target = resolveOnlinePlayer(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("The player must be online to inspect live permissions: "
                    + targetText));
            return 0;
        }
        ServerPlayer player = target.orElseThrow();
        var config = configs.current().permissions();
        var admin = permissions.check(
                player,
                FabricPermissionService.ADMIN,
                true,
                config.commandFallbackOperatorLevel());
        var alerts = permissions.check(
                player,
                FabricPermissionService.ALERTS,
                true,
                config.staffFallbackOperatorLevel());
        boolean operatorBypass = config.operatorsBypassModeration();
        int bypassFallback = config.bypassFallbackOperatorLevel();

        source.sendSuccess(() -> Component.literal("Chat AutoMod permissions for "
                + player.getGameProfile().getName()).withStyle(ChatFormatting.GOLD), false);
        sendPermissionLine(source, "Admin", admin);
        sendPermissionLine(source, "Staff alerts", alerts);
        sendPermissionLine(source, "Full bypass", permissions.checkBypass(
                player, FabricPermissionService.BYPASS, operatorBypass, bypassFallback));
        sendPermissionLine(source, "Spam bypass", permissions.checkBypass(
                player, FabricPermissionService.BYPASS_SPAM, operatorBypass, bypassFallback));
        sendPermissionLine(source, "Filter bypass", permissions.checkBypass(
                player, FabricPermissionService.BYPASS_FILTER, operatorBypass, bypassFallback));
        sendPermissionLine(source, "Advertising bypass", permissions.checkBypass(
                player, FabricPermissionService.BYPASS_ADVERTISING, operatorBypass, bypassFallback));
        sendPermissionLine(source, "Security bypass", permissions.checkBypass(
                player, FabricPermissionService.BYPASS_SECURITY, operatorBypass, bypassFallback));

        BypassProfile bypass = permissions.bypassProfile(player, operatorBypass, bypassFallback);
        source.sendSuccess(() -> Component.literal(bypass.all() || !bypass.categories().isEmpty()
                ? "Live chat bypass: " + describeBypass(bypass)
                : "Live chat will be moderated.")
                .withStyle(bypass.all() || !bypass.categories().isEmpty()
                        ? ChatFormatting.YELLOW
                        : ChatFormatting.GREEN), false);
        if (!permissions.providerInstalled() && !operatorBypass) {
            source.sendSuccess(() -> Component.literal(
                    "No permission provider is active; moderation bypass defaults to false.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static void sendPermissionLine(
            CommandSourceStack source,
            String label,
            FabricPermissionService.PermissionCheck check
    ) {
        source.sendSuccess(() -> Component.literal(label + ": " + yesNo(check.allowed())
                + " - " + check.description()), false);
    }

    CompletableFuture<Suggestions> suggestPlayers(SuggestionsBuilder builder) {
        Set<String> names = new HashSet<>();
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.getPlayerList().getPlayers()
                    .forEach(player -> names.add(player.getGameProfile().getName()));
        }
        states.snapshots().forEach(state -> names.add(state.lastKnownName()));
        names.stream()
                .filter(name -> !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private Optional<TargetPlayer> resolveTarget(String value) {
        Optional<ServerPlayer> online = resolveOnlinePlayer(value);
        if (online.isPresent()) {
            ServerPlayer player = online.orElseThrow();
            return Optional.of(new TargetPlayer(player.getUUID(), player.getGameProfile().getName()));
        }
        try {
            UUID uuid = UUID.fromString(value);
            Optional<PlayerModerationState> stored = states.snapshots().stream()
                    .filter(state -> state.playerId().equals(uuid))
                    .findFirst();
            String name = stored.map(PlayerModerationState::lastKnownName)
                    .filter(candidate -> !candidate.isBlank())
                    .orElse(value);
            return Optional.of(new TargetPlayer(uuid, name));
        } catch (IllegalArgumentException ignored) {
            return states.snapshots().stream()
                    .filter(state -> state.lastKnownName().equalsIgnoreCase(value))
                    .findFirst()
                    .map(state -> new TargetPlayer(state.playerId(), state.lastKnownName()));
        }
    }

    private Optional<ServerPlayer> resolveOnlinePlayer(String value) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return Optional.empty();
        }
        ServerPlayer byName = currentServer.getPlayerList().getPlayerByName(value);
        if (byName != null) {
            return Optional.of(byName);
        }
        try {
            return Optional.ofNullable(currentServer.getPlayerList().getPlayer(UUID.fromString(value)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    Optional<MuteState> ensureAutomaticMute(ModerationAction.Mute mute) {
        if (mute.kind() == MuteKind.PERMANENT) {
            return ensureAutomaticPermanentMute(
                    mute.playerId(),
                    mute.reason(),
                    mute.source(),
                    mute.ruleId());
        }
        return ensureAutomaticTemporaryMute(
                mute.playerId(), mute.until(), mute.reason(),
                mute.source(), mute.ruleId());
    }

    Optional<MuteState> ensureAutomaticTemporaryMute(
            UUID playerId,
            Instant until,
            String reason,
            String source,
            String ruleId
    ) {
        Instant now = clock.instant();
        if (until == null || !until.isAfter(now)) {
            return Optional.empty();
        }
        String playerName = resolveTarget(playerId.toString())
                .map(TargetPlayer::playerName)
                .orElse(playerId.toString());
        Optional<MuteState> current = mutes.activeMute(playerId, playerName);
        if (current.filter(mute -> mute.kind() == MuteKind.PERMANENT
                || !mute.mutedUntil().isBefore(until)).isPresent()) {
            return current;
        }
        Duration requested = Duration.between(now, until);
        var config = configs.current();
        Duration maximum = config.mutes().maximumDuration();
        if (requested.compareTo(maximum) > 0) {
            requested = maximum;
        }
        MuteState applied = mutes.muteTemporary(
                playerId,
                playerName,
                requested,
                maximum,
                safeReason(reason),
                source,
                ruleId,
                null,
                config.state().maximumTrackedPlayers());
        scheduleSnapshot();
        return Optional.of(applied);
    }

    private Optional<MuteState> ensureAutomaticPermanentMute(
            UUID playerId, String reason, String source, String ruleId) {
        String playerName = resolveTarget(playerId.toString())
                .map(TargetPlayer::playerName)
                .orElse(playerId.toString());
        Optional<MuteState> current = mutes.activeMute(playerId, playerName);
        if (current.filter(mute -> mute.kind() == MuteKind.PERMANENT).isPresent()) {
            return current;
        }
        var config = configs.current();
        MuteState applied = mutes.mutePermanent(
                playerId,
                playerName,
                safeReason(reason),
                source,
                ruleId,
                null,
                config.state().maximumTrackedPlayers());
        scheduleSnapshot();
        return Optional.of(applied);
    }

    private BypassProfile liveBypass(ServerPlayer player) {
        var permissionConfig = configs.current().permissions();
        return permissions.bypassProfile(
                player,
                permissionConfig.operatorsBypassModeration(),
                permissionConfig.bypassFallbackOperatorLevel());
    }

    private static String describePreviewAction(ModerationAction action, Instant evaluatedAt) {
        return switch (action) {
            case ModerationAction.NotifyStaff alert -> "NOTIFY_STAFF: " + alert.decision()
                    + ", rules " + boundedText(String.join(", ", alert.ruleIds()), 160);
            case ModerationAction.Warn warning -> "WARN: " + boundedText(warning.message(), 160);
            case ModerationAction.Mute mute -> mute.kind() == MuteKind.PERMANENT
                    ? "PERMANENT MUTE: " + boundedText(mute.reason(), 160)
                    : "MUTE for " + FabricModerationPlatform.compactDuration(
                    Duration.between(evaluatedAt, mute.until()))
                    + ", reason: " + boundedText(mute.reason(), 160);
            case ModerationAction.Kick kick -> "KICK: " + boundedText(kick.reason(), 160);
            case ModerationAction.ExecuteCommand command -> "COMMAND: "
                    + boundedText(command.command(), 160);
        };
    }

    private static String describeMute(MuteState mute) {
        if (mute.kind() == MuteKind.PERMANENT) {
            return "permanent | reason: " + boundedText(mute.reason(), 160);
        }
        return "temporary until " + mute.mutedUntil() + " | reason: " + boundedText(mute.reason(), 160);
    }

    private static String describeBypass(BypassProfile bypass) {
        if (bypass.all()) {
            return "Yes (full bypass)";
        }
        if (bypass.categories().isEmpty()) {
            return "No";
        }
        return "Yes (" + bypass.categories().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(category -> category.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    private static String boundedText(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximumCodePoints));
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && (codePoint < 0xD800 || codePoint > 0xDFFF)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(maximumCodePoints)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String safeReason(String reason) {
        String sanitized = boundedText(reason, 256);
        return sanitized.isBlank() ? "Muted by a staff member" : sanitized;
    }

    private static <T> Page<T> page(List<T> values, int requestedPage) {
        int totalPages = Math.max(1, (values.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selectedPage = Math.max(1, Math.min(requestedPage, totalPages));
        int start = Math.min(values.size(), (selectedPage - 1) * PAGE_SIZE);
        int end = Math.min(values.size(), start + PAGE_SIZE);
        return new Page<>(selectedPage, totalPages, List.copyOf(values.subList(start, end)));
    }

    private String version() {
        return FabricLoader.getInstance().getModContainer(ChatAutoModFabric.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    MinecraftServer server() {
        return server;
    }

    boolean ready() {
        return ready;
    }

    Path configDirectory() {
        return configDirectory;
    }

    Path worldDataDirectory() {
        Path value = worldDataDirectory;
        if (value == null) {
            throw new IllegalStateException("No Minecraft server is running");
        }
        return value;
    }

    FabricPermissionService permissions() {
        return permissions;
    }

    int commandFallbackOperatorLevel() {
        return configs.current().permissions().commandFallbackOperatorLevel();
    }

    int staffFallbackOperatorLevel() {
        return configs.current().permissions().staffFallbackOperatorLevel();
    }

    boolean showOriginalAlerts() {
        return configs.current().staffAlerts().showOriginal();
    }

    boolean inspectEnabled(UUID playerId) {
        return inspectors.contains(playerId);
    }

    private record TargetPlayer(UUID playerId, String playerName) {}

    private record Page<T>(int page, int totalPages, List<T> values) {}
}
