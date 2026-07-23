package com.maxello1.chatautomod.fabric262;

import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.api.PermissionService;
import com.maxello1.chatautomod.core.model.RuleCategory;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class FabricPermissionService implements PermissionService {
    static final String ADMIN = "chatautomod.admin";
    static final String RELOAD = "chatautomod.reload";
    static final String TEST = "chatautomod.test";
    static final String HISTORY = "chatautomod.history";
    static final String CLEAR = "chatautomod.clear";
    static final String MUTE = "chatautomod.mute";
    static final String INSPECT = "chatautomod.inspect";
    static final String PERMISSIONS = "chatautomod.permissions";
    static final String ALERTS = "chatautomod.alerts";
    static final String BYPASS = "chatautomod.bypass";
    static final String BYPASS_SPAM = "chatautomod.bypass.spam";
    static final String BYPASS_FILTER = "chatautomod.bypass.filter";
    static final String BYPASS_ADVERTISING = "chatautomod.bypass.advertising";
    static final String BYPASS_SECURITY = "chatautomod.bypass.security";

    private final Supplier<MinecraftServer> serverSupplier;

    public FabricPermissionService(Logger logger, Supplier<MinecraftServer> serverSupplier) {
        Objects.requireNonNull(logger, "logger");
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier");
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

    boolean hasPermission(ServerPlayer player, String permission, int fallbackOperatorLevel) {
        return check(player, permission, true, fallbackOperatorLevel).allowed();
    }

    boolean hasPermission(CommandSourceStack source, String permission, int fallbackOperatorLevel) {
        return check(source, permission, fallbackOperatorLevel).allowed();
    }

    PermissionCheck check(CommandSourceStack source, String permission, int fallbackOperatorLevel) {
        int fallback = validatedOperatorLevel(fallbackOperatorLevel);
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return check(player, permission, true, fallback);
        }
        return ((PermissionContextOwner) source).checkPermission(identifier(permission), PermissionLevel.byId(fallback))
                ? new PermissionCheck(true, PermissionOrigin.CONSOLE)
                : new PermissionCheck(false, PermissionOrigin.DENIED);
    }

    PermissionCheck check(
            ServerPlayer player,
            String permission,
            boolean allowOperatorFallback,
            int fallbackOperatorLevel
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(permission, "permission");
        int fallback = validatedOperatorLevel(fallbackOperatorLevel);
        TriState explicit = permissionValue(player, permission);
        if (explicit == TriState.TRUE) {
            return new PermissionCheck(true, PermissionOrigin.EXPLICIT);
        }
        if (explicit == TriState.FALSE) {
            return new PermissionCheck(false, PermissionOrigin.EXPLICIT_DENY);
        }
        if (allowOperatorFallback && ((PermissionContextOwner) player)
                .checkPermission(identifier(permission), PermissionLevel.byId(fallback))) {
            return new PermissionCheck(true, PermissionOrigin.OPERATOR_FALLBACK);
        }
        return new PermissionCheck(false, PermissionOrigin.DENIED);
    }

    BypassProfile bypassProfile(ServerPlayer player, boolean operatorsBypassModeration, int fallbackOperatorLevel) {
        if (bypassGranted(player, BYPASS, operatorsBypassModeration, fallbackOperatorLevel)) {
            return BypassProfile.ALL;
        }

        Set<RuleCategory> categories = EnumSet.noneOf(RuleCategory.class);
        if (bypassGranted(player, BYPASS_SPAM, operatorsBypassModeration, fallbackOperatorLevel)) {
            categories.add(RuleCategory.SPAM);
            categories.add(RuleCategory.DUPLICATE);
            categories.add(RuleCategory.SIMILARITY);
            categories.add(RuleCategory.FLOODING);
        }
        if (bypassGranted(player, BYPASS_FILTER, operatorsBypassModeration, fallbackOperatorLevel)) {
            categories.add(RuleCategory.FILTERED_CONTENT);
        }
        if (bypassGranted(player, BYPASS_ADVERTISING, operatorsBypassModeration, fallbackOperatorLevel)) {
            categories.add(RuleCategory.ADVERTISING);
        }
        if (bypassGranted(player, BYPASS_SECURITY, operatorsBypassModeration, fallbackOperatorLevel)) {
            categories.add(RuleCategory.SECURITY);
        }
        return categories.isEmpty() ? BypassProfile.NONE : new BypassProfile(false, categories);
    }

    PermissionCheck checkBypass(
            ServerPlayer player,
            String permission,
            boolean operatorsBypassModeration,
            int fallbackOperatorLevel
    ) {
        return check(player, permission, operatorsBypassModeration, fallbackOperatorLevel);
    }

    boolean providerInstalled() {
        return true;
    }

    private boolean bypassGranted(
            ServerPlayer player,
            String permission,
            boolean operatorsBypassModeration,
            int fallbackOperatorLevel
    ) {
        return checkBypass(player, permission, operatorsBypassModeration, fallbackOperatorLevel).allowed();
    }

    private TriState permissionValue(ServerPlayer player, String permission) {
        return ((PermissionContextOwner) player).checkPermission(identifier(permission));
    }

    private static int validatedOperatorLevel(int level) {
        if (level < 0 || level > 4) {
            throw new IllegalArgumentException("fallback operator level must be between 0 and 4");
        }
        return level;
    }

    private static Identifier identifier(String dottedNode) {
        int separator = dottedNode.indexOf('.');
        if (separator <= 0 || separator == dottedNode.length() - 1) {
            throw new IllegalArgumentException("Permission node must contain a namespace: " + dottedNode);
        }
        return Identifier.fromNamespaceAndPath(
                dottedNode.substring(0, separator),
                dottedNode.substring(separator + 1));
    }

    enum PermissionOrigin {
        EXPLICIT,
        EXPLICIT_DENY,
        OPERATOR_FALLBACK,
        CONSOLE,
        DENIED
    }

    record PermissionCheck(boolean allowed, PermissionOrigin origin) {
        String description() {
            return switch (origin) {
                case EXPLICIT -> "explicit permission";
                case EXPLICIT_DENY -> "explicit denial";
                case OPERATOR_FALLBACK -> "operator fallback";
                case CONSOLE -> "server console";
                case DENIED -> "not granted";
            };
        }
    }
}
