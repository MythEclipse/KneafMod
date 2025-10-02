package com.kneaf.core.chunkstorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory implementation of DatabaseAdapter for testing and development.
 * Uses ConcurrentHashMap for thread-safe operations.
 */
public class InMemoryDatabaseAdapter extends AbstractDatabaseAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryDatabaseAdapter.class);
    
    /**
     * Exception thrown for database operation failures.
     */
    public static class DatabaseOperationException extends RuntimeException {
        public DatabaseOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    private final Map<String, byte[]> chunkStorage = new ConcurrentHashMap<>();
    private final AtomicLong totalSizeBytes = new AtomicLong(0);
    private final ReadWriteLock statsLock = new ReentrantReadWriteLock();
    
    // Performance tracking
    private volatile long readLatencyMs = 0;
    private volatile long writeLatencyMs = 0;
    private volatile long lastMaintenanceTime = System.currentTimeMillis();
    private volatile boolean healthy = true;
    
    private final String databaseType;
    
    public InMemoryDatabaseAdapter() {
        this("in-memory");
    }
    
    public InMemoryDatabaseAdapter(String databaseType) {
        this.databaseType = databaseType;
        LOGGER.info("Initialized InMemoryDatabaseAdapter of type: {}", databaseType);
    }
    
    @Override
    public void putChunk(String key, byte[] data) throws IOException {
        validateKey(key);
        validateData(data);
        
        long startTime = System.nanoTime();
        
        try {
            byte[] existingData = chunkStorage.get(key);
            int existingSize = existingData != null ? existingData.length : 0;
            
            chunkStorage.put(key, data.clone());
            
            // Update size tracking
            totalSizeBytes.addAndGet((long) data.length - existingSize);
            
            long duration = System.nanoTime() - startTime;
            writeLatencyMs = duration / 1_000_000;
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Stored chunk {} ({} bytes) in {} ms", key, data.length, writeLatencyMs);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to store chunk {}", key, e);
            throw new IOException("Failed to store chunk", e);
        }
    }
    
    @Override
    public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
        return CompletableFuture.runAsync(() -> {
            try {
                putChunk(key, data);
            } catch (IOException e) {
                LOGGER.error("Failed to store chunk asynchronously {}", key, e);
                throw new DatabaseOperationException("Failed to store chunk asynchronously", e);
            }
        });
    }
    
    @Override
    public Optional<byte[]> getChunk(String key) throws IOException {
        validateKey(key);
        
        long startTime = System.nanoTime();
        
        try {
            byte[] data = chunkStorage.get(key);
            
            long duration = System.nanoTime() - startTime;
            readLatencyMs = duration / 1_000_000;
            
            if (LOGGER.isDebugEnabled()) {
                if (data != null) {
                    LOGGER.debug("Retrieved chunk {} ({} bytes) in {} ms", key, data.length, readLatencyMs);
                } else {
                    LOGGER.debug("Chunk {} not found (retrieved in {} ms)", key, readLatencyMs);
                }
            }
            
            return data != null ? Optional.of(data.clone()) : Optional.empty();
            
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve chunk {}", key, e);
            throw new IOException("Failed to retrieve chunk", e);
        }
    }
    
    @Override
    public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getChunk(key);
            } catch (IOException e) {
                LOGGER.error("Failed to retrieve chunk asynchronously {}", key, e);
                throw new DatabaseOperationException("Failed to retrieve chunk asynchronously", e);
            }
        });
    }
    
    @Override
    public boolean deleteChunk(String key) throws IOException {
        validateKey(key);
        
        try {
            byte[] removedData = chunkStorage.remove(key);
            if (removedData != null) {
                totalSizeBytes.addAndGet(-removedData.length);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Deleted chunk {} ({} bytes)", key, removedData.length);
                }
                return true;
            }
            return false;
            
        } catch (Exception e) {
            LOGGER.error("Failed to delete chunk {}", key, e);
            throw new IOException("Failed to delete chunk", e);
        }
    }
    
    @Override
    public CompletableFuture<Boolean> deleteChunkAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return deleteChunk(key);
            } catch (IOException e) {
                LOGGER.error("Failed to delete chunk asynchronously {}", key, e);
                throw new DatabaseOperationException("Failed to delete chunk asynchronously", e);
            }
        });
    }
    
    @Override
    public boolean hasChunk(String key) throws IOException {
        validateKey(key);
        
        try {
            return chunkStorage.containsKey(key);
        } catch (Exception e) {
            LOGGER.error("Failed to check chunk existence {}", key, e);
            throw new IOException("Failed to check chunk existence", e);
        }
    }
    
    @Override
    public long getChunkCount() throws IOException {
        try {
            return chunkStorage.size();
        } catch (Exception e) {
            LOGGER.error("Failed to get chunk count", e);
            throw new IOException("Failed to get chunk count", e);
        }
    }
    
    @Override
    public DatabaseStats getStats() throws IOException {
        statsLock.readLock().lock();
        try {
            long chunkCount = getChunkCount();
            long currentSize = totalSizeBytes.get();
            long currentReadLatency = readLatencyMs;
            long currentWriteLatency = writeLatencyMs;
            long lastMaint = lastMaintenanceTime;
            boolean health = healthy;
            
            // In-memory adapter doesn't support swap operations, so use zeros for swap metrics
            return new DatabaseStats(chunkCount, currentSize, currentReadLatency,
                                   currentWriteLatency, lastMaint, health,
                                   0L, 0L, 0L, 0L, 0L);
        } catch (Exception e) {
            LOGGER.error("Failed to get database stats", e);
            throw new IOException("Failed to get database stats", e);
        } finally {
            statsLock.readLock().unlock();
        }
    }
    
    @Override
    public void performMaintenance() throws IOException {
        try {
            long startTime = System.currentTimeMillis();
            
            // In-memory database doesn't need much maintenance, but we can:
            // 1. Update health status
            // 2. Clean up any internal state if needed
            // 3. Update maintenance timestamp
            
            healthy = true; // Assume healthy after maintenance
            lastMaintenanceTime = startTime;
            
            if (LOGGER.isInfoEnabled()) {
                long duration = System.currentTimeMillis() - startTime;
                LOGGER.info("Database maintenance completed in {} ms for {} chunks", 
                          duration, getChunkCount());
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to perform database maintenance", e);
            healthy = false;
            throw new IOException("Failed to perform database maintenance", e);
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Closing InMemoryDatabaseAdapter with {} chunks ({} bytes)", 
                          getChunkCount(), totalSizeBytes.get());
            }
            
            chunkStorage.clear();
            totalSizeBytes.set(0);
            healthy = false;
            
        } catch (Exception e) {
            LOGGER.error("Failed to close database", e);
            throw new IOException("Failed to close database", e);
        }
    }
    
    @Override
    public String getDatabaseType() {
        return databaseType;
    }
    
    @Override
    public boolean isHealthy() {
        return healthy;
    }
    
    @Override
    public void createBackup(String backupPath) throws IOException {
        validateBackupPath(backupPath);
        
        File backupFile = new File(backupPath);
        File parentDir = backupFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create backup directory: " + parentDir.getAbsolutePath());
            }
        }
        
        try {
            long chunkCount = getChunkCount();
            long totalSize = totalSizeBytes.get();
            
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Creating backup at {} for {} chunks ({} bytes)",
                          backupPath, chunkCount, totalSize);
            }
            
            // Write backup file with atomic write pattern
            File tempFile = new File(backupPath + ".tmp");
            
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tempFile)))) {
                
                // Write backup header
                dos.writeUTF("KNEAF_DB_BACKUP_v1");
                
                // Write metadata as simple key-value pairs
                dos.writeLong(System.currentTimeMillis()); // backupTimestamp
                dos.writeUTF(databaseType); // databaseType
                dos.writeLong(chunkCount); // chunkCount
                dos.writeLong(totalSize); // totalSizeBytes
                dos.writeUTF("InMemoryDB_v1"); // backupFormat
                
                // Write chunk data
                dos.writeInt(chunkStorage.size());
                
                long writtenChunks = 0;
                long writtenBytes = 0;
                
                for (Map.Entry<String, byte[]> entry : chunkStorage.entrySet()) {
                    String key = entry.getKey();
                    byte[] data = entry.getValue();
                    
                    // Write key length and key
                    dos.writeUTF(key);
                    
                    // Write data length and data
                    dos.writeInt(data.length);
                    dos.write(data);
                    
                    writtenChunks++;
                    writtenBytes += data.length;
                    
                    if (LOGGER.isDebugEnabled() && writtenChunks % 1000 == 0) {
                        LOGGER.debug("Backup progress: {}/{} chunks written", writtenChunks, chunkCount);
                    }
                }
                
                // Write footer with verification data
                dos.writeUTF("BACKUP_END");
                dos.writeLong(writtenChunks);
                dos.writeLong(writtenBytes);
                
                // Flush and sync to ensure data integrity
                dos.flush();
                
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Backup serialization completed: {} chunks ({} bytes) written to temporary file",
                              writtenChunks, writtenBytes);
                }
                
            } catch (Exception e) {
                // Clean up temporary file on failure
                if (tempFile.exists() && !tempFile.delete()) {
                    LOGGER.warn("Failed to clean up temporary backup file: {}", tempFile.getAbsolutePath());
                }
                throw e;
            }
            
            // Atomic rename from temp to final file
            if (!tempFile.renameTo(backupFile)) {
                throw new IOException("Failed to rename temporary backup file to: " + backupPath);
            }
            
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Backup successfully created at {} ({} chunks, {} bytes)",
                          backupPath, chunkCount, totalSize);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to create backup at {}", backupPath, e);
            throw new IOException("Failed to create backup", e);
        }
    }
}