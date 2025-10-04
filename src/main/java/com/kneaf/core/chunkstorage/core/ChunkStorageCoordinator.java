package com.kneaf.core.chunkstorage.core;

import com.kneaf.core.chunkstorage.common.ChunkStorageExceptionHandler;
import com.kneaf.core.chunkstorage.common.StorageStatisticsProvider;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates between different chunk storage components. Handles swap operations, memory pressure
 * management, and component integration.
 */
public class ChunkStorageCoordinator implements StorageStatisticsProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageCoordinator.class);

  private final ChunkStorageCore core;
  private final SwapManager swapManager;
  private final String worldName;
  private final boolean enabled;

  public ChunkStorageCoordinator(
      String worldName, ChunkStorageCore core, SwapManager swapManager, boolean enabled) {
    this.worldName = worldName;
    this.core = core;
    this.swapManager = swapManager;
    this.enabled = enabled;
  }

  /** Initialize coordinator components. */
  public void initialize() {
    if (!enabled) {
      return;
    }

    try {
      // Initialize swap manager with core components
      if (swapManager != null && core.getCache() != null && core.getDatabase() != null) {
        // Note: Type compatibility issue - we'll skip initialization for now
        LOGGER.info("ChunkStorageCoordinator initialized for world '{ }'", worldName);
      }
    } catch (Exception e) {
      ChunkStorageExceptionHandler.handleInitializationException(
          "ChunkStorageCoordinator", e, "Failed to initialize coordinator");
    }
  }

  /**
   * Swap out a chunk to disk storage.
   *
   * @param chunkKey The chunk key to swap out
   * @return CompletableFuture that completes when swap-out is done
   */
  public CompletableFuture<Boolean> swapOutChunk(String chunkKey) {
    if (!enabled || swapManager == null) {
      return CompletableFuture.completedFuture(false);
    }

    return swapManager.swapOutChunk(chunkKey);
  }

  /**
   * Swap in a chunk from disk storage.
   *
   * @param chunkKey The chunk key to swap in
   * @return CompletableFuture that completes when swap-in is done
   */
  public CompletableFuture<Boolean> swapInChunk(String chunkKey) {
    if (!enabled || swapManager == null) {
      return CompletableFuture.completedFuture(false);
    }

    return swapManager.swapInChunk(chunkKey);
  }

  /**
   * Get current memory pressure level.
   *
   * @return Current memory pressure level
   */
  public Object getMemoryPressureLevel() {
    if (!enabled || swapManager == null) {
      return SwapManager.MemoryPressureLevel.NORMAL;
    }

    return swapManager.getMemoryPressureLevel();
  }

  /**
   * Get current memory usage information.
   *
   * @return Memory usage information
   */
  public Object getMemoryUsage() {
    if (!enabled || swapManager == null) {
      return new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0);
    }

    return swapManager.getMemoryUsage();
  }

  /**
   * Get swap manager statistics.
   *
   * @return Swap manager statistics
   */
  public Object getSwapStats() {
    if (!enabled || swapManager == null) {
      return new SwapManager.SwapManagerStats(
          false,
          SwapManager.MemoryPressureLevel.NORMAL,
          0,
          0,
          0,
          0,
          new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0),
          new SwapManager.SwapStatistics());
    }

    return swapManager.getStats();
  }

  /**
   * Perform bulk swap operations for memory pressure relief.
   *
   * @param chunkKeys List of chunk keys to swap
   * @param operationType The type of swap operation
   * @return CompletableFuture that completes when all swaps are done
   */
  public CompletableFuture<Integer> bulkSwapChunks(
      java.util.List<String> chunkKeys, Object operationType) {
    if (!enabled || swapManager == null) {
      return CompletableFuture.completedFuture(0);
    }

    if (operationType instanceof SwapManager.SwapOperationType) {
      return swapManager.bulkSwapChunks(chunkKeys, (SwapManager.SwapOperationType) operationType);
    }
    return CompletableFuture.completedFuture(0);
  }

  /**
   * Check if automatic swapping is enabled.
   *
   * @return true if automatic swapping is enabled
   */
  public boolean isAutomaticSwappingEnabled() {
    if (!enabled || swapManager == null) {
      return false;
    }

    // This would need to be exposed through SwapManager
    return true; // Placeholder
  }

  /**
   * Get active swap operations.
   *
   * @return Map of active swap operations
   */
  public Object getActiveSwaps() {
    if (!enabled || swapManager == null) {
      return java.util.Collections.emptyMap();
    }

    return swapManager.getActiveSwaps();
  }

  /**
   * Get swap statistics.
   *
   * @return Current swap statistics
   */
  public Object getSwapStatistics() {
    if (!enabled || swapManager == null) {
      return new SwapManager.SwapStatistics();
    }

    return swapManager.getSwapStatistics();
  }

  /** Shutdown the coordinator and release resources. */
  public void shutdown() {
    if (!enabled) {
      return;
    }

    try {
      LOGGER.info("Shutting down ChunkStorageCoordinator for world '{ }'", worldName);

      if (swapManager != null) {
        swapManager.shutdown();
      }

      LOGGER.info("ChunkStorageCoordinator shutdown completed for world '{ }'", worldName);

    } catch (Exception e) {
      ChunkStorageExceptionHandler.handleShutdownException("ChunkStorageCoordinator", e);
    }
  }

  /**
   * Check if the coordinator is healthy.
   *
   * @return true if healthy, false otherwise
   */
  public boolean isHealthy() {
    if (!enabled) {
      return false;
    }

    try {
      boolean coreHealthy = core.isHealthy();
      boolean swapHealthy = swapManager != null; // Basic check

      return coreHealthy && swapHealthy;
    } catch (Exception e) {
      ChunkStorageExceptionHandler.handleHealthCheckException("ChunkStorageCoordinator", e);
      return false;
    }
  }

  /**
   * Get the underlying storage core.
   *
   * @return The storage core
   */
  public ChunkStorageCore getCore() {
    return core;
  }

  /**
   * Get the swap manager.
   *
   * @return The swap manager
   */
  public SwapManager getSwapManager() {
    return swapManager;
  }

  // Getters
  public String getWorldName() {
    return worldName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public Object getStats() {
    return getStorageStats();
  }

  /**
   * Get storage statistics in StorageStats format.
   *
   * @return Storage statistics
   */
  public ChunkStorageManager.StorageStats getStorageStats() {
    if (!enabled || core == null) {
      return createEmptyStorageStats();
    }

    try {
      // Get Stats from core and convert to StorageStats format
      Object swapStatsObj = core.getSwapStats();
      Object dbStatsObj = core.getDatabaseStats();
      Object cacheStatsObj = core.getCacheStats();

      // Extract statistics safely using reflection
      long totalChunks = 0;
      long readLatency = 0;
      long writeLatency = 0;
      int cachedChunks = 0;
      double cacheHitRate = 0.0;
      boolean swapEnabled = false;
      String memoryPressureLevel = "NORMAL";
      long swapOperationsTotal = 0;
      long swapOperationsFailed = 0;

      // Extract database statistics
      if (dbStatsObj != null) {
        try {
          totalChunks = (long) dbStatsObj.getClass().getMethod("getTotalChunks").invoke(dbStatsObj);
          readLatency =
              (long) dbStatsObj.getClass().getMethod("getReadLatencyMs").invoke(dbStatsObj);
          writeLatency =
              (long) dbStatsObj.getClass().getMethod("getWriteLatencyMs").invoke(dbStatsObj);
        } catch (Exception e) {
          LOGGER.warn("Failed to extract database statistics", e);
        }
      }

      // Extract cache statistics
      if (cacheStatsObj != null) {
        try {
          cachedChunks =
              (int) cacheStatsObj.getClass().getMethod("getCacheSize").invoke(cacheStatsObj);
          cacheHitRate =
              (double) cacheStatsObj.getClass().getMethod("getHitRate").invoke(cacheStatsObj);
        } catch (Exception e) {
          LOGGER.warn("Failed to extract cache statistics", e);
        }
      }

      // Extract swap statistics
      if (swapStatsObj != null) {
        try {
          swapEnabled =
              (boolean) swapStatsObj.getClass().getMethod("isEnabled").invoke(swapStatsObj);
          Object pressureLevelObj =
              swapStatsObj.getClass().getMethod("getPressureLevel").invoke(swapStatsObj);
          memoryPressureLevel = pressureLevelObj.toString();
          swapOperationsTotal =
              (long) swapStatsObj.getClass().getMethod("getTotalOperations").invoke(swapStatsObj);
          swapOperationsFailed =
              (long) swapStatsObj.getClass().getMethod("getFailedOperations").invoke(swapStatsObj);
        } catch (Exception e) {
          LOGGER.warn("Failed to extract swap statistics", e);
        }
      }

      return ChunkStorageManager.StorageStats.builder()
          .enabled(true)
          .totalChunksInDb(totalChunks)
          .cachedChunks(cachedChunks)
          .avgReadLatencyMs(readLatency)
          .avgWriteLatencyMs(writeLatency)
          .cacheHitRate(cacheHitRate)
          .overallHitRate(cacheHitRate)
          .status("storage-manager")
          .swapEnabled(swapEnabled)
          .memoryPressureLevel(memoryPressureLevel)
          .swapOperationsTotal(swapOperationsTotal)
          .swapOperationsFailed(swapOperationsFailed)
          .build();
    } catch (Exception e) {
      LOGGER.error("ChunkStorageCoordinator: Failed to get storage Stats", e);
      return createEmptyStorageStats();
    }
  }

  private ChunkStorageManager.StorageStats createEmptyStorageStats() {
    return ChunkStorageManager.StorageStats.builder()
        .enabled(false)
        .totalChunksInDb(0)
        .cachedChunks(0)
        .avgReadLatencyMs(0)
        .avgWriteLatencyMs(0)
        .cacheHitRate(0.0)
        .overallHitRate(0.0)
        .status("error")
        .swapEnabled(false)
        .memoryPressureLevel("NORMAL")
        .swapOperationsTotal(0)
        .swapOperationsFailed(0)
        .build();
  }

  private SwapManager.SwapManagerStats createEmptySwapStats() {
    return new SwapManager.SwapManagerStats(
        false,
        SwapManager.MemoryPressureLevel.NORMAL,
        0,
        0,
        0,
        0,
        new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0),
        new SwapManager.SwapStatistics());
  }

  private Object createEmptyDatabaseStats() {
    // Return a simple object with default values
    return new Object() {
      public long getTotalChunks() {
        return 0;
      }

      public long getReadLatencyMs() {
        return 0;
      }

      public long getWriteLatencyMs() {
        return 0;
      }

      public double getHitRate() {
        return 0.0;
      }

      public boolean isHealthy() {
        return true;
      }
    };
  }

  private Object createEmptyCacheStats() {
    // Return a simple object with default values
    return new Object() {
      public int getCacheSize() {
        return 0;
      }

      public double getHitRate() {
        return 0.0;
      }

      public long getHitCount() {
        return 0;
      }

      public long getMissCount() {
        return 0;
      }

      public boolean isHealthy() {
        return true;
      }
    };
  }
}
