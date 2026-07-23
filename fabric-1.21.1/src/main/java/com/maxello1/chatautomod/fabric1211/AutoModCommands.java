package com.maxello1.chatautomod.fabric1211;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.maxello1.chatautomod.core.state.StateClearScope;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Objects;

final class AutoModCommands {
    private final FabricRuntime runtime;

    AutoModCommands(FabricRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("automod")
                        .executes(context -> runtime.showCommandSummary(context.getSource()))
                        .then(Commands.literal("reload")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.RELOAD))
                                .executes(context -> runtime.reloadConfiguration(context.getSource())))
                        .then(Commands.literal("test")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.TEST))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> runtime.testMessage(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "message")))))
                        .then(pagedPlayerCommand("history", false))
                        .then(pagedPlayerCommand("violations", true))
                        .then(clearCommand())
                        .then(Commands.literal("mute")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.MUTE))
                                .then(playerArgument()
                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                .executes(context -> runtime.mutePlayer(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "player"),
                                                        StringArgumentType.getString(context, "duration"),
                                                        "Muted by a staff member"))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(context -> runtime.mutePlayer(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "player"),
                                                                StringArgumentType.getString(context, "duration"),
                                                                StringArgumentType.getString(context, "reason")))))))
                        .then(Commands.literal("unmute")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.MUTE))
                                .then(playerArgument()
                                        .executes(context -> runtime.unmutePlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("inspect")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.INSPECT))
                                .executes(context -> runtime.setInspect(context.getSource(), null))
                                .then(Commands.literal("on")
                                        .executes(context -> runtime.setInspect(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> runtime.setInspect(context.getSource(), false))))
                        .then(Commands.literal("permissions")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.PERMISSIONS))
                                .then(playerArgument()
                                        .executes(context -> runtime.showPermissions(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player")))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> pagedPlayerCommand(String name, boolean activeViolations) {
        return Commands.literal(name)
                .requires(source -> runtime.mayUse(source, FabricPermissionService.HISTORY))
                .then(playerArgument()
                        .executes(context -> runtime.showPlayerRecords(
                                context.getSource(),
                                StringArgumentType.getString(context, "player"),
                                1,
                                activeViolations))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> runtime.showPlayerRecords(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "player"),
                                        IntegerArgumentType.getInteger(context, "page"),
                                        activeViolations))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> clearCommand() {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("clear")
                .requires(source -> runtime.mayUse(source, FabricPermissionService.CLEAR));
        var player = playerArgument();
        player.then(clearScope("score", StateClearScope.SCORE));
        player.then(clearScope("history", StateClearScope.HISTORY));
        player.then(clearScope("spam", StateClearScope.SPAM));
        player.then(clearScope("all", StateClearScope.ALL));
        return command.then(player);
    }

    private LiteralArgumentBuilder<CommandSourceStack> clearScope(String literal, StateClearScope scope) {
        return Commands.literal(literal)
                .executes(context -> runtime.clearPlayer(
                        context.getSource(),
                        StringArgumentType.getString(context, "player"),
                        scope));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((context, builder) -> runtime.suggestPlayers(builder));
    }
}
