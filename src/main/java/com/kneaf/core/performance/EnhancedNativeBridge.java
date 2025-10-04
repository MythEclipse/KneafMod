package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced JNI bridge with optimized batching to reduce JNI boundary crossing.
 * Implements adaptive batching with dynamic sizing based on load and performance metrics.
 */
public final class EnhancedNativeBridge {
    static {
        try {
            System.loadLibrary("rustperf");
        } catch (UnsatisfiedLinkError e) {
            // library may not be present in test environment
        }
    }

    private EnhancedNativeBridge() {}

    // Batch configuration with adaptive sizing
    private static int adaptiveBatchTimeoutMs() {
        double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
        double tickDelay = com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
    return (int) com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBatchTimeoutMs(tps, tickDelay);
    }
    private static int minBatchSize() { return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBatchSize(com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS(), com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs()); }
    private static int maxBatchSize() { return Math.max(50, com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBatchSize(com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS(), com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs()) * 2); }
    private static int maxPendingBatches() { return Math.max(10, com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveQueueCapacity(com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS()) / 20); }

    // Batch operation types
    public static final byte OPERATION_TYPE_ENTITY = 0x01;
    public static final byte OPERATION_TYPE_ITEM = 0x02;
    public static final byte OPERATION_TYPE_MOB = 0x03;
    public static final byte OPERATION_TYPE_BLOCK = 0x04;
    public static final byte OPERATION_TYPE_SPATIAL = 0x05;
    public static final byte OPERATION_TYPE_SIMD = 0x06;

    // Performance metrics
    private static final AtomicLong totalBatchesProcessed = new AtomicLong(0);
    private static final AtomicLong totalOperationsBatched = new AtomicLong(0);
    private static final AtomicLong averageBatchSize = new AtomicLong(0);
    private static final AtomicBoolean batchProcessorRunning = new AtomicBoolean(false);

    // Batch queues per operation type
    private static final ConcurrentLinkedQueue<BatchOperation>[] operationQueues;
    private static final Thread[] batchProcessorThreads;

    static {
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<BatchOperation>[] queues = new ConcurrentLinkedQueue[7];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ConcurrentLinkedQueue<>();
        }
        operationQueues = queues;
        
        // Initialize batch processor threads
        batchProcessorThreads = new Thread[4]; // 4 threads for parallel batch processing
        startBatchProcessors();
    }

    /**
     * Enhanced batch operation with result callback
     */
    public static class BatchOperation {
        public final byte operationType;
        public final ByteBuffer inputData;
        public final CompletableFuture<byte[]> resultFuture;
        public final long timestamp;
        public final int estimatedSize;

        public BatchOperation(byte operationType, ByteBuffer inputData, CompletableFuture<byte[]> resultFuture) {
            this.operationType = operationType;
            this.inputData = inputData;
            this.resultFuture = resultFuture;
            this.timestamp = System.nanoTime();
            this.estimatedSize = inputData != null ? inputData.remaining() : 0;
        }
    }

    /**
     * Batch processing result
     */
    public static class BatchResult {
        public final byte operationType;
        public final List<byte[]> results;
        public final long processingTimeNs;

        public BatchResult(byte operationType, List<byte[]> results, long processingTimeNs) {
            this.operationType = operationType;
            this.results = results;
            this.processingTimeNs = processingTimeNs;
        }
    }

    /**
     * Submit operation for batch processing
     */
    public static CompletableFuture<byte[]> submitOperation(byte operationType, ByteBuffer inputData) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        BatchOperation operation = new BatchOperation(operationType, inputData, future);
        
        // Submit to appropriate queue
        if (operationType >= 0 && operationType < operationQueues.length) {
            operationQueues[operationType].offer(operation);
        } else {
            future.completeExceptionally(new IllegalArgumentException("Invalid operation type: " + operationType));
        }
        
        return future;
    }

    /**
     * Process batch of operations with optimized JNI calls
     */
    private static void processBatch(byte operationType, List<BatchOperation> batch) {
        if (batch.isEmpty()) return;

        long startTime = System.nanoTime();
        
        try {
            // Prepare batch data for JNI call
            int totalSize = 0;
            for (BatchOperation op : batch) {
                totalSize += op.estimatedSize;
            }

            // Create combined buffer for batch processing
            ByteBuffer batchBuffer = ByteBuffer.allocateDirect(totalSize + batch.size() * 8);
            batchBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            
            // Write batch header
            batchBuffer.putInt(batch.size()); // Number of operations
            batchBuffer.putInt(totalSize);    // Total data size
            
            // Write individual operations
            for (BatchOperation op : batch) {
                batchBuffer.putInt(op.estimatedSize);
                if (op.inputData != null) {
                    batchBuffer.put(op.inputData);
                }
            }
            batchBuffer.flip();

            // Call native batch processing method
            byte[] batchResult = nativeProcessBatch(operationType, batchBuffer);

            if (batchResult != null) {
                // Parse batch results
                ByteBuffer resultBuffer = ByteBuffer.wrap(batchResult).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int resultCount = resultBuffer.getInt();
                
                List<byte[]> results = new ArrayList<>(resultCount);
                for (int i = 0; i < resultCount; i++) {
                    int resultSize = resultBuffer.getInt();
                    if (resultSize > 0 && resultBuffer.remaining() >= resultSize) {
                        byte[] resultData = new byte[resultSize];
                        resultBuffer.get(resultData);
                        results.add(resultData);
                    } else {
                        results.add(new byte[0]);
                    }
                }

                // Complete futures with results
                for (int i = 0; i < Math.min(batch.size(), results.size()); i++) {
                    BatchOperation op = batch.get(i);
                    if (!op.resultFuture.isDone()) {
                        op.resultFuture.complete(results.get(i));
                    }
                }
            } else {
                // Complete with empty results
                for (BatchOperation op : batch) {
                    if (!op.resultFuture.isDone()) {
                        op.resultFuture.complete(new byte[0]);
                    }
                }
            }
        } catch (Exception e) {
            // Complete all futures with exception
            for (BatchOperation op : batch) {
                if (!op.resultFuture.isDone()) {
                    op.resultFuture.completeExceptionally(e);
                }
            }
        }

        // Update metrics
        long processingTime = System.nanoTime() - startTime;
        totalBatchesProcessed.incrementAndGet();
        totalOperationsBatched.addAndGet(batch.size());
        
        // Update average batch size (exponential moving average)
        long currentAvg = averageBatchSize.get();
        long newAvg = (currentAvg * 9 + batch.size()) / 10; // 90% weight on historical, 10% on new
        averageBatchSize.set(newAvg);
    }

    /**
     * Batch processor thread implementation
     */
    private static class BatchProcessor implements Runnable {
        private final byte operationType;
        private final int processorId;

        public BatchProcessor(byte operationType, int processorId) {
            this.operationType = operationType;
            this.processorId = processorId;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("EnhancedNativeBridge-BatchProcessor-" + operationType + "-" + processorId);
            
            while (batchProcessorRunning.get()) {
                try {
                    List<BatchOperation> batch = collectBatch();
                    if (!batch.isEmpty()) {
                        processBatch(operationType, batch);
                    }
                    
                    // Adaptive sleep based on load
                    if (batch.size() < minBatchSize()) {
                        Thread.sleep(adaptiveBatchTimeoutMs());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Log error but continue processing
                    System.err.println("Error in batch processor: " + e.getMessage());
                }
            }
        }

        private List<BatchOperation> collectBatch() {
            List<BatchOperation> batch = new ArrayList<>();
            ConcurrentLinkedQueue<BatchOperation> queue = operationQueues[operationType];
            
            long startTime = System.currentTimeMillis();
            int targetSize = calculateOptimalBatchSize();
            
         while (batch.size() < targetSize && 
             (System.currentTimeMillis() - startTime) < adaptiveBatchTimeoutMs()) {
                
                BatchOperation op = queue.poll();
                if (op != null) {
                    batch.add(op);
                } else if (!batch.isEmpty()) {
                    // Have some operations, break if timeout approaching
                    break;
                } else {
                    // No operations available, wait a bit
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            return batch;
        }

        private int calculateOptimalBatchSize() {
            // Adaptive batch sizing based on recent performance
            long avgSize = averageBatchSize.get();
            long totalOps = totalOperationsBatched.get();
            long totalBatches = totalBatchesProcessed.get();
            
            if (totalBatches > 0 && totalOps > 0) {
                double actualAvg = (double) totalOps / totalBatches;
                
                // Adjust target size based on actual performance
                if (actualAvg > avgSize * 1.2) {
                    // Performance is good, increase batch size
                    return Math.min(maxBatchSize(), (int)(avgSize * 1.1));
                } else if (actualAvg < avgSize * 0.8) {
                    // Performance is poor, decrease batch size
                    return Math.max(minBatchSize(), (int)(avgSize * 0.9));
                }
            }
            
            return (int)Math.max(minBatchSize(), Math.min(maxBatchSize(), avgSize));
        }
    }

    /**
     * Start batch processor threads
     */
    private static void startBatchProcessors() {
        if (batchProcessorRunning.compareAndSet(false, true)) {
            for (int i = 0; i < batchProcessorThreads.length; i++) {
                final int processorId = i;
                batchProcessorThreads[i] = new Thread(() -> {
                    // Distribute operation types across processors
                    for (byte opType = 0; opType < operationQueues.length; opType++) {
                        if (opType % batchProcessorThreads.length == processorId) {
                            new BatchProcessor(opType, processorId).run();
                        }
                    }
                });
                batchProcessorThreads[i].setDaemon(true);
                batchProcessorThreads[i].setName("EnhancedNativeBridge-Processor-" + i);
                batchProcessorThreads[i].start();
            }
        }
    }

    /**
     * Shutdown batch processors
     */
    public static void shutdown() {
        batchProcessorRunning.set(false);
        for (Thread thread : batchProcessorThreads) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    /**
     * Get batch processing metrics
     */
    public static BatchMetrics getMetrics() {
        return new BatchMetrics(
            totalBatchesProcessed.get(),
            totalOperationsBatched.get(),
            averageBatchSize.get(),
            getCurrentQueueDepth()
        );
    }

    private static int getCurrentQueueDepth() {
        int totalDepth = 0;
        for (ConcurrentLinkedQueue<BatchOperation> queue : operationQueues) {
            totalDepth += queue.size();
        }
        return totalDepth;
    }

    /**
     * Batch processing metrics
     */
    public static class BatchMetrics {
        public final long totalBatchesProcessed;
        public final long totalOperationsBatched;
        public final long averageBatchSize;
        public final int currentQueueDepth;

        public BatchMetrics(long totalBatchesProcessed, long totalOperationsBatched, 
                          long averageBatchSize, int currentQueueDepth) {
            this.totalBatchesProcessed = totalBatchesProcessed;
            this.totalOperationsBatched = totalOperationsBatched;
            this.averageBatchSize = averageBatchSize;
            this.currentQueueDepth = currentQueueDepth;
        }
    }

    // Enhanced native methods with batch support
    public static native void initRustAllocator();
    public static native long nativeCreateWorker(int concurrency);
    public static native void nativePushTask(long workerHandle, byte[] payload);
    public static native byte[] nativePollResult(long workerHandle);
    public static native void nativeDestroyWorker(long workerHandle);
    
    // New batch processing native method
    private static native byte[] nativeProcessBatch(byte operationType, ByteBuffer batchData);
    
    // SIMD operations
    public static native String getSimdFeatures();
    public static native int initSimd(boolean enableAvx2, boolean enableSse41, boolean enableSse42, 
                                    boolean enableFma, boolean forceScalarFallback);
    
    // Batch processor metrics
    public static native String getBatchProcessorMetrics();
    public static native int initBatchProcessor(int maxBatchSize, long batchTimeoutMs, 
                                              int maxQueueSize, int workerThreads);
}