package com.kneaf.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Command to display optimization metrics on demand.
 * Usage: /kneaf metrics
 */
public class MetricsCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("kneaf")
                .then(Commands.literal("metrics")
                        .requires(source -> source.hasPermission(2)) // OP level 2 required
                        .executes(MetricsCommand::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        java.util.Map<String, Object> perfMetrics = com.kneaf.core.PerformanceManager.getInstance()
                .getOptimizationMetrics();
        String asyncSummary = com.kneaf.core.async.AsyncMetricsCollector.getInstance().getMetricsSummary();

        source.sendSuccess(() -> Component.literal("§6=== KneafCore Optimization Metrics ==="), false);
        source.sendSuccess(() -> Component.literal("§eConfiguration:"), false);
        perfMetrics.forEach((k, v) -> source.sendSuccess(() -> Component.literal("  §7" + k + ": §f" + v), false));

        source.sendSuccess(() -> Component.literal("§eAsync Performance:"), false);
        for (String line : asyncSummary.split("\n")) {
            source.sendSuccess(() -> Component.literal("  §7" + line), false);
        }

        source.sendSuccess(() -> Component.literal("§7Use §f/kneaf metrics §7to view these metrics anytime."), false);

        return 1;
    }
}