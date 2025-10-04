package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Optimized memory pool for native float buffer allocations with adaptive sizing,
 * LRU eviction, and memory pressure-aware allocation.
 */
public final class NativeFloatBufferAllocation {
  // Memory pool configuration
  private static final int INITIAL_POOL_SIZE = 16;
  private static final int MAX_POOL_SIZE = 128;
  private static final double MEMORY_PRESSURE_THRESHOLD = 0.8;
  private static final double ADAPTIVE_GROWTH_FACTOR = 1.5;
  private static final double ADAPTIVE_SHRINK_FACTOR = 0.75;

  // LRU cache for buffer reuse
  private static final LinkedHashMap<BufferKey, PooledBuffer> bufferPool = new LinkedHashMap<BufferKey, PooledBuffer>(INITIAL_POOL_SIZE, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<BufferKey, PooledBuffer> eldest) {
      return size() > currentMaxPoolSize.get();
    }
  };

  // Adaptive sizing state
  private static final AtomicLong currentMaxPoolSize = new AtomicLong(INITIAL_POOL_SIZE);
  private static final AtomicLong totalAllocations = new AtomicLong(0);
  private static final AtomicLong poolHits = new AtomicLong(0);
  private static final AtomicLong poolMisses = new AtomicLong(0);

  // Memory pressure monitoring
  private static final Runtime runtime = Runtime.getRuntime();
  private static final ReentrantLock poolLock = new ReentrantLock();

  // Instance fields (backward compatibility)
  private final ByteBuffer buffer;
  private final long rows;
  private final long cols;
  private final boolean fromPool;
  private final BufferKey key;

  /**
   * Create a new buffer allocation, potentially reusing from pool.
   */
  public static NativeFloatBufferAllocation allocate(long rows, long cols) {
    BufferKey key = new BufferKey(rows, cols);
    long bufferSize = rows * cols * 4L; // 4 bytes per float

    poolLock.lock();
    try {
      // Check memory pressure
      if (isMemoryPressureHigh()) {
        performEviction();
      }

      // Try to get from pool
      PooledBuffer pooled = bufferPool.get(key);
      if (pooled != null && pooled.isValid()) {
        poolHits.incrementAndGet();
        pooled.markUsed();
        return new NativeFloatBufferAllocation(pooled.getBuffer(), rows, cols, true, key);
      }

      // Pool miss - create new buffer
      poolMisses.incrementAndGet();
      totalAllocations.incrementAndGet();

      ByteBuffer buffer = ByteBuffer.allocateDirect((int)bufferSize);

      // Adaptive sizing: grow pool if hit rate is good
      adaptPoolSize();

      return new NativeFloatBufferAllocation(buffer, rows, cols, false, key);

    } finally {
      poolLock.unlock();
    }
  }

  /**
   * Legacy constructor for backward compatibility.
   */
  public NativeFloatBufferAllocation(ByteBuffer buffer, long rows, long cols) {
    this(buffer, rows, cols, false, null);
  }

  private NativeFloatBufferAllocation(ByteBuffer buffer, long rows, long cols, boolean fromPool, BufferKey key) {
    this.buffer = buffer;
    this.rows = rows;
    this.cols = cols;
    this.fromPool = fromPool;
    this.key = key;
  }

  public ByteBuffer getBuffer() {
    return buffer;
  }

  public long getRows() {
    return rows;
  }

  public long getCols() {
    return cols;
  }

  /**
   * Return this allocation to the pool for reuse.
   */
  public void release() {
    if (fromPool && key != null) {
      poolLock.lock();
      try {
        // Reset buffer position
        buffer.clear();

        // Add back to pool if not already present
        if (!bufferPool.containsKey(key)) {
          bufferPool.put(key, new PooledBuffer(buffer, System.nanoTime()));
        }
      } finally {
        poolLock.unlock();
      }
    }
  }

  /**
   * Get pool statistics.
   */
  public static PoolStats getPoolStats() {
    poolLock.lock();
    try {
      long totalRequests = poolHits.get() + poolMisses.get();
      double hitRate = totalRequests > 0 ? (double) poolHits.get() / totalRequests : 0.0;

      return new PoolStats(
        bufferPool.size(),
        currentMaxPoolSize.get(),
        totalAllocations.get(),
        hitRate,
        getMemoryPressureLevel()
      );
    } finally {
      poolLock.unlock();
    }
  }

  private static boolean isMemoryPressureHigh() {
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    return (double) usedMemory / maxMemory > MEMORY_PRESSURE_THRESHOLD;
  }

  private static void performEviction() {
    // Remove least recently used buffers until memory pressure is relieved
    int evicted = 0;
    while (isMemoryPressureHigh() && !bufferPool.isEmpty()) {
      BufferKey eldestKey = bufferPool.keySet().iterator().next();
      bufferPool.remove(eldestKey);
      evicted++;
    }

    if (evicted > 0) {
      // Shrink pool size adaptively
      long newSize = Math.max(INITIAL_POOL_SIZE,
        (long)(currentMaxPoolSize.get() * ADAPTIVE_SHRINK_FACTOR));
      currentMaxPoolSize.set(newSize);
    }
  }

  private static void adaptPoolSize() {
    long totalRequests = poolHits.get() + poolMisses.get();
    if (totalRequests >= 100) { // Only adapt after sufficient requests
      double hitRate = (double) poolHits.get() / totalRequests;

      if (hitRate > 0.7 && currentMaxPoolSize.get() < MAX_POOL_SIZE) {
        // High hit rate - grow pool
        long newSize = Math.min(MAX_POOL_SIZE,
          (long)(currentMaxPoolSize.get() * ADAPTIVE_GROWTH_FACTOR));
        currentMaxPoolSize.set(newSize);
      } else if (hitRate < 0.3 && currentMaxPoolSize.get() > INITIAL_POOL_SIZE) {
        // Low hit rate - shrink pool
        long newSize = Math.max(INITIAL_POOL_SIZE,
          (long)(currentMaxPoolSize.get() * ADAPTIVE_SHRINK_FACTOR));
        currentMaxPoolSize.set(newSize);
      }
    }
  }

  private static MemoryPressureLevel getMemoryPressureLevel() {
    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
    long maxMemory = runtime.maxMemory();
    double ratio = (double) usedMemory / maxMemory;

    if (ratio < 0.6) return MemoryPressureLevel.LOW;
    else if (ratio < 0.8) return MemoryPressureLevel.MODERATE;
    else if (ratio < 0.9) return MemoryPressureLevel.HIGH;
    else return MemoryPressureLevel.CRITICAL;
  }

  /**
   * Buffer key for pool indexing.
   */
  private static class BufferKey {
    final long rows;
    final long cols;

    BufferKey(long rows, long cols) {
      this.rows = rows;
      this.cols = cols;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof BufferKey)) return false;
      BufferKey that = (BufferKey) o;
      return rows == that.rows && cols == that.cols;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(rows) * 31 + Long.hashCode(cols);
    }
  }

  /**
   * Pooled buffer wrapper with usage tracking.
   */
  private static class PooledBuffer {
    private final ByteBuffer buffer;
    @SuppressWarnings("unused")
    private final long creationTime;
    @SuppressWarnings("unused")
    private volatile long lastUsedTime;

    PooledBuffer(ByteBuffer buffer, long creationTime) {
      this.buffer = buffer;
      this.creationTime = creationTime;
      this.lastUsedTime = creationTime;
    }

    ByteBuffer getBuffer() {
      markUsed();
      return buffer;
    }

    void markUsed() {
      lastUsedTime = System.nanoTime();
    }

    boolean isValid() {
      // Check if buffer is still valid (not garbage collected)
      return buffer != null && buffer.isDirect();
    }
  }

  /**
   * Memory pressure levels.
   */
  public enum MemoryPressureLevel {
    LOW, MODERATE, HIGH, CRITICAL
  }

  /**
   * Pool statistics.
   */
  public static class PoolStats {
    public final int currentSize;
    public final long maxSize;
    public final long totalAllocations;
    public final double hitRate;
    public final MemoryPressureLevel memoryPressure;

    PoolStats(int currentSize, long maxSize, long totalAllocations, double hitRate, MemoryPressureLevel memoryPressure) {
      this.currentSize = currentSize;
      this.maxSize = maxSize;
      this.totalAllocations = totalAllocations;
      this.hitRate = hitRate;
      this.memoryPressure = memoryPressure;
    }
  }
}
