package com.kneaf.core.protocol.commands;

import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Standardized Rust performance status command with consistent error handling and logging. */
public class RustPerfStatusCommand extends BaseCommand {

  private static final String COMMAND_NAME = "rustperf";
  private static final String DESCRIPTION = "Show Rust performance status information";
  private static final String USAGE = "/rustperf status";
  private static final int PERMISSION_LEVEL = ProtocolConstants.COMMAND_PERMISSION_LEVEL_USER;

  /** Create a new Rust performance status command. */
  public RustPerfStatusCommand() {
    super(COMMAND_NAME, DESCRIPTION, PERMISSION_LEVEL, USAGE);
  }

  /**
   * Register the Rust performance status command.
   *
   * @param dispatcher the command dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    // Create command instance
    RustPerfStatusCommand command = new RustPerfStatusCommand();

    // Build command structure
    LiteralArgumentBuilder<CommandSourceStack> builder =
        Commands.literal(COMMAND_NAME)
            .then(
                Commands.literal("status")
                    .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                    .executes(context -> command.executeStatus(context)));

    dispatcher.register(builder);
  }

  @Override
  protected int executeCommand(CommandContext<CommandSourceStack> context) throws Exception {
    // This command only has one subcommand, so delegate to it
    return executeStatus(context);
  }

  /**
   * Execute status subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeStatus(CommandContext<CommandSourceStack> context) {
    try {
      // Get status from Rust performance monitoring
      double tps = RustPerformance.getCurrentTPS();
      String memoryStats = RustPerformance.getMemoryStats();
      String cpuStats = RustPerformance.getCpuStats();

      // Format and send the status message
      String message =
          String.format(
              "RustPerf Status: TPS: %.2f, CPU: %s, Memory: %s", tps, cpuStats, memoryStats);

      sendSuccess(context, message);

      // Log performance metrics
      getLogger()
          .logMetrics(
              "rustperf_status",
              java.util.Map.of(
                  "tps", tps,
                  "cpu_Stats_available", cpuStats != null && !cpuStats.isEmpty() ? 1 : 0,
                  "memory_Stats_available", memoryStats != null && !memoryStats.isEmpty() ? 1 : 0),
              ProtocolUtils.generateTraceId());

      return 1;

    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to get Rust performance status: %s", e.getMessage());
      return 0;
    }
  }

  @Override
  public boolean validateArguments(CommandContext<CommandSourceStack> context) {
    // This command has no arguments to validate
    return true;
  }
}
