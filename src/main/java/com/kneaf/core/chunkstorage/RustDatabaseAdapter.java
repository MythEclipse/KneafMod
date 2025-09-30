package com.kneaf.core.chunkstorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * High-performance Rust-based database adapter implementation.
 * Provides native performance with checksum validation and thread-safe operations.
 */
public class RustDatabaseAdapter implements DatabaseAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustDatabaseAdapter.class);
    
    /**
     * Exception thrown for Rust database operation failures.
     */
    public static class RustDatabaseException extends RuntimeException {
        public RustDatabaseException(String message) {
            super(message);
        }
        
        public RustDatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    private final long nativePointer;
    private final String databaseType;
    private final boolean checksumEnabled;
    
    // Load native library
    static {
        try {
            System.loadLibrary("rustperf");
            LOGGER.info("Rust database library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            throw new RustDatabaseException("Failed to load Rust database library: " + e.getMessage(), e);
        }
    }
    
    /**
     * Creates a new Rust database adapter.
     * 
     * @param databaseType The type of database (e.g., "memory", "sled", "rocksdb")
     * @param checksumEnabled Whether to enable checksum validation
     */
    public RustDatabaseAdapter(String databaseType, boolean checksumEnabled) {
        this.databaseType = databaseType;
        this.checksumEnabled = checksumEnabled;
        this.nativePointer = nativeInit(databaseType, checksumEnabled);
        
        if (nativePointer == 0) {
            throw new RustDatabaseException("Failed to initialize Rust database adapter");
        }
        
        LOGGER.info("RustDatabaseAdapter initialized with type: {}, checksum: {}", 
                   databaseType, checksumEnabled);
    }
    
    @Override
    public void putChunk(String key, byte[] data) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        
        try {
            boolean success = nativePutChunk(nativePointer, key, data);
            if (!success) {
                throw new IOException("Failed to store chunk in Rust database");
            }
        } catch (Exception e) {
            throw new IOException("Rust database operation failed", e);
        }
    }
    
    @Override
    public Optional<byte[]> getChunk(String key) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        
        try {
            byte[] data = nativeGetChunk(nativePointer, key);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            throw new IOException("Rust database operation failed", e);
        }
    }
    
    @Override
    public boolean deleteChunk(String key) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        
        try {
            return nativeDeleteChunk(nativePointer, key);
        } catch (Exception e) {
            throw new IOException("Rust database operation failed", e);
        }
    }
    
    @Override
    public CompletableFuture<Boolean> deleteChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return deleteChunk(key);
            } catch (IOException e) {
                throw new RustDatabaseException("Async delete operation failed for key: " + key, e);
            }
        });
    }
    
    @Override
    public boolean hasChunk(String key) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        
        try {
            return nativeHasChunk(nativePointer, key);
        } catch (Exception e) {
            throw new IOException("Rust database operation failed", e);
        }
    }
    
    @Override
    public long getChunkCount() throws IOException {
        try {
            return nativeGetChunkCount(nativePointer);
        } catch (Exception e) {
            throw new IOException("Failed to get chunk count: " + e.getMessage(), e);
        }
    }
    
    @Override
    public DatabaseStats getStats() throws IOException {
        try {
            return nativeGetStats(nativePointer);
        } catch (Exception e) {
            throw new IOException("Failed to get database statistics", e);
        }
    }
    
    @Override
    public void performMaintenance() throws IOException {
        try {
            boolean success = nativePerformMaintenance(nativePointer);
            if (!success) {
                throw new IOException("Failed to perform maintenance on Rust database");
            }
        } catch (Exception e) {
            throw new IOException("Rust database maintenance failed", e);
        }
    }
    
    @Override
    public void createBackup(String backupPath) throws IOException {
        if (backupPath == null || backupPath.isEmpty()) {
            throw new IllegalArgumentException("Backup path cannot be null or empty");
        }
        
        try {
            boolean success = nativeCreateBackup(nativePointer, backupPath);
            if (!success) {
                throw new IOException("Failed to create backup of Rust database");
            }
        } catch (Exception e) {
            throw new IOException("Rust database backup failed", e);
        }
    }
    
    @Override
    public String getDatabaseType() {
        try {
            return nativeGetDatabaseType(nativePointer);
        } catch (Exception e) {
            LOGGER.error("Failed to get database type from Rust", e);
            return "unknown";
        }
    }
    
    @Override
    public boolean isHealthy() {
        try {
            return nativeIsHealthy(nativePointer);
        } catch (Exception e) {
            LOGGER.error("Failed to check health status of Rust database", e);
            return false;
        }
    }
    
    @Override
    public void close() throws IOException {
        if (nativePointer != 0) {
            nativeDestroy(nativePointer);
            LOGGER.info("RustDatabaseAdapter closed");
        }
    }
    
    @Override
    public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
        return CompletableFuture.runAsync(() -> {
            try {
                putChunk(key, data);
            } catch (IOException e) {
                throw new RustDatabaseException("Async put operation failed for key: " + key, e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getChunk(key);
            } catch (IOException e) {
                throw new RustDatabaseException("Async get operation failed for key: " + key, e);
            }
        });
    }
    
    /**
     * Gets the checksum status of this adapter.
     * 
     * @return true if checksum validation is enabled
     */
    public boolean isChecksumEnabled() {
        return checksumEnabled;
    }
    
    // Native method declarations
    private native long nativeInit(String databaseType, boolean checksumEnabled);
    private native boolean nativePutChunk(long nativePointer, String key, byte[] data);
    private native byte[] nativeGetChunk(long nativePointer, String key);
    private native boolean nativeDeleteChunk(long nativePointer, String key);
    private native boolean nativeHasChunk(long nativePointer, String key);
    private native long nativeGetChunkCount(long nativePointer);
    private native DatabaseStats nativeGetStats(long nativePointer);
    private native boolean nativePerformMaintenance(long nativePointer);
    private native boolean nativeCreateBackup(long nativePointer, String backupPath);
    private native String nativeGetDatabaseType(long nativePointer);
    private native boolean nativeIsHealthy(long nativePointer);
    private native void nativeDestroy(long nativePointer);
    
    @Override
    public String toString() {
        return String.format("RustDatabaseAdapter[type=%s, checksum=%s, healthy=%s]", 
                           databaseType, checksumEnabled, isHealthy());
    }
}