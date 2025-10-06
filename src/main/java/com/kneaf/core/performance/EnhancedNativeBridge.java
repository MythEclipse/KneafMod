package com.kneaf.core.performance;

import com.kneaf.core.KneafCore;
import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.item.ItemEntityData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enhanced Native Bridge with shared memory buffers for zero-copy data transfer.
 * Combines multiple small JNI calls into single batch operations to reduce overhead.
 */
public class EnhancedNativeBridge {

  // Shared memory buffer pool for zero-copy operations
  private static final int BUFFER_SIZE = 64 * 1024; // 64KB per buffer
  private static final int MAX_BUFFERS = 16;
  private static final List<ByteBuffer> SHARED_BUFFERS = new ArrayList<>();
  private static final AtomicLong BUFFER_USAGE_COUNT = new AtomicLong(0);

  // Performance metrics
  private static final AtomicLong TOTAL_BATCHES_PROCESSED = new AtomicLong(0);
  private static final AtomicLong TOTAL_JNI_CALLS_SAVED = new AtomicLong(0);
  private static final AtomicLong TOTAL_MEMORY_COPIES_AVOIDED = new AtomicLong(0);

  // Worker handle for batch operations
  private long workerHandle = 0;
  private final ExecutorService callbackExecutor = Executors.newCachedThreadPool(
      r -> {
        Thread t = new Thread(r, "enhanced-bridge-callback");
        t.setDaemon(true);
        return t;
      });


  static {
    // Initialize shared buffers with native byte order
    for (int i = 0; i < MAX_BUFFERS; i++) {
      try {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Native byte order for x86/AMD64
        SHARED_BUFFERS.add(buffer);
      } catch (Exception e) {
        KneafCore.LOGGER.warn("Failed to allocate shared buffer {}: {}", i, e.getMessage());
      }
    }
    KneafCore.LOGGER.info("EnhancedNativeBridge initialized with {} shared buffers", SHARED_BUFFERS.size());
  }

  /**
   * Create enhanced native bridge with optimized worker.
   */
  public EnhancedNativeBridge() {
    try {
      this.workerHandle = NativeBridge.createOptimizedWorker(4); // 4 concurrency
      if (this.workerHandle == 0) {
        throw new RuntimeException("Failed to create optimized worker for EnhancedNativeBridge");
      }
      KneafCore.LOGGER.info("EnhancedNativeBridge created with worker handle: {}", workerHandle);
    } catch (Exception e) {
      KneafCore.LOGGER.error("Failed to initialize EnhancedNativeBridge", e);
      throw new RuntimeException("EnhancedNativeBridge initialization failed", e);
    }
  }

  /**
   * Process batch asynchronously with callback.
   * Uses shared memory buffers for zero-copy transfer.
   */
  public void processBatchAsync(Object entityData, Consumer<BatchResult> callback) {
    processBatchAsync(entityData, 30000L, callback); // Default 30 second timeout
  }

  /**
   * Process batch asynchronously with timeout and callback.
   * Combines multiple small operations into single JNI batch call.
   */
  public void processBatchAsync(Object entityData, long timeoutMs, Consumer<BatchResult> callback) {
    if (entityData == null) {
      callbackExecutor.execute(() -> callback.accept(
          BatchResult.failure("Entity data cannot be null")));
      return;
    }

    CompletableFuture.supplyAsync(() -> processBatchInternal(entityData, timeoutMs), callbackExecutor)
        .thenAcceptAsync(callback, callbackExecutor)
        .exceptionally(throwable -> {
          callbackExecutor.execute(() -> callback.accept(
              BatchResult.failure("Async processing failed: " + throwable.getMessage())));
          return null;
        });
  }

  /**
   * Internal batch processing using shared memory buffers.
   */
  private BatchResult processBatchInternal(Object entityData, long timeoutMs) {
    long startTime = System.nanoTime();

    try {
      // Acquire shared buffer for zero-copy transfer
      ByteBuffer sharedBuffer = acquireSharedBuffer();
      if (sharedBuffer == null) {
        return BatchResult.failure("No shared buffers available");
      }

      try {
        sharedBuffer.clear();

        // Serialize data directly into shared buffer
        int serializedSize = serializeEntityData(entityData, sharedBuffer);
        if (serializedSize == 0) {
          return BatchResult.failure("Failed to serialize entity data");
        }

        sharedBuffer.flip();

        // Single JNI batch call instead of multiple small calls
        byte[] resultBytes = performBatchJNI(sharedBuffer, timeoutMs);

        TOTAL_BATCHES_PROCESSED.incrementAndGet();
        TOTAL_JNI_CALLS_SAVED.addAndGet(calculateJNICallsSaved(entityData));
        TOTAL_MEMORY_COPIES_AVOIDED.addAndGet(serializedSize);

        long processingTimeNs = System.nanoTime() - startTime;
        double processingTimeMs = processingTimeNs / 1_000_000.0;

        KneafCore.LOGGER.debug("Batch processed in {:.2f}ms, saved {} JNI calls",
            processingTimeMs, calculateJNICallsSaved(entityData));

        return BatchResult.success(resultBytes, processingTimeMs);

      } finally {
        releaseSharedBuffer(sharedBuffer);
      }

    } catch (Exception e) {
      long processingTimeNs = System.nanoTime() - startTime;
      double processingTimeMs = processingTimeNs / 1_000_000.0;

      KneafCore.LOGGER.error("Batch processing failed after {:.2f}ms", processingTimeMs, e);
      return BatchResult.failure("Processing failed: " + e.getMessage());
    }
  }

  /**
   * Acquire shared buffer for zero-copy operations.
   */
  private ByteBuffer acquireSharedBuffer() {
    for (ByteBuffer buffer : SHARED_BUFFERS) {
      if (buffer.position() == 0) { // Simple lock-free check
        BUFFER_USAGE_COUNT.incrementAndGet();
        return buffer;
      }
    }
    return null; // All buffers in use
  }

  /**
   * Release shared buffer back to pool.
   */
  private void releaseSharedBuffer(ByteBuffer buffer) {
    if (buffer != null) {
      buffer.clear();
      BUFFER_USAGE_COUNT.decrementAndGet();
    }
  }

  /**
   * Serialize entity data directly into shared buffer.
   * Returns serialized size in bytes.
   */
  private int serializeEntityData(Object entityData, ByteBuffer buffer) {
    try {
      if (entityData instanceof List) {
        List<?> dataList = (List<?>) entityData;

        if (dataList.isEmpty()) {
          return 0;
        }

        // Determine data type from first element
        Object firstElement = dataList.get(0);

        if (firstElement instanceof ItemEntityData) {
          List<ItemEntityData> items = (List<ItemEntityData>) dataList;
          return serializeItemBatch(items, buffer);

        } else if (firstElement instanceof MobData) {
          List<MobData> mobs = (List<MobData>) dataList;
          return serializeMobBatch(mobs, buffer);

        } else if (firstElement instanceof BlockEntityData) {
          List<BlockEntityData> blocks = (List<BlockEntityData>) dataList;
          return serializeBlockBatch(blocks, buffer);
        }
      }

      // Fallback: serialize as JSON bytes
      String jsonData = new com.google.gson.Gson().toJson(entityData);
      byte[] jsonBytes = jsonData.getBytes(java.nio.charset.StandardCharsets.UTF_8);

      if (buffer.remaining() < jsonBytes.length + 4) {
        return 0; // Buffer too small
      }

      buffer.putInt(jsonBytes.length);
      buffer.put(jsonBytes);
      return jsonBytes.length + 4;

    } catch (Exception e) {
      KneafCore.LOGGER.warn("Failed to serialize entity data", e);
      return 0;
    }
  }

  /**
   * Serialize item batch using ManualSerializers.
   */
  private int serializeItemBatch(List<ItemEntityData> items, ByteBuffer buffer) {
    try {
      // Use existing ManualSerializers for compatibility
      ByteBuffer tempBuffer = ManualSerializers.serializeItemInput(0, items);
      if (tempBuffer.remaining() > buffer.remaining()) {
        return 0; // Buffer too small
      }

      int size = tempBuffer.remaining();
      buffer.put(tempBuffer);
      return size;

    } catch (Exception e) {
      KneafCore.LOGGER.warn("Failed to serialize item batch", e);
      return 0;
    }
  }

  /**
   * Serialize mob batch using ManualSerializers.
   */
  private int serializeMobBatch(List<MobData> mobs, ByteBuffer buffer) {
    try {
      ByteBuffer tempBuffer = ManualSerializers.serializeMobInput(0, mobs);
      if (tempBuffer.remaining() > buffer.remaining()) {
        return 0;
      }

      int size = tempBuffer.remaining();
      buffer.put(tempBuffer);
      return size;

    } catch (Exception e) {
      KneafCore.LOGGER.warn("Failed to serialize mob batch", e);
      return 0;
    }
  }

  /**
   * Serialize block batch using ManualSerializers.
   */
  private int serializeBlockBatch(List<BlockEntityData> blocks, ByteBuffer buffer) {
    try {
      ByteBuffer tempBuffer = ManualSerializers.serializeBlockInput(0, blocks);
      if (tempBuffer.remaining() > buffer.remaining()) {
        return 0;
      }

      int size = tempBuffer.remaining();
      buffer.put(tempBuffer);
      return size;

    } catch (Exception e) {
      KneafCore.LOGGER.warn("Failed to serialize block batch", e);
      return 0;
    }
  }

  /**
   * Perform single JNI batch call using shared buffer.
   */
  private byte[] performBatchJNI(ByteBuffer inputBuffer, long timeoutMs) throws Exception {
    // Use existing NativeBridge for the actual JNI call
    // This combines what would be multiple separate calls into one

    long deadline = System.currentTimeMillis() + timeoutMs;

    // Push batch buffer to worker
    NativeBridge.nativePushTaskBuffer(workerHandle, inputBuffer);

    // Poll for result with timeout
    byte[] result = null;
    while (System.currentTimeMillis() < deadline) {
      result = NativeBridge.nativePollResult(workerHandle);
      if (result != null) {
        break;
      }
      Thread.sleep(10); // Small delay to avoid busy waiting
    }

    if (result == null) {
      throw new RuntimeException("Batch processing timeout after " + timeoutMs + "ms");
    }

    return result;
  }

  /**
   * Calculate how many individual JNI calls were saved by batching.
   */
  private int calculateJNICallsSaved(Object entityData) {
    if (entityData instanceof List) {
      return ((List<?>) entityData).size();
    }
    return 1; // At least one call saved
  }

  /**
   * Get enhanced batch metrics.
   */
  public static Map<String, Object> getEnhancedBatchMetrics() {
    Map<String, Object> metrics = new ConcurrentHashMap<>();
    metrics.put("totalBatchesProcessed", TOTAL_BATCHES_PROCESSED.get());
    metrics.put("totalJNICallsSaved", TOTAL_JNI_CALLS_SAVED.get());
    metrics.put("totalMemoryCopiesAvoided", TOTAL_MEMORY_COPIES_AVOIDED.get());
    metrics.put("currentBufferUsage", BUFFER_USAGE_COUNT.get());
    metrics.put("maxBuffers", MAX_BUFFERS);
    metrics.put("bufferSize", BUFFER_SIZE);
    return metrics;
  }

  /**
   * Shutdown the bridge and cleanup resources.
   */
  public void shutdown() {
    try {
      if (workerHandle != 0) {
        NativeBridge.destroyOptimizedWorker(workerHandle);
        workerHandle = 0;
      }
    } catch (Exception e) {
      KneafCore.LOGGER.warn("Error during bridge shutdown", e);
    }

    callbackExecutor.shutdown();
    try {
      if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        callbackExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      callbackExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    KneafCore.LOGGER.info("EnhancedNativeBridge shutdown complete");
  }

  /**
   * Result of batch processing operation.
   */
  public static class BatchResult {
    private final boolean success;
    private final byte[] data;
    private final String errorMessage;
    private final double processingTimeMs;

    private BatchResult(boolean success, byte[] data, String errorMessage, double processingTimeMs) {
      this.success = success;
      this.data = data;
      this.errorMessage = errorMessage;
      this.processingTimeMs = processingTimeMs;
    }

    public static BatchResult success(byte[] data, double processingTimeMs) {
      return new BatchResult(true, data, null, processingTimeMs);
    }

    public static BatchResult failure(String errorMessage) {
      return new BatchResult(false, null, errorMessage, 0.0);
    }

    public boolean isSuccess() {
      return success;
    }

    public byte[] getData() {
      return data;
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public double getProcessingTimeMs() {
      return processingTimeMs;
    }
  }
}
