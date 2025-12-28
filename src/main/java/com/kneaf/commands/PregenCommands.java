/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Commands for Chunk Pre-generation.
 */
package com.kneaf.commands;

import com.kneaf.core.util.ChunkPreGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

@SuppressWarnings("null")
public class PregenCommands {

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("kneaf")
                        .then(Commands.literal("pregen")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("start")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                                                .executes(PregenCommands::startPregen)))
                                .then(Commands.literal("stop")
                                        .executes(PregenCommands::stopPregen))
                                .then(Commands.literal("status")
                                        .executes(PregenCommands::showStatus))));
    }

    @SuppressWarnings("null")
    private static int startPregen(CommandContext<CommandSourceStack> context) {
        if (ChunkPreGenerator.isActive()) {
            context.getSource().sendFailure(Component.literal("Pre-generation is already active!"));
            return 0;
        }

        int radius = IntegerArgumentType.getInteger(context, "radius");
        ServerLevel level = context.getSource().getLevel();
        net.minecraft.world.entity.Entity entity = context.getSource().getEntity();
        ChunkPos center;

        if (entity != null) {
            center = new ChunkPos(entity.blockPosition());
        } else {
            // Fallback for console: use world spawn
            center = new ChunkPos(level.getSharedSpawnPos());
        }

        ChunkPreGenerator.start(level, radius, center.x, center.z);
        context.getSource().sendSuccess(() -> Component.literal(
                String.format("Started pre-generation (Radius: %d chunks). center=[%d, %d]", radius, center.x,
                        center.z)),
                true);

        return 1;
    }

    @SuppressWarnings("null")
    private static int stopPregen(CommandContext<CommandSourceStack> context) {
        ChunkPreGenerator.stop();
        context.getSource().sendSuccess(() -> Component.literal("Pre-generation stopped."), true);
        return 1;
    }

    @SuppressWarnings("null")
    private static int showStatus(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                String.format("Pre-generation Status: %s", ChunkPreGenerator.getStatus())), false);
        return 1;
    }
}
