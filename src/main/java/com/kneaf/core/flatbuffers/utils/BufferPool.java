package com.kneaf.core.flatbuffers.utils;

import com.kneaf.core.flatbuffers.core.SerializationConstants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe buffer pool for reusable ByteBuffer instances. Provides efficient buffer management
 * to reduce garbage collection pressure.
 */
public class BufferPool {

  private final ConcurrentLinkedQueue<ByteBuffer> availableBuffers;
  private final AtomicInteger activeCount;
  private final AtomicInteger totalCreated;
  private final int maxpoolSize;
  private final int bufferSize;
  private final ReentrantLock creationLock;

  /** Create a new buffer pool with default configuration. */
  public BufferPool() {
    this(SerializationConstants.DEFAULT_POOL_SIZE, SerializationConstants.DEFAULT_BUFFER_SIZE);
  }

  /**
   * Create a new buffer pool with specified configuration.
   *
   * @param maxpoolSize maximum number of buffers to keep in pool
   * @param bufferSize size of each buffer
   */
  public BufferPool(int maxpoolSize, int bufferSize) {
    if (maxpoolSize <= 0) {
      throw new IllegalArgumentException("Max pool size must be positive");
    }
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("Buffer size must be positive");
    }

    this.maxpoolSize = Math.min(maxpoolSize, SerializationConstants.MAX_POOL_SIZE);
    this.bufferSize = bufferSize;
    this.availableBuffers = new ConcurrentLinkedQueue<>();
    this.activeCount = new AtomicInteger(0);
    this.totalCreated = new AtomicInteger(0);
    this.creationLock = new ReentrantLock();
  }

  /**
   * Borrow a buffer from the pool.
   *
   * @return a ByteBuffer ready for use
   */
  public ByteBuffer borrowBuffer() {
    ByteBuffer buffer = availableBuffers.poll();

    if (buffer == null) {
      // Create new buffer if pool is empty
      creationLock.lock();
      try {
        // Double-check after acquiring lock
        buffer = availableBuffers.poll();
        if (buffer == null) {
          buffer = createNewBuffer();
          totalCreated.incrementAndGet();
        }
      } finally {
        creationLock.unlock();
      }
    }

    if (buffer != null) {
      activeCount.incrementAndGet();
      buffer.clear(); // Reset buffer state
    }

    return buffer;
  }

  /**
   * Return a buffer to the pool.
   *
   * @param buffer the buffer to return
   */
  public void returnBuffer(ByteBuffer buffer) {
    if (buffer == null) {
      return;
    }

    // Validate buffer properties
    if (buffer.capacity() != bufferSize) {
      throw new IllegalArgumentException("Buffer capacity mismatch");
    }

    // Reset buffer state
    buffer.clear();

    // Only return to pool if we haven't exceeded max size
    if (availableBuffers.size() < maxpoolSize) {
      availableBuffers.offer(buffer);
    }

    activeCount.decrementAndGet();
  }

  /**
   * Create a new buffer with proper configuration.
   *
   * @return a new ByteBuffer
   */
  private ByteBuffer createNewBuffer() {
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    return buffer;
  }

  /**
   * Get the number of available buffers in the pool.
   *
   * @return number of available buffers
   */
  public int getAvailableCount() {
    return availableBuffers.size();
  }

  /**
   * Get the number of active (borrowed) buffers.
   *
   * @return number of active buffers
   */
  public int getActiveCount() {
    return activeCount.get();
  }

  /**
   * Get the total number of buffers created by this pool.
   *
   * @return total number of buffers created
   */
  public int getTotalCreated() {
    return totalCreated.get();
  }

  /**
   * Get the maximum pool size.
   *
   * @return maximum pool size
   */
  public int getMaxpoolSize() {
    return maxpoolSize;
  }

  /**
   * Get the buffer size.
   *
   * @return buffer size
   */
  public int getBufferSize() {
    return bufferSize;
  }

  /**
   * Get pool statistics.
   *
   * @return pool statistics
   */
  public BufferPoolStatistics getStatistics() {
    return new BufferPoolStatistics(
        getAvailableCount(),
        getActiveCount(),
        getTotalCreated(),
        getMaxpoolSize(),
        getBufferSize());
  }

  /** Clear all buffers from the pool. */
  public void clear() {
    availableBuffers.clear();
    activeCount.set(0);
    totalCreated.set(0);
  }

  /**
   * Pre-populate the pool with buffers.
   *
   * @param count number of buffers to pre-create
   */
  public void preallocate(int count) {
    if (count <= 0) {
      return;
    }

    creationLock.lock();
    try {
      int toCreate = Math.min(count, maxpoolSize - availableBuffers.size());
      for (int i = 0; i < toCreate; i++) {
        availableBuffers.offer(createNewBuffer());
        totalCreated.incrementAndGet();
      }
    } finally {
      creationLock.unlock();
    }
  }

  /** Buffer pool statistics. */
  public static class BufferPoolStatistics {
    private final int availableCount;
    private final int activeCount;
    private final int totalCreated;
    private final int maxpoolSize;
    private final int bufferSize;

    public BufferPoolStatistics(
        int availableCount, int activeCount, int totalCreated, int maxpoolSize, int bufferSize) {
      this.availableCount = availableCount;
      this.activeCount = activeCount;
      this.totalCreated = totalCreated;
      this.maxpoolSize = maxpoolSize;
      this.bufferSize = bufferSize;
    }

    public int getAvailableCount() {
      return availableCount;
    }

    public int getActiveCount() {
      return activeCount;
    }

    public int getTotalCreated() {
      return totalCreated;
    }

    public int getMaxpoolSize() {
      return maxpoolSize;
    }

    public int getBufferSize() {
      return bufferSize;
    }

    public int getUtilizationPercent() {
      return maxpoolSize > 0 ? (activeCount * 100) / maxpoolSize : 0;
    }

    @Override
    public String toString() {
      return String.format(
          "BufferPoolStatistics{available=%d, active=%d, totalCreated=%d, maxSize=%d, bufferSize=%d, utilization=%d%%}",
          availableCount,
          activeCount,
          totalCreated,
          maxpoolSize,
          bufferSize,
          getUtilizationPercent());
    }
  }
}
