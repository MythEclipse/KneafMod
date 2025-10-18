package com.kneaf.commands;

import com.kneaf.core.OptimizationInjector;
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

        String metrics = OptimizationInjector.getOptimizationMetrics();
        String combinedMetrics = OptimizationInjector.getCombinedOptimizationMetrics();

        source.sendSuccess(() -> Component.literal("§6=== KneafCore Optimization Metrics ==="), false);
        source.sendSuccess(() -> Component.literal("§eEntity Optimization: §f" + metrics), false);
        source.sendSuccess(() -> Component.literal("§eCombined Optimization: §f" + combinedMetrics), false);
        source.sendSuccess(() -> Component.literal("§7Use §f/kneaf metrics §7to view these metrics anytime."), false);

        return 1;
    }
}