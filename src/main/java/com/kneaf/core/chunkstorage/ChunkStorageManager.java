package com.kneaf.core.chunkstorage;

import com.kneaf.core.chunkstorage.cache.ChunkCache;
import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import com.kneaf.core.chunkstorage.database.DatabaseAdapter;
import com.kneaf.core.chunkstorage.database.InMemoryDatabaseAdapter;
import com.kneaf.core.chunkstorage.database.RustDatabaseAdapter;
import com.kneaf.core.chunkstorage.serialization.ChunkSerializer;
import com.kneaf.core.chunkstorage.serialization.NbtChunkSerializer;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main manager for chunk storage operations. Coordinates between cache, database, and serialization
 * components. This implementation includes proper error handling for test environments.
 */
public class ChunkStorageManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageManager.class);

  // Pre-resolved MethodHandles for performance optimization
  private static final MethodHandle LEVEL_CHUNK_GET_POS;
  private static final MethodHandle CHUNK_POS_X;
  private static final MethodHandle CHUNK_POS_Z;
  private static final MethodHandle SERVER_LEVEL_DIMENSION;
  private static final MethodHandle DIMENSION_LOCATION;
  private static final MethodHandle STATS_GET_TOTAL_CHUNKS;
  private static final MethodHandle STATS_GET_READ_LATENCY_MS;
  private static final MethodHandle STATS_GET_WRITE_LATENCY_MS;
  private static final MethodHandle STATS_GET_CACHE_SIZE;
  private static final MethodHandle STATS_GET_HIT_RATE;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    MethodHandle levelChunkGetPos = null;
    MethodHandle chunkPosX = null;
    MethodHandle chunkPosZ = null;
    MethodHandle serverLevelDimension = null;
    MethodHandle dimensionLocation = null;
    MethodHandle statsGetTotalChunks = null;
    MethodHandle statsGetReadLatencyMs = null;
    MethodHandle statsGetWriteLatencyMs = null;
    MethodHandle statsGetCacheSize = null;
    MethodHandle statsGetHitRate = null;

    try {
      // Resolve LevelChunk methods
      Class<?> levelChunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
      levelChunkGetPos = lookup.findVirtual(levelChunkClass, "getPos", MethodType.methodType(Object.class));

      // Resolve ChunkPos methods
      Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos");
      chunkPosX = lookup.findVirtual(chunkPosClass, "x", MethodType.methodType(int.class));
      chunkPosZ = lookup.findVirtual(chunkPosClass, "z", MethodType.methodType(int.class));

      // Resolve ServerLevel methods
      Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
      serverLevelDimension = lookup.findVirtual(serverLevelClass, "dimension", MethodType.methodType(Object.class));

      // Resolve Dimension methods
      Class<?> dimensionClass = Class.forName("net.minecraft.world.level.dimension.Dimension");
      dimensionLocation = lookup.findVirtual(dimensionClass, "location", MethodType.methodType(Object.class));

      // Resolve stats methods (generic, will work with any stats object that has these methods)
      MethodType longReturn = MethodType.methodType(long.class);
      MethodType intReturn = MethodType.methodType(int.class);
      MethodType doubleReturn = MethodType.methodType(double.class);

      statsGetTotalChunks = lookup.findVirtual(Object.class, "getTotalChunks", longReturn);
      statsGetReadLatencyMs = lookup.findVirtual(Object.class, "getReadLatencyMs", longReturn);
      statsGetWriteLatencyMs = lookup.findVirtual(Object.class, "getWriteLatencyMs", longReturn);
      statsGetCacheSize = lookup.findVirtual(Object.class, "getCacheSize", intReturn);
      statsGetHitRate = lookup.findVirtual(Object.class, "getHitRate", doubleReturn);

    } catch (Exception e) {
      LOGGER.warn("Failed to resolve MethodHandles for Minecraft classes: {}", e.getMessage());
    }

    LEVEL_CHUNK_GET_POS = levelChunkGetPos;
    CHUNK_POS_X = chunkPosX;
    CHUNK_POS_Z = chunkPosZ;
    SERVER_LEVEL_DIMENSION = serverLevelDimension;
    DIMENSION_LOCATION = dimensionLocation;
    STATS_GET_TOTAL_CHUNKS = statsGetTotalChunks;
    STATS_GET_READ_LATENCY_MS = statsGetReadLatencyMs;
    STATS_GET_WRITE_LATENCY_MS = statsGetWriteLatencyMs;
    STATS_GET_CACHE_SIZE = statsGetCacheSize;
    STATS_GET_HIT_RATE = statsGetHitRate;
  }

  private final ChunkSerializer serializer;
  private final DatabaseAdapter database;
  private final ChunkCache cache;
  private final ExecutorService asyncExecutor;
  private final String worldName;
  private final boolean enabled;
  private final SwapManager swapManager;

  private volatile boolean shutdown = false;

  /**
   * Create a new ChunkStorageManager.
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
      // Initialize components
      ChunkSerializer tempSerializer = null;
      try {
        // Check if Minecraft classes are available before trying to create serializer
        if (NbtChunkSerializer.isMinecraftAvailable()) {
          tempSerializer = new NbtChunkSerializer();
          LOGGER.info("Using NbtChunkSerializer for chunk serialization");
        } else {
          LOGGER.warn("Minecraft classes not available - serializer will be null");
          tempSerializer = null;
        }
      } catch (Exception e) {
        LOGGER.warn(
            "Failed to initialize NbtChunkSerializer, serializer will be null: { }",
            e.getMessage());
        tempSerializer = null;
      }
      this.serializer = tempSerializer;

      // Initialize database based on configuration
      DatabaseAdapter tempDatabase = null;
      if (config.isUseRustDatabase()) {
        try {
          LOGGER.info(
              "Attempting to create RustDatabaseAdapter with type: { }, checksums: { }",
              config.getDatabaseType(),
              config.isEnableChecksums());
          LOGGER.info(
              "RustDatabaseAdapter.isNativeLibraryAvailable(): { }",
              RustDatabaseAdapter.isNativeLibraryAvailable());
          tempDatabase =
              new RustDatabaseAdapter(config.getDatabaseType(), config.isEnableChecksums());
          LOGGER.info("Successfully created Rust database adapter");
        } catch (Exception e) {
          // Fallback to in-memory database if Rust database fails to initialize
          LOGGER.error(
              "Failed to initialize Rust database adapter, falling back to in-memory database", e);
          LOGGER.warn("Exception details: { }: { }", e.getClass().getSimpleName(), e.getMessage());
          tempDatabase = new InMemoryDatabaseAdapter("in-memory-" + worldName);
          LOGGER.info("Using in-memory database adapter as fallback");
        }
      } else {
        tempDatabase = new InMemoryDatabaseAdapter("in-memory-" + worldName);
        LOGGER.info("Using in-memory database adapter");
      }
      this.database = tempDatabase;

      // Initialize cache with configured capacity and eviction policy
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
          LOGGER.warn(
              "Unknown eviction policy '{ }', defaulting to LRU", config.getEvictionPolicy());
      }

      this.cache = new ChunkCache(config.getCacheCapacity(), evictionPolicy);

      // Initialize async executor
      ThreadFactory threadFactory =
          new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
              Thread thread =
                  new Thread(
                      r, "chunk-storage-" + worldName + "-" + threadNumber.getAndIncrement());
              thread.setDaemon(true);
              return thread;
            }
          };

      this.asyncExecutor =
          Executors.newFixedThreadPool(config.getAsyncThreadpoolSize(), threadFactory);

      // Initialize swap manager if enabled
      SwapManager tempSwapManager = null;
      if (config.isEnableSwapManager()
          && config.isUseRustDatabase()
          && this.database instanceof RustDatabaseAdapter) {
        try {
          SwapManager.SwapConfig swapConfig = createSwapConfig(config);
          tempSwapManager = new SwapManager(swapConfig);
          LOGGER.info("Initialized SwapManager for world '{ }'", worldName);

          // Initialize swap manager components after database is ready
          tempSwapManager.initializeComponents(this.cache, (RustDatabaseAdapter) this.database);
        } catch (Exception e) {
          LOGGER.warn(
              "Failed to initialize SwapManager, disabling swap functionality: { }",
              e.getMessage());
          tempSwapManager = null;
        }
      } else {
        LOGGER.info("SwapManager disabled for world '{ }'", worldName);
      }
      this.swapManager = tempSwapManager;

      LOGGER.info(
          "Initialized ChunkStorageManager for world '{ }' with cache capacity { } and { } eviction policy",
          worldName,
          config.getCacheCapacity(),
          config.getEvictionPolicy());
    } else {
      // Disabled mode - use no-op implementations
      this.serializer = null;
      this.database = null;
      this.cache = null;
      this.asyncExecutor = null;
      this.swapManager = null;
      LOGGER.info("ChunkStorageManager disabled for world '{ }'", worldName);
    }
  }

  /**
   * Save a chunk to storage (cache + database).
   *
   * @param chunk The chunk to save
   * @return CompletableFuture that completes when the save is done
   */
  public CompletableFuture<Void> saveChunk(Object chunk) {
    if (!enabled || shutdown || serializer == null) {
      return CompletableFuture.completedFuture(null);
    }

    String chunkKey = createChunkKey(chunk);

    try {
      // Serialize the chunk
      byte[] serializedData = serializer.serialize(chunk);

      // Cache the chunk - handle both LevelChunk and Object types
      ChunkCache.CachedChunk evicted = null;
      try {
        // Try to cast to LevelChunk if Minecraft classes are available
        if (isMinecraftLevelChunk(chunk)) {
          // Cast to LevelChunk after checking
          Object levelChunk =
              Class.forName("net.minecraft.world.level.chunk.LevelChunk").cast(chunk);
          evicted =
              cache.putChunk(chunkKey, (net.minecraft.world.level.chunk.LevelChunk) levelChunk);
        } else {
          LOGGER.debug("Chunk object is not a LevelChunk, skipping cache for { }", chunkKey);
        }
      } catch (Exception e) {
        LOGGER.debug("Failed to cache chunk { }: { }", chunkKey, e.getMessage());
      }

      // Handle evicted chunk if needed (save to database if dirty)
      CompletableFuture<Void> evictedSave = CompletableFuture.completedFuture(null);
      if (evicted != null && evicted.isDirty()) {
        Object evictedChunk = evicted.getChunk();
        String evictedKey = createChunkKey(evictedChunk);
        evictedSave = saveChunkToDatabase(evictedKey, evictedChunk);
      }

      // Save current chunk to database asynchronously
      CompletableFuture<Void> currentSave = saveChunkToDatabase(chunkKey, serializedData);

      // Combine both operations
      return CompletableFuture.allOf(evictedSave, currentSave);

    } catch (Exception e) {
      LOGGER.error("Failed to save chunk { }", chunkKey, e);
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
    if (!enabled || shutdown || serializer == null) {
      return Optional.empty();
    }

    String chunkKey = createChunkKey(level, chunkX, chunkZ);

    try {
      // Try cache first
      Optional<ChunkCache.CachedChunk> cached = cache.getChunk(chunkKey);
      if (cached.isPresent()) {
        LOGGER.trace("Chunk { } found in cache", chunkKey);

        // Check if chunk is swapped out and needs to be swapped in
        if (cached.get().isSwapped() && swapManager != null) {
          LOGGER.debug("Chunk { } is swapped out, initiating swap-in", chunkKey);
          boolean swapSuccess = swapManager.swapInChunk(chunkKey).join();
          if (swapSuccess) {
            LOGGER.debug("Successfully swapped in chunk: { }", chunkKey);
            // Chunk should now be available in cache
            cached = cache.getChunk(chunkKey);
            if (cached.isPresent() && !cached.get().isSwapped()) {
              // Cached chunk is now available in memory. Prefer returning it directly
              // if it's already in a usable form (LevelChunk or CompoundTag). If it's a
              // serialized byte[] try to deserialize it. As a last resort attempt a
              // serialize->deserialize roundtrip to produce a CompoundTag-like object.
              try {
                Object cachedObj = cached.get().getChunk();

                if (cachedObj != null) {
                  // If it's a live LevelChunk, return it immediately
                  if (isMinecraftLevelChunk(cachedObj)) {
                    LOGGER.debug("Returning LevelChunk from cache for {}", chunkKey);
                    return Optional.of(cachedObj);
                  }

                  // If it's already an NBT CompoundTag, return that
                  if (isMinecraftCompoundTag(cachedObj)) {
                    LOGGER.debug("Returning CompoundTag from cache for {}", chunkKey);
                    return Optional.of(cachedObj);
                  }

                  // If cached object is a serialized byte[], attempt to deserialize
                  if (cachedObj instanceof byte[]) {
                    try {
                      Object deserialized = serializer.deserialize((byte[]) cachedObj);
                      if (deserialized != null) {
                        LOGGER.debug("Returning deserialized cached chunk for {}", chunkKey);
                        return Optional.of(deserialized);
                      }
                    } catch (Exception e) {
                      LOGGER.warn(
                          "Failed to deserialize cached byte[] for {}: {}",
                          chunkKey,
                          e.getMessage());
                    }
                  }

                  // As a last resort, try to serialize the cached object and then deserialize
                  // to get it into the expected CompoundTag form (if supported by serializer)
                  try {
                    byte[] roundtrip = serializer.serialize(cachedObj);
                    Object deserialized = serializer.deserialize(roundtrip);
                    if (deserialized != null) {
                      LOGGER.debug(
                          "Returning serialized->deserialized cached chunk for {}", chunkKey);
                      return Optional.of(deserialized);
                    }
                  } catch (Exception e) {
                    LOGGER.debug(
                        "Could not convert cached object to CompoundTag for {}: {}",
                        chunkKey,
                        e.getMessage());
                  }
                }
              } catch (Exception e) {
                LOGGER.warn(
                    "Error while returning cached chunk for {}: {}", chunkKey, e.getMessage());
              }
            }
          } else {
            LOGGER.warn("Failed to swap in chunk: { }", chunkKey);
          }
        }
      }

      // Try database
      Optional<byte[]> dbData = database.getChunk(chunkKey);
      if (dbData.isPresent()) {
        LOGGER.trace("Chunk { } found in database", chunkKey);
        try {
          Object chunkData = serializer.deserialize(dbData.get());

          // Try to cast to CompoundTag if Minecraft classes are available
          if (chunkData != null && isMinecraftCompoundTag(chunkData)) {
            // Cache the loaded chunk for future access
            // Note: We'd need to reconstruct the LevelChunk from the NBT data
            // For now, we just return the NBT data
            return Optional.of(chunkData);
          } else {
            LOGGER.warn("Deserialized data is not a valid CompoundTag for chunk { }", chunkKey);
            return Optional.empty();
          }
        } catch (Exception e) {
          LOGGER.error(
              "Failed to deserialize chunk data for { }: { }", chunkKey, e.getMessage(), e);
          return Optional.empty();
        }
      }

      LOGGER.trace("Chunk { } not found in storage", chunkKey);
      return Optional.empty();

    } catch (Exception e) {
      LOGGER.error("Failed to load chunk { }", chunkKey, e);
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
    if (!enabled || shutdown) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    return CompletableFuture.supplyAsync(() -> loadChunk(level, chunkX, chunkZ), asyncExecutor);
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
    if (!enabled || shutdown) {
      return false;
    }

    String chunkKey = createChunkKey(level, chunkX, chunkZ);

    try {
      // Remove from cache
      ChunkCache.CachedChunk removedFromCache = cache.removeChunk(chunkKey);

      // Remove from database
      boolean removedFromDb = database.deleteChunk(chunkKey);

      boolean deleted = removedFromCache != null || removedFromDb;

      if (deleted && LOGGER.isDebugEnabled()) {
        LOGGER.debug("Deleted chunk { } from storage", chunkKey);
      }

      return deleted;

    } catch (Exception e) {
      LOGGER.error("Failed to delete chunk { }", chunkKey, e);
      return false;
    }
  }

  /**
   * Get storage statistics.
   *
   * @return StorageStats object containing performance metrics
   */
  public StorageStats getStats() {
    if (!enabled) {
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

    try {
      Object dbStatsObj = database.getStats();
      Object cacheStatsObj = cache.getStats();

      // Get swap statistics if available
      SwapManager.MemoryPressureLevel pressureLevel = SwapManager.MemoryPressureLevel.NORMAL;
      long swapOperationsTotal = 0;
      long swapOperationsFailed = 0;
      boolean swapEnabled = false;

      if (swapManager != null) {
        SwapManager.SwapManagerStats swapStats = swapManager.getStats();
        pressureLevel = swapStats.getPressureLevel();
        swapOperationsTotal = swapStats.getTotalOperations();
        swapOperationsFailed = swapStats.getFailedOperations();
        swapEnabled = swapStats.isEnabled();
      }

      // Extract statistics safely from database and cache objects
      long totalChunks = 0;
      long readLatency = 0;
      long writeLatency = 0;
      int cachedChunks = 0;
      double cacheHitRate = 0.0;

      if (dbStatsObj != null) {
        // Try to extract database Stats using pre-resolved MethodHandles
        try {
          if (STATS_GET_TOTAL_CHUNKS != null) {
            totalChunks = (long) STATS_GET_TOTAL_CHUNKS.invoke(dbStatsObj);
          }
          if (STATS_GET_READ_LATENCY_MS != null) {
            readLatency = (long) STATS_GET_READ_LATENCY_MS.invoke(dbStatsObj);
          }
          if (STATS_GET_WRITE_LATENCY_MS != null) {
            writeLatency = (long) STATS_GET_WRITE_LATENCY_MS.invoke(dbStatsObj);
          }
        } catch (Throwable e) {
          LOGGER.warn("Failed to extract database statistics", e);
        }
      }

      if (cacheStatsObj != null) {
        // Try to extract cache Stats using pre-resolved MethodHandles
        try {
          if (STATS_GET_CACHE_SIZE != null) {
            cachedChunks = (int) STATS_GET_CACHE_SIZE.invoke(cacheStatsObj);
          }
          if (STATS_GET_HIT_RATE != null) {
            cacheHitRate = (double) STATS_GET_HIT_RATE.invoke(cacheStatsObj);
          }
        } catch (Throwable e) {
          LOGGER.warn("Failed to extract cache statistics", e);
        }
      }

      return StorageStats.builder()
          .enabled(true)
          .totalChunksInDb(totalChunks)
          .cachedChunks(cachedChunks)
          .avgReadLatencyMs(readLatency)
          .avgWriteLatencyMs(writeLatency)
          .cacheHitRate(cacheHitRate)
          .overallHitRate(cacheHitRate) // Using cache hit rate for overall hit rate
          .status("storage-manager")
          .swapEnabled(swapEnabled)
          .memoryPressureLevel(pressureLevel.toString())
          .swapOperationsTotal(swapOperationsTotal)
          .swapOperationsFailed(swapOperationsFailed)
          .build();

    } catch (Exception e) {
      LOGGER.error("Failed to get storage Stats", e);
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
  }

  /** Perform storage maintenance. */
  public void performMaintenance() {
    if (!enabled || shutdown) {
      return;
    }

    try {
      LOGGER.info("Performing storage maintenance for world '{ }'", worldName);

      // Perform database maintenance
      database.performMaintenance();

      // Reset cache statistics
      cache.resetStats();

      LOGGER.info("Storage maintenance completed for world '{ }'", worldName);

    } catch (Exception e) {
      LOGGER.error("Failed to perform storage maintenance for world '{ }'", worldName, e);
    }
  }

  /** Shutdown the storage manager and release resources. */
  public void shutdown() {
    if (!enabled || shutdown) {
      return;
    }

    shutdown = true;

    try {
      LOGGER.info("Shutting down ChunkStorageManager for world '{ }'", worldName);

      // Shutdown swap manager first
      if (swapManager != null) {
        swapManager.shutdown();
      }

      // Shutdown async executor
      shutdownExecutor();

      // Close database
      database.close();

      // Clear cache
      cache.clear();

      LOGGER.info("ChunkStorageManager shutdown completed for world '{ }'", worldName);

    } catch (Exception e) {
      LOGGER.error("Error during shutdown of ChunkStorageManager for world '{ }'", worldName, e);
    }
  }

  /** Shutdown the async executor. */
  private void shutdownExecutor() {
    asyncExecutor.shutdown();
    try {
      if (!asyncExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        asyncExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      asyncExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Check if the storage manager is healthy.
   *
   * @return true if healthy, false otherwise
   */
  public boolean isHealthy() {
    if (!enabled || shutdown) {
      return false;
    }

    try {
      return database.isHealthy() && !asyncExecutor.isShutdown();
    } catch (Exception e) {
      LOGGER.error("Health check failed for world '{ }'", worldName, e);
      return false;
    }
  }

  /**
   * Create a backup of the storage.
   *
   * @param backupPath Path where the backup should be stored
   */
  public void createBackup(String backupPath) {
    if (!enabled || shutdown) {
      return;
    }

    try {
      LOGGER.info("Creating backup for world '{ }' at '{ }'", worldName, backupPath);
      database.createBackup(backupPath);
      LOGGER.info("Backup completed for world '{ }'", worldName);
    } catch (Exception e) {
      LOGGER.error("Failed to create backup for world '{ }' at '{ }'", worldName, backupPath, e);
    }
  }

  /** Clear the in-memory cache for this storage manager (best-effort). */
  public void clearCache() {
    if (!enabled || shutdown) {
      return;
    }

    try {
      // If core exists, delegate; otherwise attempt to clear cache directly if available
      try {
        java.lang.reflect.Field coreField = this.getClass().getDeclaredField("core");
        coreField.setAccessible(true);
        Object coreObj = coreField.get(this);
        if (coreObj != null) {
          java.lang.reflect.Method m = coreObj.getClass().getMethod("clearCache");
          m.invoke(coreObj);
          return;
        }
      } catch (NoSuchFieldException nsf) {
        // ignore
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to clear cache via core delegation: { }", e.getMessage());
    }
  }

  /** Set cache capacity (best-effort no-op). */
  public void setCacheCapacity(int capacity) {
    if (!enabled || shutdown) {
      return;
    }

    try {
      boolean applied = cache.setMaxCapacity(capacity);
      if (applied) {
        LOGGER.info("Cache capacity changed to { } for world '{ }'", capacity, worldName);
      } else {
        LOGGER.warn(
            "Requested cache capacity change to { } for world '{ }' was rejected",
            capacity,
            worldName);
      }
    } catch (Exception e) {
      LOGGER.warn(
          "Failed to change cache capacity for world '{ }': { }", worldName, e.getMessage());
    }
  }

  /** Set eviction policy (best-effort no-op). */
  public void setEvictionPolicy(String policy) {
    if (!enabled || shutdown) {
      return;
    }

    try {
      boolean applied = cache.setEvictionPolicy(policy);
      if (applied) {
        LOGGER.info("Eviction policy changed to { } for world '{ }'", policy, worldName);
      } else {
        LOGGER.warn(
            "Requested eviction policy change to { } for world '{ }' was rejected",
            policy,
            worldName);
      }
    } catch (Exception e) {
      LOGGER.warn(
          "Failed to change eviction policy for world '{ }': { }", worldName, e.getMessage());
    }
  }

  /** Save chunk to database (helper method). */
  private CompletableFuture<Void> saveChunkToDatabase(String chunkKey, byte[] data) {
    return database
        .putChunkAsync(chunkKey, data)
        .exceptionally(
            throwable -> {
              LOGGER.error("Failed to save chunk { } to database", chunkKey, throwable);
              return null;
            });
  }

  /** Save chunk to database (helper method). */
  private CompletableFuture<Void> saveChunkToDatabase(String chunkKey, Object chunk) {
    if (serializer == null) {
      LOGGER.warn("Cannot serialize chunk { } - serializer is null", chunkKey);
      return CompletableFuture.completedFuture(null);
    }
    try {
      byte[] data = serializer.serialize(chunk);
      return saveChunkToDatabase(chunkKey, data);
    } catch (Exception e) {
      LOGGER.error("Failed to serialize chunk { } for database storage", chunkKey, e);
      return CompletableFuture.failedFuture(e);
    }
  }

  /** Create chunk key from LevelChunk. */
  private String createChunkKey(Object chunk) {
    try {
      // Use pre-resolved MethodHandles for performance
      if (LEVEL_CHUNK_GET_POS != null && CHUNK_POS_X != null && CHUNK_POS_Z != null) {
        Object chunkPos = LEVEL_CHUNK_GET_POS.invoke(chunk);
        int x = (int) CHUNK_POS_X.invoke(chunkPos);
        int z = (int) CHUNK_POS_Z.invoke(chunkPos);
        return String.format("%s:%d:%d", worldName, x, z);
      } else {
        // Fallback to reflection if MethodHandles not available
        Object chunkPos = chunk.getClass().getMethod("getPos").invoke(chunk);
        int x = (int) chunkPos.getClass().getMethod("x").invoke(chunkPos);
        int z = (int) chunkPos.getClass().getMethod("z").invoke(chunkPos);
        return String.format("%s:%d:%d", worldName, x, z);
      }
    } catch (Throwable e) {
      LOGGER.error("Failed to create chunk key from chunk object", e);
      return String.format("%s:unknown", worldName);
    }
  }

  /** Create chunk key from coordinates. */
  private String createChunkKey(Object level, int chunkX, int chunkZ) {
    try {
      // Use pre-resolved MethodHandles for performance
      if (SERVER_LEVEL_DIMENSION != null && DIMENSION_LOCATION != null) {
        Object dimension = SERVER_LEVEL_DIMENSION.invoke(level);
        Object location = DIMENSION_LOCATION.invoke(dimension);
        String dimensionName = location.toString();
        return String.format("%s:%d:%d", dimensionName, chunkX, chunkZ);
      } else {
        // Fallback to reflection if MethodHandles not available
        Object dimension = level.getClass().getMethod("dimension").invoke(level);
        Object location = dimension.getClass().getMethod("location").invoke(dimension);
        String dimensionName = location.toString();
        return String.format("%s:%d:%d", dimensionName, chunkX, chunkZ);
      }
    } catch (Throwable e) {
      LOGGER.error("Failed to create chunk key from level object", e);
      return String.format("unknown:%d:%d", chunkX, chunkZ);
    }
  }

  /** Check if the given object is a Minecraft LevelChunk. */
  private boolean isMinecraftLevelChunk(Object obj) {
    try {
      return obj != null
          && Class.forName("net.minecraft.world.level.chunk.LevelChunk").isInstance(obj);
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** Check if the given object is a Minecraft CompoundTag. */
  private boolean isMinecraftCompoundTag(Object obj) {
    try {
      return obj != null && Class.forName("net.minecraft.nbt.CompoundTag").isInstance(obj);
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Swap out a chunk to disk storage.
   *
   * @param chunkKey The chunk key to swap out
   * @return CompletableFuture that completes when swap-out is done
   */
  public CompletableFuture<Boolean> swapOutChunk(String chunkKey) {
    if (!enabled || shutdown || swapManager == null) {
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
    if (!enabled || shutdown || swapManager == null) {
      return CompletableFuture.completedFuture(false);
    }

    return swapManager.swapInChunk(chunkKey);
  }

  /**
   * Get swap manager statistics.
   *
   * @return Swap manager statistics
   */
  public SwapManager.SwapManagerStats getSwapStats() {
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
   * Get current memory pressure level.
   *
   * @return Current memory pressure level
   */
  public SwapManager.MemoryPressureLevel getMemoryPressureLevel() {
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
  public SwapManager.MemoryUsageInfo getMemoryUsage() {
    if (!enabled || swapManager == null) {
      return new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0);
    }

    return swapManager.getMemoryUsage();
  }

  /** Create swap configuration from chunk storage config. */
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
    swapConfig.setEnablePerformanceMonitoring(true); // Always enable for integration
    return swapConfig;
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
