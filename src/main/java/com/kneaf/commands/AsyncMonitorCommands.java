package com.kneaf.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Command untuk memonitor performance profiling.
 */
@SuppressWarnings("null")
public class AsyncMonitorCommands {

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
                dispatcher.register(
                                Commands.literal("kneaf")
                                                .then(Commands.literal("profile")
                                                                .requires(source -> source.hasPermission(2))
                                                                .then(Commands.literal("start")
                                                                                .executes(AsyncMonitorCommands::startProfiling))
                                                                .then(Commands.literal("stop")
                                                                                .executes(AsyncMonitorCommands::stopProfiling))
                                                                .then(Commands.literal("report")
                                                                                .executes(AsyncMonitorCommands::showProfileReport))));
        }

        private static int startProfiling(CommandContext<CommandSourceStack> context) {
                com.kneaf.core.ProfileMonitor.start();
                context.getSource().sendSuccess(() -> Component.literal("§aPerformance profiling started."), true);
                return 1;
        }

        @SuppressWarnings("null")
        private static int stopProfiling(CommandContext<CommandSourceStack> context) {
                String report = com.kneaf.core.ProfileMonitor.stop();
                if (report != null) {
                        context.getSource().sendSuccess(
                                        () -> Component.literal("§eProfiling stopped. Report generated:"), false);
                        context.getSource().sendSuccess(() -> Component.literal(report), false);
                } else {
                        context.getSource().sendFailure(Component.literal("Failed to generate report."));
                }
                return 1;
        }

        @SuppressWarnings("null")
        private static int showProfileReport(CommandContext<CommandSourceStack> context) {
                if (com.kneaf.core.ProfileMonitor.isActive()) {
                        context.getSource().sendFailure(Component.literal(
                                        "§cProfiling is currently active. Use /kneaf profile stop to see report."));
                        return 0;
                }
                // This command is simpler if it just stops current profiler or shows last
                // report,
                // but for now let's just use stop to show the report.
                return stopProfiling(context);
        }
}
