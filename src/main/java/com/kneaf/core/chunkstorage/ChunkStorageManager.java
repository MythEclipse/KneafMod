package com.kneaf.core.chunkstorage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

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
 */
public class ChunkStorageManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
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
            this.serializer = new NbtChunkSerializer();
            
            // Initialize database based on configuration
            if (config.isUseRustDatabase()) {
                this.database = new RustDatabaseAdapter(config.getDatabaseType(), config.isEnableChecksums());
                LOGGER.info("Using Rust database adapter with type: {}", config.getDatabaseType());
            } else {
                this.database = new InMemoryDatabaseAdapter("in-memory-" + worldName);
                LOGGER.info("Using in-memory database adapter");
            }
            
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
            if (config.isEnableSwapManager() && config.isUseRustDatabase()) {
                SwapManager.SwapConfig swapConfig = createSwapConfig(config);
                this.swapManager = new SwapManager(swapConfig);
                LOGGER.info("Initialized SwapManager for world '{}'", worldName);
                
                // Initialize swap manager components after database is ready
                this.swapManager.initializeComponents(this.cache, (RustDatabaseAdapter) this.database);
            } else {
                this.swapManager = null;
                LOGGER.info("SwapManager disabled for world '{}'", worldName);
            }
            
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
    public CompletableFuture<Void> saveChunk(LevelChunk chunk) {
        if (!enabled || shutdown) {
            return CompletableFuture.completedFuture(null);
        }
        
        String chunkKey = createChunkKey(chunk);
        
        try {
            // Serialize the chunk
            byte[] serializedData = serializer.serialize(chunk);
            
            // Cache the chunk
            ChunkCache.CachedChunk evicted = cache.putChunk(chunkKey, chunk);
            
            // Handle evicted chunk if needed (save to database if dirty)
            CompletableFuture<Void> evictedSave = CompletableFuture.completedFuture(null);
            if (evicted != null && evicted.isDirty()) {
                String evictedKey = createChunkKey(evicted.getChunk());
                evictedSave = saveChunkToDatabase(evictedKey, evicted.getChunk());
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
    public Optional<CompoundTag> loadChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!enabled || shutdown) {
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
                CompoundTag chunkData = serializer.deserialize(dbData.get());
                
                // Cache the loaded chunk for future access
                // Note: We'd need to reconstruct the LevelChunk from the NBT data
                // For now, we just return the NBT data
                
                return Optional.of(chunkData);
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
    public CompletableFuture<Optional<CompoundTag>> loadChunkAsync(ServerLevel level, int chunkX, int chunkZ) {
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
    public boolean deleteChunk(ServerLevel level, int chunkX, int chunkZ) {
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
            DatabaseAdapter.DatabaseStats dbStats = database.getStats();
            ChunkCache.CacheStats cacheStats = cache.getStats();
            
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
            
            return StorageStats.builder()
                .enabled(true)
                .totalChunksInDb(dbStats.getTotalChunks())
                .cachedChunks(cacheStats.getCacheSize())
                .avgReadLatencyMs(dbStats.getReadLatencyMs())
                .avgWriteLatencyMs(dbStats.getWriteLatencyMs())
                .cacheHitRate(cacheStats.getHitRate())
                .overallHitRate(cacheStats.getHitRate()) // Using cache hit rate for overall hit rate
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
    private CompletableFuture<Void> saveChunkToDatabase(String chunkKey, LevelChunk chunk) {
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
    private String createChunkKey(LevelChunk chunk) {
        return String.format("%s:%d:%d", worldName, chunk.getPos().x, chunk.getPos().z);
    }
    
    /**
     * Create chunk key from coordinates.
     */
    private String createChunkKey(ServerLevel level, int chunkX, int chunkZ) {
        return String.format("%s:%d:%d", level.dimension().location().toString(), chunkX, chunkZ);
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