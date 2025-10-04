package com.kneaf.core.performance.bridge;

import com.kneaf.core.KneafCore;
// import com.kneaf.core.performance.NativeBridge; // NativeBridge not available in current
// structure
import com.kneaf.core.performance.NativeFloatBufferAllocation;
import com.kneaf.core.performance.core.NativeBridgeProvider;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Manages native integration with Rust code, handling worker lifecycle and resource management. */
public class NativeIntegrationManager implements NativeBridgeProvider {

  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private final AtomicBoolean nativeAvailable = new AtomicBoolean(false);
  private final Map<Long, WorkerInfo> activeWorkers = new ConcurrentHashMap<>();
  private final AtomicLong nextWorkerId = new AtomicLong(1);

  private final int defaultConcurrency;
  private final int maxWorkers;

  public NativeIntegrationManager() {
    this.defaultConcurrency = 4; // Default concurrency
    this.maxWorkers = 8; // Maximum workers
  }

  /** Initialize native integration manager. */
  @Override
  public boolean initialize() {
    if (initialized.compareAndSet(false, true)) {
      try {
        // Attempt to initialize the native allocator. If this fails we must
        // mark nativeAvailable as false so higher-level code chooses the
        // pure-Java execution paths (which are faster for our unit tests)
        com.kneaf.core.performance.NativeBridge.initRustAllocator();
        nativeAvailable.set(true);
        KneafCore.LOGGER.info("Native allocator initialized successfully");
        KneafCore.LOGGER.info("Native integration manager initialized successfully");
        return true;
      } catch (Throwable t) {
        // Native init failed - do not advertise native availability. Use Java fallback.
        nativeAvailable.set(false);
        KneafCore.LOGGER.warn(
            "Native allocator init failed; native integration unavailable, using Java fallback: { }",
            t.getMessage());
        return false;
      }
    }
    return nativeAvailable.get();
  }

  /** Create a new worker for native processing. */
  public long createWorker(int concurrency) throws RuntimeException {
    // allow creation even if native library is not present - use Java fallback

    if (activeWorkers.size() >= maxWorkers) {
      throw new RuntimeException("Maximum worker limit reached: " + maxWorkers);
    }

    try {
      long workerHandle = com.kneaf.core.performance.NativeBridge.nativeCreateWorker(concurrency);
      if (workerHandle == 0) {
        throw new RuntimeException("Failed to create native worker");
      }

      long workerId = nextWorkerId.getAndIncrement();
      WorkerInfo workerInfo = new WorkerInfo(workerId, workerHandle, concurrency);
      activeWorkers.put(workerId, workerInfo);

      KneafCore.LOGGER.debug(
          "Created native worker { } with handle { } and concurrency { }",
          workerId,
          workerHandle,
          concurrency);

      return workerId;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create native worker", e);
    }
  }

  /** Create a worker with default concurrency. */
  public long createWorker() throws RuntimeException {
    return createWorker(defaultConcurrency);
  }

  /** Destroy a worker and cleanup resources. */
  public void destroyWorker(long workerId) throws RuntimeException {
    WorkerInfo workerInfo = activeWorkers.remove(workerId);
    if (workerInfo == null) {
      KneafCore.LOGGER.warn("Attempted to destroy non-existent worker: { }", workerId);
      return;
    }

    try {
      com.kneaf.core.performance.NativeBridge.nativeDestroyWorker(workerInfo.handle);
      KneafCore.LOGGER.debug("Destroyed native worker { }", workerId);
    } catch (Exception e) {
      throw new RuntimeException("Failed to destroy native worker " + workerId, e);
    }
  }

  /** Push task to worker for processing. */
  public void pushTask(long workerId, byte[] payload) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);
    if (payload == null || payload.length == 0) {
      return;
    }

    try {
      com.kneaf.core.performance.NativeBridge.nativePushTask(workerInfo.handle, payload);
      workerInfo.incrementTaskCount();
    } catch (Exception e) {
      throw new RuntimeException("Failed to push task to worker " + workerId, e);
    }
  }

  /** Push batch of tasks to worker for processing. */
  public void pushBatch(long workerId, byte[][] payloads) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);
    if (payloads == null || payloads.length == 0) {
      return;
    }

    try {
      com.kneaf.core.performance.NativeBridge.nativePushBatch(
          workerInfo.handle, payloads, payloads.length);
      workerInfo.incrementTaskCount(payloads.length);
    } catch (Exception e) {
      throw new RuntimeException("Failed to push batch to worker " + workerId, e);
    }
  }

  /** Poll result from worker. */
  public byte[] pollResult(long workerId) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);

    try {
      byte[] result = com.kneaf.core.performance.NativeBridge.nativePollResult(workerInfo.handle);
      if (result != null && result.length > 0) {
        workerInfo.incrementResultCount();
      }
      return result;
    } catch (Exception e) {
      throw new RuntimeException("Failed to poll result from worker " + workerId, e);
    }
  }

  /** Get worker statistics. */
  public WorkerStatistics getWorkerStatistics(long workerId) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);

    try {
      long queueDepth =
          com.kneaf.core.performance.NativeBridge.nativeGetWorkerQueueDepth(workerInfo.handle);
      double avgProcessingMs =
          com.kneaf.core.performance.NativeBridge.nativeGetWorkerAvgProcessingMs(workerInfo.handle);
      long memoryUsage =
          com.kneaf.core.performance.NativeBridge.nativeGetWorkerMemoryUsage(workerInfo.handle);

      return new WorkerStatistics(
          workerId,
          queueDepth,
          avgProcessingMs,
          memoryUsage,
          workerInfo.getTaskCount(),
          workerInfo.getResultCount());
    } catch (Exception e) {
      throw new RuntimeException("Failed to get statistics for worker " + workerId, e);
    }
  }

  /** Get all worker statistics. */
  public Map<Long, WorkerStatistics> getAllWorkerStatistics() {
    Map<Long, WorkerStatistics> Stats = new ConcurrentHashMap<>();
    for (Long workerId : activeWorkers.keySet()) {
      try {
        Stats.put(workerId, getWorkerStatistics(workerId));
      } catch (RuntimeException e) {
        KneafCore.LOGGER.warn(
            "Failed to get statistics for worker { }: { }", workerId, e.getMessage());
      }
    }
    return Stats;
  }

  /** Cleanup all resources and shutdown. */
  public void shutdown() {
    if (!initialized.get()) {
      return;
    }

    KneafCore.LOGGER.info("Shutting down native integration manager");

    // Destroy all active workers
    for (Long workerId : activeWorkers.keySet()) {
      try {
        destroyWorker(workerId);
      } catch (Exception e) {
        KneafCore.LOGGER.error("Failed to destroy worker { } during shutdown", workerId, e);
      }
    }

    // Cleanup buffer pool (placeholder - actual implementation would cleanup native resources)
    // try {
    //     NativeBridge.cleanupBufferPool(); // Not available in current structure
    // } catch (Exception e) {
    //     KneafCore.LOGGER.error("Failed to cleanup buffer pool", e);
    // }

    activeWorkers.clear();
    initialized.set(false);
    nativeAvailable.set(false);

    KneafCore.LOGGER.info("Native integration manager shutdown complete");
  }

  @Override
  public boolean isNativeAvailable() {
    return nativeAvailable.get();
  }

  @Override
  public byte[] processEntitiesBinary(ByteBuffer input) {
    // Implementation would use a worker to process entities
    return new byte[0];
  }

  @Override
  public String processEntitiesJson(String input) {
    // Implementation would use a worker to process entities
    return "{ }";
  }

  @Override
  public byte[] processItemEntitiesBinary(ByteBuffer input) {
    // Implementation would use a worker to process item entities
    return new byte[0];
  }

  @Override
  public String processItemEntitiesJson(String input) {
    // Implementation would use a worker to process item entities
    return "{ }";
  }

  @Override
  public byte[] processMobAiBinary(ByteBuffer input) {
    // Implementation would use a worker to process mob AI
    return new byte[0];
  }

  @Override
  public String processMobAiJson(String input) {
    // Implementation would use a worker to process mob AI
    return "{ }";
  }

  @Override
  public byte[] processBlockEntitiesBinary(ByteBuffer input) {
    // Implementation would use a worker to process block entities
    return new byte[0];
  }

  @Override
  public String processBlockEntitiesJson(String input) {
    // Implementation would use a worker to process block entities
    return "{ }";
  }

  @Override
  public byte[] processVillagerAiBinary(ByteBuffer input) {
    // Implementation would use a worker to process villager AI
    return new byte[0];
  }

  @Override
  public String processVillagerAiJson(String jsonInput) {
    // Implementation would use a worker to process villager AI
    return "{ }";
  }

  @Override
  public String getMemoryStats() {
    // Return memory statistics as JSON string
    Map<String, Object> Stats = new ConcurrentHashMap<>();
    Stats.put("activeWorkers", activeWorkers.size());
    Stats.put("nativeAvailable", nativeAvailable.get());
    return new com.google.gson.Gson().toJson(Stats);
  }

  @Override
  public String getCpuStats() {
    // Return CPU statistics as JSON string
    Map<String, Object> Stats = new ConcurrentHashMap<>();
    Stats.put("activeWorkers", activeWorkers.size());
    Stats.put("avgProcessingMs", getNativeWorkerAvgProcessingMs());
    return new com.google.gson.Gson().toJson(Stats);
  }

  @Override
  public java.util.concurrent.CompletableFuture<Integer> preGenerateNearbyChunksAsync(
      int centerX, int centerZ, int radius) {
    // Implementation would pre-generate nearby chunks asynchronously
    return java.util.concurrent.CompletableFuture.completedFuture(0);
  }

  @Override
  public boolean isChunkGenerated(int chunkX, int chunkZ) {
    // Implementation would check if chunk is generated
    return false;
  }

  @Override
  public long getGeneratedChunkCount() {
    // Implementation would return generated chunk count
    return 0;
  }

  @Override
  public int getNativeWorkerQueueDepth() {
    // Return average queue depth across all workers
    if (activeWorkers.isEmpty()) {
      return 0;
    }

    long totalDepth = 0;
    int count = 0;
    for (Long workerId : activeWorkers.keySet()) {
      try {
        WorkerStatistics Stats = getWorkerStatistics(workerId);
        totalDepth += Stats.getQueueDepth();
        count++;
      } catch (Exception e) {
        // Skip this worker
      }
    }

    return count > 0 ? (int) (totalDepth / count) : 0;
  }

  @Override
  public double getNativeWorkerAvgProcessingMs() {
    // Return average processing time across all workers
    if (activeWorkers.isEmpty()) {
      return 0.0;
    }

    double totalTime = 0;
    int count = 0;
    for (Long workerId : activeWorkers.keySet()) {
      try {
        WorkerStatistics Stats = getWorkerStatistics(workerId);
        totalTime += Stats.getAvgProcessingMs();
        count++;
      } catch (Exception e) {
        // Skip this worker
      }
    }

    return count > 0 ? totalTime / count : 0.0;
  }

  /** Generate float buffer native. */
  public ByteBuffer generateFloatBuffer(int size, int flags) {
    // Placeholder implementation - actual would use native code
    ByteBuffer b = ByteBuffer.allocateDirect(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    java.nio.FloatBuffer fb = b.asFloatBuffer();
    for (int i = 0; i < size; i++) fb.put(i, (float) i);
    return b;
  }

  /** Generate float buffer with shape native. */
  public NativeFloatBufferAllocation generateFloatBufferWithShape(long rows, long cols) {
    // Placeholder implementation - actual would use native code
    int count = (int) (rows * cols);
    ByteBuffer buffer =
        ByteBuffer.allocateDirect(count * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    java.nio.FloatBuffer fb = buffer.asFloatBuffer();
    for (int i = 0; i < count; i++) fb.put(i, (float) i);
    return new NativeFloatBufferAllocation(buffer, rows, cols);
  }

  /** Free float buffer native. */
  public void freeFloatBuffer(ByteBuffer buffer) {
    // Placeholder implementation - actual would use native code to free
    if (buffer != null && buffer.isDirect()) {
      // In a real implementation, this would call native code to free the direct buffer
    }
  }

  // Additional helper methods for internal use

  public Map<String, Object> getMemoryStatsMap() {
    // Return memory statistics as Map
    Map<String, Object> Stats = new ConcurrentHashMap<>();
    Stats.put("activeWorkers", activeWorkers.size());
    Stats.put("nativeAvailable", nativeAvailable.get());
    return Stats;
  }

  public Map<String, Object> getCpuStatsMap() {
    // Return CPU statistics as Map
    Map<String, Object> Stats = new ConcurrentHashMap<>();
    Stats.put("activeWorkers", activeWorkers.size());
    Stats.put("avgProcessingMs", getNativeWorkerAvgProcessingMs());
    return Stats;
  }

  /** Get worker info by ID. */
  private WorkerInfo getWorkerInfo(long workerId) throws RuntimeException {
    WorkerInfo workerInfo = activeWorkers.get(workerId);
    if (workerInfo == null) {
      throw new RuntimeException("Worker not found: " + workerId);
    }
    return workerInfo;
  }

  /** Worker information holder. */
  private static class WorkerInfo {
    private final long handle;
    private final AtomicLong taskCount = new AtomicLong(0);
    private final AtomicLong resultCount = new AtomicLong(0);

    public WorkerInfo(long id, long handle, int concurrency) {
      this.handle = handle;
    }

    public void incrementTaskCount() {
      taskCount.incrementAndGet();
    }

    public void incrementTaskCount(int count) {
      taskCount.addAndGet(count);
    }

    public void incrementResultCount() {
      resultCount.incrementAndGet();
    }

    public long getTaskCount() {
      return taskCount.get();
    }

    public long getResultCount() {
      return resultCount.get();
    }
  }

  /** Worker statistics data class. */
  public static class WorkerStatistics {
    private final long workerId;
    private final long queueDepth;
    private final double avgProcessingMs;
    private final long memoryUsage;
    private final long taskCount;
    private final long resultCount;

    public WorkerStatistics(
        long workerId,
        long queueDepth,
        double avgProcessingMs,
        long memoryUsage,
        long taskCount,
        long resultCount) {
      this.workerId = workerId;
      this.queueDepth = queueDepth;
      this.avgProcessingMs = avgProcessingMs;
      this.memoryUsage = memoryUsage;
      this.taskCount = taskCount;
      this.resultCount = resultCount;
    }

    public long getWorkerId() {
      return workerId;
    }

    public long getQueueDepth() {
      return queueDepth;
    }

    public double getAvgProcessingMs() {
      return avgProcessingMs;
    }

    public long getMemoryUsage() {
      return memoryUsage;
    }

    public long getTaskCount() {
      return taskCount;
    }

    public long getResultCount() {
      return resultCount;
    }

    @Override
    public String toString() {
      return String.format(
          "WorkerStatistics{id=%d, queueDepth=%d, avgProcessingMs=%.2f, memoryUsage=%d, taskCount=%d, resultCount=%d}",
          workerId, queueDepth, avgProcessingMs, memoryUsage, taskCount, resultCount);
    }
  }
}
