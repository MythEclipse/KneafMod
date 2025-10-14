package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

// Native bridge untuk zero-copy operations
class NativeZeroCopyBridge {
    // Native method declarations untuk zero-copy operations
    public static native byte[] nativeExecuteSync(String operationName, Object[] parameters);
    public static native long nativeAllocateZeroCopyBuffer(long size, int bufferType);
    public static native void nativeFreeBuffer(long bufferHandle);
    public static native ByteBuffer nativeGetBufferContent(long bufferHandle);
    public static native long nativeCreateWorker(int threadCount);
    public static native void nativeDestroyWorker(long workerHandle);
    public static native long nativePushTask(long workerHandle, NativeTask task);
    public static native List<TaskResult> nativePollTasks(long workerHandle, int maxResults);
    
    // Load native library
    static {
        try {
            if (!com.kneaf.core.performance.bridge.NativeLibraryLoader.loadNativeLibrary()) {
                Logger.getLogger(NativeZeroCopyBridge.class.getName()).log(Level.WARNING,
                    "Native library 'rustperf' not available via NativeLibraryLoader; JNI features will be disabled.");
            } else {
                Logger.getLogger(NativeZeroCopyBridge.class.getName()).log(Level.INFO,
                    "Native library 'rustperf' loaded successfully via NativeLibraryLoader.");
            }
        } catch (Throwable t) {
            Logger.getLogger(NativeZeroCopyBridge.class.getName()).log(Level.WARNING,
                "Failed to load native library via NativeLibraryLoader: " + t.getMessage(), t);
        }
    }
}

/**
 * Zero-copy bridge implementation for efficient direct memory access operations.
 * Minimizes data copying between Java and native code by using direct memory access.
 */
public class ZeroCopyBridge implements UnifiedBridge {
    private static final Logger LOGGER = Logger.getLogger(ZeroCopyBridge.class.getName());
    
    private BridgeConfiguration config;
    private final ExecutorService executorService;
    private final BridgeErrorHandler errorHandler;
    private final BridgeMetrics metrics;
    private final JniCallManager jniCallManager;
    private final BufferPoolManager bufferPoolManager;
    private final ResourceManager resourceManager;
    private final WorkerManager workerManager;

    /**
     * Create a new ZeroCopyBridge instance.
     * @param config Bridge configuration
     */
    public ZeroCopyBridge(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        this.errorHandler = BridgeErrorHandler.getDefault();
        this.metrics = BridgeMetrics.getInstance(config);
        this.jniCallManager = JniCallManager.getInstance(config);
        this.bufferPoolManager = BufferPoolManager.getInstance(config);
        this.resourceManager = ResourceManager.getInstance(config);
        this.workerManager = WorkerManager.getInstance(config);
        
        // Create thread pool for zero-copy operations
        this.executorService = Executors.newFixedThreadPool(
                config.getDefaultWorkerConcurrency(),
                r -> new Thread(r, "ZeroCopyBridge-Executor-" + r.hashCode())
        );
        
        LOGGER.info("ZeroCopyBridge initialized with configuration: " + config.toMap());
    }

    @Override
    public CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // For zero-copy bridge, async operations use direct memory access
                return executeSync(operationName, parameters);
                
            } catch (Exception e) {
                metrics.recordOperation(operationName, startTime, System.nanoTime(), 
                        calculateBytesProcessed(parameters), false);
                
                return errorHandler.handleBridgeError("Async operation failed: " + operationName, 
                        e, BridgeException.BridgeErrorType.GENERIC_ERROR);
            }
        }, executorService);
    }

    @Override
    public BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            // Call actual native sync method with zero-copy optimizations
            LOGGER.log(FINE, "Executing zero-copy sync operation: {0} with {1} parameters",
                    new Object[]{operationName, parameters.length});
            
            byte[] nativeResult = NativeZeroCopyBridge.nativeExecuteSync(operationName, parameters);
            
            // Convert native result to BridgeResult with zero-copy metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("nativeOperation", operationName);
            metadata.put("nativeResult", nativeResult != null ? nativeResult.length : 0);
            metadata.put("zeroCopyOptimized", true);
            
            BridgeResult result = BridgeResultFactory.createSuccess(operationName, nativeResult, metadata);
            
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 
                    calculateBytesProcessed(parameters), true);
            
            return result;
            
        } catch (Exception e) {
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 
                    calculateBytesProcessed(parameters), false);
            
            throw new BridgeException("Zero-copy sync operation failed: " + operationName, 
                    BridgeException.BridgeErrorType.GENERIC_ERROR, e);
        }
    }

    @Override
    public BatchResult executeBatch(String batchName, List<BridgeOperation> operations) throws BridgeException {
        Objects.requireNonNull(batchName, "Batch name cannot be null");
        Objects.requireNonNull(operations, "Operations cannot be null");
        
        long batchStartTime = System.nanoTime();
        BatchResult.Builder resultBuilder = new BatchResult.Builder()
                .batchName(batchName)
                .totalOperations(operations.size())
                .startTime(System.currentTimeMillis());
        
        int successfulOperations = 0;
        long totalBytesProcessed = 0;
        
        try {
            // Use zero-copy optimized batch processing
            for (BridgeOperation operation : operations) {
                try {
                    BridgeResult operationResult = executeSync(operation.getOperationName(), 
                            operation.getParameters());
                    resultBuilder.addOperationResult(operationResult);
                    successfulOperations++;
                    totalBytesProcessed += calculateBytesProcessed(operation.getParameters());
                } catch (BridgeException e) {
                    resultBuilder.addOperationResult(BridgeResultFactory.createFailure(
                            operation.getOperationName(),
                            e.getMessage()
                    ));
                    LOGGER.log(Level.WARNING, "Zero-copy batch operation failed: " + operation.getOperationName(), e);
                }
            }
            
            resultBuilder.successfulOperations(successfulOperations)
                    .totalBytesProcessed(totalBytesProcessed)
                    .endTime(System.currentTimeMillis())
                    .success(true);
            
            metrics.recordBatchOperation(System.currentTimeMillis(), batchName, batchStartTime, 
                    System.nanoTime(), operations.size(), successfulOperations, 
                    operations.size() - successfulOperations, totalBytesProcessed, true);
            
            return resultBuilder.build();
            
        } catch (Exception e) {
            metrics.recordBatchOperation(System.currentTimeMillis(), batchName, batchStartTime, 
                    System.nanoTime(), operations.size(), successfulOperations, 
                    operations.size() - successfulOperations, totalBytesProcessed, false);
            
            throw new BridgeException("Zero-copy batch operation failed: " + batchName, 
                    BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED, e);
        }
    }

    /**
     * Allocate zero-copy buffer from native memory.
     * @param size Buffer size in bytes
     * @param bufferType Type of buffer to allocate
     * @return Handle to allocated buffer
     * @throws BridgeException If buffer allocation fails
     */
    public long allocateZeroCopyBuffer(long size, BufferType bufferType) throws BridgeException {
        Objects.requireNonNull(bufferType, "Buffer type cannot be null");
        
        if (size <= 0) {
            throw new BridgeException("Buffer size must be positive",
                    BridgeException.BridgeErrorType.GENERIC_ERROR);
        }
        
        long startTime = System.nanoTime();
        
        try {
            // Validate buffer size against configuration limits
            if (size > config.getMaxBufferSize()) {
                throw new BridgeException("Buffer size " + size + " exceeds maximum allowed " + config.getMaxBufferSize(),
                        BridgeException.BridgeErrorType.GENERIC_ERROR);
            }
            
            // Allocate buffer with zero-copy semantics - uses direct memory allocation
            LOGGER.log(FINE, "Allocating zero-copy buffer of size {0} with type {1}",
                    new Object[]{size, bufferType.name()});
            
            // Call actual native zero-copy buffer allocation
            long bufferHandle = NativeZeroCopyBridge.nativeAllocateZeroCopyBuffer(size, bufferType.ordinal());
            
            if (bufferHandle <= 0) {
                throw new BridgeException("Failed to allocate zero-copy buffer (invalid handle: " + bufferHandle + ")",
                        BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
            }
            
            // Log buffer allocation for monitoring
            LOGGER.log(FINE, "Zero-copy buffer allocated with handle: {0}, size: {1}, type: {2}",
                    new Object[]{bufferHandle, size, bufferType.name()});
            
            metrics.recordOperation("buffer.allocate.zeroCopy", startTime, System.nanoTime(),
                    size, true);
            
            return bufferHandle;
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.allocate.zeroCopy", startTime, System.nanoTime(),
                    size, false);
            
            throw new BridgeException("Zero-copy buffer allocation failed",
                    BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED, e);
        }
    }
    
    /**
     * Free previously allocated zero-copy buffer.
     * @param bufferHandle Handle of buffer to free
     * @throws BridgeException If buffer free operation fails
     */
    public void freeBuffer(long bufferHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Freeing zero-copy buffer with handle {0}", bufferHandle);
            
            // Call actual native buffer free method
            NativeZeroCopyBridge.nativeFreeBuffer(bufferHandle);
            
            metrics.recordOperation("buffer.free.zeroCopy", startTime, System.nanoTime(), 0, true);
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.free.zeroCopy", startTime, System.nanoTime(), 0, false);
            
            throw new BridgeException("Zero-copy buffer free failed: " + bufferHandle,
                    BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED, e);
        }
    }
    
    /**
     * Get content of allocated zero-copy buffer.
     * @param bufferHandle Handle of buffer to get content from
     * @return ByteBuffer containing buffer content
     * @throws BridgeException If buffer access fails
     */
    public ByteBuffer getBufferContent(long bufferHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Verify buffer exists and is accessible
            LOGGER.log(FINE, "Getting content for zero-copy buffer with handle {0}", bufferHandle);
            
            // Call actual native buffer content retrieval
            ByteBuffer content = NativeZeroCopyBridge.nativeGetBufferContent(bufferHandle);
            
            if (content == null || !content.isDirect()) {
                throw new BridgeException("Invalid buffer content for handle: " + bufferHandle,
                        BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
            }
            
            metrics.recordOperation("buffer.getContent.zeroCopy", startTime, System.nanoTime(),
                    content.capacity(), true);
            
            return content;
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.getContent.zeroCopy", startTime, System.nanoTime(), 0, false);
            
            throw new BridgeException("Failed to get zero-copy buffer content: " + bufferHandle,
                    BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED, e);
        }
    }
    
    /**
     * Create a worker optimized for zero-copy operations.
     * @param workerConfig Configuration for the worker
     * @return Handle to created worker
     * @throws BridgeException If worker creation fails
     */
    public long createWorker(WorkerConfig workerConfig) throws BridgeException {
        Objects.requireNonNull(workerConfig, "Worker config cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            // Create worker optimized for zero-copy operations
            LOGGER.log(FINE, "Creating zero-copy worker with config: {0}", workerConfig);
            
            // Create worker optimized for zero-copy operations using actual native implementation
            long workerHandle = NativeZeroCopyBridge.nativeCreateWorker(workerConfig.getThreadCount());
            
            metrics.recordOperation("worker.create.zeroCopy", startTime, System.nanoTime(), 0, true);
            
            return workerHandle;
            
        } catch (Exception e) {
            metrics.recordOperation("worker.create.zeroCopy", startTime, System.nanoTime(), 0, false);
            
            throw new BridgeException("Zero-copy worker creation failed", 
                    BridgeException.BridgeErrorType.WORKER_CREATION_FAILED, e);
        }
    }
    
    /**
     * Destroy a zero-copy worker.
     * @param workerHandle Handle of worker to destroy
     * @throws BridgeException If worker destruction fails
     */
    public void destroyWorker(long workerHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Call actual native worker destruction method
            NativeZeroCopyBridge.nativeDestroyWorker(workerHandle);
            
            metrics.recordOperation("worker.destroy.zeroCopy", startTime, System.nanoTime(), 0, true);
            
        } catch (Exception e) {
            metrics.recordOperation("worker.destroy.zeroCopy", startTime, System.nanoTime(), 0, false);
            
            throw new BridgeException("Zero-copy worker destruction failed: " + workerHandle, 
                    BridgeException.BridgeErrorType.WORKER_DESTROY_FAILED, e);
        }
    }
    
    /**
     * Push a task to a zero-copy worker for processing.
     * @param workerHandle Handle of worker to push task to
     * @param task Task to process
     * @return Operation ID for the task
     * @throws BridgeException If task push fails
     */
    public long pushTask(long workerHandle, NativeTask task) throws BridgeException {
        Objects.requireNonNull(task, "Task cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            // Push task to worker with zero-copy optimizations
            LOGGER.log(FINE, "Pushing zero-copy task to worker {0}", workerHandle);
            
            // Call actual native task push method with zero-copy optimizations
            long operationId = NativeZeroCopyBridge.nativePushTask(workerHandle, task);
            
            if (operationId <= 0) {
                throw new BridgeException("Failed to push zero-copy task (invalid operation ID: " + operationId + ")",
                        BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED);
            }
            
            metrics.recordOperation("task.push.zeroCopy", startTime, System.nanoTime(),
                    calculateBytesProcessed(task), true);
            
            return operationId;
            
        } catch (Exception e) {
            metrics.recordOperation("task.push.zeroCopy", startTime, System.nanoTime(),
                    calculateBytesProcessed(task), false);
            
            throw new BridgeException("Zero-copy task push failed",
                    BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED, e);
        }
    }
    
    /**
     * Poll for completed tasks from a zero-copy worker.
     * @param workerHandle Handle of worker to poll
     * @param maxResults Maximum number of results to return
     * @return List of completed task results
     * @throws BridgeException If task polling fails
     */
    public List<TaskResult> pollTasks(long workerHandle, int maxResults) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Poll tasks from worker with zero-copy optimizations
            LOGGER.log(FINE, "Polling zero-copy tasks from worker {0}, max results: {1}", 
                    new Object[]{workerHandle, maxResults});
            
            // Call actual native task polling method with zero-copy optimizations
            List<TaskResult> results = NativeZeroCopyBridge.nativePollTasks(workerHandle, maxResults);
            
            metrics.recordOperation("task.poll.zeroCopy", startTime, System.nanoTime(),
                    calculateBytesProcessed(results), true);
            
            return results;
            
        } catch (Exception e) {
            metrics.recordOperation("task.poll.zeroCopy", startTime, System.nanoTime(), 0, false);
            
            throw new BridgeException("Zero-copy task polling failed", 
                    BridgeException.BridgeErrorType.RESULT_POLLING_FAILED, e);
        }
    }

    @Override
    public BridgeConfiguration getConfiguration() {
        return config;
    }

    @Override
    public void setConfiguration(BridgeConfiguration config) {
        // Field config adalah final, jadi kita tidak bisa mengubahnya
        // this.config = Objects.requireNonNull(config);
        LOGGER.info("ZeroCopyBridge configuration update requested but config is immutable");
    }

    @Override
    public BridgeMetrics getMetrics() {
        return metrics;
    }

    @Override
    public BridgeErrorHandler getErrorHandler() {
        return errorHandler;
    }
    
    /**
     * Register a zero-copy bridge plugin.
     * @param plugin Plugin to register
     * @return true if registration succeeded, false otherwise
     */
    public boolean registerPlugin(BridgePlugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        try {
            plugin.initialize(this);
            LOGGER.log(FINE, "Registered zero-copy plugin: {0}", plugin.getClass().getName());
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to register zero-copy plugin", e);
            return false;
        }
    }
    
    public boolean unregisterPlugin(BridgePlugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        try {
            plugin.destroy();
            LOGGER.log(FINE, "Unregistered zero-copy plugin: {0}", plugin.getClass().getName());
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to unregister zero-copy plugin", e);
            return false;
        }
    }

    @Override
    public boolean isValid() {
        return executorService.isShutdown() == false && workerManager != null && bufferPoolManager != null;
    }

    @Override
    public void shutdown() {
        LOGGER.info("ZeroCopyBridge shutting down");
        
        try {
            // Gracefully shutdown executor service
            executorService.shutdown();
            if (!executorService.awaitTermination(config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warning("Executor service did not terminate gracefully");
                executorService.shutdownNow();
            }
            
            // Shutdown all managers
            workerManager.shutdown();
            bufferPoolManager.shutdown();
            resourceManager.shutdown();
            jniCallManager.shutdown();
            
            LOGGER.info("ZeroCopyBridge shutdown complete");
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "ZeroCopyBridge shutdown failed", e);
            throw new BridgeException("Shutdown failed", 
                    BridgeException.BridgeErrorType.SHUTDOWN_ERROR, e);
        }
    }

    /**
     * Calculate approximate bytes processed from parameters.
     * @param parameters Operation parameters
     * @return Approximate bytes processed
     */
    private long calculateBytesProcessed(Object... parameters) {
        if (parameters == null || parameters.length == 0) {
            return 0;
        }
        
        long totalBytes = 0;
        for (Object param : parameters) {
            if (param instanceof byte[]) {
                totalBytes += ((byte[]) param).length;
            } else if (param instanceof ByteBuffer) {
                totalBytes += ((ByteBuffer) param).capacity();
            } else if (param instanceof String) {
                totalBytes += ((String) param).getBytes().length;
            } else if (param instanceof Number) {
                totalBytes += 8; // Approximate size for numbers
            }
            // Add more types as needed
        }
        
        return totalBytes;
    }

    /**
     * Calculate approximate bytes processed from a task.
     * @param task Native task
     * @return Approximate bytes processed
     */
    private long calculateBytesProcessed(NativeTask task) {
        // Calculate bytes processed from task parameters using zero-copy optimizations
        if (task == null || task.getParameters() == null) {
            return 0;
        }
        return calculateBytesProcessed(task.getParameters());
    }
    
    private long calculateBytesProcessed(List<TaskResult> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        
        long totalBytes = 0;
        for (TaskResult result : results) {
            Object resultData = result.getResult();
            if (resultData instanceof byte[]) {
                totalBytes += ((byte[]) resultData).length;
            } else if (resultData instanceof ByteBuffer) {
                totalBytes += ((ByteBuffer) resultData).capacity();
            } else if (resultData != null) {
                totalBytes += resultData.toString().getBytes().length;
            }
        }
        return totalBytes;
    }
}