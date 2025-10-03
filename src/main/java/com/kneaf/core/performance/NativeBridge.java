package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced JNI bridge for worker-based native processing with optimized batch processing.
 * Implements memory pooling and batch processing to minimize memory copies.
 */
public final class NativeBridge {
    static {
        try {
            System.loadLibrary("rustperf");
        } catch (UnsatisfiedLinkError e) {
            // library may not be present in test environment
        }
    }

    // Batch processing configuration - increased for better throughput
    private static final int OPTIMIZED_BATCH_SIZE = 100; // Increased from 50 to 100
    private static final int MAX_BATCH_SIZE = 200; // Maximum batch size for heavy loads
    private static final int MIN_BATCH_SIZE = 25; // Minimum batch size
    
    // Memory pool configuration
    private static final int BUFFER_POOL_SIZE = 50;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB max buffer size
    
    // Performance metrics
    private static final AtomicLong totalBatchesProcessed = new AtomicLong(0);
    private static final AtomicLong totalTasksProcessed = new AtomicLong(0);
    private static final AtomicLong totalMemorySaved = new AtomicLong(0);
    private static final AtomicInteger currentWorkerCount = new AtomicInteger(0);
    
    // Buffer pooling for memory efficiency
    private static final ConcurrentLinkedQueue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pooledBufferCount = new AtomicInteger(0);
    
    private NativeBridge() {}

    // Initialize the Rust allocator - should be called once at startup
    public static native void initRustAllocator();

    // Enhanced worker methods with batch processing
    public static native long nativeCreateWorker(int concurrency);
    public static native void nativePushTask(long workerHandle, byte[] payload);
    public static native void nativePushTaskBuffer(long workerHandle, ByteBuffer payload);
    public static native byte[] nativePollResult(long workerHandle);
    public static native ByteBuffer nativePollResultBuffer(long workerHandle);
    public static native void nativeDestroyWorker(long workerHandle);
    
    // Batch processing methods - minimize memory copies
    public static native void nativePushBatch(long workerHandle, byte[][] payloads, int batchSize);
    public static native void nativePushBatchBuffer(long workerHandle, ByteBuffer[] payloads, int batchSize);
    public static native byte[][] nativePollBatchResults(long workerHandle, int maxResults);
    public static native ByteBuffer[] nativePollBatchResultsBuffer(long workerHandle, int maxResults);
    
    // Memory pool management
    public static native ByteBuffer nativeAllocateBuffer(int size);
    public static native void nativeFreeBuffer(ByteBuffer buffer);
    public static native long nativeGetBufferAddress(ByteBuffer buffer);
    
    // Performance monitoring
    public static native long nativeGetWorkerQueueDepth(long workerHandle);
    public static native double nativeGetWorkerAvgProcessingMs(long workerHandle);
    public static native long nativeGetWorkerMemoryUsage(long workerHandle);
    
    /**
     * Create worker with optimized configuration
     */
    public static long createOptimizedWorker(int concurrency) {
        long workerHandle = nativeCreateWorker(concurrency);
        if (workerHandle != 0) {
            currentWorkerCount.incrementAndGet();
        }
        return workerHandle;
    }
    
    /**
     * Destroy worker and cleanup resources
     */
    public static void destroyOptimizedWorker(long workerHandle) {
        if (workerHandle != 0) {
            nativeDestroyWorker(workerHandle);
            currentWorkerCount.decrementAndGet();
        }
    }
    
    /**
     * Push task with buffer pooling to minimize memory allocation
     */
    public static void pushTaskOptimized(long workerHandle, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        
        // Use direct buffer if payload is large to avoid JVM heap copies
        if (payload.length > 8192) { // 8KB threshold
            ByteBuffer buffer = getPooledBuffer(payload.length);
            try {
                buffer.clear();
                buffer.put(payload);
                buffer.flip();
                nativePushTaskBuffer(workerHandle, buffer);
            } finally {
                returnPooledBuffer(buffer);
            }
        } else {
            nativePushTask(workerHandle, payload);
        }
    }
    
    /**
     * Push batch of tasks with optimized memory management
     */
    public static void pushBatchOptimized(long workerHandle, byte[][] payloads) {
        if (payloads == null || payloads.length == 0) {
            return;
        }
        
        int batchSize = Math.min(payloads.length, MAX_BATCH_SIZE);
        totalBatchesProcessed.incrementAndGet();
        totalTasksProcessed.addAndGet(batchSize);
        
        // Use buffer-based batch processing for large payloads
        boolean useBuffers = false;
        int totalSize = 0;
        for (int i = 0; i < batchSize; i++) {
            if (payloads[i] != null && payloads[i].length > 4096) {
                useBuffers = true;
                totalSize += payloads[i].length;
            }
        }
        
        if (useBuffers && totalSize > 16384) { // 16KB threshold
            ByteBuffer[] buffers = new ByteBuffer[batchSize];
            try {
                for (int i = 0; i < batchSize; i++) {
                    if (payloads[i] != null) {
                        buffers[i] = getPooledBuffer(payloads[i].length);
                        buffers[i].clear();
                        buffers[i].put(payloads[i]);
                        buffers[i].flip();
                    }
                }
                nativePushBatchBuffer(workerHandle, buffers, batchSize);
            } finally {
                for (ByteBuffer buffer : buffers) {
                    if (buffer != null) {
                        returnPooledBuffer(buffer);
                    }
                }
            }
        } else {
            nativePushBatch(workerHandle, payloads, batchSize);
        }
    }
    
    /**
     * Poll results with buffer pooling
     */
    public static byte[] pollResultOptimized(long workerHandle) {
        // Try buffer-based result first for zero-copy where possible
        ByteBuffer bufferResult = nativePollResultBuffer(workerHandle);
        if (bufferResult != null) {
            byte[] result = new byte[bufferResult.remaining()];
            bufferResult.get(result);
            returnPooledBuffer(bufferResult);
            return result;
        }
        
        // Fallback to regular byte array result
        return nativePollResult(workerHandle);
    }
    
    /**
     * Get pooled buffer to minimize allocations
     */
    private static ByteBuffer getPooledBuffer(int size) {
        if (size > MAX_BUFFER_SIZE) {
            return nativeAllocateBuffer(size);
        }
        
        ByteBuffer buffer = bufferPool.poll();
        if (buffer != null && buffer.capacity() >= size) {
            pooledBufferCount.decrementAndGet();
            totalMemorySaved.addAndGet(size);
            return buffer;
        }
        
        return nativeAllocateBuffer(size);
    }
    
    /**
     * Return buffer to pool for reuse
     */
    private static void returnPooledBuffer(ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() > MAX_BUFFER_SIZE) {
            if (buffer != null) {
                nativeFreeBuffer(buffer);
            }
            return;
        }
        
        if (pooledBufferCount.get() < BUFFER_POOL_SIZE) {
            buffer.clear();
            bufferPool.offer(buffer);
            pooledBufferCount.incrementAndGet();
        } else {
            nativeFreeBuffer(buffer);
        }
    }
    
    /**
     * Get batch processing statistics
     */
    public static Map<String, Object> getBatchStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalBatchesProcessed", totalBatchesProcessed.get());
        stats.put("totalTasksProcessed", totalTasksProcessed.get());
        stats.put("totalMemorySaved", totalMemorySaved.get());
        stats.put("currentWorkerCount", currentWorkerCount.get());
        stats.put("pooledBufferCount", pooledBufferCount.get());
        
        if (totalBatchesProcessed.get() > 0) {
            double avgBatchSize = (double) totalTasksProcessed.get() / totalBatchesProcessed.get();
            stats.put("averageBatchSize", String.format("%.2f", avgBatchSize));
        }
        
        return stats;
    }
    
    /**
     * Cleanup buffer pool and free resources
     */
    public static void cleanupBufferPool() {
        ByteBuffer buffer;
        while ((buffer = bufferPool.poll()) != null) {
            nativeFreeBuffer(buffer);
            pooledBufferCount.decrementAndGet();
        }
    }
    
    /**
     * Get optimal batch size based on current load
     */
    public static int getOptimalBatchSize(int requestedSize) {
        if (requestedSize <= MIN_BATCH_SIZE) {
            return MIN_BATCH_SIZE;
        } else if (requestedSize >= MAX_BATCH_SIZE) {
            return MAX_BATCH_SIZE;
        } else {
            // Round to nearest optimized size
            int batchSize = Math.round(requestedSize / 25.0f) * 25;
            return Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, batchSize));
        }
    }
}
