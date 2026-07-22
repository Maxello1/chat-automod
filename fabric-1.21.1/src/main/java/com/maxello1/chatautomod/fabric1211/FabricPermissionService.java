package com.maxello1.chatautomod.fabric1211;

import com.maxello1.chatautomod.core.api.PermissionService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class FabricPermissionService implements PermissionService {
    private static final String PERMISSIONS_MOD_ID = "fabric-permissions-api-v0";
    private static final String PERMISSIONS_CLASS = "me.lucko.fabric.api.permissions.v0.Permissions";

    private final Logger logger;
    private final Supplier<MinecraftServer> serverSupplier;
    private final Method entityCheck;
    private boolean bridgeFailureReported;

    public FabricPermissionService(Logger logger, Supplier<MinecraftServer> serverSupplier) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier");
        this.entityCheck = findEntityCheck(logger);
    }

    @Override
    public boolean hasPermission(UUID playerId, String permission, int fallbackOperatorLevel) {
        MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && hasPermission(player, permission, fallbackOperatorLevel);
    }

    public boolean hasPermission(ServerPlayer player, String permission, int fallbackOperatorLevel) {
        int fallback = validatedOperatorLevel(fallbackOperatorLevel);
        if (entityCheck != null) {
            try {
                return (boolean) entityCheck.invoke(null, player, permission, fallback);
            } catch (IllegalAccessException | InvocationTargetException | ClassCastException exception) {
                reportBridgeFailure(exception);
            }
        }
        return player.createCommandSourceStack().hasPermission(fallback);
    }

    public boolean hasPermission(CommandSourceStack source, String permission, int fallbackOperatorLevel) {
        ServerPlayer player = source.getPlayer();
        return player == null
                ? source.hasPermission(validatedOperatorLevel(fallbackOperatorLevel))
                : hasPermission(player, permission, fallbackOperatorLevel);
    }

    private static Method findEntityCheck(Logger logger) {
        if (!FabricLoader.getInstance().isModLoaded(PERMISSIONS_MOD_ID)) {
            return null;
        }
        try {
            Class<?> permissions = Class.forName(PERMISSIONS_CLASS, false, FabricPermissionService.class.getClassLoader());
            return permissions.getMethod("check", Entity.class, String.class, int.class);
        } catch (ReflectiveOperationException | LinkageError exception) {
            logger.warn("The optional permissions bridge is present but could not be initialized; operator levels will be used.", exception);
            return null;
        }
    }

    private static int validatedOperatorLevel(int level) {
        if (level < 0 || level > 4) {
            throw new IllegalArgumentException("fallback operator level must be between 0 and 4");
        }
        return level;
    }

    private void reportBridgeFailure(Exception exception) {
        if (!bridgeFailureReported) {
            bridgeFailureReported = true;
            logger.warn("The optional permissions bridge failed during a check; operator levels will be used.", exception);
        }
    }
}
