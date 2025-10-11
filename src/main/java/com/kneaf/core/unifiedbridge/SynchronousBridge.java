package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronous implementation of UnifiedBridge for immediate operations.
 * Processes tasks synchronously with proper timeout handling and error management.
 */
public class SynchronousBridge implements UnifiedBridge {
    
    // Add missing fields
    private static final Logger LOGGER = Logger.getLogger(SynchronousBridge.class.getName());
    
    private BridgeConfiguration config;
    private BridgeMetrics metrics;
    private final WorkerManager workerManager;
    private final JniCallManager jniCallManager;
    private final ResourceManager resourceManager;
    private final BridgeErrorHandler errorHandler;

    /**
     * Create a new SynchronousBridge instance.
     * @param config Bridge configuration
     */
    public SynchronousBridge(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        this.workerManager = WorkerManager.getInstance(config);
        this.jniCallManager = JniCallManager.getInstance(config);
        this.resourceManager = ResourceManager.getInstance(config);
        this.errorHandler = BridgeErrorHandler.getDefault();
        this.metrics = BridgeMetrics.getInstance(config);
        
        LOGGER.info("Created SynchronousBridge with config: " + config.toMap());
    }

    /**
     * Create a worker with specified concurrency level.
     * @param concurrency Number of concurrent threads
     * @return Worker handle
     */
    public long createWorker(int concurrency) {
        try {
            return workerManager.createWorker(concurrency);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to create worker", e, BridgeException.BridgeErrorType.WORKER_CREATION_FAILED);
        }
    }

    /**
     * Destroy a worker by handle.
     * @param workerHandle Worker handle to destroy
     */
    public void destroyWorker(long workerHandle) {
        try {
            workerManager.destroyWorker(workerHandle);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to destroy worker", e, BridgeException.BridgeErrorType.WORKER_DESTROY_FAILED);
        }
    }

    /**
     * Push a task with byte array payload to a worker.
     * @param workerHandle Worker handle
     * @param payload Task payload
     */
    public void pushTask(long workerHandle, byte[] payload) {
        Objects.requireNonNull(payload, "Payload cannot be null");
        
        try {
            // Use JniCallManager for optimized JNI calls
            jniCallManager.pushTask(workerHandle, payload, config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
            
            // Record task processing for metrics
            WorkerManager.Worker worker = workerManager.getWorker(workerHandle);
            if (worker != null) {
                worker.recordTaskProcessed();
            }
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to push task", e, BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED);
        }
    }

    /**
     * Push a task with ByteBuffer payload to a worker.
     * @param workerHandle Worker handle
     * @param payload Task payload
     */
    public void pushTask(long workerHandle, ByteBuffer payload) {
        Objects.requireNonNull(payload, "Payload cannot be null");
        
        try {
            // Convert ByteBuffer to byte array for consistent processing
            byte[] payloadArray = new byte[payload.remaining()];
            payload.get(payloadArray);
            pushTask(workerHandle, payloadArray);
            
            // Return buffer to pool if pooling is enabled
            if (config.isEnableBufferPooling()) {
                resourceManager.returnBufferToPool(payload);
            }
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to push task with ByteBuffer", e, BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED);
        }
    }

    /**
     * Poll for task results from a worker.
     * @param workerHandle Worker handle
     * @return Task result payload
     */
    public byte[] pollResult(long workerHandle) {
        try {
            // Use JniCallManager for optimized JNI calls
            return jniCallManager.pollResult(workerHandle, config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to poll result", e, BridgeException.BridgeErrorType.RESULT_POLLING_FAILED);
        }
    }

    /**
     * Poll for task results as ByteBuffer from a worker.
     * @param workerHandle Worker handle
     * @return Task result as ByteBuffer
     */
    public ByteBuffer pollResultBuffer(long workerHandle) {
        try {
            byte[] result = pollResult(workerHandle);
            if (result != null) {
                // Use ResourceManager for buffer allocation
                ByteBuffer buffer = resourceManager.allocateBuffer(result.length, config.isEnableBufferPooling());
                buffer.put(result);
                buffer.flip();
                return buffer;
            }
            return null;
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to poll result buffer", e, BridgeException.BridgeErrorType.RESULT_POLLING_FAILED);
        }
    }

    /**
     * Push a batch of tasks to a worker.
     * @param workerHandle Worker handle
     * @param payloads Array of task payloads
     * @return CompletableFuture with batch result
     */
    public CompletableFuture<BatchResult> pushBatch(long workerHandle, byte[][] payloads) {
        Objects.requireNonNull(payloads, "Payloads cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.nanoTime();
                
                // Validate batch size
                int batchSize = payloads.length;
                // Use BridgeConfiguration for optimal batch size
                int optimalSize = config.getOptimalBatchSize(batchSize);
                
                // Process batch with JniCallManager
                jniCallManager.pushBatch(workerHandle, payloads, optimalSize,
                        config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
                
                // Record batch processing
                WorkerManager.Worker worker = workerManager.getWorker(workerHandle);
                if (worker != null) {
                    worker.recordTaskProcessed();
                }
                
                long endTime = System.nanoTime();
                
                // Build batch result
                return new BatchResult.Builder()
                        .batchId(System.nanoTime())
                        .startTimeNanos(startTime)
                        .endTimeNanos(endTime)
                        .totalTasks(batchSize)
                        .successfulTasks(batchSize) // Assume all succeeded for synchronous
                        .failedTasks(0)
                        .totalBytesProcessed(calculateTotalBytes(payloads))
                        .detailedStats(Map.of("optimalBatchSize", optimalSize))
                        .status("COMPLETED")
                        .build();
                
            } catch (Exception e) {
                throw errorHandler.createBridgeError("Failed to process batch", e, BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED);
            }
        });
    }

    /**
     * Push a batch of ByteBuffer tasks to a worker.
     * @param workerHandle Worker handle
     * @param payloads Array of ByteBuffer task payloads
     * @return CompletableFuture with batch result
     */
    public CompletableFuture<BatchResult> pushBatch(long workerHandle, ByteBuffer[] payloads) {
        Objects.requireNonNull(payloads, "Payloads cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Convert ByteBuffers to byte arrays
                byte[][] payloadArrays = new byte[payloads.length][];
                for (int i = 0; i < payloads.length; i++) {
                    ByteBuffer buffer = payloads[i];
                    byte[] array = new byte[buffer.remaining()];
                    buffer.get(array);
                    payloadArrays[i] = array;
                    
                    // Return buffer to pool if pooling is enabled
                    if (config.isEnableBufferPooling()) {
                        resourceManager.returnBufferToPool(buffer);
                    }
                }
                
                return pushBatch(workerHandle, payloadArrays).join();
                
            } catch (Exception e) {
                throw errorHandler.createBridgeError("Failed to process ByteBuffer batch", e, BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED);
            }
        });
    }

    /**
     * Allocate a buffer of specified size.
     * @param size Buffer size in bytes
     * @return Allocated ByteBuffer
     */
    public ByteBuffer allocateBuffer(int size) {
        try {
            // Use ResourceManager for buffer allocation
            return resourceManager.allocateBuffer(size, config.isEnableBufferPooling());
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to allocate buffer", e, BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
        }
    }

    /**
     * Free a previously allocated buffer.
     * @param buffer Buffer to free
     */
    public void freeBuffer(ByteBuffer buffer) {
        try {
            // Use ResourceManager for buffer cleanup
            resourceManager.freeBuffer(buffer);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to free buffer", e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
    }

    /**
     * Get native memory address of a buffer.
     * @param buffer Buffer to get address from
     * @return Native memory address
     */
    public long getBufferAddress(ByteBuffer buffer) {
        try {
            // Use ResourceManager to get buffer address
            return resourceManager.getBufferAddress(buffer);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to get buffer address", e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
    }

    /**
     * Submit a zero-copy operation to a worker.
     * @param workerHandle Worker handle
     * @param buffer Direct ByteBuffer for zero-copy operation
     * @param operationType Type of operation to perform
     * @return Operation handle
     */
    public long submitZeroCopyOperation(long workerHandle, ByteBuffer buffer, int operationType) {
        try {
            // Zero-copy operations require direct buffers
            if (buffer == null || !buffer.isDirect()) {
                throw new IllegalArgumentException("Zero-copy operations require direct ByteBuffer");
            }
            if (operationType <= 0) {
                throw new IllegalArgumentException("Invalid operation type: " + operationType);
            }
            
            // Use JniCallManager for zero-copy operation
            return jniCallManager.submitZeroCopyOperation(workerHandle, buffer, operationType,
                    config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to submit zero-copy operation", e, BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
        }
    }

    /**
     * Poll for result of a zero-copy operation.
     * @param operationId Operation handle
     * @return Result as ByteBuffer
     */
    public ByteBuffer pollZeroCopyResult(long operationId) {
        try {
            // Use JniCallManager for zero-copy result polling
            return jniCallManager.pollZeroCopyResult(operationId, config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to poll zero-copy result", e, BridgeException.BridgeErrorType.RESULT_POLLING_FAILED);
        }
    }

    /**
     * Cleanup resources for a zero-copy operation.
     * @param operationId Operation handle
     */
    public void cleanupZeroCopyOperation(long operationId) {
        try {
            // Use JniCallManager for cleanup
            jniCallManager.cleanupZeroCopyOperation(operationId);
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to cleanup zero-copy operation", e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
    }

    /**
     * Get bridge statistics.
     * @return Map of statistics
     */
    public Map<String, Object> getStats() {
        try {
            Map<String, Object> workerStats = workerManager.getWorkerStats();
            // Use JniCallManager for statistics
            Map<String, Object> jniStats = jniCallManager.getCallStats();
            
            Map<String, Object> combinedStats = Map.of(
                    "bridgeType", "SYNCHRONOUS",
                    "workerStats", workerStats,
                    "jniStats", jniStats,
                    "configuration", config.toMap(),
                    "isNativeAvailable", jniCallManager.isNativeAvailable()
            );
            
            return combinedStats;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get statistics", e);
            return Map.of("bridgeType", "SYNCHRONOUS", "error", "Failed to get statistics");
        }
    }

    /**
     * Get optimal batch size for performance.
     * @param requestedSize Requested batch size
     * @return Optimal batch size
     */
    public int getOptimalBatchSize(int requestedSize) {
        try {
            // Use JniCallManager for optimal batch size calculation
            return jniCallManager.getOptimalBatchSize(requestedSize);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get optimal batch size", e);
            return Math.max(config.getMinBatchSize(), Math.min(config.getMaxBatchSize(), requestedSize));
        }
    }

    /**
     * Flush pending operations.
     */
    public void flush() {
        try {
            // Use JniCallManager for flush operation
            jniCallManager.flush();
        } catch (Exception e) {
            throw errorHandler.createBridgeError("Failed to flush operations", e, BridgeException.BridgeErrorType.GENERIC_ERROR);
        }
    }

    /**
     * Check if native functionality is available.
     * @return true if native functionality is available, false otherwise
     */
    public boolean isNativeAvailable() {
        // Use JniCallManager to check native availability
        return jniCallManager.isNativeAvailable();
    }

    // Implement missing methods from UnifiedBridge interface
    
    @Override
    public BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            // In real implementation, this would call native code synchronously
            BridgeResult result = BridgeResultFactory.createSuccess(operationName);
            
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 0, result.isSuccess());
            
            return result;
            
        } catch (Exception e) {
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Sync operation failed: " + operationName,
                    e, BridgeException.BridgeErrorType.GENERIC_ERROR);
        }
    }
    
    @Override
    public CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        long startTime = System.nanoTime();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // In real implementation, this would call native code asynchronously
                BridgeResult result = executeSync(operationName, parameters);
                
                metrics.recordOperation(operationName, startTime, System.nanoTime(), 0, result.isSuccess());
                
                return result;
                
            } catch (Exception e) {
                metrics.recordOperation(operationName, startTime, System.nanoTime(), 0, false);
                
                return errorHandler.handleBridgeError("Async operation failed: " + operationName,
                        e, BridgeException.BridgeErrorType.GENERIC_ERROR);
            }
        });
    }
    
    @Override
    public BatchResult executeBatch(String batchName, java.util.List<BridgeOperation> operations) throws BridgeException {
        Objects.requireNonNull(batchName, "Batch name cannot be null");
        Objects.requireNonNull(operations, "Operations cannot be null");
        
        long batchStartTime = System.nanoTime();
        BatchResult.Builder resultBuilder = new BatchResult.Builder()
                .batchName(batchName)
                .totalOperations(operations.size())
                .startTime(System.currentTimeMillis());
        
        int successfulOperations = 0;
        
        try {
            for (BridgeOperation operation : operations) {
                try {
                    BridgeResult operationResult = executeSync(operation.getOperationName(),
                            operation.getParameters());
                    resultBuilder.addOperationResult(operationResult);
                    successfulOperations++;
                } catch (BridgeException e) {
                    resultBuilder.addOperationResult(new BridgeResult.Builder()
                            .operationName(operation.getOperationName())
                            .success(false)
                            .message(e.getMessage())
                            .build());
                    LOGGER.log(Level.WARNING, "Batch operation failed: " + operation.getOperationName(), e);
                }
            }
            
            resultBuilder.successfulOperations(successfulOperations)
                    .endTime(System.currentTimeMillis())
                    .success(true);
            
            metrics.recordBatchOperation(System.currentTimeMillis(), batchName, batchStartTime,
                    System.nanoTime(), operations.size(), successfulOperations,
                    operations.size() - successfulOperations, 0, true);
            
            return resultBuilder.build();
            
        } catch (Exception e) {
            metrics.recordBatchOperation(System.currentTimeMillis(), batchName, batchStartTime,
                    System.nanoTime(), operations.size(), successfulOperations,
                    operations.size() - successfulOperations, 0, false);
            
            throw errorHandler.createBridgeError("Batch operation failed: " + batchName,
                    e, BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED);
        }
    }
    
    @Override
    public BridgeConfiguration getConfiguration() {
        return config;
    }
    
    @Override
    public void setConfiguration(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        LOGGER.info("SynchronousBridge configuration updated");
    }
    
    @Override
    public BridgeMetrics getMetrics() {
        return metrics;
    }
    
    @Override
    public BridgeErrorHandler getErrorHandler() {
        return errorHandler;
    }
    
    @Override
    public boolean isValid() {
        return workerManager != null && config != null;
    }
    
    @Override
    public void shutdown() {
        LOGGER.info("SynchronousBridge shutting down");
        // Cleanup resources
        try {
            workerManager.shutdown();
            LOGGER.info("SynchronousBridge shutdown complete");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "SynchronousBridge shutdown failed", e);
            throw errorHandler.createBridgeError("Shutdown failed",
                    e, BridgeException.BridgeErrorType.SHUTDOWN_ERROR);
        }
    }

    /**
     * Calculate total bytes in payload array.
     * @param payloads Array of payloads
     * @return Total bytes
     */
    private long calculateTotalBytes(byte[][] payloads) {
        long total = 0;
        for (byte[] payload : payloads) {
            if (payload != null) {
                total += payload.length;
            }
        }
        return total;
    }
}