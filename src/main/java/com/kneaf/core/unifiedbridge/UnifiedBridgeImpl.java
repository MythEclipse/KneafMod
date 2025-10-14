package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

// Native bridge untuk actual JNI calls
class NativeUnifiedBridge {
    // Native method declarations untuk unified operations
    public static native byte[] nativeExecuteSync(String operationName, Object[] parameters);
    public static native byte[] nativeExecuteAsync(String operationName, Object[] parameters);
    public static native long nativeAllocateZeroCopyBuffer(long size, int bufferType);
    public static native void nativeFreeBuffer(long bufferHandle);
    public static native ByteBuffer nativeGetBufferContent(long bufferHandle);
    public static native long nativeCreateWorker(int threadCount);
    public static native void nativeDestroyWorker(long workerHandle);
    public static native long nativePushTask(long workerHandle, NativeTask task);
    public static native List<BridgeResult> nativePollTasks(long workerHandle, int maxResults);
    
    // Load native library
    static {
        try {
            if (!com.kneaf.core.performance.bridge.NativeLibraryLoader.loadNativeLibrary()) {
                Logger.getLogger(NativeUnifiedBridge.class.getName()).log(Level.WARNING,
                    "Native library 'rustperf' not available via NativeLibraryLoader; JNI features will be disabled.");
            } else {
                Logger.getLogger(NativeUnifiedBridge.class.getName()).log(Level.INFO,
                    "Native library 'rustperf' loaded successfully via NativeLibraryLoader.");
            }
        } catch (Throwable t) {
            Logger.getLogger(NativeUnifiedBridge.class.getName()).log(Level.WARNING,
                "Failed to load native library via NativeLibraryLoader: " + t.getMessage(), t);
        }
    }
}

/**
 * Main implementation that coordinates all unified bridge components.
 * Serves as the central entry point for all bridge operations.
 */
public class UnifiedBridgeImpl implements UnifiedBridge {
    private static final Logger LOGGER = Logger.getLogger(UnifiedBridgeImpl.class.getName());
    
    private BridgeConfiguration config;
    private final BridgeErrorHandler errorHandler;
    private final BridgeMetrics metrics;
    private final JniCallManager jniCallManager;
    private final BufferPoolManager bufferPoolManager;
    private final ResourceManager resourceManager;
    private final WorkerManager workerManager;
    private final ConcurrentMap<String, BridgePlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Create a new UnifiedBridgeImpl instance.
     * @param config Bridge configuration
     */
    public UnifiedBridgeImpl(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        this.errorHandler = BridgeErrorHandler.getDefault();
        this.metrics = BridgeMetrics.getInstance(config);
        this.jniCallManager = JniCallManager.getInstance(config);
        this.bufferPoolManager = BufferPoolManager.getInstance(config);
        this.resourceManager = ResourceManager.getInstance(config);
        this.workerManager = WorkerManager.getInstance(config);
        
        LOGGER.info("UnifiedBridgeImpl initialized with configuration: " + config.toMap());
    }

    @Override
    public CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        long startTime = System.nanoTime();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.log(FINE, "Executing async operation: {0} with {1} parameters",
                        new Object[]{operationName, parameters.length});
                
                // Call actual native async method
                byte[] nativeResult = NativeUnifiedBridge.nativeExecuteAsync(operationName, parameters);
                
                // Convert native result to BridgeResult
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("nativeOperation", operationName);
                metadata.put("nativeResult", nativeResult != null ? nativeResult.length : 0);
                BridgeResult result = BridgeResultFactory.createSuccess(operationName, nativeResult, metadata);
                
                metrics.recordOperation(operationName, startTime, System.nanoTime(),
                        calculateBytesProcessed(parameters), result.isSuccess());
                
                return result;
                
            } catch (Exception e) {
                metrics.recordOperation(operationName, startTime, System.nanoTime(),
                        calculateBytesProcessed(parameters), false);
                
                return errorHandler.handleBridgeError("Async operation failed: " + operationName,
                        e, BridgeException.BridgeErrorType.GENERIC_ERROR);
            }
        }).exceptionally(ex -> {
            metrics.recordOperation(operationName, System.nanoTime(), System.nanoTime(),
                    calculateBytesProcessed(parameters), false);
            
            return errorHandler.handleBridgeError("Async operation failed with exception: " + operationName,
                    ex, BridgeException.BridgeErrorType.GENERIC_ERROR);
        });
    }

    @Override
    public BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Executing sync operation: {0} with {1} parameters", 
                    new Object[]{operationName, parameters.length});
            
            // Call actual native sync method
            byte[] nativeResult = NativeUnifiedBridge.nativeExecuteSync(operationName, parameters);
            
            // Convert native result to BridgeResult
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("nativeOperation", operationName);
            metadata.put("nativeResult", nativeResult != null ? nativeResult.length : 0);
            BridgeResult result = BridgeResultFactory.createSuccess(operationName, nativeResult, metadata);
            
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 
                    calculateBytesProcessed(parameters), result.isSuccess());
            
            return result;
            
        } catch (Exception e) {
            metrics.recordOperation(operationName, startTime, System.nanoTime(), 
                    calculateBytesProcessed(parameters), false);
            
            throw errorHandler.createBridgeError("Sync operation failed: " + operationName,
                    e, BridgeException.BridgeErrorType.GENERIC_ERROR);
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
                    LOGGER.log(Level.WARNING, "Batch operation failed: " + operation.getOperationName(), e);
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
            
            throw errorHandler.createBridgeError("Batch operation failed: " + batchName,
                    e, BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED);
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
        
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Allocating zero-copy buffer of size {0} with type {1}",
                    new Object[]{size, bufferType.name()});
            
            // Call actual native buffer allocation
            long bufferHandle = NativeUnifiedBridge.nativeAllocateZeroCopyBuffer(size, bufferType.ordinal());
            
            metrics.recordOperation("buffer.allocate.zeroCopy", startTime, System.nanoTime(),
                    size, true);
            
            return bufferHandle;
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.allocate.zeroCopy", startTime, System.nanoTime(),
                    size, false);
            
            throw errorHandler.createBridgeError("Zero-copy buffer allocation failed",
                    e, BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
        }
    }

    /**
     * Free previously allocated buffer.
     * @param bufferHandle Handle of buffer to free
     * @throws BridgeException If buffer free operation fails
     */
    public void freeBuffer(long bufferHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Call actual native buffer free method
            NativeUnifiedBridge.nativeFreeBuffer(bufferHandle);
            
            metrics.recordOperation("buffer.free", startTime, System.nanoTime(), 0, true);
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.free", startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Buffer free failed: " + bufferHandle,
                    e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
    }

    /**
     * Get content of allocated buffer.
     * @param bufferHandle Handle of buffer to get content from
     * @return ByteBuffer containing buffer content
     * @throws BridgeException If buffer access fails
     */
    public ByteBuffer getBufferContent(long bufferHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Call actual native buffer content retrieval
            ByteBuffer content = NativeUnifiedBridge.nativeGetBufferContent(bufferHandle);
            
            if (content == null) {
                throw new BridgeException("Buffer content not found: " + bufferHandle,
                        BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
            }
            
            metrics.recordOperation("buffer.getContent", startTime, System.nanoTime(),
                    content.capacity(), true);
            
            return content;
            
        } catch (Exception e) {
            metrics.recordOperation("buffer.getContent", startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Failed to get buffer content: " + bufferHandle,
                    e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
    }

    /**
     * Create a worker for processing tasks.
     * @param workerConfig Configuration for the worker
     * @return Handle to created worker
     * @throws BridgeException If worker creation fails
     */
    public long createWorker(WorkerConfig workerConfig) throws BridgeException {
        Objects.requireNonNull(workerConfig, "Worker config cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Creating worker with config: {0}", workerConfig);
            
            // Call actual native worker creation
            long workerHandle = NativeUnifiedBridge.nativeCreateWorker(workerConfig.getThreadCount());
            
            if (workerHandle <= 0) {
                throw new BridgeException("Failed to create native worker",
                        BridgeException.BridgeErrorType.WORKER_CREATION_FAILED);
            }
            
            metrics.recordOperation("worker.create", startTime, System.nanoTime(), 0, true);
            
            return workerHandle;
            
        } catch (Exception e) {
            metrics.recordOperation("worker.create", startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Worker creation failed",
                    e, BridgeException.BridgeErrorType.WORKER_CREATION_FAILED);
        }
    }

    /**
     * Destroy a worker.
     * @param workerHandle Handle of worker to destroy
     * @throws BridgeException If worker destruction fails
     */
    public void destroyWorker(long workerHandle) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            // Call actual native worker destruction
            NativeUnifiedBridge.nativeDestroyWorker(workerHandle);
            
            metrics.recordOperation("worker.destroy", startTime, System.nanoTime(), 0, true);
            
        } catch (Exception e) {
            metrics.recordOperation("worker.destroy", startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Worker destruction failed: " + workerHandle,
                    e, BridgeException.BridgeErrorType.WORKER_DESTROY_FAILED);
        }
    }

    /**
     * Push a task to a worker for processing.
     * @param workerHandle Handle of worker to push task to
     * @param task Task to process
     * @return Operation ID for the task
     * @throws BridgeException If task push fails
     */
    public long pushTask(long workerHandle, NativeTask task) throws BridgeException {
        Objects.requireNonNull(task, "Task cannot be null");
        
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Pushing task to worker {0}", workerHandle);
            
            // Call actual native task push method
            long operationId = NativeUnifiedBridge.nativePushTask(workerHandle, task);
            
            metrics.recordOperation("task.push", startTime, System.nanoTime(),
                    calculateBytesProcessed(task), operationId > 0);
            
            return operationId;
            
        } catch (Exception e) {
            metrics.recordOperation("task.push", startTime, System.nanoTime(),
                    calculateBytesProcessed(task), false);
            
            throw errorHandler.createBridgeError("Task push failed",
                    e, BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED);
        }
    }

    /**
     * Poll for completed tasks from a worker.
     * @param workerHandle Handle of worker to poll
     * @param maxResults Maximum number of results to return
     * @return List of completed task results
     * @throws BridgeException If task polling fails
     */
    public List<BridgeResult> pollTasks(long workerHandle, int maxResults) throws BridgeException {
        long startTime = System.nanoTime();
        
        try {
            LOGGER.log(FINE, "Polling tasks from worker {0}, max results: {1}",
                    new Object[]{workerHandle, maxResults});
            
            // Call actual native task polling method
            List<BridgeResult> results = NativeUnifiedBridge.nativePollTasks(workerHandle, maxResults);
            
            metrics.recordOperation("task.poll", startTime, System.nanoTime(),
                    results != null ? results.size() : 0, true);
            
            return results != null ? results : List.of();
            
        } catch (Exception e) {
            metrics.recordOperation("task.poll", startTime, System.nanoTime(), 0, false);
            
            throw errorHandler.createBridgeError("Task polling failed",
                    e, BridgeException.BridgeErrorType.RESULT_POLLING_FAILED);
        }
    }

    @Override
    public BridgeConfiguration getConfiguration() {
        return config;
    }

    @Override
    public void setConfiguration(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        LOGGER.info("UnifiedBridgeImpl configuration updated");
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
     * Register a bridge plugin.
     * @param plugin Plugin to register
     * @return true if registration succeeded, false otherwise
     */
    public boolean registerPlugin(BridgePlugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        try {
            plugin.initialize(this);
            plugins.put(plugin.getPluginId(), plugin);
            LOGGER.log(FINE, "Registered plugin: {0}", plugin.getPluginId());
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to register plugin", e);
            return false;
        }
    }

    /**
     * Unregister a bridge plugin.
     * @param plugin Plugin to unregister
     * @return true if unregistration succeeded, false otherwise
     */
    public boolean unregisterPlugin(BridgePlugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null");
        
        try {
            plugin.destroy();
            plugins.remove(plugin.getPluginId());
            LOGGER.log(FINE, "Unregistered plugin: {0}", plugin.getPluginId());
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to unregister plugin", e);
            return false;
        }
    }

    @Override
    public boolean isValid() {
        return workerManager != null && bufferPoolManager != null && resourceManager != null;
    }

    @Override
    public void shutdown() {
        LOGGER.info("UnifiedBridgeImpl shutting down");
        
        try {
            // Shutdown all managers in reverse order of initialization
            jniCallManager.shutdown();
            bufferPoolManager.shutdown();
            resourceManager.shutdown();
            workerManager.shutdown();
            
            // Unregister all plugins
            for (BridgePlugin plugin : new java.util.ArrayList<>(plugins.values())) {
                try {
                    plugin.destroy();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to destroy plugin during shutdown", e);
                }
            }
            plugins.clear();
            
            LOGGER.info("UnifiedBridgeImpl shutdown complete");
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "UnifiedBridgeImpl shutdown failed", e);
            throw errorHandler.createBridgeError("Shutdown failed",
                    e, BridgeException.BridgeErrorType.SHUTDOWN_ERROR);
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
        // For tasks, we can't easily calculate bytes without executing them
        // In real implementation, this would be more sophisticated
        return 0;
    }
}