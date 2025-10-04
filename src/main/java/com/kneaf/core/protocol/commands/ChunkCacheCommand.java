package com.kneaf.core.protocol.commands;

import com.kneaf.core.chunkstorage.ChunkStorageManager;
import com.kneaf.core.chunkstorage.ChunkStorageManager.StorageStats;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Standardized chunk cache command with consistent error handling and logging. */
public class ChunkCacheCommand extends BaseCommand {

  private static final String COMMAND_NAME = "chunkcache";
  private static final String DESCRIPTION = "Chunk storage cache management commands";
  private static final String USAGE = "/chunkcache <subcommand>";
  private static final int PERMISSION_LEVEL = ProtocolConstants.COMMAND_PERMISSION_LEVEL_ADMIN;

  // Storage managers per world
  private static final Map<String, ChunkStorageManager> STORAGE_MANAGERS =
      new ConcurrentHashMap<>();

  // Argument names
  private static final String ARG_POLICY = "policy";
  private static final String ARG_SIZE = "size";

  /** Create a new chunk cache command. */
  public ChunkCacheCommand() {
    super(COMMAND_NAME, DESCRIPTION, PERMISSION_LEVEL, USAGE);
  }

  /**
   * Register the chunk cache command and its subcommands.
   *
   * @param dispatcher the command dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    // Create main command instance
    ChunkCacheCommand mainCommand = new ChunkCacheCommand();

    // Build command structure
    LiteralArgumentBuilder<CommandSourceStack> builder =
        Commands.literal(COMMAND_NAME)
            .requires(source -> source.hasPermission(PERMISSION_LEVEL))
            .then(Commands.literal("Stats").executes(context -> mainCommand.executeStats(context)))
            .then(Commands.literal("clear").executes(context -> mainCommand.executeClear(context)))
            .then(
                Commands.literal("maintenance")
                    .executes(context -> mainCommand.executeMaintenance(context)))
            .then(
                Commands.literal("backup").executes(context -> mainCommand.executeBackup(context)))
            .then(
                Commands.literal("config")
                    .then(
                        Commands.literal("capacity")
                            .then(
                                Commands.argument(ARG_SIZE, IntegerArgumentType.integer(1, 10000))
                                    .executes(
                                        context ->
                                            mainCommand.executeSetCapacity(
                                                context,
                                                IntegerArgumentType.getInteger(
                                                    context, ARG_SIZE)))))
                    .then(
                        Commands.literal(ARG_POLICY)
                            .then(
                                Commands.argument(ARG_POLICY, StringArgumentType.string())
                                    .executes(
                                        context ->
                                            mainCommand.executeSetEvictionPolicy(
                                                context,
                                                StringArgumentType.getString(
                                                    context, ARG_POLICY))))))
            .then(Commands.literal("help").executes(context -> mainCommand.executeHelp(context)));

    dispatcher.register(builder);
  }

  @Override
  protected int executeCommand(CommandContext<CommandSourceStack> context) throws Exception {
    // This method should not be called directly for complex commands like this
    // Instead, specific execute methods are called for each subcommand
    throw new UnsupportedOperationException("Use specific execute methods for subcommands");
  }

  /**
   * Register a storage manager for a world.
   *
   * @param worldName the world name
   * @param manager the storage manager
   */
  public static void registerStorageManager(String worldName, ChunkStorageManager manager) {
    if (worldName != null && manager != null) {
      STORAGE_MANAGERS.put(worldName, manager);
      // Note: We can't access logger here as it's instance-specific
      System.out.println(
          "Registered ChunkStorageManager for world '" + worldName + "' in command system");
    }
  }

  /**
   * Unregister a storage manager.
   *
   * @param worldName the world name
   */
  public static void unregisterStorageManager(String worldName) {
    if (worldName != null) {
      ChunkStorageManager removed = STORAGE_MANAGERS.remove(worldName);
      if (removed != null) {
        System.out.println(
            "Unregistered ChunkStorageManager for world '" + worldName + "' from command system");
      }
    }
  }

  /**
   * Execute Stats subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeStats(CommandContext<CommandSourceStack> context) {
    if (STORAGE_MANAGERS.isEmpty()) {
      sendFailure(context, "§cNo chunk storage managers registered");
      return 0;
    }

    sendSuccess(context, "§6=== Chunk Storage Statistics ===");

    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      String worldName = entry.getKey();
      ChunkStorageManager manager = entry.getValue();
      StorageStats Stats = manager.getStats();

      if (Stats.isEnabled()) {
        final String pattern =
            """
                        §bWorld: §f%s
                        §bStatus: §a%s
                        §bDatabase Chunks: §f%d
                        §bCached Chunks: §f%d
                        §bRead Latency: §f%d ms
                        §bWrite Latency: §f%d ms
                        §bCache Hit Rate: §f%.2f%%
                        §bOverall Hit Rate: §f%.2f%%
                        """;

        sendSuccessFormatted(
            context,
            pattern,
            worldName,
            Stats.getStatus(),
            Stats.getTotalChunksInDb(),
            Stats.getCachedChunks(),
            Stats.getAvgReadLatencyMs(),
            Stats.getAvgWriteLatencyMs(),
            Stats.getCacheHitRate() * 100,
            Stats.getOverallHitRate() * 100);
      } else {
        sendSuccessFormatted(context, "§bWorld: §f%s §7[DISABLED]", worldName);
      }
    }

    // Log statistics metrics
    getLogger()
        .logMetrics(
            "chunk_Stats",
            java.util.Map.of("worlds_count", STORAGE_MANAGERS.size()),
            ProtocolUtils.generateTraceId());

    return 1;
  }

  /**
   * Execute clear subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeClear(CommandContext<CommandSourceStack> context) {
    int clearedCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().clearCache();
        clearedCount++;
      } catch (Exception e) {
        // Log and continue
        getLogger()
            .logError(
                "clear_cache_error",
                e,
                ProtocolUtils.generateTraceId(),
                java.util.Map.of("world", entry.getKey()));
      }
    }

    sendSuccessFormatted(context, "§aCleared caches for §f%d §aworlds", clearedCount);

    getLogger()
        .logMetrics(
            "clear_cache",
            java.util.Map.of("worlds_cleared", clearedCount),
            ProtocolUtils.generateTraceId());

    return 1;
  }

  /**
   * Execute maintenance subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeMaintenance(CommandContext<CommandSourceStack> context) {
    sendSuccess(context, "§6Performing storage maintenance...");

    int maintainedCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      String worldName = entry.getKey();
      ChunkStorageManager manager = entry.getValue();

      try {
        manager.performMaintenance();
        maintainedCount++;
        sendSuccessFormatted(context, "§aMaintenance completed for world '%s'", worldName);
      } catch (Exception e) {
        sendSuccessFormatted(
            context, "§cMaintenance failed for world '%s': %s", worldName, e.getMessage());
        // Log error but continue with other worlds
        getLogger()
            .logError(
                "maintenance",
                e,
                ProtocolUtils.generateTraceId(),
                java.util.Map.of("world", worldName));
      }
    }

    sendSuccessFormatted(context, "§aMaintenance completed for §f%d §aworlds", maintainedCount);

    getLogger()
        .logMetrics(
            "maintenance",
            java.util.Map.of("worlds_maintained", maintainedCount),
            ProtocolUtils.generateTraceId());

    return 1;
  }

  /**
   * Execute backup subcommand.
   *
   * @param context the command context
   * @return command result
   */
  private int executeBackup(CommandContext<CommandSourceStack> context) {
    sendSuccess(context, "§6Creating backups...");

    int backupCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      String worldName = entry.getKey();
      ChunkStorageManager manager = entry.getValue();

      try {
        String backupPath =
            String.format("backups/chunkstorage/%s_%d", worldName, System.currentTimeMillis());
        manager.createBackup(backupPath);
        backupCount++;
        sendSuccessFormatted(
            context, "§aBackup created for world '%s' at '%s'", worldName, backupPath);
      } catch (Exception e) {
        sendSuccessFormatted(
            context, "§cBackup failed for world '%s': %s", worldName, e.getMessage());
        getLogger()
            .logError(
                "backup", e, ProtocolUtils.generateTraceId(), java.util.Map.of("world", worldName));
      }
    }

    sendSuccessFormatted(context, "§aBackups created for §f%d §aworlds", backupCount);

    getLogger()
        .logMetrics(
            "backup",
            java.util.Map.of("backups_created", backupCount),
            ProtocolUtils.generateTraceId());

    return 1;
  }

  /**
   * Execute set capacity subcommand.
   *
   * @param context the command context
   * @param capacity the new capacity
   * @return command result
   */
  private int executeSetCapacity(CommandContext<CommandSourceStack> context, int capacity) {
    int applied = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().setCacheCapacity(capacity);
        applied++;
      } catch (Exception e) {
        getLogger()
            .logError(
                "set_capacity_error",
                e,
                ProtocolUtils.generateTraceId(),
                java.util.Map.of("world", entry.getKey()));
      }
    }

    sendSuccessFormatted(
        context, "§aCache capacity set to §f%d §afor %d worlds", capacity, applied);

    getLogger()
        .logMetrics(
            "set_capacity",
            java.util.Map.of("new_capacity", capacity, "applied", applied),
            ProtocolUtils.generateTraceId());

    return 1;
  }

  /**
   * Execute set eviction policy subcommand.
   *
   * @param context the command context
   * @param policy the eviction policy
   * @return command result
   */
  private int executeSetEvictionPolicy(CommandContext<CommandSourceStack> context, String policy) {
    // Validate policy
    if (!policy.equalsIgnoreCase("LRU")
        && !policy.equalsIgnoreCase("Distance")
        && !policy.equalsIgnoreCase("Hybrid")) {
      sendSuccessFormatted(
          context, "§cInvalid eviction policy '%s'. Valid policies: LRU, Distance, Hybrid", policy);
      return 0;
    }

    int applied = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().setEvictionPolicy(policy);
        applied++;
      } catch (Exception e) {
        getLogger()
            .logError(
                "set_eviction_policy_error",
                e,
                ProtocolUtils.generateTraceId(),
                java.util.Map.of("world", entry.getKey()));
      }
    }

    sendSuccessFormatted(context, "§aEviction policy set to §f%s §afor %d worlds", policy, applied);

    getLogger()
        .logMetrics(
            "set_eviction_policy",
            java.util.Map.of(
                "new_policy",
                policy.equalsIgnoreCase("LRU") ? 1 : policy.equalsIgnoreCase("Distance") ? 2 : 3,
                "applied",
                applied),
            ProtocolUtils.generateTraceId());

    return 1;
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
            + "§b  Stats §7- Show storage statistics\n"
            + "§b  clear §7- Clear all caches\n"
            + "§b  maintenance §7- Perform storage maintenance\n"
            + "§b  backup §7- Create backups\n"
            + "§b  config capacity <size> §7- Set cache capacity\n"
            + "§b  config "
            + ARG_POLICY
            + " <LRU|Distance|Hybrid> §7- Set eviction policy\n"
            + "§b  help §7- Show this help message";

    sendSuccess(context, helpMessage);
    return 1;
  }

  @Override
  public boolean validateArguments(CommandContext<CommandSourceStack> context) {
    // Chunk cache command has subcommands, so basic validation passes
    return true;
  }
}
