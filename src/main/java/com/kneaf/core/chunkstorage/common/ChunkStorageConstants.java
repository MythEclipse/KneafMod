package com.kneaf.core.chunkstorage.common;

/** Constants used throughout the chunk storage system. */
public final class ChunkStorageConstants {

  private ChunkStorageConstants() {
    // Private constructor to prevent instantiation
  }

  // Memory pressure thresholds (as percentages of max heap)
  public static final double CRITICAL_MEMORY_THRESHOLD = 0.95; // 95% of max heap
  public static final double HIGH_MEMORY_THRESHOLD = 0.85; // 85% of max heap
  public static final double ELEVATED_MEMORY_THRESHOLD = 0.75; // 75% of max heap
  public static final double NORMAL_MEMORY_THRESHOLD = 0.60; // 60% of max heap

  // Swap configuration defaults
  public static final long DEFAULT_SWAP_MEMORY_CHECK_INTERVAL_MS = 5000; // 5 seconds
  public static final int DEFAULT_MAX_CONCURRENT_SWAPS = 10;
  public static final int DEFAULT_SWAP_BATCH_SIZE = 50;
  public static final long DEFAULT_SWAP_TIMEOUT_MS = 30000; // 30 seconds
  public static final int DEFAULT_MIN_SWAP_CHUNK_AGE_MS = 60000; // 1 minute

  // Cache configuration defaults
  public static final int DEFAULT_CACHE_CAPACITY = 1000;
  public static final String DEFAULT_EVICTION_POLICY = "LRU";
  public static final int DEFAULT_ASYNC_THREAD_POOL_SIZE = 4;

  // Database configuration defaults
  public static final String DEFAULT_DATABASE_TYPE = "rust";
  public static final boolean DEFAULT_USE_RUST_DATABASE = true;
  public static final boolean DEFAULT_ENABLE_CHECKSUMS = true;

  // Backup configuration defaults
  public static final boolean DEFAULT_ENABLE_BACKUPS = true;
  public static final String DEFAULT_BACKUP_PATH = "backups/chunkstorage";
  public static final int DEFAULT_MAX_BACKUP_FILES = 10;
  public static final long DEFAULT_BACKUP_RETENTION_DAYS = 7;
  public static final long DEFAULT_MAINTENANCE_INTERVAL_MINUTES = 60;

  // Chunk key format patterns
  public static final String CHUNK_KEY_SEPARATOR = ":";
  public static final String CHUNK_KEY_FORMAT = "%s:%d:%d";
  public static final String UNKNOWN_CHUNK_KEY_FORMAT = "%s:unknown";

  // Thread naming patterns
  public static final String THREAD_NAME_PREFIX_CHUNK_STORAGE = "chunk-storage-";
  public static final String THREAD_NAME_SWAP_WORKER = "swap-worker";
  public static final String THREAD_NAME_SWAP_MONITOR = "swap-monitor";

  // Timing constants
  public static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
  public static final long MONITOR_SHUTDOWN_TIMEOUT_SECONDS = 10;
  public static final long FAST_PATH_SWAP_OUT_NANOS = 25_000_000L; // 25ms
  public static final long FAST_PATH_SWAP_IN_NANOS = 12_000_000L; // 12ms

  // Chunk size estimates
  public static final long ESTIMATED_CHUNK_SIZE_BYTES = 16 * 1024; // 16KB estimate

  // Serialization constants
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final int SERIALIZED_CHUNK_SIZE = 1024; // Placeholder size for tests

  // Minecraft class names (for reflection)
  public static final String MINECRAFT_LEVEL_CHUNK_CLASS =
      "net.minecraft.world.level.chunk.LevelChunk";
  public static final String MINECRAFT_COMPOUND_TAG_CLASS = "net.minecraft.nbt.CompoundTag";

  // Status strings
  public static final String STATUS_DISABLED = "disabled";
  public static final String STATUS_ERROR = "error";
  public static final String STATUS_STORAGE_MANAGER = "storage-manager";
  public static final String STATUS_NORMAL = "NORMAL";

  // Eviction policy names
  public static final String EVICTION_POLICY_LRU = "LRU";
  public static final String EVICTION_POLICY_DISTANCE = "Distance";
  public static final String EVICTION_POLICY_HYBRID = "Hybrid";
  public static final String EVICTION_POLICY_SWAP_AWARE = "SwapAware";
}
