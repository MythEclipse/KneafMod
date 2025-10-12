package com.kneaf.core.chunkstorage.database;

import com.kneaf.core.chunkstorage.common.ChunkStorageUtils;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory only implementation of DatabaseAdapter for testing.
 * This implementation simulates swap operations without actual file I/O.
 */
public class InMemoryOnlyDatabaseAdapter extends AbstractDatabaseAdapter {
  /** Exception thrown for database operation failures. */
  public static class DatabaseOperationException extends RuntimeException {
    public DatabaseOperationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
  private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryOnlyDatabaseAdapter.class);

  private final Map<String, byte[]> chunkStorage = new ConcurrentHashMap<>();
  private final Map<String, byte[]> swappedOutChunks = new ConcurrentHashMap<>(); // key -> original data
  private final AtomicLong totalSizeBytes = new AtomicLong(0);
  private final AtomicLong swapOutCount = new AtomicLong(0);
  private final AtomicLong swapInCount = new AtomicLong(0);
  private final AtomicLong swapOutBytes = new AtomicLong(0);
  private final AtomicLong swapInBytes = new AtomicLong(0);
  private final AtomicLong swapQueueSize = new AtomicLong(0);
  private final ReadWriteLock statsLock = new ReentrantReadWriteLock();
  private final ReadWriteLock swapLock = new ReentrantReadWriteLock();
  private final String databaseType;

  public InMemoryOnlyDatabaseAdapter() {
    this("in-memory-only");
  }

  public InMemoryOnlyDatabaseAdapter(String databaseType) {
    super(databaseType);
    this.databaseType = databaseType;
    LOGGER.info("Initialized InMemoryOnlyDatabaseAdapter of type: {}", databaseType);
  }

  @Override
  public void putChunk(String key, byte[] data) throws IOException {
    ChunkStorageUtils.validateKey(key);
    ChunkStorageUtils.validateData(data);

    try {
      byte[] existingData = chunkStorage.get(key);
      int existingSize = existingData != null ? existingData.length : 0;

      chunkStorage.put(key, data.clone());

      // Update size tracking
      totalSizeBytes.addAndGet((long) data.length - existingSize);

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Stored chunk {} ({} bytes)", key, data.length);
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

    try {
      byte[] data = chunkStorage.get(key);

      if (LOGGER.isDebugEnabled()) {
        if (data != null) {
          LOGGER.debug("Retrieved chunk {} ({} bytes)", key, data.length);
        } else {
          LOGGER.debug("Chunk {} not found", key);
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

      // Return stats including swap metrics
      Map<String, Object> stats = new java.util.HashMap<>();
      stats.put("chunkCount", chunkCount);
      stats.put("totalSize", currentSize);
      stats.put("swapOutCount", swapOutCount.get());
      stats.put("swapInCount", swapInCount.get());
      stats.put("swapOutBytes", swapOutBytes.get());
      stats.put("swapInBytes", swapInBytes.get());
      stats.put("swapQueueSize", swapQueueSize.get());
      return stats;
    } catch (Exception e) {
      LOGGER.error("Failed to get database stats", e);
      throw new RuntimeException("Failed to get database stats", e);
    } finally {
      statsLock.readLock().unlock();
    }
  }

  @Override
  public void performMaintenance() throws IOException {
    try {
      long startTime = System.currentTimeMillis();

      // In-memory database doesn't need much maintenance
      if (LOGGER.isInfoEnabled()) {
        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info(
            "Database maintenance completed in {} ms for {} chunks", duration, getChunkCount());
      }

    } catch (Exception e) {
      LOGGER.error("Failed to perform database maintenance", e);
      throw new IOException("Failed to perform database maintenance", e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (LOGGER.isInfoEnabled()) {
        LOGGER.info(
            "Closing InMemoryOnlyDatabaseAdapter with {} chunks ({} bytes)",
            getChunkCount(),
            totalSizeBytes.get());
      }

      chunkStorage.clear();
      swappedOutChunks.clear();
      totalSizeBytes.set(0);

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
    return true;
  }

  @Override
  public void createBackup(String backupPath) throws IOException {
    throw new UnsupportedOperationException("Backup not supported in InMemoryOnlyDatabaseAdapter");
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

    try {
      swapLock.writeLock().lock();
      
      // Check if chunk exists in memory
      byte[] data = chunkStorage.get(chunkKey);
      if (data == null) {
        return false;
      }

      // Simulate swap out - move to "swapped out" map without file I/O
      swappedOutChunks.put(chunkKey, data.clone());
      chunkStorage.remove(chunkKey);
      totalSizeBytes.addAndGet(-data.length);
      
      swapOutCount.incrementAndGet();
      swapOutBytes.addAndGet(data.length);

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Simulated swap out of chunk {} ({} bytes)", chunkKey, data.length);
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
    if (chunkKey == null || chunkKey.trim().isEmpty()) {
      throw new IllegalArgumentException("Chunk key cannot be null or empty");
    }

    try {
      swapLock.writeLock().lock();

      // Check if chunk is "swapped out"
      byte[] data = swappedOutChunks.get(chunkKey);
      if (data == null) {
        return Optional.empty();
      }

      // Simulate swap in - move back to memory without file I/O
      chunkStorage.put(chunkKey, data.clone());
      totalSizeBytes.addAndGet(data.length);
      
      // Remove from swapped out tracking
      swappedOutChunks.remove(chunkKey);
      
      swapInCount.incrementAndGet();
      swapInBytes.addAndGet(data.length);

      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Simulated swap in of chunk {} ({} bytes)", chunkKey, data.length);
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