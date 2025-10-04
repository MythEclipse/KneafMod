package com.kneaf.core.command;

import com.kneaf.core.chunkstorage.ChunkStorageManager;
import com.kneaf.core.chunkstorage.ChunkStorageManager.StorageStats;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Command for monitoring and managing chunk cache operations. Provides statistics, configuration,
 * and maintenance operations.
 */
public class ChunkCacheCommand {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Storage managers per world
  private static final Map<String, ChunkStorageManager> STORAGE_MANAGERS =
      new ConcurrentHashMap<>();

  // Argument names / repeated literals
  private static final String ARG_POLICY = "policy";

  // Prevent instantiation
  private ChunkCacheCommand() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Register the chunk cache command.
   *
   * @param dispatcher The command dispatcher
   */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    LiteralArgumentBuilder<CommandSourceStack> command =
        Commands.literal("chunkcache")
            .requires(source -> source.hasPermission(2)) // Requires OP level 2
            .then(Commands.literal("Stats").executes(ChunkCacheCommand::showStats))
            .then(Commands.literal("clear").executes(ChunkCacheCommand::clearCache))
            .then(Commands.literal("maintenance").executes(ChunkCacheCommand::performMaintenance))
            .then(Commands.literal("backup").executes(ChunkCacheCommand::createBackup))
            .then(
                Commands.literal("config")
                    .then(
                        Commands.literal("capacity")
                            .then(
                                Commands.argument("size", IntegerArgumentType.integer(1, 10000))
                                    .executes(
                                        context ->
                                            setCacheCapacity(
                                                context,
                                                IntegerArgumentType.getInteger(context, "size")))))
                    .then(
                        Commands.literal(ARG_POLICY)
                            .then(
                                Commands.argument(ARG_POLICY, StringArgumentType.string())
                                    .executes(
                                        context ->
                                            setEvictionPolicy(
                                                context,
                                                StringArgumentType.getString(
                                                    context, ARG_POLICY))))))
            .then(Commands.literal("help").executes(ChunkCacheCommand::showHelp));

    dispatcher.register(command);
  }

  /**
   * Register a storage manager for a world.
   *
   * @param worldName The world name
   * @param manager The storage manager
   */
  public static void registerStorageManager(String worldName, ChunkStorageManager manager) {
    if (worldName != null && manager != null) {
      STORAGE_MANAGERS.put(worldName, manager);
      LOGGER.info("Registered ChunkStorageManager for world '{ }' in command system", worldName);
    }
  }

  /**
   * Unregister a storage manager.
   *
   * @param worldName The world name
   */
  public static void unregisterStorageManager(String worldName) {
    if (worldName != null) {
      ChunkStorageManager removed = STORAGE_MANAGERS.remove(worldName);
      if (removed != null) {
        LOGGER.info(
            "Unregistered ChunkStorageManager for world '{ }' from command system", worldName);
      }
    }
  }

  /** Show storage statistics. */
  private static int showStats(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    if (STORAGE_MANAGERS.isEmpty()) {
      source.sendSuccess(() -> Component.literal("§cNo chunk storage managers registered"), false);
      return 0;
    }

    source.sendSuccess(() -> Component.literal("§6=== Chunk Storage Statistics ==="), false);

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

        source.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        pattern,
                        worldName,
                        Stats.getStatus(),
                        Stats.getTotalChunksInDb(),
                        Stats.getCachedChunks(),
                        Stats.getAvgReadLatencyMs(),
                        Stats.getAvgWriteLatencyMs(),
                        Stats.getCacheHitRate() * 100,
                        Stats.getOverallHitRate() * 100)),
            false);
      } else {
        source.sendSuccess(
            () -> Component.literal(String.format("§bWorld: §f%s §7[DISABLED]", worldName)), false);
      }
    }

    return 1;
  }

  /** Clear all caches. */
  private static int clearCache(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    int clearedCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().clearCache();
        clearedCount++;
      } catch (Exception e) {
        LOGGER.warn("Failed to clear cache for world { }: { }", entry.getKey(), e.getMessage());
      }
    }

    final int finalCleared = clearedCount;
    source.sendSuccess(
        () -> Component.literal(String.format("§aCleared caches for §f%d §aworlds", finalCleared)),
        false);

    return 1;
  }

  /** Perform maintenance on all storage managers. */
  private static int performMaintenance(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    source.sendSuccess(() -> Component.literal("§6Performing storage maintenance..."), false);

    int maintainedCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      String worldName = entry.getKey();
      ChunkStorageManager manager = entry.getValue();

      try {
        manager.performMaintenance();
        maintainedCount++;
        source.sendSuccess(
            () ->
                Component.literal(
                    String.format("§aMaintenance completed for world '%s'", worldName)),
            false);
      } catch (Exception e) {
        source.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "§cMaintenance failed for world '%s': %s", worldName, e.getMessage())),
            false);
        LOGGER.error("Maintenance failed for world '{ }'", worldName, e);
      }
    }

    final int finalMaintainedCount = maintainedCount;
    source.sendSuccess(
        () ->
            Component.literal(
                String.format("§aMaintenance completed for §f%d §aworlds", finalMaintainedCount)),
        false);

    return 1;
  }

  /** Create backups for all storage managers. */
  private static int createBackup(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    source.sendSuccess(() -> Component.literal("§6Creating backups..."), false);

    int backupCount = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      String worldName = entry.getKey();
      ChunkStorageManager manager = entry.getValue();

      try {
        String backupPath =
            String.format("backups/chunkstorage/%s_%d", worldName, System.currentTimeMillis());
        manager.createBackup(backupPath);
        backupCount++;
        source.sendSuccess(
            () ->
                Component.literal(
                    String.format(
                        "§aBackup created for world '%s' at '%s'", worldName, backupPath)),
            false);
      } catch (Exception e) {
        source.sendSuccess(
            () ->
                Component.literal(
                    String.format("§cBackup failed for world '%s': %s", worldName, e.getMessage())),
            false);
        LOGGER.error("Backup failed for world '{ }'", worldName, e);
      }
    }

    final int finalBackupCount = backupCount;
    source.sendSuccess(
        () ->
            Component.literal(
                String.format("§aBackups created for §f%d §aworlds", finalBackupCount)),
        false);

    return 1;
  }

  /** Set cache capacity for all storage managers. */
  private static int setCacheCapacity(CommandContext<CommandSourceStack> context, int capacity) {
    CommandSourceStack source = context.getSource();

    int applied = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().setCacheCapacity(capacity);
        applied++;
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to set cache capacity for world { }: { }", entry.getKey(), e.getMessage());
      }
    }

    final int finalApplied = applied;
    source.sendSuccess(
        () ->
            Component.literal(
                String.format(
                    "§aCache capacity set to §f%d §afor %d worlds", capacity, finalApplied)),
        false);

    return 1;
  }

  /** Set eviction policy for all storage managers. */
  private static int setEvictionPolicy(CommandContext<CommandSourceStack> context, String policy) {
    CommandSourceStack source = context.getSource();

    // Validate policy
    if (!policy.equalsIgnoreCase("LRU")
        && !policy.equalsIgnoreCase("Distance")
        && !policy.equalsIgnoreCase("Hybrid")) {
      source.sendSuccess(
          () ->
              Component.literal(
                  String.format(
                      "§cInvalid eviction policy '%s'. Valid policies: LRU, Distance, Hybrid",
                      policy)),
          false);
      return 0;
    }

    int applied = 0;
    for (Map.Entry<String, ChunkStorageManager> entry : STORAGE_MANAGERS.entrySet()) {
      try {
        entry.getValue().setEvictionPolicy(policy);
        applied++;
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to set eviction policy for world { }: { }", entry.getKey(), e.getMessage());
      }
    }

    final int finalApplied2 = applied;
    source.sendSuccess(
        () ->
            Component.literal(
                String.format(
                    "§aEviction policy set to §f%s §afor %d worlds", policy, finalApplied2)),
        false);

    return 1;
  }

  /** Show help information. */
  private static int showHelp(CommandContext<CommandSourceStack> context) {
    CommandSourceStack source = context.getSource();

    source.sendSuccess(() -> Component.literal("§6=== Chunk Cache Command Help ==="), false);
    source.sendSuccess(
        () -> Component.literal("§b/chunkcache Stats §7- Show storage statistics"), false);
    source.sendSuccess(() -> Component.literal("§b/chunkcache clear §7- Clear all caches"), false);
    source.sendSuccess(
        () -> Component.literal("§b/chunkcache maintenance §7- Perform storage maintenance"),
        false);
    source.sendSuccess(() -> Component.literal("§b/chunkcache backup §7- Create backups"), false);
    source.sendSuccess(
        () -> Component.literal("§b/chunkcache config capacity <size> §7- Set cache capacity"),
        false);
    source.sendSuccess(
        () ->
            Component.literal(
                String.format(
                    "§b/chunkcache config %s <LRU|Distance|Hybrid> §7- Set eviction policy",
                    ARG_POLICY)),
        false);
    source.sendSuccess(() -> Component.literal("§b/chunkcache help §7- Show this help"), false);

    return 1;
  }
}
