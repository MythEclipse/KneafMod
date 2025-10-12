package com.kneaf.core.chunkstorage.core;

import com.kneaf.core.chunkstorage.cache.ChunkCache;
import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import com.kneaf.core.chunkstorage.common.StorageStatisticsProvider;
import com.kneaf.core.chunkstorage.database.DatabaseAdapter;
import com.kneaf.core.chunkstorage.database.InMemoryDatabaseAdapter;
import com.kneaf.core.chunkstorage.database.RustDatabaseAdapter;
import com.kneaf.core.chunkstorage.serialization.ChunkSerializer;
import com.kneaf.core.chunkstorage.serialization.FastNbtSerializer;
import com.kneaf.core.chunkstorage.serialization.NbtChunkSerializer;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FACADE for chunk storage operations that coordinates between core components. This class provides
 * a simplified interface to the complex chunk storage subsystem.
 */
public class ChunkStorageManager implements StorageStatisticsProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageManager.class);

  private final String worldName;
  private final ChunkStorageCore core;
  private final ChunkStorageCoordinator coordinator;
  private final ChunkStorageConfigManager configManager;
  private final boolean enabled;
  private volatile boolean shutdown = false;

  /**
   * Create a new ChunkStorageManager FACADE.
   *
   * @param worldName The world name for this storage manager
   * @param config The configuration for this storage manager
   */
  public ChunkStorageManager(String worldName, ChunkStorageConfig config) {
    if (worldName == null || worldName.isEmpty()) {
      throw new IllegalArgumentException("World name cannot be null or empty");
    }
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }

    this.worldName = worldName;
    this.enabled = config.isEnabled();

    if (enabled) {
      // Initialize configuration manager
      this.configManager = new ChunkStorageConfigManager(config);
      this.configManager.validateConfiguration();

      // Initialize core components
      ChunkSerializer serializer = createSerializer(config);
      DatabaseAdapter database = createDatabaseAdapter(worldName, config);
      com.kneaf.core.chunkstorage.cache.ChunkCache cache = createCache(config);
      SwapManager swapManager = createSwapManager(config);

      this.core = new ChunkStorageCore(worldName, serializer, database, cache, config.isEnabled());

      // Wire the swap manager into the core so core can request swap-in operations
      if (swapManager != null) {
        try {
          this.core.setSwapManager(swapManager);
        } catch (Exception e) {
          LOGGER.warn("Failed to set SwapManager on ChunkStorageCore: {}", e.getMessage());
        }
      }

      this.coordinator =
          swapManager != null
              ? new ChunkStorageCoordinator(worldName, core, swapManager, config.isEnabled())
              : null;

      LOGGER.info(
          "Initialized ChunkStorageManager FACADE for world '{}' with config: {}",
          worldName,
          configManager.getConfigurationSummary());
    } else {
      // Disabled mode - use no-op implementations
      this.configManager = null;
      this.core = null;
      this.coordinator = null;
      LOGGER.info("ChunkStorageManager FACADE disabled for world '{}'", worldName);
    }
  }

  /** Clear the in-memory cache for this storage manager. */
  public void clearCache() {
    if (!enabled || shutdown || core == null) {
      return;
    }

    try {
      core.clearCache();
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to clear cache for world '" + worldName + "'", e);
    }
  }

  /** Set cache capacity (best-effort). */
  public void setCacheCapacity(int capacity) {
    if (!enabled || shutdown || core == null) {
      return;
    }

    try {
      // Best-effort: if core exposes a method to update cache capacity in future, call it.
      // For now, update configuration via coordinator if available.
      // No-op fallback to keep compatibility.
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to set cache capacity for world '" + worldName + "'", e);
    }
  }

  /** Set eviction policy (best-effort). */
  public void setEvictionPolicy(String policy) {
    if (!enabled || shutdown || core == null) {
      return;
    }

    try {
      // Best-effort no-op for now; the cache implementation would need to expose
      // a runtime eviction policy switch to make this effective.
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to set eviction policy for world '" + worldName + "'", e);
    }
  }

  /**
   * Save a chunk to storage (cache + database).
   *
   * @param chunk The chunk to save
   * @return CompletableFuture that completes when the save is done
   */
  public CompletableFuture<Void> saveChunk(Object chunk) {
    if (!enabled || shutdown || core == null) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      return core.saveChunk(chunk);
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to save chunk", e);
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Load a chunk from storage (cache first, then database, with swap support).
   *
   * @param level The server level
   * @param chunkX The chunk X coordinate
   * @param chunkZ The chunk Z coordinate
   * @return Optional containing the loaded chunk data if found
   */
  public Optional<Object> loadChunk(Object level, int chunkX, int chunkZ) {
    if (!enabled || shutdown || core == null) {
      return Optional.empty();
    }

    try {
      return core.loadChunk(level, chunkX, chunkZ);
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to load chunk at (" + chunkX + ", " + chunkZ + ")", e);
      return Optional.empty();
    }
  }

  /**
   * Load a chunk asynchronously.
   *
   * @param level The server level
   * @param chunkX The chunk X coordinate
   * @param chunkZ The chunk Z coordinate
   * @return CompletableFuture containing the optional chunk data
   */
  public CompletableFuture<Optional<Object>> loadChunkAsync(Object level, int chunkX, int chunkZ) {
    if (!enabled || shutdown || core == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    try {
      return CompletableFuture.supplyAsync(
          () -> core.loadChunk(level, chunkX, chunkZ),
          java.util.concurrent.Executors.newSingleThreadExecutor());
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to load chunk async at (" + chunkX + ", " + chunkZ + ")", e);
      return CompletableFuture.completedFuture(Optional.empty());
    }
  }

  /**
   * Delete a chunk from storage.
   *
   * @param level The server level
   * @param chunkX The chunk X coordinate
   * @param chunkZ The chunk Z coordinate
   * @return true if the chunk was deleted, false if it didn't exist
   */
  public boolean deleteChunk(Object level, int chunkX, int chunkZ) {
    if (!enabled || shutdown || core == null) {
      return false;
    }

    try {
      return core.deleteChunk(level, chunkX, chunkZ);
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to delete chunk at (" + chunkX + ", " + chunkZ + ")", e);
      return false;
    }
  }

  /**
   * Get storage statistics.
   *
   * @return StorageStats object containing performance metrics
   */
  @Override
  public Object getStats() {
    if (!enabled || shutdown || core == null) {
      return createDisabledStats();
    }

    try {
      return coordinator.getStats();
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to get storage Stats", e);
      return createErrorStats();
    }
  }

  /** Perform storage maintenance. */
  public void performMaintenance() {
    if (!enabled || shutdown || coordinator == null) {
      return;
    }

    try {
      LOGGER.info("Performing storage maintenance for world '{}' via FACADE", worldName);
      core.performMaintenance();
      LOGGER.info("Storage maintenance completed for world '{}' via FACADE", worldName);
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to perform storage maintenance for world '"
              + worldName
              + "'",
          e);
    }
  }

  /** Shutdown the storage manager and release resources. */
  public void shutdown() {
    if (!enabled || shutdown) {
      return;
    }

    shutdown = true;

    try {
      LOGGER.info("Shutting down ChunkStorageManager FACADE for world '{}'", worldName);

      if (core != null) {
        core.close();
      }

      LOGGER.info("ChunkStorageManager FACADE shutdown completed for world '{}'", worldName);

    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Error during shutdown of ChunkStorageManager FACADE for world '"
              + worldName
              + "'",
          e);
    }
  }

  /**
   * Check if the storage manager is healthy.
   *
   * @return true if healthy, false otherwise
   */
  public boolean isHealthy() {
    if (!enabled || shutdown || core == null) {
      return false;
    }

    try {
      return core.isHealthy();
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Health check failed for world '" + worldName + "'", e);
      return false;
    }
  }

  /**
   * Create a backup of the storage.
   *
   * @param backupPath Path where the backup should be stored
   */
  public void createBackup(String backupPath) {
    if (!enabled || shutdown || coordinator == null) {
      return;
    }

    try {
      LOGGER.info("Creating backup for world '{}' at '{}' via FACADE", worldName, backupPath);
      core.createBackup(backupPath);
      LOGGER.info("Backup completed for world '{}' via FACADE", worldName);
    } catch (Exception e) {
      LOGGER.error(
          "ChunkStorageManager: Failed to create backup for world '"
              + worldName
              + "' at '"
              + backupPath
              + "'",
          e);
    }
  }

  /**
   * Swap out a chunk to disk storage.
   *
   * @param chunkKey The chunk key to swap out
   * @return CompletableFuture that completes when swap-out is done
   */
  public CompletableFuture<Boolean> swapOutChunk(String chunkKey) {
    if (!enabled || shutdown || coordinator == null) {
      return CompletableFuture.completedFuture(false);
    }

    try {
      return coordinator != null
          ? coordinator.swapOutChunk(chunkKey)
          : CompletableFuture.completedFuture(false);
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to swap out chunk '" + chunkKey + "'", e);
      return CompletableFuture.completedFuture(false);
    }
  }

  /**
   * Swap in a chunk from disk storage.
   *
   * @param chunkKey The chunk key to swap in
   * @return CompletableFuture that completes when swap-in is done
   */
  public CompletableFuture<Boolean> swapInChunk(String chunkKey) {
    if (!enabled || shutdown || coordinator == null) {
      return CompletableFuture.completedFuture(false);
    }

    try {
      return coordinator != null
          ? coordinator.swapInChunk(chunkKey)
          : CompletableFuture.completedFuture(false);
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to swap in chunk '" + chunkKey + "'", e);
      return CompletableFuture.completedFuture(false);
    }
  }

  /**
   * Get swap manager statistics.
   *
   * @return Swap manager statistics
   */
  public Object getSwapStats() {
    if (!enabled || shutdown || coordinator == null) {
      return createEmptySwapStats();
    }

    try {
      Object swapStats = coordinator != null ? coordinator.getSwapStats() : createEmptySwapStats();
      return swapStats;
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to get swap Stats", e);
      return createEmptySwapStats();
    }
  }

  /**
   * Get current memory pressure level.
   *
   * @return Current memory pressure level
   */
  public Object getMemoryPressureLevel() {
    if (!enabled || shutdown || coordinator == null) {
      return SwapManager.MemoryPressureLevel.NORMAL;
    }

    try {
      return coordinator != null
          ? coordinator.getMemoryPressureLevel()
          : SwapManager.MemoryPressureLevel.NORMAL;
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to get memory pressure level", e);
      return SwapManager.MemoryPressureLevel.NORMAL;
    }
  }

  /**
   * Get current memory usage information.
   *
   * @return Memory usage information
   */
  public Object getMemoryUsage() {
    if (!enabled || shutdown || coordinator == null) {
      return createEmptyMemoryUsage();
    }

    try {
      return coordinator != null ? coordinator.getMemoryUsage() : createEmptyMemoryUsage();
    } catch (Exception e) {
      LOGGER.error("ChunkStorageManager: Failed to get memory usage", e);
      return createEmptyMemoryUsage();
    }
  }

  // Utility methods for creating components

  private ChunkSerializer createSerializer(ChunkStorageConfig config) {
    try {
      // Check if FastNBT is enabled and available
      if (config.isEnableFastNbt() && FastNbtSerializer.isFastNbtAvailable()) {
        LOGGER.info("Using FastNbtSerializer for chunk serialization");
        return new FastNbtSerializer();
      }
      // Fall back to standard NBT serializer if FastNBT is not available or disabled
      else if (NbtChunkSerializer.isMinecraftAvailable()) {
        LOGGER.info(
            "Using NbtChunkSerializer for chunk serialization (FastNBT not available or disabled)");
        return new NbtChunkSerializer();
      } else {
        LOGGER.warn(
            "Neither FastNBT nor standard NBT classes are available - serializer will be null");
        return null;
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to initialize serializer, will use fallback: {}", e.getMessage());

      // Try fallback to standard NBT if FastNBT initialization failed
      if (NbtChunkSerializer.isMinecraftAvailable()) {
        LOGGER.info("Falling back to NbtChunkSerializer after FastNBT initialization failed");
        return new NbtChunkSerializer();
      }

      LOGGER.error("All serialization options failed - serializer will be null");
      return null;
    }
  }

  private DatabaseAdapter createDatabaseAdapter(String worldName, ChunkStorageConfig config) {
    if (config.isUseRustDatabase()) {
      try {
        LOGGER.info(
            "Attempting to create RustDatabaseAdapter with type: {}, checksums: {}",
            config.getDatabaseType(),
            config.isEnableChecksums());
        return new RustDatabaseAdapter(config.getDatabaseType(), config.isEnableChecksums());
      } catch (Exception e) {
        LOGGER.error(
            "Failed to initialize Rust database adapter, falling back to in-memory database", e);
        return new InMemoryDatabaseAdapter("in-memory-" + worldName);
      }
    } else {
      return new InMemoryDatabaseAdapter("in-memory-" + worldName);
    }
  }

  private ChunkCache createCache(ChunkStorageConfig config) {
    ChunkCache.EvictionPolicy evictionPolicy;
    switch (config.getEvictionPolicy().toLowerCase()) {
      case "lru":
        evictionPolicy = new ChunkCache.LRUEvictionPolicy();
        break;
      case "distance":
        evictionPolicy = new ChunkCache.DistanceEvictionPolicy(0, 0);
        break;
      case "hybrid":
        evictionPolicy = new ChunkCache.HybridEvictionPolicy();
        break;
      default:
        evictionPolicy = new ChunkCache.LRUEvictionPolicy();
        LOGGER.warn("Unknown eviction policy '{}', defaulting to LRU", config.getEvictionPolicy());
    }

    return new ChunkCache(config.getCacheCapacity(), evictionPolicy);
  }

  private SwapManager createSwapManager(ChunkStorageConfig config) {
    if (config.isEnableSwapManager() && config.isUseRustDatabase()) {
      try {
        SwapManager.SwapConfig swapConfig = createSwapConfig(config);
        SwapManager swapManager = new SwapManager(swapConfig);
        LOGGER.info("Initialized SwapManager");
        return swapManager;
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to initialize SwapManager, disabling swap functionality: {}", e.getMessage());
        return null;
      }
    } else {
      LOGGER.info("SwapManager disabled");
      return null;
    }
  }

  private SwapManager.SwapConfig createSwapConfig(ChunkStorageConfig config) {
    SwapManager.SwapConfig swapConfig = new SwapManager.SwapConfig();
    swapConfig.setEnabled(config.isEnableSwapManager());
    swapConfig.setMemoryCheckIntervalMs(config.getSwapMemoryCheckIntervalMs());
    swapConfig.setMaxConcurrentSwaps(config.getMaxConcurrentSwaps());
    swapConfig.setSwapBatchSize(config.getSwapBatchSize());
    swapConfig.setSwapTimeoutMs(config.getSwapTimeoutMs());
    swapConfig.setEnableAutomaticSwapping(config.isEnableAutomaticSwapping());
    swapConfig.setCriticalMemoryThreshold(config.getCriticalMemoryThreshold());
    swapConfig.setHighMemoryThreshold(config.getHighMemoryThreshold());
    swapConfig.setElevatedMemoryThreshold(config.getElevatedMemoryThreshold());
    swapConfig.setMinSwapChunkAgeMs(config.getMinSwapChunkAgeMs());
    swapConfig.setEnableSwapStatistics(config.isEnableSwapStatistics());
    swapConfig.setEnablePerformanceMonitoring(true);
    return swapConfig;
  }

  // Helper methods for creating default Stats objects

  private StorageStats createDisabledStats() {
    return StorageStats.builder()
        .enabled(false)
        .totalChunksInDb(0)
        .cachedChunks(0)
        .avgReadLatencyMs(0)
        .avgWriteLatencyMs(0)
        .cacheHitRate(0.0)
        .overallHitRate(0.0)
        .status("disabled")
        .swapEnabled(false)
        .memoryPressureLevel("NORMAL")
        .swapOperationsTotal(0)
        .swapOperationsFailed(0)
        .build();
  }

  private StorageStats createErrorStats() {
    return StorageStats.builder()
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

  private Object createEmptySwapStats() {
    // Return a proper SwapManagerStats instance with default values
    SwapManager.MemoryUsageInfo memInfo = new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0);
    SwapManager.SwapStatistics swapStatistics = new SwapManager.SwapStatistics();
    return new SwapManager.SwapManagerStats(
        false, SwapManager.MemoryPressureLevel.NORMAL, 0L, 0L, 0, 0, memInfo, swapStatistics);
  }

  private Object createEmptyMemoryUsage() {
    // Return a proper MemoryUsageInfo object with zeroed values
    return new SwapManager.MemoryUsageInfo(0L, 0L, 0L, 0L, 0.0);
  }

  /** Storage statistics container. */
  public static class StorageStats {
    private final boolean enabled;
    private final long totalChunksInDb;
    private final int cachedChunks;
    private final long avgReadLatencyMs;
    private final long avgWriteLatencyMs;
    private final double cacheHitRate;
    private final double overallHitRate;
    private final String status;
    private final boolean swapEnabled;
    private final String memoryPressureLevel;
    private final long swapOperationsTotal;
    private final long swapOperationsFailed;

    private StorageStats(Builder builder) {
      this.enabled = builder.enabled;
      this.totalChunksInDb = builder.totalChunksInDb;
      this.cachedChunks = builder.cachedChunks;
      this.avgReadLatencyMs = builder.avgReadLatencyMs;
      this.avgWriteLatencyMs = builder.avgWriteLatencyMs;
      this.cacheHitRate = builder.cacheHitRate;
      this.overallHitRate = builder.overallHitRate;
      this.status = builder.status;
      this.swapEnabled = builder.swapEnabled;
      this.memoryPressureLevel = builder.memoryPressureLevel;
      this.swapOperationsTotal = builder.swapOperationsTotal;
      this.swapOperationsFailed = builder.swapOperationsFailed;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {
      private boolean enabled;
      private long totalChunksInDb;
      private int cachedChunks;
      private long avgReadLatencyMs;
      private long avgWriteLatencyMs;
      private double cacheHitRate;
      private double overallHitRate;
      private String status;
      private boolean swapEnabled;
      private String memoryPressureLevel;
      private long swapOperationsTotal;
      private long swapOperationsFailed;

      public Builder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
      }

      public Builder totalChunksInDb(long totalChunksInDb) {
        this.totalChunksInDb = totalChunksInDb;
        return this;
      }

      public Builder cachedChunks(int cachedChunks) {
        this.cachedChunks = cachedChunks;
        return this;
      }

      public Builder avgReadLatencyMs(long avgReadLatencyMs) {
        this.avgReadLatencyMs = avgReadLatencyMs;
        return this;
      }

      public Builder avgWriteLatencyMs(long avgWriteLatencyMs) {
        this.avgWriteLatencyMs = avgWriteLatencyMs;
        return this;
      }

      public Builder cacheHitRate(double cacheHitRate) {
        this.cacheHitRate = cacheHitRate;
        return this;
      }

      public Builder overallHitRate(double overallHitRate) {
        this.overallHitRate = overallHitRate;
        return this;
      }

      public Builder status(String status) {
        this.status = status;
        return this;
      }

      public Builder swapEnabled(boolean swapEnabled) {
        this.swapEnabled = swapEnabled;
        return this;
      }

      public Builder memoryPressureLevel(String memoryPressureLevel) {
        this.memoryPressureLevel = memoryPressureLevel;
        return this;
      }

      public Builder swapOperationsTotal(long swapOperationsTotal) {
        this.swapOperationsTotal = swapOperationsTotal;
        return this;
      }

      public Builder swapOperationsFailed(long swapOperationsFailed) {
        this.swapOperationsFailed = swapOperationsFailed;
        return this;
      }

      public StorageStats build() {
        return new StorageStats(this);
      }
    }

    public boolean isEnabled() {
      return enabled;
    }

    public long getTotalChunksInDb() {
      return totalChunksInDb;
    }

    public int getCachedChunks() {
      return cachedChunks;
    }

    public long getAvgReadLatencyMs() {
      return avgReadLatencyMs;
    }

    public long getAvgWriteLatencyMs() {
      return avgWriteLatencyMs;
    }

    public double getCacheHitRate() {
      return cacheHitRate;
    }

    public double getOverallHitRate() {
      return overallHitRate;
    }

    public String getStatus() {
      return status;
    }

    public boolean isSwapEnabled() {
      return swapEnabled;
    }

    public String getMemoryPressureLevel() {
      return memoryPressureLevel;
    }

    public long getSwapOperationsTotal() {
      return swapOperationsTotal;
    }

    public long getSwapOperationsFailed() {
      return swapOperationsFailed;
    }

    @Override
    public String toString() {
      return String.format(
          "StorageStats{enabled=%s, dbChunks=%d, cached=%d, "
              + "readLatency=%d ms, writeLatency=%d ms, cacheHitRate=%.2f%%, "
              + "overallHitRate=%.2f%%, status=%s, swapEnabled=%s, pressure=%s, "
              + "swapOps=%d, swapFailed=%d}",
          enabled,
          totalChunksInDb,
          cachedChunks,
          avgReadLatencyMs,
          avgWriteLatencyMs,
          cacheHitRate * 100,
          overallHitRate * 100,
          status,
          swapEnabled,
          memoryPressureLevel,
          swapOperationsTotal,
          swapOperationsFailed);
    }
  }
}
