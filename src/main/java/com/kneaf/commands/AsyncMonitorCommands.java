package com.kneaf.commands;

import com.kneaf.core.async.AsyncLoggingManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Command untuk memonitor async logging system.
 */
public class AsyncMonitorCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("kneaf")
                        .then(Commands.literal("async")
                                .then(Commands.literal("stats")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(AsyncMonitorCommands::showAsyncStats))
                                .then(Commands.literal("enable")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(AsyncMonitorCommands::enableAsync))
                                .then(Commands.literal("disable")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(AsyncMonitorCommands::disableAsync))));
    }

    @SuppressWarnings("null")
    private static int showAsyncStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AsyncLoggingManager.ManagerStatistics stats = AsyncLoggingManager.getStatistics();

        source.sendSuccess(() -> Component.literal("§6=== Async Logging Statistics ==="), false);
        source.sendSuccess(() -> Component.literal(String.format("§eInitialized: §f%s", stats.initialized)), false);
        source.sendSuccess(() -> Component.literal(String.format("§eEnabled: §f%s", stats.enabled)), false);

        source.sendSuccess(() -> Component.literal("§6\n[Logging]"), false);
        source.sendSuccess(
                () -> Component.literal(String.format("  §eActive loggers: §f%d", stats.loggerStats.activeLoggers)),
                false);
        source.sendSuccess(
                () -> Component
                        .literal(String.format("  §eMessages logged: §f%d", stats.loggerStats.totalMessagesLogged)),
                false);
        source.sendSuccess(
                () -> Component
                        .literal(String.format("  §eMessages dropped: §f%d", stats.loggerStats.totalMessagesDropped)),
                false);
        source.sendSuccess(
                () -> Component
                        .literal(String.format("  §ePending messages: §f%d", stats.loggerStats.totalPendingMessages)),
                false);

        source.sendSuccess(() -> Component.literal("§6\n[Metrics]"), false);
        source.sendSuccess(
                () -> Component.literal(String.format("  §eCounters: §f%d", stats.metricsStats.counterCount)), false);
        source.sendSuccess(() -> Component.literal(String.format("  §eGauges: §f%d", stats.metricsStats.gaugeCount)),
                false);
        source.sendSuccess(
                () -> Component.literal(String.format("  §eHistograms: §f%d", stats.metricsStats.histogramCount)),
                false);
        source.sendSuccess(() -> Component.literal(String.format("  §eTimers: §f%d", stats.metricsStats.timerCount)),
                false);
        source.sendSuccess(
                () -> Component.literal(String.format("  §eTotal recorded: §f%d", stats.metricsStats.totalRecorded)),
                false);
        source.sendSuccess(() -> Component.literal(String.format("  §eDropped: §f%d", stats.metricsStats.totalDropped)),
                false);

        source.sendSuccess(() -> Component.literal("§6\n[Async Operations]"), false);
        source.sendSuccess(() -> Component.literal(String.format("  §eTotal async: §f%d", stats.totalAsyncOperations)),
                false);
        source.sendSuccess(() -> Component.literal(String.format("  §eSync fallbacks: §f%d", stats.totalSyncFallbacks)),
                false);
        source.sendSuccess(
                () -> Component.literal(String.format("  §eSuccess rate: §f%.2f%%", stats.getAsyncSuccessRate() * 100)),
                false);

        return 1;
    }

    private static int enableAsync(CommandContext<CommandSourceStack> context) {
        AsyncLoggingManager.setEnabled(true);
        context.getSource().sendSuccess(() -> Component.literal("§aAsync logging enabled"), true);
        return 1;
    }

    private static int disableAsync(CommandContext<CommandSourceStack> context) {
        AsyncLoggingManager.setEnabled(false);
        context.getSource().sendSuccess(() -> Component.literal("§cAsync logging disabled"), true);
        return 1;
    }
}
