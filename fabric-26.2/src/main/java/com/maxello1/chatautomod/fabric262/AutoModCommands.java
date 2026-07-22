package com.maxello1.chatautomod.fabric262;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
                        .then(pagedPlayerCommand("history", FabricPermissionService.HISTORY, false))
                        .then(pagedPlayerCommand("violations", FabricPermissionService.HISTORY, true))
                        .then(Commands.literal("clear")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.CLEAR))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> runtime.suggestPlayers(builder))
                                        .executes(context -> runtime.clearPlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("mute")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.MUTE))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> runtime.suggestPlayers(builder))
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
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> runtime.suggestPlayers(builder))
                                        .executes(context -> runtime.unmutePlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("inspect")
                                .requires(source -> runtime.mayUse(source, FabricPermissionService.INSPECT))
                                .executes(context -> runtime.setInspect(context.getSource(), null))
                                .then(Commands.literal("on")
                                        .executes(context -> runtime.setInspect(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> runtime.setInspect(context.getSource(), false))))));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>
            pagedPlayerCommand(String name, String permission, boolean activeViolations) {
        return Commands.literal(name)
                .requires(source -> runtime.mayUse(source, permission))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> runtime.suggestPlayers(builder))
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
}
