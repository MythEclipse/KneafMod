package com.kneaf.core.chunkstorage.database;

import com.kneaf.core.chunkstorage.common.StorageStatisticsProvider;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract database adapter interface for chunk storage operations.
 * Supports multiple database backends (Sled, RocksDB, LMDB) with async operations.
 */
public interface DatabaseAdapter extends StorageStatisticsProvider {
    
    /**
     * Store a chunk in the database.
     * 
     * @param key The chunk key (format: "world:x:z" or similar)
     * @param data The serialized chunk data
     * @throws IOException if storage fails
     */
    void putChunk(String key, byte[] data) throws IOException;
    
    /**
     * Store a chunk in the database asynchronously.
     * 
     * @param key The chunk key
     * @param data The serialized chunk data
     * @return CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> putChunkAsync(String key, byte[] data);
    
    /**
     * Retrieve a chunk from the database.
     * 
     * @param key The chunk key
     * @return Optional containing the chunk data if found, empty otherwise
     * @throws IOException if retrieval fails
     */
    Optional<byte[]> getChunk(String key) throws IOException;
    
    /**
     * Retrieve a chunk from the database asynchronously.
     * 
     * @param key The chunk key
     * @return CompletableFuture containing the optional chunk data
     */
    CompletableFuture<Optional<byte[]>> getChunkAsync(String key);
    
    /**
     * Delete a chunk from the database.
     * 
     * @param key The chunk key
     * @return true if the chunk was deleted, false if it didn't exist
     * @throws IOException if deletion fails
     */
    boolean deleteChunk(String key) throws IOException;
    
    /**
     * Delete a chunk from the database asynchronously.
     * 
     * @param key The chunk key
     * @return CompletableFuture containing true if deleted, false if didn't exist
     */
    CompletableFuture<Boolean> deleteChunkAsync(String key);
    
    /**
     * Check if a chunk exists in the database.
     * 
     * @param key The chunk key
     * @return true if the chunk exists, false otherwise
     * @throws IOException if check fails
     */
    boolean hasChunk(String key) throws IOException;
    
    /**
     * Get the number of chunks stored in the database.
     * 
     * @return The count of stored chunks
     * @throws IOException if count fails
     */
    long getChunkCount() throws IOException;
    
    /**
     * Perform database maintenance (compaction, cleanup, etc.).
     * 
     * @throws IOException if maintenance fails
     */
    void performMaintenance() throws IOException;
    
    /**
     * Close the database and release resources.
     * 
     * @throws IOException if close fails
     */
    void close() throws IOException;
    
    /**
     * Get the database type identifier.
     * 
     * @return Database type string (e.g., "rocksdb", "sled", "lmdb")
     */
    String getDatabaseType();
    
    /**
     * Check if the database is healthy and operational.
     * 
     * @return true if healthy, false otherwise
     */
    boolean isHealthy();
    
    /**
     * Create a backup of the database.
     * 
     * @param backupPath Path where the backup should be stored
     * @throws IOException if backup fails
     */
    void createBackup(String backupPath) throws IOException;
    
    /**
     * Perform bulk swap-out operations for multiple chunks.
     * 
     * @param chunkKeys List of chunk keys to swap out
     * @return Number of successfully swapped chunks
     */
    default int bulkSwapOut(java.util.List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return 0;
        }
        
        int successCount = 0;
        for (String chunkKey : chunkKeys) {
            try {
                if (swapOutChunk(chunkKey)) {
                    successCount++;
                }
            } catch (Exception e) {
                // Log and continue with other chunks
            }
        }
        return successCount;
    }
    
    /**
     * Perform bulk swap-in operations for multiple chunks.
     * 
     * @param chunkKeys List of chunk keys to swap in
     * @return List of successfully swapped chunk data
     */
    default java.util.List<byte[]> bulkSwapIn(java.util.List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        
        java.util.List<byte[]> results = new java.util.ArrayList<>();
        for (String chunkKey : chunkKeys) {
            try {
                java.util.Optional<byte[]> data = swapInChunk(chunkKey);
                if (data.isPresent()) {
                    results.add(data.get());
                }
            } catch (Exception e) {
                // Log and continue with other chunks
            }
        }
        return results;
    }
    
    /**
     * Swap out a chunk to secondary storage.
     * 
     * @param chunkKey The chunk key to swap out
     * @return true if successful, false otherwise
     * @throws IOException if swap fails
     */
    boolean swapOutChunk(String chunkKey) throws IOException;
    
    /**
     * Swap in a chunk from secondary storage.
     * 
     * @param chunkKey The chunk key to swap in
     * @return Optional containing the chunk data if found
     * @throws IOException if swap fails
     */
    Optional<byte[]> swapInChunk(String chunkKey) throws IOException;
}