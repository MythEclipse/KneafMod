package com.kneaf.core.protocol.commands;

import com.kneaf.core.network.NetworkHandler;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.monitoring.PerformanceManager;
import com.kneaf.core.performance.monitoring.PerformanceMetricsLogger;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

/** Standardized performance command with consistent error handling and logging. */
public class PerformanceCommand extends BaseCommand {

  private static final String COMMAND_NAME = "perf";
  private static final String DESCRIPTION = "Kneaf performance management commands";
  private static final String USAGE = "/kneaf perf <subcommand>";
  private static final int PERMISSION_LEVEL = ProtocolConstants.COMMAND_PERMISSION_LEVEL_MODERATOR;

  /** Create a new performance command. */
  public PerformanceCommand() {
    super(COMMAND_NAME, DESCRIPTION, PERMISSION_LEVEL, USAGE);
  }

  /**
   * Register the performance command and its subcommands.
   *
   * @param dispatcher the command dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    // Create main command instance
    PerformanceCommand mainCommand = new PerformanceCommand();

    // Build command structure
    LiteralArgumentBuilder<CommandSourceStack> builder =
        Commands.literal("kneaf")
            .then(
                Commands.literal(COMMAND_NAME)
                    .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                    .then(
                        Commands.literal("toggle")
                            .executes(context -> mainCommand.executeToggle(context)))
                    .then(
                        Commands.literal("status")
                            .executes(context -> mainCommand.executeStatus(context)))
                    .then(
                        Commands.literal("metrics")
                            .executes(context -> mainCommand.executeMetrics(context)))
                    .then(
                        Commands.literal("broadcast")
                            .then(
                                Commands.argument("message", StringArgumentType.greedyString())
                                    .executes(context -> mainCommand.executeBroadcast(context))))
                    .then(
                        Commands.literal("rotatelog")
                            .executes(context -> mainCommand.executeRotateLog(context)))
                    .then(
                        Commands.literal("help")
                            .executes(context -> mainCommand.executeHelp(context))));

    dispatcher.register(builder);

    // Also register chunk cache command as a subcommand
    ChunkCacheCommand.register(dispatcher);
  }

  @Override
  protected int executeCommand(CommandContext<CommandSourceStack> context) throws Exception {
    // This method should not be called directly for complex commands like this
    // Instead, specific execute methods are called for each subcommand
    throw new UnsupportedOperationException("Use specific execute methods for subcommands");
  }

  /**
   * Execute toggle subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeToggle(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    try {
      boolean newVal = !PerformanceManager.isEnabled();
      PerformanceManager.setEnabled(newVal);

      sendSuccessFormatted(context, "Kneaf performance manager enabled=%s", newVal);
      getLogger()
          .logMetrics(
              "toggle_performance",
              java.util.Map.of("enabled", newVal ? 1 : 0),
              ProtocolUtils.generateTraceId());

      return 1;

    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to toggle performance manager: %s", e.getMessage());
      return 0;
    }
  }

  /**
   * Execute status subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeStatus(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    try {
      double tps = RustPerformance.getCurrentTPS();
      String cpu = RustPerformance.getCpuStats();
      String mem = RustPerformance.getMemoryStats();

      String message =
          String.format(
              "Kneaf Performance - enabled=%s TPS=%.2f CPU=%s MEM=%s log=run/logs/kneaf-performance.log",
              PerformanceManager.isEnabled(), tps, cpu, mem);

      sendSuccess(context, message);

      // Log performance metrics
      getLogger()
          .logMetrics(
              "performance_status",
              java.util.Map.of("tps", tps, "enabled", PerformanceManager.isEnabled() ? 1 : 0),
              ProtocolUtils.generateTraceId());

      return 1;

    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to get performance status: %s", e.getMessage());
      return 0;
    }
  }

  /**
   * Execute metrics subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeMetrics(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    try {
      long entitiesProcessed = RustPerformance.getTotalEntitiesProcessed();
      long mobsProcessed = RustPerformance.getTotalMobsProcessed();
      long blocksProcessed = RustPerformance.getTotalBlocksProcessed();
      long itemsMerged = RustPerformance.getTotalMerged();
      long itemsDespawned = RustPerformance.getTotalDespawned();

      String message =
          String.format(
              "EntitiesProcessed=%d MobsProcessed=%d BlocksProcessed=%d ItemsMerged=%d ItemsDespawned=%d",
              entitiesProcessed, mobsProcessed, blocksProcessed, itemsMerged, itemsDespawned);

      sendSuccess(context, message);

      // Log detailed metrics
      getLogger()
          .logMetrics(
              "performance_metrics",
              java.util.Map.of(
                  "entities_processed", entitiesProcessed,
                  "mobs_processed", mobsProcessed,
                  "blocks_processed", blocksProcessed,
                  "items_merged", itemsMerged,
                  "items_despawned", itemsDespawned),
              ProtocolUtils.generateTraceId());

      return 1;

    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to get performance metrics: %s", e.getMessage());
      return 0;
    }
  }

  /**
   * Execute rotatelog subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeRotateLog(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    try {
      PerformanceMetricsLogger.rotateNow();
      sendSuccess(context, "Kneaf performance log rotated.");

      getLogger()
          .logMetrics(
              "rotate_log", java.util.Map.of("rotation_count", 1), ProtocolUtils.generateTraceId());

      return 1;

    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to rotate performance log: %s", e.getMessage());
      return 0;
    }
  }

  /** Execute broadcast subcommand - send a test performance line to all players. */
  private int executeBroadcast(CommandContext<CommandSourceStack> context) {
    try {
      String message = StringArgumentType.getString(context, "message");
      CommandSourceStack src = context.getSource();
      MinecraftServer server = src.getServer();
      if (server == null) {
        sendFailureFormatted(context, "Server instance not available");
        return 0;
      }
      NetworkHandler.broadcastPerformanceLine(server, message);
      sendSuccess(context, "Broadcasted performance line: " + message);
      return 1;
    } catch (Exception e) {
      sendFailureFormatted(context, "Failed to broadcast message: %s", e.getMessage());
      return 0;
    }
  }

  /**
   * Execute help subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeHelp(CommandContext<CommandSourceStack> context) {
    String helpMessage =
        createHelpMessage()
            + "\n"
            + "§bSubcommands:\n"
            + "§b  toggle §7- Toggle performance monitoring on/off\n"
            + "§b  status §7- Show current performance status\n"
            + "§b  metrics §7- Show detailed performance metrics\n"
            + "§b  rotatelog §7- Rotate the performance log file\n"
            + "§b  help §7- Show this help message";

    sendSuccess(context, helpMessage);
    return 1;
  }

  @Override
  public boolean validateArguments(CommandContext<CommandSourceStack> context) {
    // Performance command has subcommands, so basic validation passes
    return true;
  }
}
