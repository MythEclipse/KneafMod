package com.kneaf.core.performance.bridge;

import com.kneaf.core.KneafCore;
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
    this.defaultConcurrency = Runtime.getRuntime().availableProcessors() / 2; // Default concurrency based on available cores
    this.maxWorkers = Runtime.getRuntime().availableProcessors(); // Maximum workers based on available cores
  }

  /**
   * Try to load the native library from several likely locations. This will attempt
   * System.loadLibrary first, then attempt to load from an environment-specified
   * path (KNEAF_NATIVE_PATH), then try common folders like 'natives' and 'run/libs'.
   * Returns true if the library was loaded.
   */
  private static boolean tryLoadNativeLibrary() {
    // Delegate to the centralized loader to keep logic in one place
    try {
      return com.kneaf.core.performance.bridge.NativeLibraryLoader.loadNativeLibrary();
    } catch (Throwable t) {
      KneafCore.LOGGER.debug("Central NativeLibraryLoader failed: " + t.getMessage());
      return false;
    }
  }

  /** Initialize native integration manager. */
  @Override
  public boolean initialize() {
    if (initialized.compareAndSet(false, true)) {
      try {
        // Ensure native library is loaded (tryLoadNativeLibrary will attempt several strategies)
        if (!NativeResourceManager.isNativeLibraryLoaded()) {
          boolean loaded = tryLoadNativeLibrary();
          if (!loaded) {
            nativeAvailable.set(false);
            KneafCore.LOGGER.warn("Native library not available; falling back to Java implementation.");
            return false;
          }
        }

        // Now initialize the native allocator. If this fails we fall back to Java.
        try {
          // Use the unified JniCallManager to initialize allocator hooks; this centralizes
          // JNI calls and avoids classloader / symbol visibility issues with inner classes.
          com.kneaf.core.unifiedbridge.JniCallManager.getInstance().initializeAllocator();
          nativeAvailable.set(true);
          KneafCore.LOGGER.info("Native allocator initialized successfully via JniCallManager");
          KneafCore.LOGGER.info("Native integration manager initialized successfully");
          return true;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
          nativeAvailable.set(false);
          // Log the full exception so the stack trace and exact cause are available in logs
          KneafCore.LOGGER.warn("Native allocator init failed; native integration unavailable, using Java fallback: " + e.getMessage(), e);
          return false;
        }
      } catch (Throwable t) {
        nativeAvailable.set(false);
        KneafCore.LOGGER.warn("Native allocator init failed and threw an unexpected error; native integration unavailable, using Java fallback: " + t.getMessage(), t);
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
      long workerHandle = NativeResourceManager.nativeCreateWorker(concurrency);
      if (workerHandle == 0) {
        throw new RuntimeException("Failed to create native worker");
      }

      long workerId = nextWorkerId.getAndIncrement();
      WorkerInfo workerInfo = new WorkerInfo(workerId, workerHandle, concurrency);
      activeWorkers.put(workerId, workerInfo);

    KneafCore.LOGGER.debug(
      "Created native worker {} with handle {} and concurrency {}",
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
  KneafCore.LOGGER.warn("Attempted to destroy non-existent worker: {}", workerId);
      return;
    }

    try {
      if (workerInfo.isCleanedUp.compareAndSet(false, true)) {
        NativeResourceManager.nativeDestroyWorker(workerInfo.handle);
  KneafCore.LOGGER.debug("Destroyed native worker {}", workerId);
        
        // Verify cleanup was successful
        if (NativeResourceManager.nativeIsResourceLeaked(workerInfo.handle)) {
          KneafCore.LOGGER.warn("Resource leak detected for worker {} after destruction", workerId);
          NativeResourceManager.nativeForceCleanup(workerInfo.handle);
        }
      }
      
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
      NativeResourceManager.nativePushTask(workerInfo.handle, payload);
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
      NativeResourceManager.nativePushBatch(
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
      byte[] result = NativeResourceManager.nativePollResult(workerInfo.handle);
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
          NativeResourceManager.nativeGetWorkerQueueDepth(workerInfo.handle);
      double avgProcessingMs =
          NativeResourceManager.nativeGetWorkerAvgProcessingMs(workerInfo.handle);
      long memoryUsage =
          NativeResourceManager.nativeGetWorkerMemoryUsage(workerInfo.handle);

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
            "Failed to get statistics for worker {}: {}", workerId, e.getMessage());
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

    // Destroy all active workers with force cleanup
    for (Long workerId : activeWorkers.keySet()) {
      try {
        destroyWorker(workerId);
      } catch (Exception e) {
        KneafCore.LOGGER.error("Failed to destroy worker {} during shutdown", workerId, e);
        
        // Force cleanup as fallback
        try {
          WorkerInfo workerInfo = activeWorkers.get(workerId);
          if (workerInfo != null) {
            NativeResourceManager.nativeForceCleanup(workerInfo.handle);
          }
        } catch (Exception cleanupEx) {
          KneafCore.LOGGER.error("Failed to force cleanup worker {}", workerId, cleanupEx);
        }
      }
    }

    // Cleanup buffer pool with actual native implementation
    try {
        NativeResourceManager.nativeCleanupBufferPool();
    } catch (Exception e) {
        KneafCore.LOGGER.error("Failed to cleanup buffer pool", e);
        
        // Perform additional cleanup operations
        try {
            forceResourceCleanup();
        } catch (Exception cleanupEx) {
            KneafCore.LOGGER.error("Failed to perform additional resource cleanup", cleanupEx);
        }
    }
    
    // Shutdown native allocator
    try {
        NativeResourceManager.nativeShutdownAllocator();
        KneafCore.LOGGER.info("Native allocator shutdown complete");
    } catch (Exception e) {
        KneafCore.LOGGER.error("Failed to shutdown native allocator", e);
    }

    activeWorkers.clear();
    initialized.set(false);
    nativeAvailable.set(false);

    KneafCore.LOGGER.info("Native integration manager shutdown complete");
  }
  
  /** Force cleanup of all native resources. */
  public void forceResourceCleanup() {
    KneafCore.LOGGER.info("Performing forced native resource cleanup");
    
    // Check for leaked resources
    for (Long workerId : activeWorkers.keySet()) {
      try {
        WorkerInfo workerInfo = activeWorkers.get(workerId);
        if (workerInfo != null) {
          if (NativeResourceManager.nativeIsResourceLeaked(workerInfo.handle)) {
            KneafCore.LOGGER.warn("Resource leak detected for worker {}", workerId);
            NativeResourceManager.nativeForceCleanup(workerInfo.handle);
          }
          // Ensure worker is destroyed even if leak check failed
          NativeResourceManager.nativeDestroyWorker(workerInfo.handle);
        }
      } catch (Exception e) {
        KneafCore.LOGGER.error("Failed to check resource leak for worker {}", workerId, e);
      }
    }
    
    // Force cleanup buffer pool as additional safety measure
    try {
      NativeResourceManager.nativeCleanupBufferPool();
      KneafCore.LOGGER.info("Forced buffer pool cleanup completed");
    } catch (Exception e) {
      KneafCore.LOGGER.error("Failed to force cleanup buffer pool", e);
    }
  }
  
  /** Set resource limits for a worker. */
  public void setWorkerResourceLimits(long workerId, long maxMemory, long maxTasks) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);
    
    try {
      NativeResourceManager.nativeSetResourceLimits(workerInfo.handle, maxMemory, maxTasks);
      KneafCore.LOGGER.debug(
          "Set resource limits for worker {}: maxMemory={}, maxTasks={}",
          workerId, maxMemory, maxTasks);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set resource limits for worker " + workerId, e);
    }
  }
  
  /** Get resource statistics for a worker. */
  public String getWorkerResourceStats(long workerId) throws RuntimeException {
    WorkerInfo workerInfo = getWorkerInfo(workerId);
    
    try {
      return NativeResourceManager.nativeGetResourceStats(workerInfo.handle);
    } catch (Exception e) {
      throw new RuntimeException("Failed to get resource stats for worker " + workerId, e);
    }
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
    return "{}";
  }

  @Override
  public byte[] processMobAiBinary(ByteBuffer input) {
    // Implementation would use a worker to process mob AI
    return new byte[0];
  }

  @Override
  public String processMobAiJson(String input) {
    // Implementation would use a worker to process mob AI
    return "{}";
  }

  @Override
  public byte[] processBlockEntitiesBinary(ByteBuffer input) {
    // Implementation would use a worker to process block entities
    return new byte[0];
  }

  @Override
  public String processBlockEntitiesJson(String input) {
    // Implementation would use a worker to process block entities
    return "{}";
  }

  @Override
  public byte[] processVillagerAiBinary(ByteBuffer input) {
    // Implementation would use a worker to process villager AI
    return new byte[0];
  }

  @Override
  public String processVillagerAiJson(String jsonInput) {
    // Implementation would use a worker to process villager AI
    return "{}";
  }

  @Override
  public byte[] processItemEntitiesBinary(ByteBuffer input) {
    // Item optimization removed - return empty result as fallback
    return new byte[0];
  }

  @Override
  public String processItemEntitiesJson(String input) {
    // Item optimization removed - return empty result as fallback
    return "{}";
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
    // Use optimized memory pool with adaptive sizing and LRU eviction
    return NativeFloatBufferAllocation.allocate(rows, cols);
  }

  /** Free float buffer native with enhanced cleanup. */
  public void freeFloatBuffer(ByteBuffer buffer) {
    if (buffer == null) return;

    // Prefer native free if available
    try {
      NativeResourceManager.nativeFreeBuffer(buffer);
      KneafCore.LOGGER.debug("Successfully freed native buffer");
      return;
    } catch (Throwable t) {
  KneafCore.LOGGER.debug("Native buffer free failed, using fallback: {}", t.getMessage());
      // Fall back to Java-based cleanup below
    }

    // Enhanced Java-based cleanup for direct buffers
    try {
      if (buffer.isDirect()) {
        // First try standard Java 9+ Cleaner API
        try {
          java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
          cleanerMethod.setAccessible(true);
          Object cleaner = cleanerMethod.invoke(buffer);
          if (cleaner != null) {
            java.lang.reflect.Method clean = cleaner.getClass().getMethod("clean");
            clean.invoke(cleaner);
            KneafCore.LOGGER.debug("Successfully cleaned direct buffer via Cleaner API");
            return;
          }
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
          // Fall through to other strategies
          KneafCore.LOGGER.debug("Cleaner API not available, trying alternative approaches");
        }

        // Try sun.misc.Cleaner for older JDKs
        try {
          java.lang.reflect.Field cleanerField = buffer.getClass().getDeclaredField("cleaner");
          cleanerField.setAccessible(true);
          Object cleaner = cleanerField.get(buffer);
          if (cleaner != null) {
            java.lang.reflect.Method clean = cleaner.getClass().getMethod("clean");
            clean.invoke(cleaner);
            KneafCore.LOGGER.debug("Successfully cleaned direct buffer via sun.misc.Cleaner");
            return;
          }
        } catch (NoSuchFieldException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
          // If all reflection attempts fail, log and rely on GC
            KneafCore.LOGGER.debug(
              "Could not explicitly free direct ByteBuffer; relying on GC: {}",
              e.getMessage());
        }
      }
    } catch (Throwable t) {
      // Safe fallback - do nothing, GC will reclaim when possible
      KneafCore.LOGGER.debug("freeFloatBuffer fallback failed: {}", t.getMessage());
    }
  }
  
  /** Bulk free multiple buffers. */
  public void freeFloatBuffers(ByteBuffer[] buffers) {
    if (buffers == null || buffers.length == 0) return;
    
    for (ByteBuffer buffer : buffers) {
      freeFloatBuffer(buffer);
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
  // Native bridge for actual resource management
  private static class NativeResourceManager {
    // Track whether native library was successfully loaded for this class
    private static volatile boolean NATIVE_LIBRARY_LOADED = false;

    // Native method declarations for resource management
      public static native void nativeInitAllocator();
      public static native void nativeShutdownAllocator();
      public static native long nativeCreateWorker(int concurrency);
      public static native void nativeDestroyWorker(long workerHandle);
      public static native void nativePushTask(long workerHandle, byte[] payload);
      public static native void nativePushBatch(long workerHandle, byte[][] payloads, int count);
      public static native byte[] nativePollResult(long workerHandle);
      public static native long nativeGetWorkerQueueDepth(long workerHandle);
      public static native double nativeGetWorkerAvgProcessingMs(long workerHandle);
      public static native long nativeGetWorkerMemoryUsage(long workerHandle);
      public static native void nativeFreeBuffer(ByteBuffer buffer);
      public static native void nativeCleanupBufferPool();
      public static native void nativeForceCleanup(long resourceHandle);
      public static native boolean nativeIsResourceLeaked(long resourceHandle);
      public static native void nativeSetResourceLimits(long resourceHandle, long maxMemory, long maxTasks);
      public static native String nativeGetResourceStats(long resourceHandle);
      
      // Load native library
    static {
      try {
        // Attempt to load the native library via the shared loader
        boolean loaded = tryLoadNativeLibrary();

        if (loaded) {
          // Perform a lightweight probe to ensure core JNI symbols are present and usable.
          // Use the unified JniCallManager probe which is intentionally tiny and returns
          // a boolean. If this call succeeds and returns true we can safely assume
          // the native library exports JNI symbols with the correct signatures.
          try {
            boolean probe = com.kneaf.core.unifiedbridge.JniCallManager.getInstance().isNativeAvailable();
            if (probe) {
              NATIVE_LIBRARY_LOADED = true;
            } else {
              NATIVE_LIBRARY_LOADED = false;
              KneafCore.LOGGER.warn("Native library loaded but probe nativeIsAvailable() returned false; treating native as unavailable");
            }
          } catch (UnsatisfiedLinkError probeEx) {
            NATIVE_LIBRARY_LOADED = false;
            KneafCore.LOGGER.warn("Native library loaded but core JNI probe failed (nativeIsAvailable missing): " + probeEx.getMessage(), probeEx);
          } catch (Throwable probeEx) {
            NATIVE_LIBRARY_LOADED = false;
            KneafCore.LOGGER.warn("Native library loaded but core JNI probe threw an unexpected error: " + probeEx.getMessage(), probeEx);
          }
        } else {
          NATIVE_LIBRARY_LOADED = false;
        }
      } catch (Throwable t) {
        NATIVE_LIBRARY_LOADED = false;
        KneafCore.LOGGER.warn("Error while attempting to load native library: " + t.getMessage(), t);
      }
    }

    public static boolean isNativeLibraryLoaded() {
      return NATIVE_LIBRARY_LOADED;
    }
      
      // Prevent direct instantiation
      private NativeResourceManager() {}
  }

  private static class WorkerInfo {
    private final long handle;
    private final int concurrency;
    private final AtomicLong taskCount = new AtomicLong(0);
    private final AtomicLong resultCount = new AtomicLong(0);
    private final AtomicBoolean isCleanedUp = new AtomicBoolean(false);

    public WorkerInfo(long id, long handle, int concurrency) {
      this.handle = handle;
      this.concurrency = concurrency;
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
