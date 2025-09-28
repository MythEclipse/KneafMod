package com.kneaf.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.kneaf.core.performance.RustPerformance;

public class RustPerfStatusCommand {
    private RustPerfStatusCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rustperf")
            .then(Commands.literal("status")
                .executes(RustPerfStatusCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Get status from Rust and TPS
        double tps = RustPerformance.getCurrentTPS();
        String memoryStats = RustPerformance.getMemoryStats();

        // Send message
        String message = String.format("RustPerf Status: TPS: %.2f, Memory: %s", tps, memoryStats);
        source.sendSuccess(() -> Component.literal(message), false);

        return 1;
    }
}