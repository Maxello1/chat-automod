package com.maxello1.chatautomod.fabric262;

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
import com.maxello1.chatautomod.core.model.MuteState;
import com.maxello1.chatautomod.core.model.ScoreEntry;
import com.maxello1.chatautomod.core.model.ViolationRecord;
import com.maxello1.chatautomod.core.persistence.PersistenceCodec;
import com.maxello1.chatautomod.core.persistence.PersistenceLoadResult;
import com.maxello1.chatautomod.core.state.InMemoryPlayerStateStore;
import com.maxello1.chatautomod.core.state.MuteService;
import com.maxello1.chatautomod.core.state.PlayerModerationState;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class FabricRuntime {
    private static final int PAGE_SIZE = 6;

    private final Logger logger;
    private final Clock clock;
    private final ActiveConfig configs;
    private final InMemoryPlayerStateStore states;
    private final ModerationService moderation;
    private final MuteService mutes;
    private final PersistenceCodec persistenceCodec = new PersistenceCodec();
    private final FabricPermissionService permissions;
    private final FabricModerationPlatform platform;
    private final Set<UUID> inspectors = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong evaluations = new AtomicLong();
    private final Path configDirectory;
    private final Path configFile;

    private volatile MinecraftServer server;
    private volatile Path worldDataDirectory;
    private volatile FabricSnapshotStore snapshotStore;
    private volatile FabricAuditLogger auditLogger;

    FabricRuntime(Logger logger) {
        this(logger, Clock.systemUTC());
    }

    FabricRuntime(Logger logger, Clock clock) {
        this.logger = logger;
        this.clock = clock;
        this.configs = new ActiveConfig();
        this.states = new InMemoryPlayerStateStore();
        this.moderation = new ModerationService(configs, states);
        this.mutes = new MuteService(states, clock);
        this.permissions = new FabricPermissionService(() -> server);
        this.platform = new FabricModerationPlatform(this);
        this.configDirectory = FabricLoader.getInstance().getConfigDir().resolve("chatautomod");
        this.configFile = configDirectory.resolve("automod.json");
        loadInitialConfiguration();
    }

    private void loadInitialConfiguration() {
        try {
            Files.createDirectories(configDirectory);
            if (Files.notExists(configFile)) {
                Files.writeString(
                        configFile,
                        configs.defaultJson(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            }
            ReloadResult result = configs.reload(Files.readString(configFile, StandardCharsets.UTF_8));
            if (!result.applied()) {
                logger.error("Chat AutoMod configuration is invalid; safe defaults remain active: {}", result.problems());
            }
        } catch (IOException exception) {
            logger.error("Could not create or read {}; safe defaults remain active", configFile, exception);
        }
    }

    void startServer(MinecraftServer startedServer) {
        this.server = startedServer;
        this.worldDataDirectory = startedServer.getWorldPath(LevelResource.ROOT).resolve("chatautomod");
        FabricSnapshotStore store = new FabricSnapshotStore(worldDataDirectory, logger);
        this.snapshotStore = store;
        this.auditLogger = new FabricAuditLogger(
                worldDataDirectory,
                logger,
                configs.current().logging().retentionDays());
        restoreState(store);
        logger.info("Chat AutoMod started with {} restored player records", states.snapshots().size());
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
            logger.error("Ignored invalid Chat AutoMod {} state: {}", candidate.description(), decoded.problems());
        }
    }

    void stopServer(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        FabricSnapshotStore store = snapshotStore;
        FabricAuditLogger logs = auditLogger;
        try {
            if (store != null) {
                try {
                    store.closeWithFinalSnapshot(encodeSnapshot());
                } catch (RuntimeException exception) {
                    logger.error("Could not encode or write final Chat AutoMod state ({})",
                            exception.getClass().getName());
                } finally {
                    try {
                        store.close();
                    } catch (RuntimeException exception) {
                        logger.error("Could not stop the Chat AutoMod state writer ({})",
                                exception.getClass().getName());
                    }
                }
            }
        } finally {
            try {
                if (logs != null) {
                    try {
                        logs.close();
                    } catch (RuntimeException exception) {
                        logger.error("Could not stop the Chat AutoMod log writer ({})",
                                exception.getClass().getName());
                    }
                }
            } finally {
                try {
                    states.snapshots().forEach(state -> states.remove(state.playerId()));
                } catch (RuntimeException exception) {
                    logger.error("Could not clear Chat AutoMod in-memory state ({})",
                            exception.getClass().getName());
                } finally {
                    inspectors.clear();
                    snapshotStore = null;
                    auditLogger = null;
                    worldDataDirectory = null;
                    server = null;
                }
            }
        }
    }

    boolean evaluatePublicChat(ServerPlayer player, String signedContent) {
        if (server == null) {
            return true;
        }
        Instant now = clock.instant();
        MessageContext context;
        LiveEvaluation result;
        try {
            context = new MessageContext(
                    player.getUUID(),
                    player.getName().getString(),
                    signedContent,
                    MessageSource.PUBLIC_CHAT,
                    "",
                    now,
                    false);
            result = moderation.evaluateLive(
                    context,
                    permissions.bypassProfile(player, fallbackOperatorLevel()));
        } catch (RuntimeException exception) {
            logChatPathFailure("Chat AutoMod could not evaluate a message from " + player.getUUID(), exception);
            return true;
        }

        MessageDecision decision = result.actionPlan().decision();
        try {
            platform.execute(result.actionPlan());
        } catch (RuntimeException exception) {
            logChatPathFailure("Chat AutoMod could not finish the action plan for " + player.getUUID(), exception);
        }
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
                states.pruneInactive(now, config.state().inactivePlayerTime(),
                        Duration.ofDays(config.history().retentionDays()), config.state().maximumTrackedPlayers());
            } catch (RuntimeException exception) {
                logChatPathFailure("Chat AutoMod could not prune inactive moderation state", exception);
            }
        }
        return decision == MessageDecision.ALLOW;
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
        PlayerModerationState state = states.snapshot(context.playerId(), context.playerName(), context.timestamp());
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

    private String encodeSnapshot() {
        return persistenceCodec.encode(states.snapshots(), clock.instant(), configs.current());
    }

    private void scheduleSnapshot() {
        FabricSnapshotStore store = snapshotStore;
        if (store != null) {
            store.scheduleSave(() -> persistenceCodec.encode(states.snapshots(), clock.instant(), configs.current()));
        }
    }

    boolean mayUse(CommandSourceStack source, String permission) {
        int fallback = fallbackOperatorLevel();
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
        source.sendSuccess(() -> Component.literal(
                "Chat AutoMod commands: reload, test, history, violations, clear, mute, unmute, inspect")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    int reloadConfiguration(CommandSourceStack source) {
        if (!requirePermission(source, FabricPermissionService.RELOAD)) return 0;
        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            Instant now = clock.instant();
            List<PlayerModerationState> currentStates = List.copyOf(states.snapshots());
            int minimumScoreEntries = currentStates.stream()
                    .mapToInt(state -> (int) state.scoreEntries().stream()
                            .filter(entry -> entry.expiresAt().isAfter(now))
                            .count())
                    .max()
                    .orElse(0);
            ReloadResult result = configs.reload(json, currentStates.size(), minimumScoreEntries);
            if (result.applied()) {
                source.sendSuccess(() -> Component.literal("Chat AutoMod configuration reloaded.")
                        .withStyle(ChatFormatting.GREEN), true);
                scheduleSnapshot();
                return 1;
            }
            source.sendFailure(Component.literal("Configuration was not changed:"));
            result.problems().forEach(problem ->
                    source.sendFailure(Component.literal("- " + problem)));
            return 0;
        } catch (IOException exception) {
            source.sendFailure(Component.literal("Could not read " + configFile + ": " + exception.getMessage()));
            return 0;
        }
    }

    int testMessage(CommandSourceStack source, String rawMessage) {
        if (!requirePermission(source, FabricPermissionService.TEST)) return 0;
        ServerPlayer player = source.getPlayer();
        UUID playerId = player == null ? new UUID(0, 0) : player.getUUID();
        String playerName = player == null ? "Server" : player.getName().getString();
        MessageContext context = new MessageContext(
                playerId,
                playerName,
                rawMessage,
                MessageSource.TEST,
                "automod test",
                clock.instant(),
                true);
        PreviewEvaluation preview = moderation.preview(context, BypassProfile.NONE);
        var report = preview.report();
        source.sendSuccess(() -> Component.literal("Chat AutoMod test result").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Original: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(rawMessage).withStyle(ChatFormatting.WHITE)), false);
        report.normalizedMessage().ifPresent(normalized -> {
            source.sendSuccess(() -> Component.literal("Canonical: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(normalized.canonical()).withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Deobfuscated: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(normalized.deobfuscated()).withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Link form: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(normalized.linkNormalized()).withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Compact: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(normalized.compact()).withStyle(ChatFormatting.WHITE)), false);
        });
        String matches = report.matches().isEmpty()
                ? "none"
                : String.join(", ", report.matches().stream().map(match -> match.ruleId()).toList());
        source.sendSuccess(() -> Component.literal("Matched: " + matches), false);
        source.sendSuccess(() -> Component.literal("Decision: " + report.decision())
                .withStyle(report.decision() == MessageDecision.BLOCK ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.literal("Points: +" + report.pointsAdded()
                + " (would become " + report.pointsAfter() + ")"), false);
        String threshold = report.thresholdCrossing()
                .map(crossing -> Integer.toString(crossing.points()))
                .orElse("none");
        source.sendSuccess(() -> Component.literal("Crossed threshold: " + threshold), false);
        List<ModerationAction> actions = report.actionsThatWouldRun();
        if (actions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Actions: none"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Actions:"), false);
            int shown = Math.min(actions.size(), 20);
            for (int index = 0; index < shown; index++) {
                String detail = describePreviewAction(actions.get(index), context.timestamp());
                source.sendSuccess(() -> Component.literal("- " + detail), false);
            }
            if (shown < actions.size()) {
                int remaining = actions.size() - shown;
                source.sendSuccess(() -> Component.literal("- ... and " + remaining + " more"), false);
            }
        }
        return 1;
    }

    int showPlayerRecords(CommandSourceStack source, String targetText, int page, boolean activeViolations) {
        if (!requirePermission(source, FabricPermissionService.HISTORY)) return 0;
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        Instant now = clock.instant();
        PlayerModerationState state = states.snapshot(player.playerId(), player.playerName(), now);
        return activeViolations
                ? showActiveScores(source, player, state, page, now)
                : showHistory(source, player, state, page);
    }

    private int showHistory(CommandSourceStack source, TargetPlayer player, PlayerModerationState state, int page) {
        List<ViolationRecord> records = new ArrayList<>(state.violations());
        records.sort(Comparator.comparing(ViolationRecord::timestamp).reversed());
        Page<ViolationRecord> result = page(records, page);
        source.sendSuccess(() -> Component.literal("AutoMod history for " + player.playerName()
                + " - page " + result.page() + "/" + result.totalPages()).withStyle(ChatFormatting.GOLD), false);
        if (result.values().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No recorded violations."), false);
        }
        for (ViolationRecord record : result.values()) {
            source.sendSuccess(() -> Component.literal(record.timestamp() + " | "
                    + String.join(", ", record.ruleIds()) + " | +" + record.pointsAdded()
                    + " -> " + record.scoreAfter() + " | " + record.decision()), false);
        }
        return 1;
    }

    private int showActiveScores(
            CommandSourceStack source,
            TargetPlayer player,
            PlayerModerationState state,
            int page,
            Instant now
    ) {
        List<ScoreEntry> scores = state.scoreEntries().stream()
                .filter(entry -> entry.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(ScoreEntry::createdAt).reversed())
                .toList();
        Page<ScoreEntry> result = page(scores, page);
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
        return 1;
    }

    int clearPlayer(CommandSourceStack source, String targetText) {
        if (!requirePermission(source, FabricPermissionService.CLEAR)) return 0;
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        states.remove(player.playerId());
        scheduleSnapshot();
        source.sendSuccess(() -> Component.literal("Cleared moderation data for " + player.playerName() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    int mutePlayer(CommandSourceStack source, String targetText, String durationText, String reason) {
        if (!requirePermission(source, FabricPermissionService.MUTE)) return 0;
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        try {
            var config = configs.current();
            Duration duration = DurationParser.parse(durationText, config.mutes().maximumDuration());
            TargetPlayer player = target.orElseThrow();
            MuteState mute = mutes.mute(
                    player.playerId(),
                    player.playerName(),
                    duration,
                    config.mutes().maximumDuration(),
                    safeReason(reason),
                    "manual",
                    config.state().maximumTrackedPlayers());
            scheduleSnapshot();
            platform.notifyPlayer(player.playerId(), "You have been muted until " + mute.mutedUntil()
                    + ". Reason: " + mute.reason());
            source.sendSuccess(() -> Component.literal("Muted " + player.playerName() + " until "
                    + mute.mutedUntil() + ".").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    int unmutePlayer(CommandSourceStack source, String targetText) {
        if (!requirePermission(source, FabricPermissionService.MUTE)) return 0;
        Optional<TargetPlayer> target = resolveTarget(targetText);
        if (target.isEmpty()) {
            source.sendFailure(Component.literal("Unknown player or UUID: " + targetText));
            return 0;
        }
        TargetPlayer player = target.orElseThrow();
        boolean existed = mutes.unmute(player.playerId(), player.playerName());
        scheduleSnapshot();
        source.sendSuccess(() -> Component.literal((existed ? "Unmuted " : "No active mute for ")
                + player.playerName() + ".").withStyle(existed ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return existed ? 1 : 0;
    }

    int setInspect(CommandSourceStack source, Boolean requested) {
        if (!requirePermission(source, FabricPermissionService.INSPECT)) return 0;
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Inspect mode can only be used by a player."));
            return 0;
        }
        boolean enabled = requested == null ? !inspectors.contains(player.getUUID()) : requested;
        if (enabled) inspectors.add(player.getUUID());
        else inspectors.remove(player.getUUID());
        source.sendSuccess(() -> Component.literal("Detailed AutoMod alerts " + (enabled ? "enabled." : "disabled."))
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return 1;
    }

    CompletableFuture<Suggestions> suggestPlayers(SuggestionsBuilder builder) {
        Set<String> names = new HashSet<>();
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            currentServer.getPlayerList().getPlayers().forEach(player -> names.add(player.getName().getString()));
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
        MinecraftServer currentServer = server;
        if (currentServer != null) {
            ServerPlayer online = currentServer.getPlayerList().getPlayer(value);
            if (online != null) {
                return Optional.of(new TargetPlayer(online.getUUID(), online.getName().getString()));
            }
        }
        try {
            UUID uuid = UUID.fromString(value);
            Optional<PlayerModerationState> stored = states.snapshots().stream()
                    .filter(state -> state.playerId().equals(uuid))
                    .findFirst();
            String name = stored.map(PlayerModerationState::lastKnownName).filter(candidate -> !candidate.isBlank())
                    .orElse(value);
            return Optional.of(new TargetPlayer(uuid, name));
        } catch (IllegalArgumentException ignored) {
            return states.snapshots().stream()
                    .filter(state -> state.lastKnownName().equalsIgnoreCase(value))
                    .findFirst()
                    .map(state -> new TargetPlayer(state.playerId(), state.lastKnownName()));
        }
    }

    void ensureAutomaticMute(UUID playerId, Instant until, String reason) {
        Instant now = clock.instant();
        String playerName = resolveTarget(playerId.toString()).map(TargetPlayer::playerName).orElse(playerId.toString());
        Optional<MuteState> current = mutes.activeMute(playerId, playerName);
        if (current.filter(mute -> !mute.mutedUntil().isBefore(until)).isPresent()) {
            return;
        }
        Duration requested = Duration.between(now, until);
        if (requested.isNegative() || requested.isZero()) {
            return;
        }
        var config = configs.current();
        Duration maximum = config.mutes().maximumDuration();
        if (requested.compareTo(maximum) > 0) requested = maximum;
        mutes.mute(playerId, playerName, requested, maximum, reason, "automatic",
                config.state().maximumTrackedPlayers());
        scheduleSnapshot();
    }

    private static String describePreviewAction(ModerationAction action, Instant evaluatedAt) {
        return switch (action) {
            case ModerationAction.NotifyStaff alert -> "NOTIFY_STAFF: " + alert.decision() + ", rules "
                    + boundedPreviewText(String.join(", ", alert.ruleIds()));
            case ModerationAction.Warn warning -> "WARN: " + boundedPreviewText(warning.message());
            case ModerationAction.Mute mute -> {
                Duration duration = Duration.between(evaluatedAt, mute.until());
                if (duration.isNegative()) duration = Duration.ZERO;
                yield "MUTE for " + compactPreviewDuration(duration) + " (until " + mute.until()
                        + "), reason: " + boundedPreviewText(mute.reason());
            }
            case ModerationAction.Kick kick -> "KICK: " + boundedPreviewText(kick.reason());
            case ModerationAction.ExecuteCommand command -> "COMMAND: "
                    + boundedPreviewText(command.command());
        };
    }

    private static String compactPreviewDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds % 604_800 == 0 && seconds > 0) return (seconds / 604_800) + "w";
        if (seconds % 86_400 == 0 && seconds > 0) return (seconds / 86_400) + "d";
        if (seconds % 3_600 == 0 && seconds > 0) return (seconds / 3_600) + "h";
        if (seconds % 60 == 0 && seconds > 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static String boundedPreviewText(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), 160));
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(160)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) return "Muted by a staff member";
        StringBuilder result = new StringBuilder();
        reason.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(256)
                .forEach(result::appendCodePoint);
        return result.isEmpty() ? "Muted by a staff member" : result.toString();
    }

    private static <T> Page<T> page(List<T> values, int requestedPage) {
        int totalPages = Math.max(1, (values.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int start = Math.min(values.size(), (page - 1) * PAGE_SIZE);
        int end = Math.min(values.size(), start + PAGE_SIZE);
        return new Page<>(page, totalPages, List.copyOf(values.subList(start, end)));
    }

    MinecraftServer server() { return server; }
    Path configDirectory() { return configDirectory; }
    Path worldDataDirectory() {
        Path value = worldDataDirectory;
        if (value == null) throw new IllegalStateException("No Minecraft server is running");
        return value;
    }
    FabricPermissionService permissions() { return permissions; }
    int fallbackOperatorLevel() { return configs.current().permissions().fallbackOperatorLevel(); }
    boolean showOriginalAlerts() { return configs.current().staffAlerts().showOriginal(); }
    Duration muteButtonDuration() { return configs.current().staffAlerts().muteButtonDuration(); }
    boolean inspectEnabled(UUID playerId) { return inspectors.contains(playerId); }

    private record TargetPlayer(UUID playerId, String playerName) {}
    private record Page<T>(int page, int totalPages, List<T> values) {}
}
