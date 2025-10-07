package com.kneaf.core.performance;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AutoCloseable wrapper for ByteBuffers allocated by native code with buffer pooling. Implements
 * size-based buffer pooling to reduce native alloc/dealloc cycles. Ensures the corresponding native
 * free call is invoked when closed or when the wrapper is reclaimed by the Cleaner.
 */
public final class NativeFloatBuffer implements AutoCloseable {
  private static final Cleaner CLEANER = Cleaner.create();

  // Static initializer to ensure native library is loaded
  static {
    try {
      // This will trigger RustPerformance static initializer if not already loaded
      Class.forName("com.kneaf.core.performance.RustPerformance");
    } catch (ClassNotFoundException e) {
      System.err.println(
          "RustPerformance class not found, native float buffer operations may fail");
    } catch (ExceptionInInitializerError e) {
      System.err.println(
          "RustPerformance initialization failed, native float buffer operations disabled: "
              + e.getMessage());
    } catch (Throwable t) {
      System.err.println(
          "Unexpected error during NativeFloatBuffer static initialization: " + t.getMessage());
    }
  }

  // Optimized buffer pooling configuration with enhanced size granularity
  private static final int[] BUCKET_SIZES = {
      4096,    // 4KB - Tiny
      8192,    // 8KB - Small
      16384,   // 16KB - Medium
      32768,   // 32KB - Large
      65536,   // 64KB - XLarge
      131072,  // 128KB - Huge
      262144,  // 256KB - Giant
      524288,  // 512KB - Massive
      1048576  // 1MB+ - Oversized
  };
  private static final int NUM_BUCKETS = BUCKET_SIZES.length;

  // Pool management with enhanced size-based bucketing
  private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<PooledBuffer>>
      BUFFER_POOLS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, AtomicInteger> POOL_SIZES =
      new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, Long> BUCKET_USAGE = new ConcurrentHashMap<>();
  private static final AtomicLong TOTAL_FRAGMENTATION = new AtomicLong(0);
  private static final AtomicLong LAST_CLEANUP_TIME = new AtomicLong(System.currentTimeMillis());
  private static final AtomicLong TOTAL_ALLOCATIONS = new AtomicLong(0);
  private static final AtomicLong TOTAL_REUSES = new AtomicLong(0);
  private static final AtomicLong TOTAL_FREES = new AtomicLong(0);

  /** Internal class to hold pooled buffer information */
  private static final class PooledBuffer {
    final ByteBuffer buffer;
    final int bucketIndex;

    PooledBuffer(ByteBuffer buffer, int bucketIndex) {
      this.buffer = buffer;
      this.bucketIndex = bucketIndex;
    }
  }

  /**
   * Safely get TPS value for adaptive buffer sizing. Falls back to 20.0 (optimal TPS) if Minecraft
   * classes are not available (e.g., in tests).
   */
  private static double getSafeTPS() {
    try {
      // Check if Minecraft classes are available before trying to access PerformanceManager
      Class.forName("net.minecraft.network.chat.Component");
      return com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    } catch (ClassNotFoundException e) {
      // Minecraft classes not available in test environment
      return 20.0; // Default optimal TPS
    } catch (Exception e) {
      // Method may not exist or other issues
      return 20.0; // Default optimal TPS
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

  private final boolean isPooled;

  private final int bucketIndex;
  
  private final long operationId;

  private static final class State implements Runnable {
    private final ByteBuffer buf;
    private final boolean isPooled;
    private final int bucketIndex;

    State(ByteBuffer buf, boolean isPooled, int bucketIndex) {
      this.buf = buf;
      this.isPooled = isPooled;
      this.bucketIndex = bucketIndex;
    }

    private void returnToPool() {
      // Ensure bucket exists
      BUFFER_POOLS.computeIfAbsent(bucketIndex, k -> new ConcurrentLinkedQueue<>());
      POOL_SIZES.computeIfAbsent(bucketIndex, k -> new AtomicInteger(0));

      int currentSize = POOL_SIZES.get(bucketIndex).get();
      int maxPerBucket =
          com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxPoolPerBucket(
              getSafeTPS());
      if (currentSize < maxPerBucket) {
        PooledBuffer pooled = new PooledBuffer(buf, bucketIndex);
        BUFFER_POOLS.get(bucketIndex).offer(pooled);
        POOL_SIZES.get(bucketIndex).incrementAndGet();
      } else {
        // Pool is full, free the buffer
        freeBuffer();
      }

      // Periodic cleanup (adaptive interval)
      long now = System.currentTimeMillis();
      long lastCleanup = LAST_CLEANUP_TIME.get();
      long interval =
          com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBufferCleanupIntervalMs(
              getSafeTPS());
      if (now - lastCleanup > interval && LAST_CLEANUP_TIME.compareAndSet(lastCleanup, now)) {
        performCleanup();
      }
    }

    private void freeBuffer() {
      try {
        RustPerformance.freeFloatBufferNative(buf);
        TOTAL_FREES.incrementAndGet();
      } catch (UnsatisfiedLinkError e) {
        // Native library not available, buffer will be freed by GC
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
            // Return to pool instead of freeing
            returnToPool();
          } else {
            // Free non-pooled buffer
            freeBuffer();
          }
        }
      } catch (Exception t) {
        // Avoid throwing during cleanup
        System.err.println("Error during buffer cleanup: " + t.getMessage());
      }
    }
  }

  /**
   * Main constructor with operation ID for zero-copy tracking
   */
  private NativeFloatBuffer(
      ByteBuffer buf, long rows, long cols, boolean isPooled, int bucketIndex, long operationId) {
    if (buf == null) throw new IllegalArgumentException("buf must not be null");
    if (rows < 0 || cols < 0) throw new IllegalArgumentException("rows/cols must be non-negative");
    this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
    this.rows = rows;
    this.cols = cols;
    this.elementCount = rows * cols;
    this.byteCapacity = this.buf.capacity();
    // Create a FloatBuffer view for convenient float access
    this.view = this.buf.asFloatBuffer();
    this.cleanable = CLEANER.register(this, new State(this.buf, isPooled, bucketIndex));
    this.isPooled = isPooled;
    this.bucketIndex = bucketIndex;
    this.operationId = operationId;
  }

  /**
   * Backward-compatible constructor for non-zero-copy buffers
   */
  private NativeFloatBuffer(
      ByteBuffer buf, long rows, long cols, boolean isPooled, int bucketIndex) {
    this(buf, rows, cols, isPooled, bucketIndex, -1); // -1 indicates non-zero-copy
  }

  /** Calculate bucket index for given byte capacity (backward compatibility) */
  private static int getBucketIndex(int byteCapacity) {
    return getOptimalBucketIndex(byteCapacity);
  }

  /** Calculate optimal bucket index for given byte capacity with best-fit selection */
  private static int getOptimalBucketIndex(int requiredBytes) {
    // Get TPS value, fallback to 20.0 (optimal TPS) if not available (e.g., in tests)
    double tps = getSafeTPS();

    int maxBucket = Math.min(
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(tps),
        NUM_BUCKETS - 1
    );

    // First try exact match for perfect fit (minimizes fragmentation)
    for (int i = 0; i <= maxBucket; i++) {
      if (BUCKET_SIZES[i] == requiredBytes) {
        return i;
      }
    }

    // If no exact match, find smallest bucket that can accommodate the request
    for (int i = 0; i <= maxBucket; i++) {
      if (BUCKET_SIZES[i] >= requiredBytes) {
        return i;
      }
    }

    // If no suitable bucket found, use largest available
    return Math.min(maxBucket, NUM_BUCKETS - 1);
  }

  /** Calculate bucket index with size-based fragmentation optimization */
  private static int getSizeBasedBucketIndex(int requiredBytes) {
    // Get TPS value, fallback to 20.0 (optimal TPS) if not available (e.g., in tests)
    double tps = getSafeTPS();

    int maxBucket = Math.min(
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(tps),
        NUM_BUCKETS - 1
    );

    // Priority 1: Exact size match (perfect fit, 0% fragmentation)
    for (int i = 0; i <= maxBucket; i++) {
      if (BUCKET_SIZES[i] == requiredBytes) {
        return i;
      }
    }

    // Priority 2: Smallest bucket that can accommodate the request (minimizes internal fragmentation)
    int bestFitIndex = -1;
    int minFragmentation = Integer.MAX_VALUE;

    for (int i = 0; i <= maxBucket; i++) {
      if (BUCKET_SIZES[i] >= requiredBytes) {
        int fragmentation = BUCKET_SIZES[i] - requiredBytes;
        if (fragmentation < minFragmentation) {
          minFragmentation = fragmentation;
          bestFitIndex = i;
        }
      }
    }

    // If no suitable bucket found, use largest available
    return bestFitIndex != -1 ? bestFitIndex : Math.min(maxBucket, NUM_BUCKETS - 1);
  }

  /** Get buffer from pool with best-fit selection to minimize fragmentation */
 private static PooledBuffer getFromPool(int requiredBytes) {
   // Get TPS value, fallback to 20.0 (optimal TPS) if not available (e.g., in tests)
   double tps = getSafeTPS();

   int maxBucket = Math.min(
       com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBucketSize(tps),
       NUM_BUCKETS - 1
   );

   // First try exact size match
   int targetBucket = getOptimalBucketIndex(requiredBytes);
   
   // Check target bucket first
   ConcurrentLinkedQueue<PooledBuffer> pool = BUFFER_POOLS.get(targetBucket);
   if (pool != null) {
     PooledBuffer pooled = pool.poll();
     while (pooled != null) {
       if (pooled.buffer != null && pooled.buffer.capacity() == BUCKET_SIZES[targetBucket]) {
         // Exact match - perfect for minimizing fragmentation
         POOL_SIZES.get(targetBucket).decrementAndGet();
         updateFragmentationStats(requiredBytes, BUCKET_SIZES[targetBucket]);
         TOTAL_REUSES.incrementAndGet();
         return pooled;
       }
       // Discard mismatched buffers
       pooled = pool.poll();
       if (pooled != null) {
         POOL_SIZES.get(targetBucket).decrementAndGet();
       }
     }
   }

   // Try next larger buckets if exact match not available
   for (int i = targetBucket + 1; i <= maxBucket; i++) {
     pool = BUFFER_POOLS.get(i);
     if (pool != null) {
       PooledBuffer pooled = pool.poll();
       while (pooled != null) {
         if (pooled.buffer != null && pooled.buffer.capacity() >= requiredBytes) {
           POOL_SIZES.get(i).decrementAndGet();
           updateFragmentationStats(requiredBytes, BUCKET_SIZES[i]);
           TOTAL_REUSES.incrementAndGet();
           return pooled;
         }
         // Discard too-small buffers
         pooled = pool.poll();
         if (pooled != null) {
           POOL_SIZES.get(i).decrementAndGet();
         }
       }
     }
   }

   // Try smaller buckets as last resort (with warning)
   for (int i = targetBucket - 1; i >= 0; i--) {
     pool = BUFFER_POOLS.get(i);
     if (pool != null) {
       PooledBuffer pooled = pool.poll();
       while (pooled != null) {
         if (pooled.buffer != null && pooled.buffer.capacity() >= requiredBytes) {
           POOL_SIZES.get(i).decrementAndGet();
           updateFragmentationStats(requiredBytes, BUCKET_SIZES[i]);
           TOTAL_REUSES.incrementAndGet();
           return pooled;
         }
         // Discard too-small buffers
         pooled = pool.poll();
         if (pooled != null) {
           POOL_SIZES.get(i).decrementAndGet();
         }
       }
     }
   }

   return null;
 }

 /** Update fragmentation statistics */
 private static void updateFragmentationStats(int requestedSize, int allocatedSize) {
   if (allocatedSize > requestedSize) {
     long fragmentation = allocatedSize - requestedSize;
     TOTAL_FRAGMENTATION.addAndGet(fragmentation);
     
     // Update bucket usage statistics
     int bucketIndex = getOptimalBucketIndex(allocatedSize);
     BUCKET_USAGE.compute(bucketIndex, (k, v) -> v == null ? fragmentation : v + fragmentation);
   }
 }

  /** Clean up excess buffers from pools */
  private static void performCleanup() {
    List<Integer> emptyBuckets = new ArrayList<>();

    for (var entry : BUFFER_POOLS.entrySet()) {
      int bucketIndex = entry.getKey();
      ConcurrentLinkedQueue<PooledBuffer> pool = entry.getValue();
      AtomicInteger size = POOL_SIZES.get(bucketIndex);

      if (pool != null && size != null) {
        int remaining = cleanupBucket(pool, size);
        // Mark empty buckets for removal
        if (remaining == 0) {
          emptyBuckets.add(bucketIndex);
        }
      }
    }

    // Remove empty buckets
    for (Integer bucketIndex : emptyBuckets) {
      BUFFER_POOLS.remove(bucketIndex);
      POOL_SIZES.remove(bucketIndex);
    }
  }

  private static int cleanupBucket(ConcurrentLinkedQueue<PooledBuffer> pool, AtomicInteger size) {
    int maxPerBucket =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxPoolPerBucket(
            getSafeTPS());
    int targetSize = maxPerBucket / 2;
    int currentSize = size.get();

    while (currentSize > targetSize) {
      PooledBuffer pooled = pool.poll();
      if (pooled != null) {
        try {
          RustPerformance.freeFloatBufferNative(pooled.buffer);
          TOTAL_FREES.incrementAndGet();
          size.decrementAndGet();
          currentSize--;
        } catch (UnsatisfiedLinkError e) {
          // Native library not available, buffer will be freed by GC
          System.err.println("Native library not available during cleanup: " + e.getMessage());
          size.decrementAndGet();
          currentSize--;
        } catch (Exception e) {
          System.err.println("Error freeing native buffer during cleanup: " + e.getMessage());
        }
      } else {
        break;
      }
    }
    return currentSize;
  }

  /**
   * Allocate a native float buffer via the Rust native helper and wrap it. Returns null if the
   * native allocation failed or native library missing.
   */
  /** Pre-allocate common buffer sizes to improve performance */
  public static void preallocateBuffers() {
    // Pre-allocate buffers for common sizes (4KB, 16KB, 64KB, 256KB, 1MB)
    int[] commonSizes = {4096, 16384, 65536, 262144, 1048576};

    int prefetch =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptivePrefetchCount(getSafeTPS());
    int maxPerBucket =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxPoolPerBucket(
            getSafeTPS());

    for (int size : commonSizes) {
      int bucketIndex = getBucketIndex(size);
      int preallocateCount = Math.min(prefetch, maxPerBucket / 2);

      for (int i = 0; i < preallocateCount; i++) {
        try {
          ByteBuffer buffer =
              RustPerformance.generateFloatBufferNative(size / 4, 1); // size/4 for float count
          if (buffer != null) {
            PooledBuffer pooled = new PooledBuffer(buffer, bucketIndex);
            BUFFER_POOLS
                .computeIfAbsent(bucketIndex, k -> new ConcurrentLinkedQueue<>())
                .offer(pooled);
            POOL_SIZES.computeIfAbsent(bucketIndex, k -> new AtomicInteger(0)).incrementAndGet();
            TOTAL_ALLOCATIONS.incrementAndGet();
          }
        } catch (Exception e) {
          // Ignore pre-allocation failures
        }
      }
    }
  }

  /**
   * Create NativeFloatBuffer from an existing direct buffer (zero-copy)
   * @param buffer Direct ByteBuffer with float data
   * @param rows Number of rows in the buffer
   * @param cols Number of columns in the buffer
   * @return NativeFloatBuffer wrapping the existing buffer
   * @throws IllegalArgumentException If buffer is not direct
   */
  public static NativeFloatBuffer fromDirectBuffer(ByteBuffer buffer, long rows, long cols) {
    if (buffer == null || !buffer.isDirect()) {
      throw new IllegalArgumentException("Zero-copy requires direct ByteBuffer");
    }
    if (rows <= 0 || cols <= 0) {
      throw new IllegalArgumentException("Rows and cols must be positive");
    }
    if (buffer.remaining() != rows * cols * 4) {
      throw new IllegalArgumentException("Buffer size must match rows * cols * 4 (float size)");
    }

    // Create buffer with LITTLE_ENDIAN order (required for native compatibility)
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    
    // Create instance without native allocation - use CLEANER for lifecycle management
    NativeFloatBuffer instance = new NativeFloatBuffer(buffer, rows, cols, false, -1);
    instance.closed = false; // Ensure buffer is not marked as closed
    
    return instance;
  }

  /**
   * Get memory address of the underlying direct buffer (JNI-compatible)
   * @return Memory address as long, or 0 if buffer is not direct
   */
  public long getBufferAddress() {
    if (buf == null || !buf.isDirect()) {
      return 0L;
    }
    return getBufferAddressImpl(buf);
  }

  /**
   * Get memory address of direct buffer (JNI implementation)
   * @param buffer Direct ByteBuffer
   * @return Memory address as long
   */
  private static native long getBufferAddressImpl(ByteBuffer buffer);

  /**
   * Create zero-copy NativeFloatBuffer from direct buffer with proper lifecycle management
   * @param buffer Direct ByteBuffer containing float data
   * @param rows Number of rows in the buffer
   * @param cols Number of columns in the buffer
   * @param operationId Unique ID for tracking this zero-copy operation
   * @return NativeFloatBuffer wrapping the existing buffer
   * @throws IllegalArgumentException If buffer is not direct or invalid dimensions
   */
  public static NativeFloatBuffer createZeroCopyBuffer(ByteBuffer buffer, long rows, long cols, long operationId) {
    if (buffer == null || !buffer.isDirect()) {
      throw new IllegalArgumentException("Zero-copy requires direct ByteBuffer");
    }
    if (rows <= 0 || cols <= 0) {
      throw new IllegalArgumentException("Rows and cols must be positive");
    }
    if (buffer.remaining() != rows * cols * 4) {
      throw new IllegalArgumentException("Buffer size must match rows * cols * 4 (float size)");
    }

    // Create buffer with LITTLE_ENDIAN order (required for native compatibility)
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    
    // Create instance with zero-copy marker and operation ID through constructor
    NativeFloatBuffer instance = new NativeFloatBuffer(buffer, rows, cols, false, -1, operationId);
    instance.closed = false;
    
    return instance;
  }

  /**
   * Get the zero-copy operation ID for this buffer (if zero-copy)
   * @return Operation ID or -1 if not a zero-copy buffer
   */
  public long getOperationId() {
    return operationId;
  }

  /**
   * Check if this buffer was created via zero-copy operation
   * @return true if zero-copy, false otherwise
   */
  public boolean isZeroCopy() {
    return operationId != -1;
  }

  /**
   * Cleanup resources for zero-copy buffer
   */
  public void cleanupZeroCopy() {
    if (isZeroCopy() && !closed) {
      RustPerformance.cleanupZeroCopyOperation(operationId);
      closed = true;
    }
  }

  /**
   * Allocate via native helper that returns both buffer and shape. Falls back to older
   * generateFloatBufferNative if the new native is unavailable.
   */
  public static NativeFloatBuffer allocateFromNative(long rows, long cols) {
    return allocateFromNative(rows, cols, true); // Use pooling by default
  }

  /** Allocate with optional pooling */
  public static NativeFloatBuffer allocateFromNative(long rows, long cols, boolean usePool) {
    try {
      int requiredBytes = (int) (rows * cols * 4); // 4 bytes per float

      if (usePool) {
        // Try to get from pool first
        PooledBuffer pooled = getFromPool(requiredBytes);
        if (pooled != null) {
          // Clear the buffer before reuse
          pooled.buffer.clear();
          return new NativeFloatBuffer(pooled.buffer, rows, cols, true, pooled.bucketIndex);
        }
      }

      // Allocate new buffer
      try {
        NativeFloatBufferAllocation alloc =
            RustPerformance.generateFloatBufferWithShapeNative(rows, cols);
        if (alloc == null) return null;

        TOTAL_ALLOCATIONS.incrementAndGet();
        int bucketIndex = usePool ? getBucketIndex(alloc.getBuffer().capacity()) : -1;
        return new NativeFloatBuffer(
            alloc.getBuffer(), alloc.getRows(), alloc.getCols(), usePool, bucketIndex);
      } catch (UnsatisfiedLinkError e) {
        // Fallback to older API
        try {
          ByteBuffer b = RustPerformance.generateFloatBufferNative((int) rows, (int) cols);
          if (b == null) return null;

          TOTAL_ALLOCATIONS.incrementAndGet();
          int bucketIndex = usePool ? getBucketIndex(b.capacity()) : -1;
          return new NativeFloatBuffer(b, rows, cols, usePool, bucketIndex);
        } catch (UnsatisfiedLinkError e2) {
          // Native library not available
          System.err.println(
              "Native library not available for buffer allocation: " + e2.getMessage());
          return null;
        }
      } catch (Exception e) {
        System.err.println("Error allocating native buffer: " + e.getMessage());
        return null;
      }
    } catch (UnsatisfiedLinkError e) {
      // Native library not available
      System.err.println("Native library not available for buffer allocation: " + e.getMessage());
      return null;
    }
  }

  /** Return the underlying ByteBuffer for read/write access. */
  public ByteBuffer buffer() {
    if (closed) throw new IllegalStateException("NativeFloatBuffer is closed");
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

  /**
   * Return a FloatBuffer view to the whole underlying buffer. The returned buffer is a view;
   * operations on it affect the native memory.
   */
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

  /**
   * Return a FloatBuffer representing a single column (0-based). This creates a new buffer with the
   * column values copied into it (non-direct), since the native memory layout is row-major.
   */
  public java.nio.FloatBuffer colBuffer(int col) {
    checkOpen();
    if (col < 0 || col >= cols) throw new IndexOutOfBoundsException("col out of range");
    // Bulk copy column into a new non-direct FloatBuffer to minimize per-element Java calls
    int rCount = (int) rows;
    float[] tmp = new float[rCount];
    int base = col; // starting index into row-major float array
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

    // Use bulk fill for better performance on large buffers
    if (cap > 1000) {
      // Fill in chunks to leverage CPU cache
      int chunkSize = Math.min(cap, 10000);
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

  /** Copy the contents into another NativeFloatBuffer (must match shape). */
  public void copyTo(NativeFloatBuffer dest) {
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
    if (closed) throw new IllegalStateException("NativeFloatBuffer is closed");
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

  /** Get pool statistics for monitoring */
  public static String getPoolStats() {
    StringBuilder Stats = new StringBuilder();
    Stats.append("Buffer Pool Statistics:\n");
    Stats.append("Total Allocations: ").append(TOTAL_ALLOCATIONS.get()).append("\n");
    Stats.append("Total Reuses: ").append(TOTAL_REUSES.get()).append("\n");
    Stats.append("Total Frees: ").append(TOTAL_FREES.get()).append("\n");
    Stats.append("Reuse Rate: ")
        .append(
            String.format(
                "%.2f%%",
                TOTAL_ALLOCATIONS.get() > 0
                    ? (100.0 * TOTAL_REUSES.get() / TOTAL_ALLOCATIONS.get())
                    : 0.0))
        .append("\n");
    Stats.append("Pool Buckets:\n");

    for (Integer bucketIndex : BUFFER_POOLS.keySet()) {
      AtomicInteger size = POOL_SIZES.get(bucketIndex);
      int bucketSize = size != null ? size.get() : 0;
      int bucketByteSize = BUCKET_SIZES[bucketIndex];
      Stats.append("  Bucket ")
          .append(bucketIndex)
          .append(" (")
          .append(bucketByteSize)
          .append(" bytes): ")
          .append(bucketSize)
          .append(" buffers\n");
    }

    return Stats.toString();
  }

  /** Manually trigger pool cleanup */
  public static void forceCleanup() {
    performCleanup();
  }

  /** Clear all pools and free all buffers */
  public static void clearPools() {
    for (var entry : BUFFER_POOLS.entrySet()) {
      ConcurrentLinkedQueue<PooledBuffer> pool = entry.getValue();
      if (pool != null) {
        PooledBuffer pooled;
        while ((pooled = pool.poll()) != null) {
          try {
            RustPerformance.freeFloatBufferNative(pooled.buffer);
            TOTAL_FREES.incrementAndGet();
          } catch (UnsatisfiedLinkError e) {
            // Native library not available, buffer will be freed by GC
            System.err.println("Native library not available during pool clear: " + e.getMessage());
          } catch (Exception e) {
            System.err.println("Error freeing native buffer during pool clear: " + e.getMessage());
          }
        }
      }
    }
    BUFFER_POOLS.clear();
    POOL_SIZES.clear();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      // run cleanup now and unregister
      cleanable.clean();
    }
  }
}
