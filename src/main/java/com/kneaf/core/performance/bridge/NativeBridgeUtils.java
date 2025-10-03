package com.kneaf.core.performance.bridge;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility methods for native bridge operations.
 */
public final class NativeBridgeUtils {
    
    private NativeBridgeUtils() {} // Prevent instantiation
    
    /**
     * Calculate optimal batch size based on current load and configuration.
     */
    public static int calculateOptimalBatchSize(int requestedSize, int minBatchSize, int maxBatchSize) {
        if (requestedSize <= minBatchSize) {
            return minBatchSize;
        } else if (requestedSize >= maxBatchSize) {
            return maxBatchSize;
        } else {
            // Round to nearest optimized size (multiples of 25)
            int batchSize = Math.round(requestedSize / 25.0f) * 25;
            return Math.max(minBatchSize, Math.min(maxBatchSize, batchSize));
        }
    }
    
    /**
     * Determine if buffer-based processing should be used based on payload size.
     */
    public static boolean shouldUseBufferProcessing(byte[] payload, int threshold) {
        return payload != null && payload.length > threshold;
    }
    
    /**
     * Determine if batch buffer processing should be used.
     */
    public static boolean shouldUseBatchBufferProcessing(byte[][] payloads, int batchSize, int totalSizeThreshold) {
        if (payloads == null || payloads.length == 0) {
            return false;
        }
        
        int totalSize = 0;
        int effectiveBatchSize = Math.min(payloads.length, batchSize);
        
        for (int i = 0; i < effectiveBatchSize; i++) {
            if (payloads[i] != null && payloads[i].length > 4096) {
                return true; // Use buffers if any payload is large
            }
            if (payloads[i] != null) {
                totalSize += payloads[i].length;
            }
        }
        
        return totalSize > totalSizeThreshold;
    }
    
    /**
     * Buffer pool statistics.
     */
    public static class BufferPoolStats {
        private final AtomicLong totalBatchesProcessed = new AtomicLong(0);
        private final AtomicLong totalTasksProcessed = new AtomicLong(0);
        private final AtomicLong totalMemorySaved = new AtomicLong(0);
        private final AtomicInteger currentWorkerCount = new AtomicInteger(0);
        private final AtomicInteger pooledBufferCount = new AtomicInteger(0);
        
        public void recordBatchProcessed(int batchSize) {
            totalBatchesProcessed.incrementAndGet();
            totalTasksProcessed.addAndGet(batchSize);
        }
        
        public void recordMemorySaved(int size) {
            totalMemorySaved.addAndGet(size);
        }
        
        public void incrementWorkerCount() {
            currentWorkerCount.incrementAndGet();
        }
        
        public void decrementWorkerCount() {
            currentWorkerCount.decrementAndGet();
        }
        
        public void incrementPooledBufferCount() {
            pooledBufferCount.incrementAndGet();
        }
        
        public void decrementPooledBufferCount() {
            pooledBufferCount.decrementAndGet();
        }
        
        public long getTotalBatchesProcessed() { return totalBatchesProcessed.get(); }
        public long getTotalTasksProcessed() { return totalTasksProcessed.get(); }
        public long getTotalMemorySaved() { return totalMemorySaved.get(); }
        public int getCurrentWorkerCount() { return currentWorkerCount.get(); }
        public int getPooledBufferCount() { return pooledBufferCount.get(); }
        
        public double getAverageBatchSize() {
            long batches = totalBatchesProcessed.get();
            return batches > 0 ? (double) totalTasksProcessed.get() / batches : 0.0;
        }
    }
    
    /**
     * Native call metrics.
     */
    public static class NativeCallMetrics {
        private final AtomicLong totalCalls = new AtomicLong(0);
        private final AtomicLong successfulCalls = new AtomicLong(0);
        private final AtomicLong failedCalls = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        
        public void recordCall(boolean success, long duration) {
            totalCalls.incrementAndGet();
            totalDuration.addAndGet(duration);
            if (success) {
                successfulCalls.incrementAndGet();
            } else {
                failedCalls.incrementAndGet();
            }
        }
        
        public long getTotalCalls() { return totalCalls.get(); }
        public long getSuccessfulCalls() { return successfulCalls.get(); }
        public long getFailedCalls() { return failedCalls.get(); }
        public long getTotalDuration() { return totalDuration.get(); }
        
        public double getAverageDuration() {
            long calls = totalCalls.get();
            return calls > 0 ? (double) totalDuration.get() / calls : 0.0;
        }
        
        public double getSuccessRate() {
            long calls = totalCalls.get();
            return calls > 0 ? (double) successfulCalls.get() / calls : 0.0;
        }
    }
    
    /**
     * Platform-specific library name resolver.
     */
    public static String getPlatformLibraryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("windows")) {
            return "rustperf.dll";
        } else if (osName.contains("mac")) {
            return "librustperf.dylib";
        } else {
            return "librustperf.so";
        }
    }
    
    /**
     * Get platform-specific library names to try.
     */
    public static String[] getPlatformLibraryNames() {
        String osName = System.getProperty("os.name").toLowerCase();
        
        if (osName.contains("windows")) {
            return new String[]{"rustperf.dll", "librustperf.dll"};
        } else if (osName.contains("mac")) {
            return new String[]{"librustperf.dylib", "librustperf.jnilib"};
        } else {
            return new String[]{"librustperf.so", "rustperf.so"};
        }
    }
    
    /**
     * Validate native method result.
     */
    public static boolean isValidNativeResult(byte[] result) {
        return result != null && result.length > 0;
    }
    
    /**
     * Validate JSON result.
     */
    public static boolean isValidJsonResult(String result) {
        return result != null && !result.isEmpty() && 
               result.trim().startsWith("{") && result.trim().endsWith("}");
    }
    
    /**
     * Calculate buffer size with alignment.
     */
    public static int calculateAlignedBufferSize(int requiredSize, int alignment) {
        return ((requiredSize + alignment - 1) / alignment) * alignment;
    }
    
    /**
     * Safe buffer allocation with size validation.
     */
    public static ByteBuffer safeAllocateBuffer(int size, int maxSize) {
        if (size <= 0 || size > maxSize) {
            throw new IllegalArgumentException("Invalid buffer size: " + size);
        }
        return ByteBuffer.allocateDirect(size);
    }
}