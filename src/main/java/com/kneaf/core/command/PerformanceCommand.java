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
                .then(Commands.literal("status").executes(PerformanceCommand::status))));
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
        String msg = String.format("Kneaf Performance - enabled=%s TPS=%.2f log=run/logs/kneaf-performance.log", PerformanceManager.isEnabled(), tps);
        source.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
