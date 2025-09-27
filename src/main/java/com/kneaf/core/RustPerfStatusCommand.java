package com.kneaf.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RustPerfStatusCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rustperf")
            .then(Commands.literal("status")
                .executes(RustPerfStatusCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Get metrics from RustPerformance
        double tps = RustPerformance.getCurrentTPS();
        long normalTicks = RustPerformance.getTotalNormalTicks();
        long throttledTicks = RustPerformance.getTotalThrottledTicks();
        long totalMerged = RustPerformance.getTotalMerged();
        long totalDespawned = RustPerformance.getTotalDespawned();

        // Send message
        source.sendSuccess(() -> Component.literal(String.format(
            "RustPerf Status:\n" +
            "Current TPS: %.2f\n" +
            "Normally Ticked Entities: %d\n" +
            "Throttled Entities: %d\n" +
            "Total Merged Items: %d\n" +
            "Total Despawned Items: %d",
            tps, normalTicks, throttledTicks, totalMerged, totalDespawned)), false);

        return 1;
    }
}