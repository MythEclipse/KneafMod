package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Handles enhanced batch processing with zero-copy optimization for Rust performance system.
 * Uses composition pattern to delegate to RustPerformanceBase for common functionality.
 */
public final class RustPerformanceBatchProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceBatchProcessor.class);
    
    // Enhanced batch processing state
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> asyncOperations = new ConcurrentHashMap<>();
    private static final Semaphore batchProcessingSemaphore = new Semaphore(10, true); // Limit concurrent batches

    // Configuration constants (reuse from base class)
    public static final int DEFAULT_MIN_BATCH_SIZE = RustPerformanceBase.DEFAULT_MIN_BATCH_SIZE;
    public static final int DEFAULT_MAX_BATCH_SIZE = RustPerformanceBase.DEFAULT_MAX_BATCH_SIZE;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = RustPerformanceBase.DEFAULT_ADAPTIVE_TIMEOUT_MS;
    public static final int DEFAULT_WORKER_THREADS = RustPerformanceBase.DEFAULT_WORKER_THREADS;

    /**
     * Record for batch processing parameters.
     * Uses Java 16+ record feature for immutable data carrier.
     */
    public record BatchConfig(
        int minBatchSize,
        int maxBatchSize,
        long adaptiveTimeoutMs,
        int maxPendingBatches,
        int workerThreads,
        boolean enableAdaptiveSizing
    ) {
        /**
         * Create a BatchConfig with default values.
         *
         * @return BatchConfig with default values
         */
        public static BatchConfig defaults() {
            return new BatchConfig(
                DEFAULT_MIN_BATCH_SIZE,
                DEFAULT_MAX_BATCH_SIZE,
                DEFAULT_ADAPTIVE_TIMEOUT_MS,
                10, // Default max pending batches
                DEFAULT_WORKER_THREADS,
                false
            );
        }
    }

    /**
     * Enum for operation types.
     */
    public enum OperationType {
        STANDARD((byte) 0),
        HIGH_PRIORITY((byte) 1),
        LOW_PRIORITY((byte) 2),
        ZERO_COPY((byte) 3);

        private final byte value;

        OperationType(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        /**
         * Get OperationType from byte value.
         *
         * @param value Byte value
         * @return OperationType or null if not found
         */
        public static OperationType fromValue(byte value) {
            return switch (value) {
                case 0 -> STANDARD;
                case 1 -> HIGH_PRIORITY;
                case 2 -> LOW_PRIORITY;
                case 3 -> ZERO_COPY;
                default -> null;
            };
        }
    }

    /**
     * Initialize enhanced batch processor with custom configuration.
     *
     * @param config Batch configuration
     * @return true if initialization was successful
     */
    public static boolean nativeInitEnhancedBatchProcessor(BatchConfig config) {
        return nativeInitEnhancedBatchProcessor(
            config.minBatchSize(),
            config.maxBatchSize(),
            config.adaptiveTimeoutMs(),
            config.maxPendingBatches(),
            config.workerThreads(),
            config.enableAdaptiveSizing()
        );
    }

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
            OperationType operationType,
            ByteBuffer buffer,
            int bufferSize
    ) {
        return nativeSubmitZeroCopyBatchedOperations(operationType.getValue(), buffer, bufferSize);
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
                    if (!tryAcquireSemaphore(Duration.ofMillis(100))) {
                        throw RustPerformanceError.SEMAPHORE_TIMEOUT.asRuntimeException();
                    }
                    
                    try {
                        return nativeSubmitZeroCopyBatchedOperationsNative(operationType, buffer, bufferSize);
                    } finally {
                        releaseSemaphore();
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
            OperationType operationType,
            byte[] operations,
            byte[] priorities
    ) {
        return nativeSubmitAsyncBatchedOperations(operationType.getValue(), operations, priorities);
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
                () -> nativeSubmitAsyncBatchedOperationsNative(operationType, operations, priorities),
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
            OperationType operationType,
            List<byte[]> operations,
            List<Byte> priorities
    ) {
        return processEnhancedBatch(operationType.getValue(), operations, priorities);
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
                    RustPerformanceError.NOT_INITIALIZED.asIllegalStateException()
            );
        }

        RustPerformanceBase.validateOperations(operations);
        RustPerformanceBase.validatePriorities(operations, priorities);

        try {
            // Acquire semaphore to limit concurrent batch processing
            if (!tryAcquireSemaphore(Duration.ofMillis(100))) {
                return CompletableFuture.failedFuture(
                        RustPerformanceError.SEMAPHORE_TIMEOUT.asIllegalStateException()
                );
            }

            // Calculate exact required buffer sizes
            int totalOperationSize = calculateTotalOperationSize(operations);
            
            // Create and populate operations buffer
            byte[] operationsData = createOperationsBuffer(operations);
            
            // Create priorities array
            byte[] prioritiesData = createPrioritiesArray(priorities);
            
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
            scheduleAsyncResultPolling(operationId, future, Duration.ofSeconds(5));

            return future;
        } catch (Exception e) {
            releaseSemaphore();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Calculate total size of operations with length prefixes.
     *
     * @param operations List of operations
     * @return Total size in bytes
     */
    private static int calculateTotalOperationSize(List<byte[]> operations) {
        int totalSize = 0;
        for (byte[] operation : operations) {
            totalSize += operation.length + 4; // 4 bytes for length prefix
        }
        return totalSize;
    }

    /**
     * Create operations buffer with length prefixes.
     *
     * @param operations List of operations
     * @return Byte array containing operations with length prefixes
     */
    private static byte[] createOperationsBuffer(List<byte[]> operations) {
        int totalSize = calculateTotalOperationSize(operations);
        ByteBuffer buffer = ByteBuffer.allocateDirect(totalSize);
        
        for (byte[] operation : operations) {
            buffer.putInt(operation.length);
            buffer.put(operation);
        }
        
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    /**
     * Create priorities array from List<Byte>.
     *
     * @param priorities List of priorities
     * @return Byte array of priorities
     */
    private static byte[] createPrioritiesArray(List<Byte> priorities) {
        byte[] result = new byte[priorities.size()];
        for (int i = 0; i < priorities.size(); i++) {
            result[i] = priorities.get(i);
        }
        return result;
    }

    /**
     * Try to acquire semaphore with timeout.
     *
     * @param timeout Duration to wait for semaphore
     * @return true if semaphore was acquired, false otherwise
     */
    private static boolean tryAcquireSemaphore(Duration timeout) {
        try {
            return batchProcessingSemaphore.tryAcquire(1, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Release semaphore permit.
     */
    private static void releaseSemaphore() {
        batchProcessingSemaphore.release();
    }

    /**
     * Execute code with semaphore permit.
     *
     * @param supplier Code to execute
     * @return Result of the code execution
     * @throws Exception If the code throws an exception
     */
    private static <T> T withSemaphorePermit(Supplier<T> supplier) throws Exception {
        if (!tryAcquireSemaphore(Duration.ofMillis(100))) {
            throw RustPerformanceError.SEMAPHORE_TIMEOUT.asRuntimeException();
        }
        try {
            return supplier.get();
        } finally {
            releaseSemaphore();
        }
    }

    /**
     * Schedule periodic polling for async batch operation results with timeout.
     *
     * @param operationId Operation ID to poll
     * @param future      CompletableFuture to complete with results
     * @param timeout     Timeout duration
     */
    private static void scheduleAsyncResultPolling(long operationId, CompletableFuture<byte[]> future, Duration timeout) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "BatchResultPoller-" + operationId)
        );
        
        // Schedule timeout task
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                LOGGER.warn("Batch operation {} timed out after {}", operationId, timeout);
                future.completeExceptionally(
                    new TimeoutException("Batch operation timed out after " + timeout)
                );
                asyncOperations.remove(operationId);
                releaseSemaphore();
                scheduler.shutdownNow();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        
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
                    releaseSemaphore();
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to poll async batch result for operation {}", operationId, e);
                future.completeExceptionally(e);
                scheduler.shutdown();
                asyncOperations.remove(operationId);
                releaseSemaphore();
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
                        return createErrorJson(RustPerformanceError.NOT_INITIALIZED);
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
                                escapeJson(cpuStats),
                                escapeJson(memoryStats),
                                escapeJson(batchMetrics));
                    } catch (Exception e) {
                        LOGGER.warn(RustPerformanceError.METRICS_ERROR.getMessage(), e);
                        return createErrorJson(RustPerformanceError.METRICS_ERROR);
                    }
                },
                RustPerformanceError.METRICS_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Escape JSON string for inclusion in JSON.
     *
     * @param input String to escape
     * @return Escaped string or "null" if input is null
     */
    private static String escapeJson(String input) {
        return input != null ? input.replace("\"", "\\\"") : "null";
    }

    /**
     * Create error JSON string.
     *
     * @param error Error to include in JSON
     * @return JSON string with error
     */
    private static String createErrorJson(RustPerformanceError error) {
        return "{\"error\":\"" + escapeJson(error.getMessage()) + "\"}";
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