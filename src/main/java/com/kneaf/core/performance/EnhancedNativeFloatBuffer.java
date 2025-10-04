package com.kneaf.core.performance;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
// cleaned: removed unused imports
import java.util.concurrent.locks.StampedLock;

/**
 * Enhanced NativeFloatBuffer with advanced memory pooling and allocation strategies. Implements
 * lock-free algorithms, memory pressure adaptation, and predictive allocation.
 */
public final class EnhancedNativeFloatBuffer implements AutoCloseable {
  private static final Cleaner CLEANER = Cleaner.create();

  // Enhanced pooling configuration with adaptive sizing
  private static final int BUCKET_SIZE_POWER = 12; // 4096 byte buckets
  // Default powers remain; intervals are adaptive via PerformanceConstants

  // Memory pressure levels
  private static final int MEMORY_PRESSURE_LOW = 0;
  private static final int MEMORY_PRESSURE_MODERATE = 1;
  private static final int MEMORY_PRESSURE_HIGH = 2;
  private static final int MEMORY_PRESSURE_CRITICAL = 3;

  // Enhanced pool management with lock-free data structures
  private static final ConcurrentHashMap<Integer, LockFreePool> BUFFER_POOLS =
      new ConcurrentHashMap<>();
  private static final AtomicReference<MemoryPressureLevel> CURRENT_PRESSURE =
      new AtomicReference<>(
          new MemoryPressureLevel(MEMORY_PRESSURE_LOW, System.currentTimeMillis()));

  // Performance metrics
  private static final AtomicLong TOTAL_ALLOCATIONS = new AtomicLong(0);
  private static final AtomicLong TOTAL_REUSES = new AtomicLong(0);
  private static final AtomicLong TOTAL_FREES = new AtomicLong(0);
  private static final AtomicLong ALLOCATION_FAILURES = new AtomicLong(0);
  private static final AtomicLong ADAPTIVE_RESIZES = new AtomicLong(0);

  // Adaptive sizing state
  private static final AtomicReference<AdaptivePoolConfig> ADAPTIVE_CONFIG =
      new AtomicReference<>(new AdaptivePoolConfig(10, 25, 0.75));
  private static final StampedLock CONFIG_LOCK = new StampedLock();

  /** Lock-free pool implementation using atomic operations */
  private static final class LockFreePool {
    private final ConcurrentLinkedQueue<PooledBuffer> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger(0);
    private final AtomicInteger highWaterMark = new AtomicInteger(0);

    @SuppressWarnings("unused")
    private final int bucketIndex;

    LockFreePool(int bucketIndex) {
      this.bucketIndex = bucketIndex;
    }

    boolean offer(PooledBuffer buffer) {
      if (size.get() < getCurrentMaxSize()) {
        queue.offer(buffer);
        int newSize = size.incrementAndGet();
        highWaterMark.updateAndGet(current -> Math.max(current, newSize));
        return true;
      }
      return false;
    }

    PooledBuffer poll() {
      PooledBuffer buffer = queue.poll();
      if (buffer != null) {
        size.decrementAndGet();
      }
      return buffer;
    }

    int getSize() {
      return size.get();
    }

    int getHighWaterMark() {
      return highWaterMark.get();
    }

    void clearExcess(int targetSize) {
      while (size.get() > targetSize) {
        PooledBuffer buffer = queue.poll();
        if (buffer != null) {
          size.decrementAndGet();
          // Free the buffer directly using RustPerformance
          try {
            RustPerformance.freeFloatBufferNative(buffer.buffer);
            TOTAL_FREES.incrementAndGet();
          } catch (Exception e) {
            System.err.println("Error freeing buffer in clearExcess: " + e.getMessage());
          }
        } else {
          break;
        }
      }
    }

    private int getCurrentMaxSize() {
      MemoryPressureLevel pressure = CURRENT_PRESSURE.get();
      AdaptivePoolConfig config = ADAPTIVE_CONFIG.get();

      return switch (pressure.level) {
        case MEMORY_PRESSURE_LOW -> config.maxpoolSize;
        case MEMORY_PRESSURE_MODERATE -> (int) (config.maxpoolSize * config.pressureFactor);
        case MEMORY_PRESSURE_HIGH -> (int) (config.maxpoolSize * config.pressureFactor * 0.5);
        case MEMORY_PRESSURE_CRITICAL -> config.minpoolSize;
        default -> config.maxpoolSize;
      };
    }
  }

  /** Memory pressure level tracking */
  private static final class MemoryPressureLevel {
    final int level;
    final long timestamp;

    MemoryPressureLevel(int level, long timestamp) {
      this.level = level;
      this.timestamp = timestamp;
    }
  }

  /** Adaptive pool configuration */
  private static final class AdaptivePoolConfig {
    final int minpoolSize;
    final int maxpoolSize;
    final double pressureFactor;

    AdaptivePoolConfig(int minpoolSize, int maxpoolSize, double pressureFactor) {
      this.minpoolSize = minpoolSize;
      this.maxpoolSize = maxpoolSize;
      this.pressureFactor = pressureFactor;
    }
  }

  /** Enhanced pooled buffer with metadata */
  private static final class PooledBuffer {
    final ByteBuffer buffer;
    final int bucketIndex;

    @SuppressWarnings("unused")
    final long creationTime;

    @SuppressWarnings("unused")
    final int reuseCount;

    PooledBuffer(ByteBuffer buffer, int bucketIndex, long creationTime, int reuseCount) {
      this.buffer = buffer;
      this.bucketIndex = bucketIndex;
      this.creationTime = creationTime;
      this.reuseCount = reuseCount;
    }
  }

  /** Enhanced state for cleaner with pooling logic */
  private static final class EnhancedState implements Runnable {
    private final ByteBuffer buf;
    private final boolean isPooled;
    private final int bucketIndex;
    private final long creationTime;
    private final int reuseCount;

    EnhancedState(
        ByteBuffer buf, boolean isPooled, int bucketIndex, long creationTime, int reuseCount) {
      this.buf = buf;
      this.isPooled = isPooled;
      this.bucketIndex = bucketIndex;
      this.creationTime = creationTime;
      this.reuseCount = reuseCount;
    }

    private void returnToPool() {
      if (reuseCount < 10
          && System.currentTimeMillis() - creationTime < 300000) { // 5 minutes max age
        int maxBucket =
            com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(
                com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
        int bucket = Math.max(0, Math.min(maxBucket, bucketIndex));
        LockFreePool pool = BUFFER_POOLS.computeIfAbsent(bucket, k -> new LockFreePool(bucket));

        PooledBuffer pooled = new PooledBuffer(buf, bucketIndex, creationTime, reuseCount + 1);
        if (pool.offer(pooled)) {
          TOTAL_REUSES.incrementAndGet();
          return;
        }
      }

      // Pool is full or buffer too old, free it
      freeBufferDirect();
    }

    private void freeBufferDirect() {
      try {
        RustPerformance.freeFloatBufferNative(buf);
        TOTAL_FREES.incrementAndGet();
      } catch (UnsatisfiedLinkError e) {
        System.err.println("Native library not available for buffer free: " + e.getMessage());
      } catch (Exception e) {
        System.err.println("Error freeing native buffer: " + e.getMessage());
      }
    }

    @Override
    public void run() {
      try {
        if (buf != null) {
          if (isPooled && bucketIndex >= 0) {
            returnToPool();
          } else {
            freeBufferDirect();
          }
        }
      } catch (Exception t) {
        System.err.println("Error during enhanced buffer cleanup: " + t.getMessage());
      }
    }
  }

  private final ByteBuffer buf;
  private final java.nio.FloatBuffer view;
  private final long rows;
  private final long cols;
  private final long elementCount;
  private final int byteCapacity;
  private final Cleaner.Cleanable cleanable;
  private volatile boolean closed = false;

  @SuppressWarnings("unused")
  private final boolean isPooled;

  @SuppressWarnings("unused")
  private final int bucketIndex;

  @SuppressWarnings("unused")
  private final long creationTime;

  private EnhancedNativeFloatBuffer(
      ByteBuffer buf, long rows, long cols, boolean isPooled, int bucketIndex) {
    if (buf == null) throw new IllegalArgumentException("buf must not be null");
    if (rows < 0 || cols < 0) throw new IllegalArgumentException("rows/cols must be non-negative");

    this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
    this.rows = rows;
    this.cols = cols;
    this.elementCount = rows * cols;
    this.byteCapacity = this.buf.capacity();
    this.view = this.buf.asFloatBuffer();
    this.creationTime = System.currentTimeMillis();
    this.cleanable =
        CLEANER.register(this, new EnhancedState(this.buf, isPooled, bucketIndex, creationTime, 0));
    this.isPooled = isPooled;
    this.bucketIndex = bucketIndex;
  }

  /** Calculate bucket index for given byte capacity with adaptive sizing */
  private static int getBucketIndex(int byteCapacity) {
    int bucket = 0;
    int size = 1 << BUCKET_SIZE_POWER; // Start with minimum bucket size (4096)

    int maxBucket =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(
            com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
    while (size < byteCapacity && bucket < maxBucket) {
      size <<= 1;
      bucket++;
    }
    return bucket;
  }

  /** Get buffer from enhanced pool with adaptive sizing */
  private static PooledBuffer getFromEnhancedPool(int byteCapacity) {
    int bucketIndex = getBucketIndex(byteCapacity);

    // Try exact bucket first
    LockFreePool pool = BUFFER_POOLS.get(bucketIndex);
    if (pool != null) {
      PooledBuffer pooled = pool.poll();
      if (pooled != null) {
        pooled.buffer.clear();
        return pooled;
      }
    }

    // Try larger buckets for better memory utilization
    int maxBucket =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(
            com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
    for (int i = bucketIndex + 1; i <= Math.min(bucketIndex + 2, maxBucket); i++) {
      LockFreePool largerPool = BUFFER_POOLS.get(i);
      if (largerPool != null) {
        PooledBuffer pooled = largerPool.poll();
        if (pooled != null) {
          pooled.buffer.clear();
          return pooled;
        }
      }
    }

    return null;
  }

  /** Adaptive pool management based on memory pressure and usage patterns */
  private static void performAdaptivePoolManagement() {
    long stamp = CONFIG_LOCK.readLock();
    try {
      int totalBuffers = 0;
      int totalHighWater = 0;

      for (LockFreePool pool : BUFFER_POOLS.values()) {
        totalBuffers += pool.getSize();
        totalHighWater += pool.getHighWaterMark();
      }

      // Calculate memory pressure based on usage patterns
      double utilizationRate = totalHighWater > 0 ? (double) totalBuffers / totalHighWater : 0.0;
      long currentTime = System.currentTimeMillis();
      MemoryPressureLevel currentLevel = CURRENT_PRESSURE.get();

      int newPressureLevel = MEMORY_PRESSURE_LOW;
      if (utilizationRate > 0.9) {
        newPressureLevel = MEMORY_PRESSURE_CRITICAL;
      } else if (utilizationRate > 0.7) {
        newPressureLevel = MEMORY_PRESSURE_HIGH;
      } else if (utilizationRate > 0.5) {
        newPressureLevel = MEMORY_PRESSURE_MODERATE;
      }

      // Update pressure level if significant change or timeout
      long adaptiveCheckInterval =
          com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveEnhancedCheckIntervalMs(
              com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
      if (newPressureLevel != currentLevel.level
          || currentTime - currentLevel.timestamp > adaptiveCheckInterval) {
        CURRENT_PRESSURE.set(new MemoryPressureLevel(newPressureLevel, currentTime));
        ADAPTIVE_RESIZES.incrementAndGet();
      }

      // Perform cleanup based on current pressure
      if (newPressureLevel >= MEMORY_PRESSURE_HIGH) {
        // Aggressive cleanup: reduce pools to 25% of current size
        for (var entry : BUFFER_POOLS.entrySet()) {
          LockFreePool pool = entry.getValue();
          int currentSize = pool.getSize();
          int targetSize = Math.max(1, currentSize / 4);
          pool.clearExcess(targetSize);
        }
      } else if (newPressureLevel == MEMORY_PRESSURE_MODERATE) {
        // Light cleanup: reduce pools to 50% of current size
        for (var entry : BUFFER_POOLS.entrySet()) {
          LockFreePool pool = entry.getValue();
          int currentSize = pool.getSize();
          int targetSize = Math.max(2, currentSize / 2);
          pool.clearExcess(targetSize);
        }
      }

    } finally {
      CONFIG_LOCK.unlockRead(stamp);
    }
  }

  /** Enhanced allocation with predictive sizing */
  public static EnhancedNativeFloatBuffer allocateEnhanced(long rows, long cols) {
    return allocateEnhanced(rows, cols, true);
  }

  public static EnhancedNativeFloatBuffer allocateEnhanced(long rows, long cols, boolean usePool) {
    try {
      int requiredBytes = (int) (rows * cols * 4);

      if (usePool) {
        // Try to get from enhanced pool first
        PooledBuffer pooled = getFromEnhancedPool(requiredBytes);
        if (pooled != null) {
          TOTAL_REUSES.incrementAndGet();
          return new EnhancedNativeFloatBuffer(pooled.buffer, rows, cols, true, pooled.bucketIndex);
        }
      }

      // Allocate new buffer with enhanced error handling
      try {
        NativeFloatBufferAllocation alloc =
            RustPerformance.generateFloatBufferWithShapeNative(rows, cols);
        if (alloc == null) {
          ALLOCATION_FAILURES.incrementAndGet();
          return null;
        }

        TOTAL_ALLOCATIONS.incrementAndGet();
        int bucketIndex = usePool ? getBucketIndex(alloc.getBuffer().capacity()) : -1;
        return new EnhancedNativeFloatBuffer(
            alloc.getBuffer(), alloc.getRows(), alloc.getCols(), usePool, bucketIndex);
      } catch (UnsatisfiedLinkError e) {
        // Fallback to older API
        try {
          ByteBuffer b = RustPerformance.generateFloatBufferNative((int) rows, (int) cols);
          if (b == null) {
            ALLOCATION_FAILURES.incrementAndGet();
            return null;
          }

          TOTAL_ALLOCATIONS.incrementAndGet();
          int bucketIndex = usePool ? getBucketIndex(b.capacity()) : -1;
          return new EnhancedNativeFloatBuffer(b, rows, cols, usePool, bucketIndex);
        } catch (UnsatisfiedLinkError e2) {
          ALLOCATION_FAILURES.incrementAndGet();
          System.err.println(
              "Native library not available for enhanced buffer allocation: " + e2.getMessage());
          return null;
        }
      } catch (Exception e) {
        ALLOCATION_FAILURES.incrementAndGet();
        System.err.println("Error allocating enhanced native buffer: " + e.getMessage());
        return null;
      }
    } catch (UnsatisfiedLinkError e) {
      ALLOCATION_FAILURES.incrementAndGet();
      System.err.println(
          "Native library not available for enhanced buffer allocation: " + e.getMessage());
      return null;
    }
  }

  /** Return the underlying ByteBuffer for read/write access. */
  public ByteBuffer buffer() {
    if (closed) throw new IllegalStateException("EnhancedNativeFloatBuffer is closed");
    return buf;
  }

  /** Convenience to read float at element index (0-based). */
  public float getFloatAtIndex(int idx) {
    return view.get(idx);
  }

  /** Convenience to set float at element index (0-based). */
  public void setFloatAtIndex(int idx, float val) {
    view.put(idx, val);
  }

  /** Return a FloatBuffer view to the whole underlying buffer. */
  public java.nio.FloatBuffer asFloatBuffer() {
    checkOpen();
    return view.duplicate();
  }

  /** Return a FloatBuffer representing a single row (0-based). */
  public java.nio.FloatBuffer rowBuffer(int row) {
    checkOpen();
    if (row < 0 || row >= rows) throw new IndexOutOfBoundsException("row out of range");
    int start = (int) (row * cols);
    java.nio.FloatBuffer dup = view.duplicate();
    dup.position(start);
    dup.limit(start + (int) cols);
    return dup.slice();
  }

  /** Return a FloatBuffer representing a single column (0-based). */
  public java.nio.FloatBuffer colBuffer(int col) {
    checkOpen();
    if (col < 0 || col >= cols) throw new IndexOutOfBoundsException("col out of range");

    // Bulk copy column into a new non-direct FloatBuffer to minimize per-element Java calls
    int rCount = (int) rows;
    float[] tmp = new float[rCount];
    int base = col;
    int c = (int) cols;
    for (int r = 0; r < rCount; r++) {
      tmp[r] = view.get(base + r * c);
    }
    return java.nio.FloatBuffer.wrap(tmp);
  }

  /** Fill the entire buffer with a value. Optimized version using bulk operations. */
  public void fill(float value) {
    checkOpen();
    int cap = view.capacity();

    // Use vectorized fill for better performance
    if (cap > 1000) {
      // Fill in SIMD-friendly chunks
      int chunkSize = Math.min(cap, 16384); // 16KB chunks
      float[] chunk = new float[chunkSize];
      java.util.Arrays.fill(chunk, value);

      for (int i = 0; i < cap; i += chunkSize) {
        int remaining = Math.min(chunkSize, cap - i);
        view.position(i);
        view.put(chunk, 0, remaining);
      }
      view.position(0); // Reset position
    } else {
      // Direct fill for smaller buffers
      for (int i = 0; i < cap; i++) {
        view.put(i, value);
      }
    }
  }

  /** Copy the contents into another EnhancedNativeFloatBuffer (must match shape). */
  public void copyTo(EnhancedNativeFloatBuffer dest) {
    checkOpen();
    if (dest == null) throw new IllegalArgumentException("dest is null");
    if (dest.getElementCount() != this.getElementCount())
      throw new IllegalArgumentException("shape mismatch");
    java.nio.FloatBuffer s = this.asFloatBuffer();
    java.nio.FloatBuffer d = dest.asFloatBuffer();
    d.position(0);
    s.position(0);
    d.put(s);
  }

  /** Row/col helpers (0-based). */
  public float getFloatAt(long row, long col) {
    checkOpen();
    checkBounds(row, col);
    int idx = (int) (row * cols + col);
    return view.get(idx);
  }

  public void setFloatAt(long row, long col, float val) {
    checkOpen();
    checkBounds(row, col);
    int idx = (int) (row * cols + col);
    view.put(idx, val);
  }

  private void checkOpen() {
    if (closed) throw new IllegalStateException("EnhancedNativeFloatBuffer is closed");
  }

  private void checkBounds(long row, long col) {
    if (row < 0 || col < 0 || row >= rows || col >= cols) {
      throw new IndexOutOfBoundsException("row/col out of range");
    }
  }

  public long getRows() {
    return rows;
  }

  public long getCols() {
    return cols;
  }

  public long getElementCount() {
    return elementCount;
  }

  public int getByteCapacity() {
    return byteCapacity;
  }

  /** Get enhanced pool statistics for monitoring */
  public static String getEnhancedPoolStats() {
    StringBuilder Stats = new StringBuilder();
    Stats.append("Enhanced Buffer Pool Statistics:\n");
    Stats.append("Total Allocations: ").append(TOTAL_ALLOCATIONS.get()).append("\n");
    Stats.append("Total Reuses: ").append(TOTAL_REUSES.get()).append("\n");
    Stats.append("Total Frees: ").append(TOTAL_FREES.get()).append("\n");
    Stats.append("Allocation Failures: ").append(ALLOCATION_FAILURES.get()).append("\n");
    Stats.append("Adaptive Resizes: ").append(ADAPTIVE_RESIZES.get()).append("\n");
    Stats.append("Reuse Rate: ")
        .append(
            String.format(
                "%.2f%%",
                TOTAL_ALLOCATIONS.get() > 0
                    ? (100.0 * TOTAL_REUSES.get() / TOTAL_ALLOCATIONS.get())
                    : 0.0))
        .append("\n");

    MemoryPressureLevel pressure = CURRENT_PRESSURE.get();
    Stats.append("Current Memory Pressure: ").append(getPressureName(pressure.level)).append("\n");

    AdaptivePoolConfig config = ADAPTIVE_CONFIG.get();
    Stats.append("Pool Config - Min: ")
        .append(config.minpoolSize)
        .append(", Max: ")
        .append(config.maxpoolSize)
        .append(", Pressure Factor: ")
        .append(config.pressureFactor)
        .append("\n");

    Stats.append("Pool Buckets:\n");
    int totalBuffers = 0;
    int totalHighWater = 0;

    for (var entry : BUFFER_POOLS.entrySet()) {
      LockFreePool pool = entry.getValue();
      int currentSize = pool.getSize();
      int highWater = pool.getHighWaterMark();
      int bucketByteSize = (1 << (BUCKET_SIZE_POWER + entry.getKey()));
      totalBuffers += currentSize;
      totalHighWater += highWater;

      Stats.append("  Bucket ")
          .append(entry.getKey())
          .append(" (")
          .append(bucketByteSize)
          .append(" bytes): ")
          .append(currentSize)
          .append(" current, ")
          .append(highWater)
          .append(" peak\n");
    }

    Stats.append("Total Buffers in Pools: ").append(totalBuffers).append("\n");
    Stats.append("Total High Water Mark: ").append(totalHighWater).append("\n");
    Stats.append("Pool Utilization: ")
        .append(
            String.format(
                "%.2f%%", totalHighWater > 0 ? (100.0 * totalBuffers / totalHighWater) : 0.0))
        .append("\n");

    return Stats.toString();
  }

  private static String getPressureName(int level) {
    return switch (level) {
      case MEMORY_PRESSURE_LOW -> "LOW";
      case MEMORY_PRESSURE_MODERATE -> "MODERATE";
      case MEMORY_PRESSURE_HIGH -> "HIGH";
      case MEMORY_PRESSURE_CRITICAL -> "CRITICAL";
      default -> "UNKNOWN";
    };
  }

  /** Manually trigger enhanced pool cleanup */
  public static void forceEnhancedCleanup() {
    performAdaptivePoolManagement();
  }

  /** Clear all enhanced pools and free all buffers */
  public static void clearEnhancedPools() {
    for (var entry : BUFFER_POOLS.entrySet()) {
      LockFreePool pool = entry.getValue();
      pool.clearExcess(0);
    }
    BUFFER_POOLS.clear();
  }

  /** Update adaptive configuration */
  public static void updateAdaptiveConfig(int minpoolSize, int maxpoolSize, double pressureFactor) {
    long stamp = CONFIG_LOCK.writeLock();
    try {
      ADAPTIVE_CONFIG.set(new AdaptivePoolConfig(minpoolSize, maxpoolSize, pressureFactor));
    } finally {
      CONFIG_LOCK.unlockWrite(stamp);
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      cleanable.clean();
    }
  }

  // Static initializer for enhanced buffer management
  static {
    // Start adaptive pool management thread
    Thread adaptiveThread =
        new Thread(
            () -> {
              while (true) {
                try {
                  long cleanupInterval =
                      com.kneaf.core.performance.core.PerformanceConstants
                          .getAdaptiveBufferCleanupIntervalMs(
                              com.kneaf.core.performance.monitoring.PerformanceManager
                                  .getAverageTPS());
                  Thread.sleep(cleanupInterval);
                  performAdaptivePoolManagement();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Exception e) {
                  System.err.println("Error in adaptive pool management: " + e.getMessage());
                }
              }
            },
            "EnhancedNativeFloatBuffer-AdaptiveManager");
    adaptiveThread.setDaemon(true);
    adaptiveThread.start();
  }
}
