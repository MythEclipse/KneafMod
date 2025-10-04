package com.kneaf.core.command;

import com.kneaf.core.performance.RustPerformance;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RustPerfStatusCommand {
  private RustPerfStatusCommand() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("rustperf")
            .then(Commands.literal("status").executes(RustPerfStatusCommand::execute)));
  }

  private static int execute(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    // Get status from Rust and TPS
    double tps = RustPerformance.getCurrentTPS();
    String memoryStats = RustPerformance.getMemoryStats();
    String cpuStats = RustPerformance.getCpuStats();

    // Send message
    String message =
        String.format(
            "RustPerf Status: TPS: %.2f, CPU: %s, Memory: %s", tps, cpuStats, memoryStats);
    source.sendSuccess(() -> Component.literal(message), false);

    return 1;
  }
}
