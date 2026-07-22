package com.maxello1.chatautomod.fabric262;

import com.maxello1.chatautomod.core.api.BypassProfile;
import com.maxello1.chatautomod.core.model.RuleCategory;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.EnumSet;
import java.util.Set;

final class FabricPermissionService implements com.maxello1.chatautomod.core.api.PermissionService {
    static final String ADMIN = "chatautomod.admin";
    static final String RELOAD = "chatautomod.reload";
    static final String TEST = "chatautomod.test";
    static final String HISTORY = "chatautomod.history";
    static final String CLEAR = "chatautomod.clear";
    static final String MUTE = "chatautomod.mute";
    static final String INSPECT = "chatautomod.inspect";
    static final String ALERTS = "chatautomod.alerts";
    static final String BYPASS = "chatautomod.bypass";
    static final String BYPASS_SPAM = "chatautomod.bypass.spam";
    static final String BYPASS_FILTER = "chatautomod.bypass.filter";
    static final String BYPASS_ADVERTISING = "chatautomod.bypass.advertising";

    private final java.util.function.Supplier<net.minecraft.server.MinecraftServer> serverSupplier;

    FabricPermissionService(java.util.function.Supplier<net.minecraft.server.MinecraftServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
    }

    @Override
    public boolean hasPermission(java.util.UUID playerId, String node, int fallbackOperatorLevel) {
        net.minecraft.server.MinecraftServer server = serverSupplier.get();
        if (server == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && hasPermission(player, node, fallbackOperatorLevel);
    }

    boolean hasPermission(CommandSourceStack source, String node, int fallbackOperatorLevel) {
        ServerPlayer player = source.getPlayer();
        return player == null || hasPermission(player, node, fallbackOperatorLevel);
    }

    boolean hasPermission(ServerPlayer player, String node, int fallbackOperatorLevel) {
        PermissionLevel fallback = PermissionLevel.byId(Math.max(0, Math.min(4, fallbackOperatorLevel)));
        return ((PermissionContextOwner) player).checkPermission(identifier(node), fallback);
    }

    BypassProfile bypassProfile(ServerPlayer player, int fallbackOperatorLevel) {
        if (hasPermission(player, BYPASS, fallbackOperatorLevel)) {
            return BypassProfile.ALL;
        }

        Set<RuleCategory> categories = EnumSet.noneOf(RuleCategory.class);
        if (hasPermission(player, BYPASS_SPAM, fallbackOperatorLevel)) {
            categories.add(RuleCategory.SPAM);
            categories.add(RuleCategory.DUPLICATE);
            categories.add(RuleCategory.SIMILARITY);
            categories.add(RuleCategory.FLOODING);
        }
        if (hasPermission(player, BYPASS_FILTER, fallbackOperatorLevel)) {
            categories.add(RuleCategory.FILTERED_CONTENT);
        }
        if (hasPermission(player, BYPASS_ADVERTISING, fallbackOperatorLevel)) {
            categories.add(RuleCategory.ADVERTISING);
        }
        return categories.isEmpty() ? BypassProfile.NONE : new BypassProfile(false, categories);
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
}
