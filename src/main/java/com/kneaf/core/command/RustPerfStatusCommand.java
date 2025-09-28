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

        // Get metrics from RustPerformance
        double tps = RustPerformance.getCurrentTPS();
        long entitiesProcessed = RustPerformance.getTotalEntitiesProcessed();
        long mobsProcessed = RustPerformance.getTotalMobsProcessed();
        long blocksProcessed = RustPerformance.getTotalBlocksProcessed();
        long totalMerged = RustPerformance.getTotalMerged();
        long totalDespawned = RustPerformance.getTotalDespawned();
        String memoryStats = RustPerformance.getMemoryStats();

        // Send message
        String message = """
            RustPerf Status:
            Current TPS: %.2f
            Entities Processed: %d
            Mobs Processed: %d
            Blocks Processed: %d
            Total Merged Items: %d
            Total Despawned Items: %d
            Memory Stats: %s
            """.formatted(tps, entitiesProcessed, mobsProcessed, blocksProcessed, totalMerged, totalDespawned, memoryStats);
        source.sendSuccess(() -> Component.literal(message), false);

        return 1;
    }
}