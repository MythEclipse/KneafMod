package com.kneaf.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.kneaf.core.performance.PerformanceManager;
import com.kneaf.core.performance.RustPerformance;

public class PerformanceCommand {
    private PerformanceCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kneaf")
            .then(Commands.literal("perf")
                .then(Commands.literal("toggle").executes(PerformanceCommand::toggle))
                .then(Commands.literal("status").executes(PerformanceCommand::status))
                .then(Commands.literal("metrics").executes(PerformanceCommand::metrics))
                .then(Commands.literal("rotatelog").executes(PerformanceCommand::rotateLog))));
        
        // Register the chunk cache command
        ChunkCacheCommand.register(dispatcher);
    }

    private static int toggle(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newVal = !PerformanceManager.isEnabled();
        PerformanceManager.setEnabled(newVal);
        source.sendSuccess(() -> Component.literal("Kneaf performance manager enabled=" + newVal), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        double tps = RustPerformance.getCurrentTPS();
        String cpu = RustPerformance.getCpuStats();
        String mem = RustPerformance.getMemoryStats();
        String msg = String.format("Kneaf Performance - enabled=%s TPS=%.2f CPU=%s MEM=%s log=run/logs/kneaf-performance.log", PerformanceManager.isEnabled(), tps, cpu, mem);
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int metrics(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String msg = String.format("EntitiesProcessed=%d MobsProcessed=%d BlocksProcessed=%d ItemsMerged=%d ItemsDespawned=%d",
                RustPerformance.getTotalEntitiesProcessed(), RustPerformance.getTotalMobsProcessed(), RustPerformance.getTotalBlocksProcessed(), RustPerformance.getTotalMerged(), RustPerformance.getTotalDespawned());
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int rotateLog(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        com.kneaf.core.performance.PerformanceMetricsLogger.rotateNow();
        source.sendSuccess(() -> Component.literal("Kneaf performance log rotated."), false);
        return 1;
    }
}
