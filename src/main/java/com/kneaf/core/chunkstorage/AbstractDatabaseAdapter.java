package com.kneaf.core.chunkstorage;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for database adapters that provides common validation logic
 * and utility methods to eliminate DRY violations across implementations.
 */
public abstract class AbstractDatabaseAdapter implements DatabaseAdapter {
    
    /**
     * Validates that a key is not null or empty.
     * 
     * @param key The key to validate
     * @throws IllegalArgumentException if key is null or empty
     */
    protected void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
    }
    
    /**
     * Validates that data is not null.
     * 
     * @param data The data to validate
     * @throws IllegalArgumentException if data is null
     */
    protected void validateData(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
    }
    
    /**
     * Validates that data is not null or empty.
     * 
     * @param data The data to validate
     * @throws IllegalArgumentException if data is null or empty
     */
    protected void validateDataNotEmpty(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
    }
    
    /**
     * Validates that a backup path is not null or empty.
     * 
     * @param backupPath The backup path to validate
     * @throws IllegalArgumentException if backup path is null or empty
     */
    protected void validateBackupPath(String backupPath) {
        if (backupPath == null || backupPath.isEmpty()) {
            throw new IllegalArgumentException("Backup path cannot be null or empty");
        }
    }
    
    @Override
    public abstract void putChunk(String key, byte[] data) throws IOException;
    
    @Override
    public abstract CompletableFuture<Void> putChunkAsync(String key, byte[] data);
    
    @Override
    public abstract Optional<byte[]> getChunk(String key) throws IOException;
    
    @Override
    public abstract CompletableFuture<Optional<byte[]>> getChunkAsync(String key);
    
    @Override
    public abstract boolean deleteChunk(String key) throws IOException;
    
    @Override
    public abstract CompletableFuture<Boolean> deleteChunkAsync(String key);
    
    @Override
    public abstract boolean hasChunk(String key) throws IOException;
    
    @Override
    public abstract long getChunkCount() throws IOException;
    
    @Override
    public abstract DatabaseStats getStats() throws IOException;
    
    @Override
    public abstract void performMaintenance() throws IOException;
    
    @Override
    public abstract void close() throws IOException;
    
    @Override
    public abstract String getDatabaseType();
    
    @Override
    public abstract boolean isHealthy();
    
    @Override
    public abstract void createBackup(String backupPath) throws IOException;
}