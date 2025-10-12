package com.kneaf.core.chunkstorage.core;

import com.kneaf.core.chunkstorage.cache.ChunkCache;
import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import com.kneaf.core.chunkstorage.common.ChunkStorageConstants;
import com.kneaf.core.chunkstorage.common.ChunkStorageExceptionHandler;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages configuration for chunk storage operations. Handles eviction policy creation and
 * configuration validation.
 */
public class ChunkStorageConfigManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageConfigManager.class);

  private final ChunkStorageConfig config;

  public ChunkStorageConfigManager(ChunkStorageConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("Config cannot be null");
    }
    this.config = config;
  }

  /**
   * Create eviction policy based on configuration.
   *
   * @return Configured eviction policy
   */
  public ChunkCache.EvictionPolicy createEvictionPolicy() {
    if (!config.isEnabled()) {
      return new ChunkCache.LRUEvictionPolicy(); // Default fallback
    }

    String evictionPolicy = config.getEvictionPolicy();
    if (evictionPolicy == null || evictionPolicy.isEmpty()) {
      evictionPolicy = ChunkStorageConstants.DEFAULT_EVICTION_POLICY;
    }

    switch (evictionPolicy.toLowerCase()) {
      case "lru":
        return new ChunkCache.LRUEvictionPolicy();
      case "distance":
        return new ChunkCache.DistanceEvictionPolicy(0, 0);
      case "hybrid":
        return new ChunkCache.HybridEvictionPolicy();
      case "swapaware":
        return new ChunkCache.SwapAwareEvictionPolicy(ChunkCache.MemoryPressureLevel.NORMAL);
      default:
        ChunkStorageExceptionHandler.logWarning(
            "ChunkStorageConfigManager",
            "Unknown eviction policy '{}', defaulting to LRU",
            evictionPolicy);
        return new ChunkCache.LRUEvictionPolicy();
    }
  }

  /**
   * Validate configuration settings.
   *
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validateConfiguration() {
    if (config.getCacheCapacity() <= 0) {
      throw new IllegalArgumentException("Cache capacity must be positive");
    }

    if (config.getAsyncThreadpoolSize() <= 0) {
      throw new IllegalArgumentException("Async thread pool size must be positive");
    }

    if (config.getMaintenanceIntervalMinutes() <= 0) {
      throw new IllegalArgumentException("Maintenance interval must be positive");
    }

    if (config.getMaxBackupFiles() <= 0) {
      throw new IllegalArgumentException("Max backup files must be positive");
    }

    if (config.getBackupRetentionDays() <= 0) {
      throw new IllegalArgumentException("Backup retention days must be positive");
    }

    if (config.getSwapMemoryCheckIntervalMs() <= 0) {
      throw new IllegalArgumentException("Swap memory check interval must be positive");
    }

    if (config.getMaxConcurrentSwaps() <= 0) {
      throw new IllegalArgumentException("Max concurrent swaps must be positive");
    }

    if (config.getSwapBatchSize() <= 0) {
      throw new IllegalArgumentException("Swap batch size must be positive");
    }

    if (config.getSwapTimeoutMs() <= 0) {
      throw new IllegalArgumentException("Swap timeout must be positive");
    }

    if (config.getMinSwapChunkAgeMs() <= 0) {
      throw new IllegalArgumentException("Min swap chunk age must be positive");
    }

    // Validate thresholds
    if (config.getCriticalMemoryThreshold() <= 0 || config.getCriticalMemoryThreshold() >= 1) {
      throw new IllegalArgumentException("Critical memory threshold must be between 0 and 1");
    }

    if (config.getHighMemoryThreshold() <= 0 || config.getHighMemoryThreshold() >= 1) {
      throw new IllegalArgumentException("High memory threshold must be between 0 and 1");
    }

    if (config.getElevatedMemoryThreshold() <= 0 || config.getElevatedMemoryThreshold() >= 1) {
      throw new IllegalArgumentException("Elevated memory threshold must be between 0 and 1");
    }

    // Validate threshold ordering
    if (config.getElevatedMemoryThreshold() >= config.getHighMemoryThreshold()) {
      throw new IllegalArgumentException("Elevated threshold must be less than high threshold");
    }

    if (config.getHighMemoryThreshold() >= config.getCriticalMemoryThreshold()) {
      throw new IllegalArgumentException("High threshold must be less than critical threshold");
    }

    // Validate string fields
    if (config.getEvictionPolicy() == null || config.getEvictionPolicy().isEmpty()) {
      throw new IllegalArgumentException("Eviction policy cannot be null or empty");
    }

    if (config.getBackupPath() == null || config.getBackupPath().isEmpty()) {
      throw new IllegalArgumentException("Backup path cannot be null or empty");
    }

    if (config.getDatabaseType() == null || config.getDatabaseType().isEmpty()) {
      throw new IllegalArgumentException("Database type cannot be null or empty");
    }

    LOGGER.info("Configuration validation passed for ChunkStorageConfig");
  }

  /**
   * Get the underlying configuration.
   *
   * @return The configuration
   */
  public ChunkStorageConfig getConfig() {
    return config;
  }

  /**
   * Check if swap manager should be enabled based on configuration.
   *
   * @return true if swap manager should be enabled
   */
  public boolean shouldEnableSwapManager() {
    return config.isEnabled() && config.isEnableSwapManager() && config.isUseRustDatabase();
  }

  /**
   * Create swap configuration from chunk storage config.
   *
   * @return Swap configuration
   */
  public SwapManager.SwapConfig createSwapConfig() {
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
    swapConfig.setEnablePerformanceMonitoring(true); // Always enable for integration

    return swapConfig;
  }

  /**
   * Create thread factory for async executor.
   *
   * @param worldName The world name for thread naming
   * @return Thread factory
   */
  public java.util.concurrent.ThreadFactory createThreadFactory(String worldName) {
    return new java.util.concurrent.ThreadFactory() {
      private final java.util.concurrent.atomic.AtomicInteger threadNumber =
          new java.util.concurrent.atomic.AtomicInteger(1);

      @Override
      public Thread newThread(Runnable r) {
        Thread thread =
            new Thread(
                r,
                ChunkStorageConstants.THREAD_NAME_PREFIX_CHUNK_STORAGE
                    + worldName
                    + "-"
                    + threadNumber.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    };
  }

  /**
   * Get a summary of the configuration.
   *
   * @return Configuration summary
   */
  public String getConfigurationSummary() {
    return String.format(
        "ChunkStorageConfig{enabled=%s, cacheCapacity=%d, evictionPolicy='%s', "
            + "asyncThreadpoolSize=%d, databaseType='%s', useRustDatabase=%s, "
            + "enableSwapManager=%s, maxConcurrentSwaps=%d, swapBatchSize=%d}",
        config.isEnabled(),
        config.getCacheCapacity(),
        config.getEvictionPolicy(),
        config.getAsyncThreadpoolSize(),
        config.getDatabaseType(),
        config.isUseRustDatabase(),
        config.isEnableSwapManager(),
        config.getMaxConcurrentSwaps(),
        config.getSwapBatchSize());
  }
}
