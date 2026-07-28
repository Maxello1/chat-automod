package com.maxello1.chatautomod.fabric1211.discord;

import com.maxello1.chatautomod.core.action.ModerationAction;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class DiscordIntegration {
    private final Object lifecycleLock = new Object();
    private final Logger logger;
    private final Path configFile;
    private final DiscordConfigLoader configLoader;
    private final DiscordAlertPublisher alertPublisher;
    private final DiscordCaseStore caseStore;
    private final DiscordInteractionListener interactionListener;
    private final AtomicLong generation = new AtomicLong();

    private volatile DiscordConfig config = DiscordConfig.defaults();
    private volatile DiscordAlertDestination destination;
    private volatile JDA jda;
    private volatile boolean runtimeStarted;
    private volatile boolean configured = true;
    private volatile boolean requestedEnabled;
    private volatile DiscordIntegrationStatus.Connection connection =
            DiscordIntegrationStatus.Connection.DISABLED;
    private volatile String lastSafeError = "";
    private volatile String lastLoggedConfigurationError = "";

    public DiscordIntegration(Logger logger, Path configFile) {
        this(
                logger,
                configFile,
                request -> java.util.concurrent.CompletableFuture.completedFuture(
                        com.maxello1.chatautomod.fabric1211.PunishmentResult.failure(
                                "Minecraft punishment handling is unavailable.",
                                true)));
    }

    public DiscordIntegration(
            Logger logger,
            Path configFile,
            DiscordPunishmentHandler punishmentHandler
    ) {
        this(logger, configFile, new DiscordConfigLoader(), punishmentHandler);
    }

    DiscordIntegration(
            Logger logger,
            Path configFile,
            DiscordConfigLoader configLoader,
            DiscordPunishmentHandler punishmentHandler
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.configFile = Objects.requireNonNull(configFile, "configFile");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.caseStore = new DiscordCaseStore();
        DiscordInteractionRouter router = new DiscordInteractionRouter(
                caseStore,
                new DiscordAuthorisationService());
        this.interactionListener = new DiscordInteractionListener(
                this::status,
                () -> config,
                router,
                caseStore,
                Objects.requireNonNull(punishmentHandler, "punishmentHandler"),
                logger);
        this.alertPublisher = new DiscordAlertPublisher(
                new DiscordAlertFormatter(),
                () -> destination,
                caseStore);
        loadConfiguration();
    }

    public void start() {
        synchronized (lifecycleLock) {
            runtimeStarted = true;
        }
        loadAndStart();
    }

    public void stop() {
        JDA previous;
        synchronized (lifecycleLock) {
            runtimeStarted = false;
            generation.incrementAndGet();
            destination = null;
            previous = jda;
            jda = null;
            connection = DiscordIntegrationStatus.Connection.STOPPED;
            caseStore.clear();
        }
        shutdown(previous);
    }

    public void reload() {
        JDA previous;
        boolean shouldStart;
        synchronized (lifecycleLock) {
            generation.incrementAndGet();
            destination = null;
            previous = jda;
            jda = null;
            shouldStart = runtimeStarted;
        }
        shutdown(previous);
        if (shouldStart) {
            loadAndStart();
        } else {
            loadConfiguration();
            connection = DiscordIntegrationStatus.Connection.STOPPED;
        }
    }

    public void publishAlert(ModerationAction.NotifyStaff alert) {
        if (connection != DiscordIntegrationStatus.Connection.READY) {
            return;
        }
        DiscordConfig currentConfig = config;
        alertPublisher.publish(alert, currentConfig).whenComplete((ignored, exception) -> {
            if (exception != null) {
                recordDeliveryFailure("Discord alert delivery failed", exception);
            }
        });
    }

    public boolean publishTestAlert() {
        if (connection != DiscordIntegrationStatus.Connection.READY) {
            return false;
        }
        alertPublisher.publishTest().whenComplete((ignored, exception) -> {
            if (exception != null) {
                recordDeliveryFailure("Discord test alert delivery failed", exception);
            }
        });
        return true;
    }

    public DiscordIntegrationStatus status() {
        DiscordConfig current = config;
        return new DiscordIntegrationStatus(
                configured,
                requestedEnabled,
                connection,
                !current.guildId().isEmpty(),
                !current.alertChannelId().isEmpty(),
                current.allowedRoleIds().size(),
                current.allowedUserIds().size(),
                caseStore.openCount(),
                lastSafeError);
    }

    DiscordConfig config() {
        return config;
    }

    private void loadAndStart() {
        DiscordConfigLoader.LoadResult result = loadConfiguration();
        if (!result.configured()) {
            connection = result.requestedEnabled()
                    ? DiscordIntegrationStatus.Connection.FAILED
                    : DiscordIntegrationStatus.Connection.DISABLED;
            return;
        }
        if (!result.config().enabled()) {
            connection = DiscordIntegrationStatus.Connection.DISABLED;
            return;
        }

        String token = configLoader.token(result.config()).orElse(null);
        if (token == null) {
            failStartup("Discord token environment variable is missing", null);
            return;
        }

        long startGeneration;
        synchronized (lifecycleLock) {
            if (!runtimeStarted) {
                connection = DiscordIntegrationStatus.Connection.STOPPED;
                return;
            }
            startGeneration = generation.incrementAndGet();
            connection = DiscordIntegrationStatus.Connection.STARTING;
            destination = null;
            lastSafeError = "";
        }
        Thread.ofPlatform()
                .name("Chat AutoMod Discord Startup")
                .daemon(true)
                .start(() -> connect(token, startGeneration));
    }

    private DiscordConfigLoader.LoadResult loadConfiguration() {
        DiscordConfigLoader.LoadResult result = configLoader.load(configFile);
        config = result.config();
        configured = result.configured();
        requestedEnabled = result.requestedEnabled();
        if (result.problems().isEmpty()) {
            lastSafeError = "";
            lastLoggedConfigurationError = "";
        } else {
            lastSafeError = "Discord configuration is invalid";
            String fingerprint = result.problems().toString();
            if (!fingerprint.equals(lastLoggedConfigurationError)) {
                lastLoggedConfigurationError = fingerprint;
                logger.warn("Chat AutoMod Discord configuration is invalid; Discord integration is disabled");
                result.problems().forEach(problem -> logger.warn("- {}", problem));
            }
        }
        return result;
    }

    private void connect(String token, long startGeneration) {
        JDA candidate = null;
        try {
            candidate = JDABuilder.createLight(token, List.of())
                    .setEnableShutdownHook(false)
                    .setAutoReconnect(true)
                    .addEventListeners(new LifecycleListener(startGeneration), interactionListener)
                    .build();
            synchronized (lifecycleLock) {
                if (!runtimeStarted || generation.get() != startGeneration) {
                    shutdown(candidate);
                    return;
                }
                jda = candidate;
            }
        } catch (RuntimeException exception) {
            shutdown(candidate);
            synchronized (lifecycleLock) {
                if (generation.get() == startGeneration) {
                    failStartup("Discord connection could not be started", exception);
                }
            }
        }
    }

    private void handleReady(long startGeneration, JDA readyJda) {
        synchronized (lifecycleLock) {
            if (!runtimeStarted || generation.get() != startGeneration) {
                shutdown(readyJda);
                return;
            }
            Guild guild = readyJda.getGuildById(config.guildId());
            TextChannel channel = readyJda.getTextChannelById(config.alertChannelId());
            if (guild == null || channel == null || channel.getGuild().getIdLong() != guild.getIdLong()) {
                failStartup("Configured Discord guild or alert channel is unavailable", null);
                generation.incrementAndGet();
                shutdown(readyJda);
                return;
            }
            if (!channel.canTalk()) {
                failStartup("Discord bot cannot send messages in the configured alert channel", null);
                generation.incrementAndGet();
                shutdown(readyJda);
                return;
            }
            jda = readyJda;
            destination = new JdaDiscordAlertDestination(channel);
            connection = DiscordIntegrationStatus.Connection.READY;
            lastSafeError = "";
            logger.info("Chat AutoMod Discord integration is ready");
        }
    }

    private void handleShutdown(long startGeneration) {
        synchronized (lifecycleLock) {
            if (runtimeStarted && generation.get() == startGeneration) {
                destination = null;
                jda = null;
                connection = DiscordIntegrationStatus.Connection.FAILED;
                lastSafeError = "Discord connection stopped unexpectedly";
                logger.warn("Chat AutoMod Discord connection stopped unexpectedly");
            }
        }
    }

    private void failStartup(String summary, Throwable exception) {
        connection = DiscordIntegrationStatus.Connection.FAILED;
        destination = null;
        jda = null;
        lastSafeError = summary;
        if (exception == null) {
            logger.warn("Chat AutoMod Discord integration failed: {}", summary);
        } else {
            logger.warn("Chat AutoMod Discord integration failed: {} ({})",
                    summary,
                    exception.getClass().getSimpleName());
        }
    }

    private void recordDeliveryFailure(String summary, Throwable exception) {
        lastSafeError = summary;
        Throwable cause = exception instanceof java.util.concurrent.CompletionException
                && exception.getCause() != null
                ? exception.getCause()
                : exception;
        logger.warn("{} ({})", summary, cause.getClass().getSimpleName());
    }

    private static void shutdown(JDA value) {
        if (value == null) {
            return;
        }
        try {
            value.shutdownNow();
        } catch (RuntimeException ignored) {
            // Shutdown must never interfere with the Minecraft server lifecycle.
        }
    }

    private final class LifecycleListener extends ListenerAdapter {
        private final long startGeneration;

        private LifecycleListener(long startGeneration) {
            this.startGeneration = startGeneration;
        }

        @Override
        public void onReady(ReadyEvent event) {
            handleReady(startGeneration, event.getJDA());
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            handleShutdown(startGeneration);
        }
    }
}
