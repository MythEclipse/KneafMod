package com.kneaf.core.chunkstorage;

import com.kneaf.core.chunkstorage.cache.ChunkCache;
import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import com.kneaf.core.chunkstorage.database.DatabaseAdapter;
import com.kneaf.core.chunkstorage.database.InMemoryDatabaseAdapter;
import com.kneaf.core.chunkstorage.database.RustDatabaseAdapter;
import com.kneaf.core.chunkstorage.serialization.ChunkSerializer;
import com.kneaf.core.chunkstorage.serialization.NbtChunkSerializer;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main manager for chunk storage operations.
 * Coordinates between cache, database, and serialization components.
 * This implementation includes proper error handling for test environments.
 */
public class ChunkStorageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageManager.class);
    
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
                LOGGER.warn("Failed to initialize NbtChunkSerializer, serializer will be null: {}", e.getMessage());
                tempSerializer = null;
            }
            this.serializer = tempSerializer;
            
            // Initialize database based on configuration
            DatabaseAdapter tempDatabase = null;
            if (config.isUseRustDatabase()) {
                try {
                    LOGGER.info("Attempting to create RustDatabaseAdapter with type: {}, checksums: {}", config.getDatabaseType(), config.isEnableChecksums());
                    LOGGER.info("RustDatabaseAdapter.isNativeLibraryAvailable(): {}", RustDatabaseAdapter.isNativeLibraryAvailable());
                    tempDatabase = new RustDatabaseAdapter(config.getDatabaseType(), config.isEnableChecksums());
                    LOGGER.info("Successfully created Rust database adapter");
                } catch (Exception e) {
                    // Fallback to in-memory database if Rust database fails to initialize
                    LOGGER.error("Failed to initialize Rust database adapter, falling back to in-memory database", e);
                    LOGGER.warn("Exception details: {}: {}", e.getClass().getSimpleName(), e.getMessage());
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
                    LOGGER.warn("Unknown eviction policy '{}', defaulting to LRU", config.getEvictionPolicy());
            }
            
            this.cache = new ChunkCache(config.getCacheCapacity(), evictionPolicy);
            
            // Initialize async executor
            ThreadFactory threadFactory = new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "chunk-storage-" + worldName + "-" + threadNumber.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            };
            
            this.asyncExecutor = Executors.newFixedThreadPool(config.getAsyncThreadPoolSize(), threadFactory);
            
            // Initialize swap manager if enabled
            SwapManager tempSwapManager = null;
            if (config.isEnableSwapManager() && config.isUseRustDatabase() && this.database instanceof RustDatabaseAdapter) {
                try {
                    SwapManager.SwapConfig swapConfig = createSwapConfig(config);
                    tempSwapManager = new SwapManager(swapConfig);
                    LOGGER.info("Initialized SwapManager for world '{}'", worldName);
                    
                    // Initialize swap manager components after database is ready
                    tempSwapManager.initializeComponents(this.cache, (RustDatabaseAdapter) this.database);
                } catch (Exception e) {
                    LOGGER.warn("Failed to initialize SwapManager, disabling swap functionality: {}", e.getMessage());
                    tempSwapManager = null;
                }
            } else {
                LOGGER.info("SwapManager disabled for world '{}'", worldName);
            }
            this.swapManager = tempSwapManager;
            
            LOGGER.info("Initialized ChunkStorageManager for world '{}' with cache capacity {} and {} eviction policy",
                       worldName, config.getCacheCapacity(), config.getEvictionPolicy());
        } else {
            // Disabled mode - use no-op implementations
            this.serializer = null;
            this.database = null;
            this.cache = null;
            this.asyncExecutor = null;
            this.swapManager = null;
            LOGGER.info("ChunkStorageManager disabled for world '{}'", worldName);
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
                    Object levelChunk = Class.forName("net.minecraft.world.level.chunk.LevelChunk").cast(chunk);
                    evicted = cache.putChunk(chunkKey, (net.minecraft.world.level.chunk.LevelChunk) levelChunk);
                } else {
                    LOGGER.debug("Chunk object is not a LevelChunk, skipping cache for {}", chunkKey);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to cache chunk {}: {}", chunkKey, e.getMessage());
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
            LOGGER.error("Failed to save chunk {}", chunkKey, e);
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
                LOGGER.trace("Chunk {} found in cache", chunkKey);
                
                // Check if chunk is swapped out and needs to be swapped in
                if (cached.get().isSwapped() && swapManager != null) {
                    LOGGER.debug("Chunk {} is swapped out, initiating swap-in", chunkKey);
                    boolean swapSuccess = swapManager.swapInChunk(chunkKey).join();
                    if (swapSuccess) {
                        LOGGER.debug("Successfully swapped in chunk: {}", chunkKey);
                        // Chunk should now be available in cache
                        cached = cache.getChunk(chunkKey);
                        if (cached.isPresent() && !cached.get().isSwapped()) {
                            // Note: In a real implementation, we'd need to serialize the cached chunk back to NBT
                            // For now, we'll continue to database lookup
                        }
                    } else {
                        LOGGER.warn("Failed to swap in chunk: {}", chunkKey);
                    }
                }
            }
            
            // Try database
            Optional<byte[]> dbData = database.getChunk(chunkKey);
            if (dbData.isPresent()) {
                LOGGER.trace("Chunk {} found in database", chunkKey);
                try {
                    Object chunkData = serializer.deserialize(dbData.get());
                    
                    // Try to cast to CompoundTag if Minecraft classes are available
                    if (chunkData != null && isMinecraftCompoundTag(chunkData)) {
                        // Cache the loaded chunk for future access
                        // Note: We'd need to reconstruct the LevelChunk from the NBT data
                        // For now, we just return the NBT data
                        return Optional.of(chunkData);
                    } else {
                        LOGGER.warn("Deserialized data is not a valid CompoundTag for chunk {}", chunkKey);
                        return Optional.empty();
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to deserialize chunk data for {}: {}", chunkKey, e.getMessage(), e);
                    return Optional.empty();
                }
            }
            
            LOGGER.trace("Chunk {} not found in storage", chunkKey);
            return Optional.empty();
            
        } catch (Exception e) {
            LOGGER.error("Failed to load chunk {}", chunkKey, e);
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
                LOGGER.debug("Deleted chunk {} from storage", chunkKey);
            }
            
            return deleted;
            
        } catch (Exception e) {
            LOGGER.error("Failed to delete chunk {}", chunkKey, e);
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
                // Try to extract database stats using reflection
                try {
                    totalChunks = (long) dbStatsObj.getClass().getMethod("getTotalChunks").invoke(dbStatsObj);
                    readLatency = (long) dbStatsObj.getClass().getMethod("getReadLatencyMs").invoke(dbStatsObj);
                    writeLatency = (long) dbStatsObj.getClass().getMethod("getWriteLatencyMs").invoke(dbStatsObj);
                } catch (Exception e) {
                    LOGGER.warn("Failed to extract database statistics", e);
                }
            }
            
            if (cacheStatsObj != null) {
                // Try to extract cache stats using reflection
                try {
                    cachedChunks = (int) cacheStatsObj.getClass().getMethod("getCacheSize").invoke(cacheStatsObj);
                    cacheHitRate = (double) cacheStatsObj.getClass().getMethod("getHitRate").invoke(cacheStatsObj);
                } catch (Exception e) {
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
            LOGGER.error("Failed to get storage stats", e);
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
    
    /**
     * Perform storage maintenance.
     */
    public void performMaintenance() {
        if (!enabled || shutdown) {
            return;
        }
        
        try {
            LOGGER.info("Performing storage maintenance for world '{}'", worldName);
            
            // Perform database maintenance
            database.performMaintenance();
            
            // Reset cache statistics
            cache.resetStats();
            
            LOGGER.info("Storage maintenance completed for world '{}'", worldName);
            
        } catch (Exception e) {
            LOGGER.error("Failed to perform storage maintenance for world '{}'", worldName, e);
        }
    }
    
    /**
     * Shutdown the storage manager and release resources.
     */
    public void shutdown() {
        if (!enabled || shutdown) {
            return;
        }
        
        shutdown = true;
        
        try {
            LOGGER.info("Shutting down ChunkStorageManager for world '{}'", worldName);
            
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
            
            LOGGER.info("ChunkStorageManager shutdown completed for world '{}'", worldName);
            
        } catch (Exception e) {
            LOGGER.error("Error during shutdown of ChunkStorageManager for world '{}'", worldName, e);
        }
    }
    
    /**
     * Shutdown the async executor.
     */
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
            LOGGER.error("Health check failed for world '{}'", worldName, e);
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
            LOGGER.info("Creating backup for world '{}' at '{}'", worldName, backupPath);
            database.createBackup(backupPath);
            LOGGER.info("Backup completed for world '{}'", worldName);
        } catch (Exception e) {
            LOGGER.error("Failed to create backup for world '{}' at '{}'", worldName, backupPath, e);
        }
    }
    
    /**
     * Save chunk to database (helper method).
     */
    private CompletableFuture<Void> saveChunkToDatabase(String chunkKey, byte[] data) {
        return database.putChunkAsync(chunkKey, data)
            .exceptionally(throwable -> {
                LOGGER.error("Failed to save chunk {} to database", chunkKey, throwable);
                return null;
            });
    }
    
    /**
     * Save chunk to database (helper method).
     */
    private CompletableFuture<Void> saveChunkToDatabase(String chunkKey, Object chunk) {
        if (serializer == null) {
            LOGGER.warn("Cannot serialize chunk {} - serializer is null", chunkKey);
            return CompletableFuture.completedFuture(null);
        }
        try {
            byte[] data = serializer.serialize(chunk);
            return saveChunkToDatabase(chunkKey, data);
        } catch (Exception e) {
            LOGGER.error("Failed to serialize chunk {} for database storage", chunkKey, e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Create chunk key from LevelChunk.
     */
    private String createChunkKey(Object chunk) {
        try {
            // Use reflection to get chunk position
            Object chunkPos = chunk.getClass().getMethod("getPos").invoke(chunk);
            int x = (int) chunkPos.getClass().getMethod("x").invoke(chunkPos);
            int z = (int) chunkPos.getClass().getMethod("z").invoke(chunkPos);
            return String.format("%s:%d:%d", worldName, x, z);
        } catch (Exception e) {
            LOGGER.error("Failed to create chunk key from chunk object", e);
            return String.format("%s:unknown", worldName);
        }
    }
    
    /**
     * Create chunk key from coordinates.
     */
    private String createChunkKey(Object level, int chunkX, int chunkZ) {
        try {
            // Use reflection to get dimension location
            Object dimension = level.getClass().getMethod("dimension").invoke(level);
            Object location = dimension.getClass().getMethod("location").invoke(dimension);
            String dimensionName = location.toString();
            return String.format("%s:%d:%d", dimensionName, chunkX, chunkZ);
        } catch (Exception e) {
            LOGGER.error("Failed to create chunk key from level object", e);
            return String.format("unknown:%d:%d", chunkX, chunkZ);
        }
    }
    
    /**
     * Check if the given object is a Minecraft LevelChunk.
     */
    private boolean isMinecraftLevelChunk(Object obj) {
        try {
            return obj != null && Class.forName("net.minecraft.world.level.chunk.LevelChunk").isInstance(obj);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Check if the given object is a Minecraft CompoundTag.
     */
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
                false, SwapManager.MemoryPressureLevel.NORMAL, 0, 0, 0, 0,
                new SwapManager.MemoryUsageInfo(0, 0, 0, 0, 0.0),
                new SwapManager.SwapStatistics()
            );
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
    
    /**
     * Create swap configuration from chunk storage config.
     */
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
    
    /**
     * Storage statistics container.
     */
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
        
        public boolean isEnabled() { return enabled; }
        public long getTotalChunksInDb() { return totalChunksInDb; }
        public int getCachedChunks() { return cachedChunks; }
        public long getAvgReadLatencyMs() { return avgReadLatencyMs; }
        public long getAvgWriteLatencyMs() { return avgWriteLatencyMs; }
        public double getCacheHitRate() { return cacheHitRate; }
        public double getOverallHitRate() { return overallHitRate; }
        public String getStatus() { return status; }
        public boolean isSwapEnabled() { return swapEnabled; }
        public String getMemoryPressureLevel() { return memoryPressureLevel; }
        public long getSwapOperationsTotal() { return swapOperationsTotal; }
        public long getSwapOperationsFailed() { return swapOperationsFailed; }
        
        @Override
        public String toString() {
            return String.format("StorageStats{enabled=%s, dbChunks=%d, cached=%d, " +
                               "readLatency=%d ms, writeLatency=%d ms, cacheHitRate=%.2f%%, " +
                               "overallHitRate=%.2f%%, status=%s, swapEnabled=%s, pressure=%s, " +
                               "swapOps=%d, swapFailed=%d}",
                               enabled, totalChunksInDb, cachedChunks, avgReadLatencyMs,
                               avgWriteLatencyMs, cacheHitRate * 100, overallHitRate * 100, status,
                               swapEnabled, memoryPressureLevel, swapOperationsTotal, swapOperationsFailed);
        }
    }
}
