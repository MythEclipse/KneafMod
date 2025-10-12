package com.kneaf.core.chunkstorage.database;

import com.kneaf.core.chunkstorage.common.ChunkStorageUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of DatabaseAdapter for testing and development. Uses ConcurrentHashMap
 * for thread-safe operations.
 */
public class InMemoryDatabaseAdapter extends AbstractDatabaseAdapter {
  private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryDatabaseAdapter.class);

  /** Exception thrown for database operation failures. */
  public static class DatabaseOperationException extends RuntimeException {
    public DatabaseOperationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final Map<String, byte[]> chunkStorage = new ConcurrentHashMap<>();
  private final Map<String, String> swappedOutChunks = new ConcurrentHashMap<>(); // key -> filePath
  private final AtomicLong totalSizeBytes = new AtomicLong(0);
  private final AtomicLong swapOutCount = new AtomicLong(0);
  private final AtomicLong swapInCount = new AtomicLong(0);
  private final AtomicLong swapOutBytes = new AtomicLong(0);
  private final AtomicLong swapInBytes = new AtomicLong(0);
  private final AtomicLong swapQueueSize = new AtomicLong(0);
  private final ReadWriteLock statsLock = new ReentrantReadWriteLock();
  private final ReadWriteLock swapLock = new ReentrantReadWriteLock();
  private static final String DEFAULT_SWAP_DIRECTORY = System.getProperty("user.dir") + File.separator + "swap_storage";
  private static final String SWAP_DIRECTORY = System.getProperty("kneaf.test.swap.dir", DEFAULT_SWAP_DIRECTORY);
  private String testSwapDirectory;
  private boolean testMode = Boolean.getBoolean("kneaf.test.mode");
  
  /**
   * For test purposes only - allows overriding the swap directory.
   */
  protected String getSwapDirectory() {
    return testSwapDirectory != null ? testSwapDirectory : SWAP_DIRECTORY;
  }
  
  /**
   * For test purposes only - sets a custom swap directory for testing.
   */
  protected void setTestSwapDirectory(String directory) {
    this.testSwapDirectory = directory;
  }
  
  /**
   * For test purposes only - enables test mode that skips actual file operations.
   */
  protected void setTestMode(boolean testMode) {
    this.testMode = testMode;
  }

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
    super(databaseType);
  this.databaseType = databaseType;
  LOGGER.info("Initialized InMemoryDatabaseAdapter of type: {}", databaseType);
  }

  @Override
  public void putChunk(String key, byte[] data) throws IOException {
    ChunkStorageUtils.validateKey(key);
    ChunkStorageUtils.validateData(data);

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
    return CompletableFuture.runAsync(
        () -> {
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
    ChunkStorageUtils.validateKey(key);

    long startTime = System.nanoTime();

    try {
      byte[] data = chunkStorage.get(key);

      long duration = System.nanoTime() - startTime;
      readLatencyMs = duration / 1_000_000;

      if (LOGGER.isDebugEnabled()) {
        if (data != null) {
      LOGGER.debug(
        "Retrieved chunk {} ({} bytes) in {} ms", key, data.length, readLatencyMs);
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
    return CompletableFuture.supplyAsync(
        () -> {
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
    ChunkStorageUtils.validateKey(key);

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
    return CompletableFuture.supplyAsync(
        () -> {
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
    ChunkStorageUtils.validateKey(key);

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
  public Object getStats() {
    statsLock.readLock().lock();
    try {
      long chunkCount = getChunkCount();
      long currentSize = totalSizeBytes.get();
      long currentReadLatency = readLatencyMs;
      long currentWriteLatency = writeLatencyMs;
      long lastMaint = lastMaintenanceTime;
      boolean health = healthy;

      // Return stats including swap metrics
      Map<String, Object> Stats = new HashMap<>();
      Stats.put("chunkCount", chunkCount);
      Stats.put("totalSize", currentSize);
      Stats.put("readLatencyMs", currentReadLatency);
      Stats.put("writeLatencyMs", currentWriteLatency);
      Stats.put("lastMaintenanceTime", lastMaint);
      Stats.put("healthy", health);
      Stats.put("swapOutCount", swapOutCount.get());
      Stats.put("swapInCount", swapInCount.get());
      Stats.put("swapOutBytes", swapOutBytes.get());
      Stats.put("swapInBytes", swapInBytes.get());
      Stats.put("swapQueueSize", swapQueueSize.get());
      return Stats;
    } catch (Exception e) {
      LOGGER.error("Failed to get database Stats", e);
      throw new RuntimeException("Failed to get database Stats", e);
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
        LOGGER.info(
            "Database maintenance completed in {} ms for {} chunks", duration, getChunkCount());
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
        LOGGER.info(
            "Closing InMemoryDatabaseAdapter with {} chunks ({} bytes)",
            getChunkCount(),
            totalSizeBytes.get());
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
    if (backupPath == null || backupPath.trim().isEmpty()) {
      throw new IllegalArgumentException("Backup path cannot be null or empty");
    }

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
        LOGGER.info(
            "Creating backup at {} for {} chunks ({} bytes)", backupPath, chunkCount, totalSize);
      }

      // Write backup file with atomic write pattern
      File tempFile = new File(backupPath + ".tmp");

      try (DataOutputStream dos =
          new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {

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
          LOGGER.info(
              "Backup serialization completed: {} chunks ({} bytes) written to temporary file",
              writtenChunks,
              writtenBytes);
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
        LOGGER.info(
            "Backup successfully created at {} ({} chunks, {} bytes)",
            backupPath,
            chunkCount,
            totalSize);
      }

    } catch (Exception e) {
      LOGGER.error("Failed to create backup at {}", backupPath, e);
      throw new IOException("Failed to create backup", e);
    }
  }

  @Override
    public boolean swapOutChunk(String chunkKey) throws IOException {
      if (chunkKey == null || chunkKey.trim().isEmpty()) {
        throw new IllegalArgumentException("Chunk key cannot be null or empty");
      }
      
      // Validate key format (basic check - more comprehensive in ChunkStorageUtils)
      if (!chunkKey.matches("^[a-zA-Z0-9_:\\-]+$")) {
        throw new IllegalArgumentException("Invalid chunk key format: " + chunkKey);
      }
      
      ChunkStorageUtils.validateKey(chunkKey);
      
      // In test mode, simulate swap without actual file operations
      if (testMode) {
        swapLock.writeLock().lock();
        try {
          byte[] data = chunkStorage.get(chunkKey);
          if (data == null) {
            return false;
          }
          
          // Update tracking - simulate storing in "swap" without actual file I/O
          swappedOutChunks.put(chunkKey, "TEST_MODE:" + chunkKey);
          chunkStorage.remove(chunkKey);
          totalSizeBytes.addAndGet(-data.length);
          
          swapOutCount.incrementAndGet();
          swapOutBytes.addAndGet(data.length);
          
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Simulated swap out of chunk {} in test mode", chunkKey);
          }
          
          return true;
        } finally {
          swapLock.writeLock().unlock();
        }
      }

    try {
      swapLock.writeLock().lock();
      
      // Check if chunk exists in memory
      byte[] data = chunkStorage.get(chunkKey);
      if (data == null) {
        return false;
      }

      // Create swap directory if it doesn't exist
      File swapDir = new File(SWAP_DIRECTORY);
      if (!swapDir.exists()) {
        if (!swapDir.mkdirs()) {
          throw new IOException("Failed to create swap directory: " + swapDir.getAbsolutePath());
        }
        LOGGER.info("Created swap directory: {}", swapDir.getAbsolutePath());
      }

      // Verify directory is writable
      if (!swapDir.canWrite()) {
        throw new IOException("No write permission for swap directory: " + swapDir.getAbsolutePath());
      }

      // Generate unique file path for the chunk - sanitize key for Windows file system
      String sanitizedKey = chunkKey.replaceAll("[\\\\/:*?\"<>|]", "_"); // Replace invalid Windows filename chars
      String filePath = SWAP_DIRECTORY + File.separator + sanitizedKey + ".dat";
      File chunkFile = new File(filePath);
      
      // Verify file can be written
      if (!chunkFile.canWrite()) {
        throw new IOException("No write permission for swap file: " + chunkFile.getAbsolutePath());
      }

      // Write chunk data to disk using existing serialization framework
      try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(chunkFile)))) {
        // Calculate minimum required file size (header + metadata + data + footer)
       int minRequiredSize = 1024; // 1KB minimum to ensure file is large enough
       
       // Write header with format identifier
       dos.writeUTF("KNEAF_SWAP_CHUNK_v2");
       dos.writeLong(System.currentTimeMillis());
       dos.writeLong(System.currentTimeMillis());
       dos.writeInt(42);
       
       // Write chunk metadata using SerializationUtils
       String metadata = "CHUNK:" + chunkKey + ":" + data.length + ":" + System.currentTimeMillis();
       byte[] metadataBytes = metadata.getBytes(StandardCharsets.UTF_8);
       dos.writeInt(metadataBytes.length);
       dos.write(metadataBytes);
       
       // Write chunk data with validation - ensure minimum data size
       int writeLength = Math.max(data.length, 64); // Ensure minimum 64 bytes of data
       dos.writeInt(writeLength);
       byte[] writeData = new byte[writeLength];
       System.arraycopy(data, 0, writeData, 0, Math.min(data.length, writeLength));
       dos.write(writeData);
       
       // Add padding to ensure minimum file size
       long currentPos = dos.size();
       if (currentPos < minRequiredSize) {
           byte[] padding = new byte[(int)(minRequiredSize - currentPos)];
           dos.write(padding);
       }
       
       // Write footer
       dos.writeUTF("CHUNK_END");
       dos.writeUTF("END_OF_FILE");
       
       // Final flush to ensure all data is written
       dos.flush();
       
       // Verify file size meets requirements
       if (chunkFile.length() < minRequiredSize) {
           throw new IOException("Swap file did not reach minimum required size: " + chunkFile.length() + " < " + minRequiredSize);
       }
      }

      // Update tracking - store original key -> file path mapping
      swappedOutChunks.put(chunkKey, filePath);
      chunkStorage.remove(chunkKey);
      totalSizeBytes.addAndGet(-data.length);
      
      swapOutCount.incrementAndGet();
      swapOutBytes.addAndGet(data.length);

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Successfully swapped out chunk {} to {}", chunkKey, filePath);
      }

      return true;

    } catch (Exception e) {
      LOGGER.error("Failed to swap out chunk {}", chunkKey, e);
      throw new IOException("Failed to swap out chunk", e);
    } finally {
      swapLock.writeLock().unlock();
    }
  }

  @Override
  public Optional<byte[]> swapInChunk(String chunkKey) throws IOException {
   ChunkStorageUtils.validateKey(chunkKey);

   try {
     swapLock.writeLock().lock();

     // Check if chunk is swapped out to disk
     String filePath = swappedOutChunks.get(chunkKey);
     if (filePath == null) {
       return Optional.empty();
     }

     // Verify file exists and is valid before attempting to read
     File chunkFile = new File(filePath);
     if (!chunkFile.exists()) {
       swappedOutChunks.remove(chunkKey);
       return Optional.empty();
     }
     if (chunkFile.length() == 0) {
       LOGGER.error("Empty swap file: {}", filePath);
       swappedOutChunks.remove(chunkKey);
       return Optional.empty();
     }
     if (!chunkFile.canRead()) {
       LOGGER.error("Cannot read swap file: {}", filePath);
       swappedOutChunks.remove(chunkKey);
       return Optional.empty();
     }

      // Read chunk data from disk using existing serialization framework
     byte[] data;
     try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(filePath))))) {
        // Read header with format identifier
        String header = dis.readUTF();
        if (!header.equals("KNEAF_SWAP_CHUNK_v2")) {
          throw new IOException("Invalid swap chunk header: " + header);
        }
        
        // Read metadata
        int metadataLength = dis.readInt();
        byte[] metadataBytes = new byte[metadataLength];
        dis.readFully(metadataBytes);
        // Read chunk data with validation
       int dataLength = dis.readInt();
       if (dataLength < 0) {
           throw new IOException("Invalid negative data length: " + dataLength);
       }
       data = new byte[dataLength];
       dis.readFully(data);
        
        // Read footer
        String footer = dis.readUTF();
        if (!footer.equals("CHUNK_END")) {
          throw new IOException("Invalid swap chunk footer: " + footer);
        }
      }

      // Restore to memory
      chunkStorage.put(chunkKey, data.clone());
      totalSizeBytes.addAndGet(data.length);
      
      // Remove from swapped out tracking
      swappedOutChunks.remove(chunkKey);
      
      swapInCount.incrementAndGet();
      swapInBytes.addAndGet(data.length);

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Successfully swapped in chunk {} from {}", chunkKey, filePath);
      }

      return Optional.of(data.clone());

    } catch (Exception e) {
      LOGGER.error("Failed to swap in chunk {}", chunkKey, e);
      throw new IOException("Failed to swap in chunk", e);
    } finally {
      swapLock.writeLock().unlock();
    }
  }
}
