package com.maxello1.chatautomod.fabric1211;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AutoModCommands {
    private static final int DEFAULT_OPERATOR_LEVEL = 3;

    private AutoModCommands() {
    }

    public static void register(FabricPermissionService permissions) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = literal("automod")
                    .executes(context -> status(context.getSource()));

            root.then(placeholder("reload", "chatautomod.reload", permissions, false));
            root.then(placeholder("test", "chatautomod.test", permissions, true));
            root.then(placeholder("history", "chatautomod.history", permissions, true));
            root.then(placeholder("violations", "chatautomod.history", permissions, true));
            root.then(placeholder("clear", "chatautomod.clear", permissions, true));
            root.then(placeholder("mute", "chatautomod.mute", permissions, true));
            root.then(placeholder("unmute", "chatautomod.mute", permissions, true));
            root.then(placeholder("inspect", "chatautomod.inspect", permissions, true));

            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> placeholder(
            String name,
            String permission,
            FabricPermissionService permissions,
            boolean acceptsArguments
    ) {
        LiteralArgumentBuilder<CommandSourceStack> command = literal(name)
                .requires(source -> permissions.hasPermission(source, permission, DEFAULT_OPERATOR_LEVEL))
                .executes(context -> unavailable(context.getSource()));
        if (acceptsArguments) {
            command.then(argument("arguments", greedyString())
                    .executes(context -> unavailable(context.getSource())));
        }
        return command;
    }

    private static int status(CommandSourceStack source) {
        source.sendSystemMessage(Component.translatable("chatautomod.command.header"));
        source.sendSystemMessage(Component.translatable("chatautomod.command.status.ready"));
        return 1;
    }

    private static int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.translatable("chatautomod.command.unavailable"));
        return 0;
    }
}
