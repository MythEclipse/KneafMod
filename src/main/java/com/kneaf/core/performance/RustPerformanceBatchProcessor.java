package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles enhanced batch processing with zero-copy optimization for Rust performance system.
 * Uses composition pattern to delegate to RustPerformanceBase for common functionality.
 */
public class RustPerformanceBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceBatchProcessor.class.getName());
    
    // Enhanced batch processing state
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> asyncOperations = new ConcurrentHashMap<>();
    private static final Semaphore batchProcessingSemaphore = new Semaphore(10, true); // Limit concurrent batches

    // Configuration constants (reuse from base class)
    public static final int DEFAULT_MIN_BATCH_SIZE = RustPerformanceBase.DEFAULT_MIN_BATCH_SIZE;
    public static final int DEFAULT_MAX_BATCH_SIZE = RustPerformanceBase.DEFAULT_MAX_BATCH_SIZE;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = RustPerformanceBase.DEFAULT_ADAPTIVE_TIMEOUT_MS;
    public static final int DEFAULT_WORKER_THREADS = RustPerformanceBase.DEFAULT_WORKER_THREADS;

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
                () -> nativeInitEnhancedBatchProcessorNative(
                        minBatchSize, maxBatchSize, adaptiveTimeoutMs,
                        maxPendingBatches, workerThreads, enableAdaptiveSizing
                ),
                RustPerformanceError.BATCH_PROCESSING_ERROR,
                RustPerformanceBase::isInitialized
        );
    }
    
    /**
     * Get enhanced batch processor metrics as JSON string.
     *
     * @return JSON string containing batch processor metrics
     */
    public static String nativeGetEnhancedBatchMetrics() {
        return RustPerformanceBase.safeNativeStringCall(
                RustPerformanceBatchProcessor::nativeGetEnhancedBatchMetricsNative,
                RustPerformanceError.BATCH_PROCESSING_ERROR,
                RustPerformanceBase::isInitialized
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
        return RustPerformanceBase.safeNativeStringCall(
                () -> {
                    RustPerformanceBase.validateDirectBuffer(buffer);
                    
                    // Acquire semaphore to limit concurrent zero-copy operations
                    try {
                        if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                            throw new IllegalStateException(RustPerformanceError.SEMAPHORE_TIMEOUT.getMessage());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(RustPerformanceError.SEMAPHORE_TIMEOUT.getMessage(), e);
                    }
                    
                    try {
                        return nativeSubmitZeroCopyBatchedOperationsNative(
                                operationType, buffer, bufferSize
                        );
                    } finally {
                        batchProcessingSemaphore.release();
                    }
                },
                RustPerformanceError.ZERO_COPY_ERROR,
                RustPerformanceBase::isInitialized
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
        return RustPerformanceBase.safeNativeLongCall(
                () -> nativeSubmitAsyncBatchedOperationsNative(
                        operationType, operations, priorities
                ),
                RustPerformanceError.BATCH_PROCESSING_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Poll async batch operation results.
     *
     * @param operationId Operation ID to poll
     * @return Byte array containing operation results
     */
    public static byte[] nativePollAsyncBatchResult(long operationId) {
        return RustPerformanceBase.safeNativeByteArrayCall(
                () -> nativePollAsyncBatchResultNative(operationId),
                RustPerformanceError.BATCH_PROCESSING_ERROR,
                RustPerformanceBase::isInitialized
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
        if (!RustPerformanceBase.isInitialized()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(RustPerformanceError.NOT_INITIALIZED.getMessage())
            );
        }

        RustPerformanceBase.validateOperations(operations);
        RustPerformanceBase.validatePriorities(operations, priorities);

        try {
            // Acquire semaphore to limit concurrent batch processing
            if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(RustPerformanceError.SEMAPHORE_TIMEOUT.getMessage())
                );
            }

            // Calculate exact required buffer sizes
            int totalOperationSize = 0;
            for (byte[] operation : operations) {
                totalOperationSize += operation.length + 4; // 4 bytes for length prefix
            }
            
            // Create separate buffers for operations and priorities
            ByteBuffer operationsBuffer = ByteBuffer.allocateDirect(totalOperationSize);
            
            // Write operations to buffer
            for (int i = 0; i < operations.size(); i++) {
                byte[] operation = operations.get(i);
                operationsBuffer.putInt(operation.length);
                operationsBuffer.put(operation);
            }
            
            operationsBuffer.flip();
            byte[] operationsData = new byte[operationsBuffer.remaining()];
            operationsBuffer.get(operationsData);
            
            // Create priorities array
            byte[] prioritiesData = new byte[priorities.size()];
            for (int i = 0; i < priorities.size(); i++) {
                prioritiesData[i] = priorities.get(i);
            }
            
            // Submit to native processor
            long operationId = nativeSubmitAsyncBatchedOperations(
                    operationType,
                    operationsData,
                    prioritiesData
            );

            // Create and return CompletableFuture for async result
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            asyncOperations.put(operationId, future);

            // Schedule result polling with timeout
            scheduleAsyncResultPolling(operationId, future, 5, TimeUnit.SECONDS);

            return future;
        } catch (Exception e) {
            batchProcessingSemaphore.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Schedule periodic polling for async batch operation results with timeout.
     *
     * @param operationId Operation ID to poll
     * @param future      CompletableFuture to complete with results
     * @param timeout     Timeout value
     * @param unit        Time unit for timeout
     */
    private static void scheduleAsyncResultPolling(long operationId, CompletableFuture<byte[]> future, long timeout, TimeUnit unit) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Schedule timeout task
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                LOGGER.warn("Batch operation {} timed out after {} {}", operationId, timeout, unit);
                future.completeExceptionally(new TimeoutException("Batch operation timed out after " + timeout + " " + unit));
                asyncOperations.remove(operationId);
                batchProcessingSemaphore.release();
                scheduler.shutdownNow();
            }
        }, timeout, unit);
        
        // Schedule polling task
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
                LOGGER.warn("Failed to poll async batch result for operation {}", operationId, e);
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
        return RustPerformanceBase.safeNativeStringCall(
                () -> {
                    if (!RustPerformanceBase.isInitialized()) {
                        return "{\"error\":\"" + RustPerformanceError.NOT_INITIALIZED.getMessage() + "\"}";
                    }

                    try {
                        long uptime = RustPerformanceBase.getUptimeMs();
                        double tps = RustPerformance.getCurrentTPS();
                        long totalTicks = RustPerformance.getTotalTicksProcessed();
                        long totalEntities = RustPerformance.getTotalEntitiesProcessed();
                        long totalMobs = RustPerformance.getTotalMobsProcessed();
                        long totalBlocks = RustPerformance.getTotalBlocksProcessed();
                        long totalMerged = RustPerformance.getTotalMerged();
                        long totalDespawned = RustPerformance.getTotalDespawned();
                        String cpuStats = RustPerformance.getCpuStats();
                        String memoryStats = RustPerformance.getMemoryStats();
                        String batchMetrics = nativeGetEnhancedBatchMetrics();

                        return String.format("{\"uptimeMs\":%d,\"tps\":%.2f,\"totalTicks\":%d,\"totalEntities\":%d,\"totalMobs\":%d,\"totalBlocks\":%d,\"totalMerged\":%d,\"totalDespawned\":%d,\"cpuStats\":\"%s\",\"memoryStats\":\"%s\",\"batchMetrics\":%s}",
                                uptime, tps, totalTicks, totalEntities, totalMobs, totalBlocks, totalMerged, totalDespawned,
                                cpuStats != null ? cpuStats.replace("\"", "\\\"") : "null",
                                memoryStats != null ? memoryStats.replace("\"", "\\\"") : "null",
                                batchMetrics != null ? batchMetrics : "null");
                    } catch (Exception e) {
                        LOGGER.warn(RustPerformanceError.METRICS_ERROR.getMessage(), e);
                        return "{\"error\":\"" + RustPerformanceError.METRICS_ERROR.getMessage() + "\"}";
                    }
                },
                RustPerformanceError.METRICS_ERROR,
                RustPerformanceBase::isInitialized
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