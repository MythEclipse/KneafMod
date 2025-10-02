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
public class RustDatabaseAdapter extends AbstractDatabaseAdapter {
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
    private static volatile boolean nativeLibraryAvailable = false;
    
    // Load native library with proper error handling
    static {
        try {
            System.loadLibrary("rustperf");
            nativeLibraryAvailable = true;
            LOGGER.info("Rust database library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            // Attempt a few fallback locations for the native library so tests running in
            // different working directories or forked JVMs can still find the binary.
            boolean loaded = false;
            String[] fallbackPaths = new String[] {
                "run/rustperf.dll",
                "run/librustperf.dll",
                "rust/target/release/librustperf.dll",
                "target/release/librustperf.dll",
                "./rust/target/release/librustperf.dll",
                "./run/rustperf.dll"
            };

            for (String path : fallbackPaths) {
                try {
                    java.io.File f = new java.io.File(path);
                    if (f.exists()) {
                        System.load(f.getAbsolutePath());
                        loaded = true;
                        LOGGER.info("Loaded Rust native library from fallback path: {}", f.getAbsolutePath());
                        break;
                    }
                } catch (UnsatisfiedLinkError ule) {
                    LOGGER.debug("Fallback load failed for {}: {}", path, ule.getMessage());
                }
            }

            if (!loaded) {
                nativeLibraryAvailable = false;
                LOGGER.warn("Failed to load Rust database library: {}. Native operations will be disabled.", e.getMessage());
            } else {
                nativeLibraryAvailable = true;
            }
        }
    }
    
    /**
     * Check if the native library is available.
     * @return true if native library is loaded and available
     */
    public static boolean isNativeLibraryAvailable() {
        return nativeLibraryAvailable;
    }
    
    /**
     * Creates a new Rust database adapter.
     *
     * @param databaseType The type of database (e.g., "memory", "sled", "rocksdb")
     * @param checksumEnabled Whether to enable checksum validation
     * @throws RustDatabaseException if native library is not available or initialization fails
     */
    public RustDatabaseAdapter(String databaseType, boolean checksumEnabled) {
        if (!nativeLibraryAvailable) {
            throw new RustDatabaseException("Rust native library is not available. Cannot initialize RustDatabaseAdapter.");
        }
        
        this.databaseType = databaseType;
        this.checksumEnabled = checksumEnabled;
        // Try native initialization a few times to tolerate transient failures
        long ptr = 0;
        int attempts = 0;
        while (ptr == 0 && attempts < 3) {
            attempts++;
            ptr = nativeInit(databaseType, checksumEnabled);
            if (ptr == 0) {
                try {
                    Thread.sleep(50 * attempts);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        this.nativePointer = ptr;

        if (nativePointer == 0) {
            throw new RustDatabaseException("Failed to initialize Rust database adapter");
        }
        
        LOGGER.info("RustDatabaseAdapter initialized with type: {}, checksum: {}",
                   databaseType, checksumEnabled);
    }
    
    @Override
    public void putChunk(String key, byte[] data) throws IOException {
        validateKey(key);
        validateDataNotEmpty(data);
        
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
        validateKey(key);
        
        try {
            byte[] data = nativeGetChunk(nativePointer, key);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            throw new IOException("Rust database operation failed", e);
        }
    }
    
    @Override
    public boolean deleteChunk(String key) throws IOException {
        validateKey(key);
        
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
        validateKey(key);
        
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
        validateBackupPath(backupPath);
        
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
    
    /**
     * Swap out a chunk to disk storage for memory pressure relief.
     *
     * @param key The chunk key to swap out
     * @return true if swap out was successful, false otherwise
     * @throws IOException if swap operation fails
     */
    public boolean swapOutChunk(String key) throws IOException {
        validateKey(key);
        
        try {
            return nativeSwapOutChunk(nativePointer, key);
        } catch (Exception e) {
            throw new IOException("Rust swap out operation failed", e);
        }
    }
    
    /**
     * Swap in a chunk from disk storage.
     *
     * @param key The chunk key to swap in
     * @return The chunk data if successful, null otherwise
     * @throws IOException if swap operation fails
     */
    public Optional<byte[]> swapInChunk(String key) throws IOException {
        validateKey(key);
        
        try {
            byte[] data = nativeSwapInChunk(nativePointer, key);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            throw new IOException("Rust swap in operation failed", e);
        }
    }
    
    /**
     * Get swap candidates based on access patterns and priority scores.
     *
     * @param limit Maximum number of candidates to return
     * @return List of chunk keys that are good swap candidates
     * @throws IOException if operation fails
     */
    public java.util.List<String> getSwapCandidates(int limit) throws IOException {
        try {
            return nativeGetSwapCandidates(nativePointer, limit);
        } catch (Exception e) {
            throw new IOException("Failed to get swap candidates", e);
        }
    }
    
    /**
     * Bulk swap out multiple chunks for efficient memory pressure handling.
     *
     * @param keys List of chunk keys to swap out
     * @return Number of chunks successfully swapped out
     * @throws IOException if operation fails
     */
    public int bulkSwapOut(java.util.List<String> keys) throws IOException {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Keys list cannot be null or empty");
        }
        
        try {
            return nativeBulkSwapOut(nativePointer, keys);
        } catch (Exception e) {
            throw new IOException("Rust bulk swap out operation failed", e);
        }
    }
    
    /**
     * Bulk swap in multiple chunks from disk storage.
     *
     * @param keys List of chunk keys to swap in
     * @return List of chunk data for successfully swapped chunks
     * @throws IOException if operation fails
     */
    public java.util.List<byte[]> bulkSwapIn(java.util.List<String> keys) throws IOException {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Keys list cannot be null or empty");
        }
        
        try {
            return nativeBulkSwapIn(nativePointer, keys);
        } catch (Exception e) {
            throw new IOException("Rust bulk swap in operation failed", e);
        }
    }
    
    /**
     * Swap out a chunk asynchronously.
     *
     * @param key The chunk key to swap out
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Boolean> swapOutChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return swapOutChunk(key);
            } catch (IOException e) {
                throw new RustDatabaseException("Async swap out operation failed for key: " + key, e);
            }
        });
    }
    
    /**
     * Swap in a chunk asynchronously.
     *
     * @param key The chunk key to swap in
     * @return CompletableFuture containing the optional chunk data
     */
    public CompletableFuture<Optional<byte[]>> swapInChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return swapInChunk(key);
            } catch (IOException e) {
                throw new RustDatabaseException("Async swap in operation failed for key: " + key, e);
            }
        });
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
    
    // Swap operation native methods
    private native boolean nativeSwapOutChunk(long nativePointer, String key);
    private native byte[] nativeSwapInChunk(long nativePointer, String key);
    private native java.util.List<String> nativeGetSwapCandidates(long nativePointer, int limit);
    private native int nativeBulkSwapOut(long nativePointer, java.util.List<String> keys);
    private native java.util.List<byte[]> nativeBulkSwapIn(long nativePointer, java.util.List<String> keys);
    
    @Override
    public String toString() {
        return String.format("RustDatabaseAdapter[type=%s, checksum=%s, healthy=%s]", 
                           databaseType, checksumEnabled, isHealthy());
    }
}