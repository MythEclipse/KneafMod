package com.kneaf.core.performance;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance zero-copy buffer management facade.
 * Provides buffer pooling, memory management, and zero-copy operations for optimal performance.
 */
public class BinaryZeroCopyFacade {
  private static final Logger LOGGER = LoggerFactory.getLogger(BinaryZeroCopyFacade.class);

  // Memory pressure thresholds
  private static final double HIGH_MEMORY_THRESHOLD = 0.85;
  private static final double CRITICAL_MEMORY_THRESHOLD = 0.95;

  // Buffer size constants
  private static final int[] BUFFER_SIZES = {1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072};
  private static final int MAX_POOL_SIZE_PER_SIZE = 100;
  private static final long BUFFER_CLEANUP_INTERVAL_MS = 30000; // 30 seconds

  // Memory monitoring
  private final MemoryMXBean memoryBean;
  private final AtomicLong totalBuffersCreated;
  private final AtomicLong totalBuffersReused;
  private final AtomicLong totalMemoryAllocated;
  private final AtomicLong totalMemoryFreed;

  // Buffer pools organized by size
  private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<ByteBuffer>> bufferPools;
  private final ConcurrentHashMap<Integer, AtomicInteger> poolSizes;
  private final ReentrantLock cleanupLock;

  // Cleanup thread
  private volatile boolean shutdown;
  private Thread cleanupThread;

  public BinaryZeroCopyFacade() {
    this.memoryBean = ManagementFactory.getMemoryMXBean();
    this.totalBuffersCreated = new AtomicLong(0);
    this.totalBuffersReused = new AtomicLong(0);
    this.totalMemoryAllocated = new AtomicLong(0);
    this.totalMemoryFreed = new AtomicLong(0);
    this.bufferPools = new ConcurrentHashMap<>();
    this.poolSizes = new ConcurrentHashMap<>();
    this.cleanupLock = new ReentrantLock();

    // Initialize buffer pools
    initializeBufferPools();

    // Start cleanup thread
    startCleanupThread();

    LOGGER.info("BinaryZeroCopyFacade initialized with {} buffer size categories", BUFFER_SIZES.length);
  }

  /**
   * Initialize buffer pools for all supported sizes.
   */
  private void initializeBufferPools() {
    for (int size : BUFFER_SIZES) {
      bufferPools.put(size, new ConcurrentLinkedQueue<>());
      poolSizes.put(size, new AtomicInteger(0));
    }
  }

  /**
   * Start the background cleanup thread for memory management.
   */
  private void startCleanupThread() {
    cleanupThread = new Thread(this::cleanupTask, "ZeroCopy-Cleanup");
    cleanupThread.setDaemon(true);
    cleanupThread.start();
  }

  /**
   * Background cleanup task to manage memory pressure.
   */
  private void cleanupTask() {
    while (!shutdown) {
      try {
        Thread.sleep(BUFFER_CLEANUP_INTERVAL_MS);
        performCleanup();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  /**
   * Perform cleanup operations based on memory pressure.
   */
  private void performCleanup() {
    cleanupLock.lock();
    try {
      MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
      double memoryPressure = (double) heapUsage.getUsed() / heapUsage.getMax();

      if (memoryPressure > CRITICAL_MEMORY_THRESHOLD) {
        // Aggressive cleanup under critical memory pressure
        aggressiveCleanup();
      } else if (memoryPressure > HIGH_MEMORY_THRESHOLD) {
        // Moderate cleanup under high memory pressure
        moderateCleanup();
      }
    } finally {
      cleanupLock.unlock();
    }
  }

  /**
   * Aggressive cleanup - clear most buffers.
   */
  private void aggressiveCleanup() {
    LOGGER.warn("Performing aggressive buffer cleanup due to critical memory pressure");
    for (int size : BUFFER_SIZES) {
      ConcurrentLinkedQueue<ByteBuffer> pool = bufferPools.get(size);
      AtomicInteger poolSize = poolSizes.get(size);

      // Keep only 10% of buffers
      int targetSize = Math.max(1, poolSize.get() / 10);
      while (poolSize.get() > targetSize && !pool.isEmpty()) {
        ByteBuffer buffer = pool.poll();
        if (buffer != null) {
          poolSize.decrementAndGet();
          totalMemoryFreed.addAndGet(buffer.capacity());
        }
      }
    }
  }

  /**
   * Moderate cleanup - reduce buffer pools by half.
   */
  private void moderateCleanup() {
    LOGGER.debug("Performing moderate buffer cleanup due to high memory pressure");
    for (int size : BUFFER_SIZES) {
      ConcurrentLinkedQueue<ByteBuffer> pool = bufferPools.get(size);
      AtomicInteger poolSize = poolSizes.get(size);

      // Reduce to half the current size
      int targetSize = poolSize.get() / 2;
      while (poolSize.get() > targetSize && !pool.isEmpty()) {
        ByteBuffer buffer = pool.poll();
        if (buffer != null) {
          poolSize.decrementAndGet();
          totalMemoryFreed.addAndGet(buffer.capacity());
        }
      }
    }
  }

  /**
   * Acquire a zero-copy buffer of the specified size.
   * Attempts to reuse from pool first, creates new buffer if necessary.
   *
   * @param capacity The buffer capacity in bytes
   * @return A ByteBuffer ready for use
   */
  public ByteBuffer acquireBuffer(int capacity) {
    // Find the appropriate buffer size category
    int bufferSize = findAppropriateBufferSize(capacity);

    // Try to get from pool first
    ConcurrentLinkedQueue<ByteBuffer> pool = bufferPools.get(bufferSize);
    if (pool != null) {
      ByteBuffer buffer = pool.poll();
      if (buffer != null) {
        AtomicInteger poolSize = poolSizes.get(bufferSize);
        if (poolSize != null) {
          poolSize.decrementAndGet();
        }
        totalBuffersReused.incrementAndGet();

        // Clear and prepare buffer
        buffer.clear();
        buffer.limit(capacity);
        return buffer;
      }
    }

    // Create new buffer if pool is empty or doesn't exist
    ByteBuffer newBuffer = ByteBuffer.allocateDirect(bufferSize);
    newBuffer.limit(capacity);
    totalBuffersCreated.incrementAndGet();
    totalMemoryAllocated.addAndGet(bufferSize);

    LOGGER.debug("Created new buffer of size {} (requested {})", bufferSize, capacity);
    return newBuffer;
  }

  /**
   * Release a buffer back to the pool for reuse.
   *
   * @param buffer The buffer to release
   */
  public void releaseBuffer(ByteBuffer buffer) {
    if (buffer == null || !buffer.isDirect()) {
      return;
    }

    int capacity = buffer.capacity();
    int bufferSize = findAppropriateBufferSize(capacity);

    ConcurrentLinkedQueue<ByteBuffer> pool = bufferPools.get(bufferSize);
    AtomicInteger poolSize = poolSizes.get(bufferSize);

    if (pool != null && poolSize != null && poolSize.get() < MAX_POOL_SIZE_PER_SIZE) {
      // Clear buffer before returning to pool
      buffer.clear();

      pool.offer(buffer);
      poolSize.incrementAndGet();
    } else {
      // Pool is full or doesn't exist, let GC handle it
      totalMemoryFreed.addAndGet(capacity);
    }
  }

  /**
   * Perform zero-copy data transfer between buffers.
   *
   * @param source The source buffer
   * @param dest The destination buffer
   * @param length The number of bytes to transfer
   */
  public void zeroCopyTransfer(ByteBuffer source, ByteBuffer dest, int length) {
    if (source == null || dest == null) {
      throw new IllegalArgumentException("Source and destination buffers cannot be null");
    }

    if (length <= 0) {
      throw new IllegalArgumentException("Transfer length must be positive");
    }

    if (source.remaining() < length) {
      throw new IllegalArgumentException("Source buffer doesn't have enough remaining data");
    }

    if (dest.remaining() < length) {
      throw new IllegalArgumentException("Destination buffer doesn't have enough space");
    }

    // Use bulk put for zero-copy transfer
    if (source.hasArray() && dest.hasArray()) {
      // Both are heap buffers, use array copy
      System.arraycopy(source.array(), source.arrayOffset() + source.position(),
                      dest.array(), dest.arrayOffset() + dest.position(), length);
      source.position(source.position() + length);
      dest.position(dest.position() + length);
    } else {
      // At least one is direct, use buffer operations
      for (int i = 0; i < length; i++) {
        dest.put(source.get());
      }
    }
  }

  /**
   * Execute an operation with a temporary buffer, automatically releasing it afterwards.
   *
   * @param capacity The buffer capacity needed
   * @param operation The operation to perform with the buffer
   */
  public void withBuffer(int capacity, Consumer<ByteBuffer> operation) {
    ByteBuffer buffer = acquireBuffer(capacity);
    try {
      operation.accept(buffer);
    } finally {
      releaseBuffer(buffer);
    }
  }

  /**
   * Find the appropriate buffer size category for the requested capacity.
   */
  private int findAppropriateBufferSize(int capacity) {
    for (int size : BUFFER_SIZES) {
      if (size >= capacity) {
        return size;
      }
    }
    // If capacity is larger than all predefined sizes, round up to next power of 2
    return Integer.highestOneBit(capacity) << 1;
  }

  /**
   * Get buffer pool statistics.
   */
  public BufferStatistics getStatistics() {
    long totalPooledBuffers = 0;
    long totalPooledMemory = 0;

    for (int size : BUFFER_SIZES) {
      AtomicInteger poolSize = poolSizes.get(size);
      if (poolSize != null) {
        int count = poolSize.get();
        totalPooledBuffers += count;
        totalPooledMemory += (long) count * size;
      }
    }

    return new BufferStatistics(
        totalBuffersCreated.get(),
        totalBuffersReused.get(),
        totalPooledBuffers,
        totalMemoryAllocated.get(),
        totalMemoryFreed.get(),
        totalPooledMemory
    );
  }

  /**
   * Shutdown the facade and cleanup resources.
   */
  public void shutdown() {
    shutdown = true;
    if (cleanupThread != null) {
      cleanupThread.interrupt();
      try {
        cleanupThread.join(5000); // Wait up to 5 seconds
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Clear all pools
    for (ConcurrentLinkedQueue<ByteBuffer> pool : bufferPools.values()) {
      pool.clear();
    }
    bufferPools.clear();
    poolSizes.clear();

    LOGGER.info("BinaryZeroCopyFacade shutdown complete");
  }

  /**
   * Statistics container for buffer operations.
   */
  public static class BufferStatistics {
    public final long buffersCreated;
    public final long buffersReused;
    public final long buffersPooled;
    public final long memoryAllocated;
    public final long memoryFreed;
    public final long memoryPooled;

    public BufferStatistics(long buffersCreated, long buffersReused, long buffersPooled,
                           long memoryAllocated, long memoryFreed, long memoryPooled) {
      this.buffersCreated = buffersCreated;
      this.buffersReused = buffersReused;
      this.buffersPooled = buffersPooled;
      this.memoryAllocated = memoryAllocated;
      this.memoryFreed = memoryFreed;
      this.memoryPooled = memoryPooled;
    }

    @Override
    public String toString() {
      return String.format(
          "BufferStatistics{buffersCreated=%d, buffersReused=%d, buffersPooled=%d, " +
          "memoryAllocated=%d, memoryFreed=%d, memoryPooled=%d}",
          buffersCreated, buffersReused, buffersPooled,
          memoryAllocated, memoryFreed, memoryPooled);
    }
  }

  /**
   * Zero-copy buffer wrapper with automatic resource management.
   */
  public static class ZeroCopyWrapper {
    private final BinaryZeroCopyFacade facade;
    private final ByteBuffer buffer;

    public ZeroCopyWrapper(BinaryZeroCopyFacade facade, int capacity) {
      this.facade = facade;
      this.buffer = facade.acquireBuffer(capacity);
    }

    public static ByteBuffer createDirectBuffer(int capacity) {
      // Fallback for backward compatibility
      return ByteBuffer.allocateDirect(capacity);
    }

    public ByteBuffer getBuffer() {
      return buffer;
    }

    public void release() {
      if (facade != null && buffer != null) {
        facade.releaseBuffer(buffer);
      }
    }

    // Auto-closeable for try-with-resources
    public void close() {
      release();
    }
  }
}