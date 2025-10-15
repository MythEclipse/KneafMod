package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles enhanced batch processing with zero-copy optimization for Rust performance system.
 */
public class RustPerformanceBatchProcessor {
    private static final Logger LOGGER = Logger.getLogger(RustPerformanceBatchProcessor.class.getName());
    
    // Enhanced batch processing state
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> asyncOperations = new ConcurrentHashMap<>();
    private static final Semaphore batchProcessingSemaphore = new Semaphore(10, true); // Limit concurrent batches
    
    // Configuration constants
    public static final int DEFAULT_MIN_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = 1;
    public static final int DEFAULT_WORKER_THREADS = 8;

    /**
     * Initialize enhanced batch processor with custom configuration.
     *
     * @param minBatchSize          Minimum batch size
     * @param maxBatchSize          Maximum batch size
     * @param adaptiveTimeoutMs     Adaptive batch timeout in milliseconds
     * @param maxPendingBatches     Maximum pending batches
     * @param workerThreads         Number of worker threads
     * @param enableAdaptiveSizing  Enable adaptive batch sizing
     * @return true if initialization was successful
     */
    public static boolean nativeInitEnhancedBatchProcessor(
            int minBatchSize,
            int maxBatchSize,
            long adaptiveTimeoutMs,
            int maxPendingBatches,
            int workerThreads,
            boolean enableAdaptiveSizing
    ) {
        return RustPerformanceBase.safeNativeBooleanCall(
            () -> RustPerformanceBatchProcessor.nativeInitEnhancedBatchProcessorNative(
                    minBatchSize, maxBatchSize, adaptiveTimeoutMs, 
                    maxPendingBatches, workerThreads, enableAdaptiveSizing
            ),
            RustPerformanceError.BATCH_PROCESSING_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get enhanced batch processor metrics as JSON string.
     *
     * @return JSON string containing batch processor metrics
     */
    public static String nativeGetEnhancedBatchMetrics() {
        return RustPerformanceBase.safeNativeCall(
            RustPerformanceBatchProcessor::nativeGetEnhancedBatchMetricsNative,
            RustPerformanceError.BATCH_PROCESSING_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Submit zero-copy batched operations directly from Java ByteBuffer.
     *
     * @param operationType Operation type identifier
     * @param buffer        Direct ByteBuffer containing operations
     * @param bufferSize    Size of the buffer in bytes
     * @return JSON string containing operation result
     */
    public static String nativeSubmitZeroCopyBatchedOperations(
            byte operationType,
            ByteBuffer buffer,
            int bufferSize
    ) {
        return RustPerformanceBase.safeNativeCall(
            () -> {
                if (!buffer.isDirect()) {
                    throw new IllegalArgumentException("Buffer must be a direct ByteBuffer");
                }
                
                // Acquire semaphore to limit concurrent zero-copy operations
                try {
                    if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("Zero-copy operation semaphore timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Zero-copy operation semaphore acquisition interrupted", e);
                }
                
                try {
                    return RustPerformanceBatchProcessor.nativeSubmitZeroCopyBatchedOperationsNative(
                            operationType, buffer, bufferSize
                    );
                } finally {
                    batchProcessingSemaphore.release();
                }
            },
            RustPerformanceError.ZERO_COPY_ERROR,
            RustPerformanceLoader::isInitialized,
            true
        );
    }
    
    /**
     * Submit async batched operations with priority support.
     *
     * @param operationType Operation type identifier
     * @param operations    Byte array containing serialized operations
     * @param priorities    Byte array containing priorities for each operation
     * @return Operation ID for polling results
     */
    public static long nativeSubmitAsyncBatchedOperations(
            byte operationType,
            byte[] operations,
            byte[] priorities
    ) {
        return RustPerformanceBase.safeNativeCall(
            () -> RustPerformanceBatchProcessor.nativeSubmitAsyncBatchedOperationsNative(
                    operationType, operations, priorities
            ),
            RustPerformanceError.BATCH_PROCESSING_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Poll async batch operation results.
     *
     * @param operationId Operation ID to poll
     * @return Byte array containing operation results
     */
    public static byte[] nativePollAsyncBatchResult(long operationId) {
        return RustPerformanceBase.safeNativeCall(
            () -> RustPerformanceBatchProcessor.nativePollAsyncBatchResultNative(operationId),
            RustPerformanceError.BATCH_PROCESSING_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Enhanced batch processing with zero-copy optimization.
     * Processes a batch of operations using direct memory access.
     *
     * @param operationType Type of operation to perform
     * @param operations    List of operations to process
     * @param priorities    Priorities for each operation
     * @return CompletableFuture containing the batch processing results
     */
    public static CompletableFuture<byte[]> processEnhancedBatch(
            byte operationType,
            List<byte[]> operations,
            List<Byte> priorities
    ) {
        if (!RustPerformanceLoader.isInitialized()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(RustPerformanceError.NOT_INITIALIZED.getMessage())
            );
        }

        if (operations == null || operations.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(RustPerformanceError.INVALID_ARGUMENTS.getMessage())
            );
        }

        if (priorities == null || priorities.size() != operations.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(RustPerformanceError.INVALID_ARGUMENTS.getMessage())
            );
        }

        try {
            // Acquire semaphore to limit concurrent batch processing
            try {
                if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException(RustPerformanceError.SEMAPHORE_TIMEOUT.getMessage())
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Batch processing semaphore acquisition interrupted", e)
                );
            }

            // Prepare operations data
            int totalSize = 0;
            for (byte[] operation : operations) {
                totalSize += operation.length + 4; // 4 bytes for length prefix
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(4 + totalSize); // 4 bytes for count prefix
            buffer.putInt(operations.size());

            for (int i = 0; i < operations.size(); i++) {
                byte[] operation = operations.get(i);
                buffer.putInt(operation.length);
                buffer.put(operation);
                
                // Store priority for later use
                Byte priority = priorities.get(i);
                if (priority == null) {
                    buffer.put((byte) 0); // Default priority
                } else {
                    buffer.put(priority);
                }
            }

            buffer.flip();

            // Submit to native processor
            long operationId = nativeSubmitAsyncBatchedOperations(
                    operationType,
                    buffer.array(),
                    buffer.array()
            );

            // Create and return CompletableFuture for async result
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            asyncOperations.put(operationId, future);

            // Schedule result polling
            scheduleAsyncResultPolling(operationId, future);

            return future;
        } catch (Exception e) {
            batchProcessingSemaphore.release();
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Schedule periodic polling for async batch operation results.
     *
     * @param operationId Operation ID to poll
     * @param future      CompletableFuture to complete with results
     */
    private static void scheduleAsyncResultPolling(long operationId, CompletableFuture<byte[]> future) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                scheduler.shutdown();
                return;
            }

            try {
                byte[] result = nativePollAsyncBatchResult(operationId);
                if (result != null && result.length > 0) {
                    future.complete(result);
                    scheduler.shutdown();
                    asyncOperations.remove(operationId);
                    batchProcessingSemaphore.release();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to poll async batch result for operation " + operationId, e);
                future.completeExceptionally(e);
                scheduler.shutdown();
                asyncOperations.remove(operationId);
                batchProcessingSemaphore.release();
            }
        }, 0, 100, TimeUnit.MILLISECONDS); // Poll every 100ms
    }
    
    /**
     * Get comprehensive performance metrics as JSON string.
     *
     * @return JSON string containing all performance metrics
     */
    public static String getComprehensiveMetrics() {
        return RustPerformanceBase.safeNativeCall(
            () -> {
                if (!RustPerformanceLoader.isInitialized()) {
                    return "{\"error\":\"" + RustPerformanceError.NOT_INITIALIZED.getMessage() + "\"}";
                }

                try {
                    long uptime = RustPerformanceLoader.getUptimeMs();
                    double tps = RustPerformanceMonitor.getCurrentTPS();
                    long totalTicks = RustPerformanceMonitor.getTotalTicksProcessed();
                    long totalEntities = RustPerformanceMonitor.getTotalEntitiesProcessed();
                    long totalMobs = RustPerformanceMonitor.getTotalMobsProcessed();
                    long totalBlocks = RustPerformanceMonitor.getTotalBlocksProcessed();
                    long totalMerged = RustPerformanceMonitor.getTotalMerged();
                    long totalDespawned = RustPerformanceMonitor.getTotalDespawned();
                    String cpuStats = RustPerformanceMonitor.getCpuStats();
                    String memoryStats = RustPerformanceMonitor.getMemoryStats();
                    String batchMetrics = nativeGetEnhancedBatchMetrics();

                    return String.format("{\"uptimeMs\":%d,\"tps\":%.2f,\"totalTicks\":%d,\"totalEntities\":%d,\"totalMobs\":%d,\"totalBlocks\":%d,\"totalMerged\":%d,\"totalDespawned\":%d,\"cpuStats\":\"%s\",\"memoryStats\":\"%s\",\"batchMetrics\":%s}",
                            uptime, tps, totalTicks, totalEntities, totalMobs, totalBlocks, totalMerged, totalDespawned,
                            cpuStats != null ? cpuStats.replace("\"", "\\\"") : "null", 
                            memoryStats != null ? memoryStats.replace("\"", "\\\"") : "null", 
                            batchMetrics != null ? batchMetrics : "null");
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, RustPerformanceError.METRICS_ERROR.getMessage(), e);
                    return "{\"error\":\"" + RustPerformanceError.METRICS_ERROR.getMessage() + "\"}";
                }
            },
            RustPerformanceError.METRICS_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    // Native method declarations
    private static native boolean nativeInitEnhancedBatchProcessorNative(
            int minBatchSize,
            int maxBatchSize,
            long adaptiveTimeoutMs,
            int maxPendingBatches,
            int workerThreads,
            boolean enableAdaptiveSizing
    );
    
    private static native String nativeGetEnhancedBatchMetricsNative();
    
    private static native String nativeSubmitZeroCopyBatchedOperationsNative(
            byte operationType,
            ByteBuffer buffer,
            int bufferSize
    );
    
    private static native long nativeSubmitAsyncBatchedOperationsNative(
            byte operationType,
            byte[] operations,
            byte[] priorities
    );
    
    private static native byte[] nativePollAsyncBatchResultNative(long operationId);
}