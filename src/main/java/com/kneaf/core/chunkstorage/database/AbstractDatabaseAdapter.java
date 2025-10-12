package com.kneaf.core.chunkstorage.database;

import com.kneaf.core.chunkstorage.common.ChunkStorageExceptionHandler;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for database adapters providing common functionality.
 * Consolidates validation logic and eliminates DRY violations across implementations.
 */
public abstract class AbstractDatabaseAdapter implements DatabaseAdapter {

  protected final String databaseName;
  protected volatile boolean healthy = true;

  public AbstractDatabaseAdapter(String databaseName) {
    if (databaseName == null || databaseName.isEmpty()) {
      throw new IllegalArgumentException("Database name cannot be null or empty");
    }
    this.databaseName = databaseName;
  }

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
  public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            putChunk(key, data);
          } catch (IOException e) {
            throw new RuntimeException("Failed to put chunk asynchronously", e);
          }
        });
  }

  @Override
  public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return getChunk(key);
          } catch (IOException e) {
            throw new RuntimeException("Failed to get chunk asynchronously", e);
          }
        });
  }

  @Override
  public CompletableFuture<Boolean> deleteChunkAsync(String key) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return deleteChunk(key);
          } catch (IOException e) {
            throw new RuntimeException("Failed to delete chunk asynchronously", e);
          }
        });
  }

  @Override
  public boolean isHealthy() {
    return healthy;
  }

  @Override
  public void createBackup(String backupPath) throws IOException {
    // Default implementation - subclasses should override
    throw new UnsupportedOperationException("Backup not supported by this database adapter");
  }

  @Override
  public boolean swapOutChunk(String chunkKey) throws IOException {
    // Default implementation - mark as swapped by storing metadata
    try {
      byte[] swapMarker = createSwapMarker(chunkKey);
      putChunk(getSwapKey(chunkKey), swapMarker);
      return true;
    } catch (Exception e) {
      ChunkStorageExceptionHandler.logDebug(
          getDatabaseType(), "Swap out failed for chunk {}: {}", chunkKey, e.getMessage());
      return false;
    }
  }

  @Override
  public Optional<byte[]> swapInChunk(String chunkKey) throws IOException {
    // Default implementation - remove swap marker and return original data
    try {
      String swapKey = getSwapKey(chunkKey);
      if (hasChunk(swapKey)) {
        deleteChunk(swapKey);
        return getChunk(chunkKey);
      }
      return Optional.empty();
    } catch (Exception e) {
      ChunkStorageExceptionHandler.logDebug(
          getDatabaseType(), "Swap in failed for chunk {}: {}", chunkKey, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Create a swap marker for the given chunk key.
   *
   * @param chunkKey The original chunk key
   * @return Swap marker data
   */
  protected byte[] createSwapMarker(String chunkKey) {
    String marker = "SWAP_MARKER:" + chunkKey + ":" + System.currentTimeMillis();
    return marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Get the swap key for the given chunk key.
   *
   * @param chunkKey The original chunk key
   * @return Swap key
   */
  protected String getSwapKey(String chunkKey) {
    return "SWAP:" + chunkKey;
  }

  /**
   * Mark the database as healthy or unhealthy.
   *
   * @param healthy true if healthy, false otherwise
   */
  protected void setHealthy(boolean healthy) {
    this.healthy = healthy;
  }

  @Override
  public String toString() {
    return String.format(
        "%s[name=%s, healthy=%s]", getClass().getSimpleName(), databaseName, healthy);
  }
}
