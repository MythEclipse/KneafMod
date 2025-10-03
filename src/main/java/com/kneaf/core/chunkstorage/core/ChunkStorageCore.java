package com.kneaf.core.chunkstorage.core;

import com.kneaf.core.chunkstorage.cache.ChunkCache;
import com.kneaf.core.chunkstorage.database.DatabaseAdapter;
import com.kneaf.core.chunkstorage.serialization.ChunkSerializer;
import com.kneaf.core.chunkstorage.common.ChunkStorageConstants;
import com.kneaf.core.chunkstorage.common.ChunkStorageExceptionHandler;
import com.kneaf.core.chunkstorage.common.ChunkStorageUtils;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Core logic for chunk storage operations.
 * Handles the fundamental save/load/delete operations without coordination concerns.
 */
public class ChunkStorageCore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageCore.class);
    
    private final ChunkSerializer serializer;
    private final DatabaseAdapter database;
    private final ChunkCache cache;
    private final SwapManager swapManager;
    private final String worldName;
    private final boolean enabled;
    
    public ChunkStorageCore(String worldName, ChunkSerializer serializer,
                           DatabaseAdapter database,
                           ChunkCache cache, boolean enabled) {
        this.worldName = worldName;
        this.serializer = serializer;
        this.database = database;
        this.cache = cache;
        this.swapManager = null; // Will be set later if needed
        this.enabled = enabled;
    }
    
    /**
     * Save a chunk to storage (cache + database).
     *
     * @param chunk The chunk to save
     * @return CompletableFuture that completes when the save is done
     */
    public CompletableFuture<Void> saveChunk(Object chunk) {
        if (!enabled || serializer == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        String chunkKey = ChunkStorageUtils.createChunkKeyFromChunk(chunk, worldName);
        
        try {
            // Serialize the chunk
            byte[] serializedData = serializer.serialize(chunk);
            
            // Cache the chunk - handle both LevelChunk and Object types
            ChunkCache.CachedChunk evicted = null;
            try {
                // Try to cast to LevelChunk if Minecraft classes are available
                if (ChunkStorageUtils.isMinecraftLevelChunk(chunk)) {
                    evicted = cache.putChunk(chunkKey, chunk);
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
                String evictedKey = ChunkStorageUtils.createChunkKeyFromChunk(evictedChunk, worldName);
                evictedSave = saveChunkToDatabase(evictedKey, evictedChunk);
            }
            
            // Save current chunk to database asynchronously
            CompletableFuture<Void> currentSave = saveChunkToDatabase(chunkKey, serializedData);
            
            // Combine both operations
            return CompletableFuture.allOf(evictedSave, currentSave);
            
        } catch (Exception e) {
            return ChunkStorageExceptionHandler.handleSaveException(chunkKey, e, "save");
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
        if (!enabled || serializer == null) {
            return Optional.empty();
        }
        
        String chunkKey = createChunkKey(level, chunkX, chunkZ);
        
        try {
            // Try cache first
            Optional<ChunkCache.CachedChunk> cached = cache.getChunk(chunkKey);
            if (cached.isPresent()) {
                LOGGER.trace("Chunk {} found in cache", chunkKey);
                
                // Check if chunk is swapped out and needs to be swapped in
                if (cached.get().isSwapped()) {
                    LOGGER.debug("Chunk {} is swapped out, initiating swap-in", chunkKey);
                    // Note: In a real implementation, we'd need to swap the chunk back in
                    // For now, we'll continue to database lookup
                }
            }
            
            // Try database
            Optional<byte[]> dbData = database.getChunk(chunkKey);
            if (dbData.isPresent()) {
                LOGGER.trace("Chunk {} found in database", chunkKey);
                try {
                    Object chunkData = serializer.deserialize(dbData.get());
                    
                    // Try to cast to CompoundTag if Minecraft classes are available
                    if (chunkData != null && ChunkStorageUtils.isMinecraftCompoundTag(chunkData)) {
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
            return ChunkStorageExceptionHandler.handleLoadException(chunkKey, e);
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
        if (!enabled) {
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
            return ChunkStorageExceptionHandler.handleDatabaseException("delete", chunkKey, e);
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
            return ChunkStorageUtils.failedFuture(e);
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
            return String.format(ChunkStorageConstants.CHUNK_KEY_FORMAT, dimensionName, chunkX, chunkZ);
        } catch (Exception e) {
            LOGGER.error("Failed to create chunk key from level object", e);
            return String.format("unknown:%d:%d", chunkX, chunkZ);
        }
    }
    
    /**
     * Check if the storage core is healthy.
     * 
     * @return true if healthy, false otherwise
     */
    public boolean isHealthy() {
        if (!enabled) {
            return false;
        }
        
        try {
            return database.isHealthy();
        } catch (Exception e) {
            ChunkStorageExceptionHandler.handleHealthCheckException("ChunkStorageCore", e);
            return false;
        }
    }
    
    /**
     * Create a backup of the storage.
     * 
     * @param backupPath Path where the backup should be stored
     */
    public void createBackup(String backupPath) {
        if (!enabled) {
            return;
        }
        
        try {
            LOGGER.info("Creating backup at '{}'", backupPath);
            database.createBackup(backupPath);
            LOGGER.info("Backup completed");
        } catch (Exception e) {
            ChunkStorageExceptionHandler.handleBackupException("ChunkStorageCore", backupPath, e);
        }
    }
    
    /**
     * Perform storage maintenance.
     */
    public void performMaintenance() {
        if (!enabled) {
            return;
        }
        
        try {
            LOGGER.info("Performing storage maintenance");
            database.performMaintenance();
            cache.resetStats();
            LOGGER.info("Storage maintenance completed");
            
        } catch (Exception e) {
            ChunkStorageExceptionHandler.handleMaintenanceException("ChunkStorageCore", e);
        }
    }
    
    /**
     * Clear all cached chunks.
     */
    public void clearCache() {
        if (!enabled) {
            return;
        }
        
        cache.clear();
    }
    
    /**
     * Close the storage core and release resources.
     */
    public void close() {
        if (!enabled) {
            return;
        }
        
        try {
            LOGGER.info("Closing ChunkStorageCore");
            database.close();
            cache.clear();
            LOGGER.info("ChunkStorageCore closed");
            
        } catch (Exception e) {
            ChunkStorageExceptionHandler.handleShutdownException("ChunkStorageCore", e);
        }
    }
    
    /**
     * Get cache statistics.
     *
     * @return Cache statistics
     */
    public Object getCacheStats() {
        return cache != null ? cache.getStats() : null;
    }
    
    /**
     * Get database statistics.
     *
     * @return Database statistics
     */
    public Object getDatabaseStats() {
        try {
            return database != null ? database.getStats() : null;
        } catch (Exception e) {
            ChunkStorageExceptionHandler.handleStatsException("ChunkStorageCore", e, null);
            return null;
        }
    }
    
    /**
     * Get swap manager.
     *
     * @return The swap manager
     */
    public SwapManager getSwapManager() {
        return swapManager;
    }
    
    /**
     * Get swap statistics.
     *
     * @return Swap statistics
     */
    public Object getSwapStats() {
        if (swapManager == null) {
            return null;
        }
        return swapManager.getStats();
    }
    
    // Getters
    public ChunkSerializer getSerializer() { return serializer; }
    public DatabaseAdapter getDatabase() { return database; }
    public ChunkCache getCache() { return cache; }
    public String getWorldName() { return worldName; }
    public boolean isEnabled() { return enabled; }
}