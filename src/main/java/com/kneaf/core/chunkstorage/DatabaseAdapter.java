package com.kneaf.core.chunkstorage;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract database adapter interface for chunk storage operations. Supports multiple database
 * backends (Sled, RocksDB, LMDB) with async operations.
 */
public interface DatabaseAdapter {

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
   * Get database statistics for monitoring.
   *
   * @return DatabaseStats object containing performance metrics
   * @throws IOException if Stats retrieval fails
   */
  DatabaseStats getStats() throws IOException;

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

  /** Database statistics for monitoring and debugging. */
  class DatabaseStats {
    private final long totalChunks;
    private final long totalSizeBytes;
    private final long readLatencyMs;
    private final long writeLatencyMs;
    private final long lastMaintenanceTime;
    private final boolean isHealthy;
    // Swap-specific metrics
    private final long swapOperationsTotal;
    private final long swapOperationsFailed;
    private final long swapInLatencyMs;
    private final long swapOutLatencyMs;
    private final long memoryMappedFilesActive;

    public DatabaseStats(
        long totalChunks,
        long totalSizeBytes,
        long readLatencyMs,
        long writeLatencyMs,
        long lastMaintenanceTime,
        boolean isHealthy,
        long swapOperationsTotal,
        long swapOperationsFailed,
        long swapInLatencyMs,
        long swapOutLatencyMs,
        long memoryMappedFilesActive) {
      this.totalChunks = totalChunks;
      this.totalSizeBytes = totalSizeBytes;
      this.readLatencyMs = readLatencyMs;
      this.writeLatencyMs = writeLatencyMs;
      this.lastMaintenanceTime = lastMaintenanceTime;
      this.isHealthy = isHealthy;
      this.swapOperationsTotal = swapOperationsTotal;
      this.swapOperationsFailed = swapOperationsFailed;
      this.swapInLatencyMs = swapInLatencyMs;
      this.swapOutLatencyMs = swapOutLatencyMs;
      this.memoryMappedFilesActive = memoryMappedFilesActive;
    }

    public long getTotalChunks() {
      return totalChunks;
    }

    public long getTotalSizeBytes() {
      return totalSizeBytes;
    }

    public long getReadLatencyMs() {
      return readLatencyMs;
    }

    public long getWriteLatencyMs() {
      return writeLatencyMs;
    }

    public long getLastMaintenanceTime() {
      return lastMaintenanceTime;
    }

    public boolean isHealthy() {
      return isHealthy;
    }

    // Swap-specific getters
    public long getSwapOperationsTotal() {
      return swapOperationsTotal;
    }

    public long getSwapOperationsFailed() {
      return swapOperationsFailed;
    }

    public long getSwapInLatencyMs() {
      return swapInLatencyMs;
    }

    public long getSwapOutLatencyMs() {
      return swapOutLatencyMs;
    }

    public long getMemoryMappedFilesActive() {
      return memoryMappedFilesActive;
    }

    @Override
    public String toString() {
      return String.format(
          "DatabaseStats{chunks=%d, size=%d bytes, readLatency=%d ms, "
              + "writeLatency=%d ms, healthy=%s, swapOps=%d, swapFailed=%d, "
              + "swapInLatency=%d ms, swapOutLatency=%d ms, mmapFiles=%d}",
          totalChunks,
          totalSizeBytes,
          readLatencyMs,
          writeLatencyMs,
          isHealthy,
          swapOperationsTotal,
          swapOperationsFailed,
          swapInLatencyMs,
          swapOutLatencyMs,
          memoryMappedFilesActive);
    }
  }
}
