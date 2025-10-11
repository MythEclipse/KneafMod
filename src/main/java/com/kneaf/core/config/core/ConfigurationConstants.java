package com.kneaf.core.config.core;

/** Centralized configuration constants to reduce duplication across the config package. */
public final class ConfigurationConstants {

  private ConfigurationConstants() {} // Prevent instantiation

  // Common configuration file paths
  public static final String DEFAULT_CONFIG_PATH = "config/kneaf-core.properties";
  public static final String PERFORMANCE_CONFIG_PATH = "config/kneaf-performance.properties";

  // Common performance constants
  public static final double DEFAULT_TPS_THRESHOLD = 19.0;
  public static final double MIN_TPS_THRESHOLD = 10.0;
  public static final double MAX_TPS_THRESHOLD = 20.0;
  public static final int DEFAULT_LOG_INTERVAL_TICKS = 100;
  public static final long DEFAULT_MAX_LOG_BYTES = 10L * 1024 * 1024; // 10MB
  public static final long DEFAULT_SLOW_TICK_THRESHOLD_MS = 50L;
  public static final int DEFAULT_PROFILING_SAMPLE_RATE = 100;
  public static final double DEFAULT_DISTANCE_CUTOFF = 256.0;

  // Common thread pool constants
  public static final int DEFAULT_THREAD_POOL_SIZE = 4;
  public static final int DEFAULT_MIN_THREAD_POOL_SIZE = 2;
  public static final int DEFAULT_MAX_THREAD_POOL_SIZE =
      Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
  public static final int DEFAULT_NETWORK_EXECUTOR_POOL_SIZE =
      Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
  public static final int DEFAULT_THREAD_POOL_KEEP_ALIVE_SECONDS = 60;

  // Common storage constants
  public static final int DEFAULT_CACHE_CAPACITY = 1000;
  public static final String DEFAULT_EVICTION_POLICY = "LRU";
  public static final int DEFAULT_ASYNC_THREAD_POOL_SIZE = 4;
  public static final String DEFAULT_BACKUP_PATH = "backups/chunkstorage";
  public static final int DEFAULT_MAX_BACKUP_FILES = 10;
  public static final long DEFAULT_BACKUP_RETENTION_DAYS = 7;
  public static final long DEFAULT_MAINTENANCE_INTERVAL_MINUTES = 60;
  public static final String DEFAULT_DATABASE_TYPE = "rust";
  public static final boolean DEFAULT_USE_RUST_DATABASE = true;
  public static final boolean DEFAULT_ENABLE_CHECKSUMS = true;
  public static final boolean DEFAULT_ENABLE_COMPRESSION = false;
  public static final boolean DEFAULT_ENABLE_BACKUPS = true;

  // Common swap constants
  public static final long DEFAULT_SWAP_MEMORY_CHECK_INTERVAL_MS = 5000; // 5 seconds
  public static final int DEFAULT_MAX_CONCURRENT_SWAPS = 10;
  public static final int DEFAULT_SWAP_BATCH_SIZE = 50;
  public static final long DEFAULT_SWAP_TIMEOUT_MS = 30000; // 30 seconds
  public static final int DEFAULT_MIN_SWAP_CHUNK_AGE_MS = 60000; // 1 minute
  public static final double DEFAULT_CRITICAL_MEMORY_THRESHOLD = 0.85; // 85% of max heap
  public static final double DEFAULT_HIGH_MEMORY_THRESHOLD = 0.90; // 90% of max heap
  public static final double DEFAULT_ELEVATED_MEMORY_THRESHOLD = 0.95; // 95% of max heap

  // Common resource constants
  public static final int DEFAULT_RESOURCE_CLEANUP_INTERVAL_SECONDS = 300; // 5 minutes
  public static final int DEFAULT_RESOURCE_HEALTH_CHECK_INTERVAL_SECONDS = 60; // 1 minute
  public static final int DEFAULT_MAX_RESOURCE_AGE_MINUTES = 30;
  public static final boolean DEFAULT_RESOURCE_POOL_ENABLED = true;
  public static final int DEFAULT_RESOURCE_POOL_MAX_SIZE = 100;
  public static final int DEFAULT_RESOURCE_POOL_INITIAL_SIZE = 10;

  // Common boolean defaults
  public static final boolean DEFAULT_ENABLED = true;
  public static final boolean DEFAULT_PROFILING_ENABLED = true;
  public static final boolean DEFAULT_ADAPTIVE_THREAD_POOL = false;
  public static final boolean DEFAULT_DYNAMIC_THREAD_SCALING = true;
  public static final boolean DEFAULT_WORK_STEALING_ENABLED = true;
  public static final boolean DEFAULT_CPU_AWARE_THREAD_SIZING = false;
  public static final boolean DEFAULT_DISTANCE_APPROXIMATION_ENABLED = true;
  public static final boolean DEFAULT_INCREMENTAL_SPATIAL_UPDATES = true;
  public static final boolean DEFAULT_ENABLE_ASYNC_OPERATIONS = true;
  public static final boolean DEFAULT_ENABLE_SWAP_STATISTICS = true;
  public static final boolean DEFAULT_ENABLE_PERFORMANCE_MONITORING = true;
  public static final boolean DEFAULT_ENABLE_AUTOMATIC_SWAPPING = true;

  // Common threshold constants
  public static final double DEFAULT_THREAD_SCALE_UP_THRESHOLD = 0.8;
  public static final double DEFAULT_THREAD_SCALE_DOWN_THRESHOLD = 0.3;
  public static final double DEFAULT_CPU_LOAD_THRESHOLD = 0.7;

  // Common timing constants
  public static final int DEFAULT_THREAD_SCALE_UP_DELAY_TICKS = 100;
  public static final int DEFAULT_THREAD_SCALE_DOWN_DELAY_TICKS = 200;
  public static final int DEFAULT_WORK_STEALING_QUEUE_SIZE = 100;
  public static final int DEFAULT_DISTANCE_CALCULATION_INTERVAL = 1;
  public static final int DEFAULT_DISTANCE_CACHE_SIZE = 100;
  public static final int DEFAULT_ITEM_PROCESSING_INTERVAL_MULTIPLIER = 1;
  public static final int DEFAULT_SPATIAL_GRID_UPDATE_INTERVAL = 1;
  public static final int DEFAULT_SCAN_INTERVAL_TICKS = 1;
  public static final int DEFAULT_MAX_ENTITIES_TO_COLLECT = 20000;

  // Eviction policy names
  public static final String EVICTION_POLICY_LRU = "LRU";
  public static final String EVICTION_POLICY_DISTANCE = "Distance";
  public static final String EVICTION_POLICY_HYBRID = "Hybrid";
  public static final String EVICTION_POLICY_SWAP_AWARE = "SwapAware";
}
