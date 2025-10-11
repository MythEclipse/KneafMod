package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utilities for gradual transition from old bridge systems to the unified bridge.
 * Provides backward compatibility and migration paths for existing code.
 */
public final class MigrationHelper {
    private static final Logger LOGGER = Logger.getLogger(MigrationHelper.class.getName());
    private static final MigrationHelper INSTANCE = new MigrationHelper();
    
    private final ConcurrentMap<String, LegacyBridgeAdapter> registeredAdapters = new ConcurrentHashMap<>();
    private BridgeConfiguration config;

    private MigrationHelper() {
        this.config = BridgeConfiguration.getDefault();
        LOGGER.info("MigrationHelper initialized with default configuration");
    }

    /**
     * Get the singleton instance of MigrationHelper.
     * @return MigrationHelper instance
     */
    public static MigrationHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return MigrationHelper instance
     */
    public static MigrationHelper getInstance(BridgeConfiguration config) {
        // Field config bukan final lagi, jadi kita bisa mengubahnya
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("MigrationHelper reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Register a legacy bridge adapter for backward compatibility.
     * @param adapterId Unique ID for the adapter
     * @param adapter Legacy bridge adapter
     * @return true if registration succeeded, false if adapter ID already exists
     */
    public boolean registerLegacyAdapter(String adapterId, LegacyBridgeAdapter adapter) {
        Objects.requireNonNull(adapterId, "Adapter ID cannot be null");
        Objects.requireNonNull(adapter, "Adapter cannot be null");
        
        return registeredAdapters.putIfAbsent(adapterId, adapter) != null;
    }

    /**
     * Unregister a legacy bridge adapter.
     * @param adapterId Unique ID for the adapter
     * @return true if unregistration succeeded, false if adapter ID didn't exist
     */
    public boolean unregisterLegacyAdapter(String adapterId) {
        Objects.requireNonNull(adapterId, "Adapter ID cannot be null");
        return registeredAdapters.remove(adapterId) != null;
    }

    /**
     * Get a legacy bridge adapter by ID.
     * @param adapterId Unique ID for the adapter
     * @return Legacy bridge adapter or null if not found
     */
    public LegacyBridgeAdapter getLegacyAdapter(String adapterId) {
        Objects.requireNonNull(adapterId, "Adapter ID cannot be null");
        return registeredAdapters.get(adapterId);
    }

    /**
     * Get statistics about migration helpers.
     * @return Map containing migration statistics
     */
    public Map<String, Object> getMigrationStats() {
        return Map.of(
                "registeredAdapters", registeredAdapters.size(),
                "migrationEnabled", config.isEnableMigrationSupport(),
                "backwardCompatibility", config.isEnableBackwardCompatibility()
        );
    }

    /**
     * Create a unified bridge that wraps a legacy bridge for gradual migration.
     * @param legacyBridge Legacy bridge instance
     * @param adapter Legacy bridge adapter
     * @return Unified bridge that delegates to the legacy bridge
     */
    public UnifiedBridge createMigratingBridge(Object legacyBridge, LegacyBridgeAdapter adapter) {
        Objects.requireNonNull(legacyBridge, "Legacy bridge cannot be null");
        Objects.requireNonNull(adapter, "Adapter cannot be null");
        
        return new MigratingBridge(legacyBridge, adapter, config);
    }

    /**
     * Convert legacy operation parameters to unified bridge parameters.
     * @param legacyParams Legacy operation parameters
     * @param operationName Operation name
     * @return Unified bridge parameters
     * @throws BridgeException If parameter conversion fails
     */
    public Object[] convertLegacyParameters(Object[] legacyParams, String operationName) throws BridgeException {
        Objects.requireNonNull(legacyParams, "Legacy parameters cannot be null");
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        try {
            // In real implementation, this would use registered adapters to convert parameters
            LOGGER.log(Level.FINE, "Converting legacy parameters for operation: {0}", operationName);
            
            // For simulation, we'll return the parameters as-is
            return legacyParams.clone();
            
        } catch (Exception e) {
            throw new BridgeException("Failed to convert legacy parameters for operation: " + operationName,
                    BridgeException.BridgeErrorType.COMPATIBILITY_ERROR, e);
        }
    }

    /**
     * Convert unified bridge result to legacy bridge result.
     * @param unifiedResult Unified bridge result
     * @param operationName Operation name
     * @return Legacy bridge result
     * @throws BridgeException If result conversion fails
     */
    public Object convertToLegacyResult(BridgeResult unifiedResult, String operationName) throws BridgeException {
        Objects.requireNonNull(unifiedResult, "Unified result cannot be null");
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        try {
            // In real implementation, this would use registered adapters to convert results
            LOGGER.log(Level.FINE, "Converting result to legacy format for operation: {0}", operationName);
            
            // For simulation, we'll return a simple representation
            return unifiedResult.isSuccess() ? 
                    unifiedResult.getMessage() : 
                    new LegacyResult(false, unifiedResult.getMessage());
            
        } catch (Exception e) {
            throw new BridgeException("Failed to convert result to legacy format for operation: " + operationName,
                    BridgeException.BridgeErrorType.COMPATIBILITY_ERROR, e);
        }
    }

    /**
     * Interface for adapting legacy bridge systems to the unified bridge.
     */
    public interface LegacyBridgeAdapter {
        /**
         * Get the legacy bridge type supported by this adapter.
         * @return Legacy bridge type
         */
        Class<?> getLegacyBridgeType();

        /**
         * Adapt a legacy operation call to a unified bridge operation.
         * @param legacyBridge Legacy bridge instance
         * @param operationName Operation name
         * @param legacyParams Legacy operation parameters
         * @return Unified bridge result
         * @throws Exception If adaptation fails
         */
        BridgeResult adaptOperation(Object legacyBridge, String operationName, Object[] legacyParams) throws Exception;

        /**
         * Adapt a unified bridge result to a legacy bridge result.
         * @param unifiedResult Unified bridge result
         * @param operationName Operation name
         * @return Legacy bridge result
         * @throws Exception If adaptation fails
         */
        Object adaptResult(BridgeResult unifiedResult, String operationName) throws Exception;

        /**
         * Adapt a legacy worker creation to unified worker creation.
         * @param legacyBridge Legacy bridge instance
         * @param concurrency Concurrency level
         * @return Unified worker handle
         * @throws Exception If adaptation fails
         */
        long adaptWorkerCreation(Object legacyBridge, int concurrency) throws Exception;

        /**
         * Adapt a legacy buffer allocation to unified buffer allocation.
         * @param legacyBridge Legacy bridge instance
         * @param size Buffer size
         * @param bufferType Buffer type
         * @return Unified buffer handle
         * @throws Exception If adaptation fails
         */
        long adaptBufferAllocation(Object legacyBridge, long size, BufferType bufferType) throws Exception;
    }

    /**
     * Example legacy result class for simulation purposes.
     */
    public static class LegacyResult {
        private final boolean success;
        private final Object result;

        public LegacyResult(boolean success, Object result) {
            this.success = success;
            this.result = result;
        }

        public boolean isSuccess() {
            return success;
        }

        public Object getResult() {
            return result;
        }
    }

    /**
     * Internal class representing a migrating bridge that wraps a legacy bridge.
     */
    private static class MigratingBridge implements UnifiedBridge {
        private final Object legacyBridge;
        private final LegacyBridgeAdapter adapter;
        private final BridgeConfiguration config;
        private final BridgeErrorHandler errorHandler;
        private final BridgeMetrics metrics;

        MigratingBridge(Object legacyBridge, LegacyBridgeAdapter adapter, BridgeConfiguration config) {
            this.legacyBridge = legacyBridge;
            this.adapter = adapter;
            this.config = config;
            this.errorHandler = BridgeErrorHandler.getDefault();
            this.metrics = BridgeMetrics.getInstance(config);
            
            LOGGER.info("Created migrating bridge with adapter: " + adapter.getClass().getName());
        }

        @Override
        public CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return executeSync(operationName, parameters);
                } catch (BridgeException e) {
                    return errorHandler.handleBridgeError("Async migration failed: " + operationName, 
                            e, BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
                }
            });
        }

        @Override
        public BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                LOGGER.log(Level.FINE, "Executing legacy operation: {0} via migrating bridge", operationName);
                
                // Delegate to legacy bridge via adapter
                BridgeResult result = adapter.adaptOperation(legacyBridge, operationName, parameters);
                
                metrics.recordOperation(operationName + ".migrated", startTime, System.nanoTime(), 
                        calculateBytesProcessed(parameters), result.isSuccess());
                
                return result;
                
            } catch (Exception e) {
                metrics.recordOperation(operationName + ".migrated", startTime, System.nanoTime(), 
                        calculateBytesProcessed(parameters), false);
                
                throw errorHandler.createBridgeError("Legacy operation failed: " + operationName,
                        e, BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
            }
        }

        @Override
        public BatchResult executeBatch(String batchName, java.util.List<BridgeOperation> operations) throws BridgeException {
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
                        BridgeResult opResult = executeSync(operation.getOperationName(), 
                                operation.getParameters());
                        resultBuilder.addOperationResult(opResult);
                        successfulOperations++;
                        totalBytesProcessed += calculateBytesProcessed(operation.getParameters());
                    } catch (BridgeException e) {
                        resultBuilder.addOperationResult(new BridgeResult.Builder()
                                .operationName(operation.getOperationName())
                                .success(false)
                                .message(e.getMessage())
                                .build());
                    }
                }
                
                resultBuilder.successfulOperations(successfulOperations)
                        .totalBytesProcessed(totalBytesProcessed)
                        .endTime(System.currentTimeMillis())
                        .success(true);
                
                metrics.recordBatchOperation(System.currentTimeMillis(), batchName + ".migrated", 
                        batchStartTime, System.nanoTime(), operations.size(), successfulOperations, 
                        operations.size() - successfulOperations, totalBytesProcessed, true);
                
                return resultBuilder.build();
                
            } catch (Exception e) {
                metrics.recordBatchOperation(System.currentTimeMillis(), batchName + ".migrated", 
                        batchStartTime, System.nanoTime(), operations.size(), successfulOperations, 
                        operations.size() - successfulOperations, totalBytesProcessed, false);
                
                throw errorHandler.createBridgeError("Legacy batch operation failed: " + batchName,
                        e, BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public long allocateZeroCopyBuffer(long size, BufferType bufferType) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                long bufferHandle = adapter.adaptBufferAllocation(legacyBridge, size, com.kneaf.core.unifiedbridge.BufferType.valueOf(bufferType.name()));
                
                metrics.recordOperation("buffer.allocate.zeroCopy.migrated", startTime, System.nanoTime(), 
                        size, true);
                
                return bufferHandle;
                
            } catch (Exception e) {
                metrics.recordOperation("buffer.allocate.zeroCopy.migrated", startTime, System.nanoTime(), 
                        size, false);
                
                throw errorHandler.createBridgeError("Legacy zero-copy buffer allocation failed",
                        e, BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public void freeBuffer(long bufferHandle) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                // In real implementation, this would delegate to legacy bridge
                LOGGER.log(Level.FINE, "Freeing legacy buffer: {0}", bufferHandle);
                
                metrics.recordOperation("buffer.free.migrated", startTime, System.nanoTime(), 0, true);
                
            } catch (Exception e) {
                metrics.recordOperation("buffer.free.migrated", startTime, System.nanoTime(), 0, false);
                
                throw errorHandler.createBridgeError("Legacy buffer free failed: " + bufferHandle,
                        e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public java.nio.ByteBuffer getBufferContent(long bufferHandle) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                // In real implementation, this would delegate to legacy bridge
                LOGGER.log(Level.FINE, "Getting legacy buffer content: {0}", bufferHandle);
                
                // For simulation, return an empty buffer
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(0);
                
                metrics.recordOperation("buffer.getContent.migrated", startTime, System.nanoTime(), 
                        buffer.capacity(), true);
                
                return buffer;
                
            } catch (Exception e) {
                metrics.recordOperation("buffer.getContent.migrated", startTime, System.nanoTime(), 0, false);
                
                throw errorHandler.createBridgeError("Legacy buffer content access failed: " + bufferHandle,
                        e, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public long createWorker(WorkerConfig workerConfig) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                long workerHandle = adapter.adaptWorkerCreation(legacyBridge, workerConfig.getThreadCount());
                
                metrics.recordOperation("worker.create.migrated", startTime, System.nanoTime(), 0, true);
                
                return workerHandle;
                
            } catch (Exception e) {
                metrics.recordOperation("worker.create.migrated", startTime, System.nanoTime(), 0, false);
                
                throw errorHandler.createBridgeError("Legacy worker creation failed",
                        e, BridgeException.BridgeErrorType.WORKER_CREATION_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public void destroyWorker(long workerHandle) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                // In real implementation, this would delegate to legacy bridge
                LOGGER.log(Level.FINE, "Destroying legacy worker: {0}", workerHandle);
                
                metrics.recordOperation("worker.destroy.migrated", startTime, System.nanoTime(), 0, true);
                
            } catch (Exception e) {
                metrics.recordOperation("worker.destroy.migrated", startTime, System.nanoTime(), 0, false);
                
                throw errorHandler.createBridgeError("Legacy worker destruction failed: " + workerHandle,
                        e, BridgeException.BridgeErrorType.WORKER_DESTROY_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public long pushTask(long workerHandle, NativeTask task) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                // In real implementation, this would delegate to legacy bridge
                LOGGER.log(Level.FINE, "Pushing task to legacy worker: {0}", workerHandle);
                
                // For simulation, execute the task synchronously
                BridgeResult result = task.execute(this);
                
                metrics.recordOperation("task.push.migrated", startTime, System.nanoTime(),
                        calculateBytesProcessed(task), result.isSuccess());
                
                return result.getTaskId();
                
            } catch (Exception e) {
                metrics.recordOperation("task.push.migrated", startTime, System.nanoTime(), 
                        calculateBytesProcessed(task), false);
                
                throw errorHandler.createBridgeError("Legacy task push failed",
                        e, BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED);
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public java.util.List<BridgeResult> pollTasks(long workerHandle, int maxResults) throws BridgeException {
            long startTime = System.nanoTime();
            
            try {
                // In real implementation, this would delegate to legacy bridge
                LOGGER.log(Level.FINE, "Polling tasks from legacy worker: {0}", workerHandle);
                
                // For simulation, return an empty list
                java.util.List<BridgeResult> results = java.util.List.of();
                
                metrics.recordOperation("task.poll.migrated", startTime, System.nanoTime(), 0, true);
                
                return results;
                
            } catch (Exception e) {
                metrics.recordOperation("task.poll.migrated", startTime, System.nanoTime(), 0, false);
                
                throw errorHandler.createBridgeError("Legacy task polling failed",
                        e, BridgeException.BridgeErrorType.RESULT_POLLING_FAILED);
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
            LOGGER.info("MigratingBridge configuration update requested but config is immutable");
        }

        @Override
        public BridgeMetrics getMetrics() {
            return metrics;
        }

        @Override
        public BridgeErrorHandler getErrorHandler() {
            return errorHandler;
        }

        // Method ini tidak ada di interface UnifiedBridge
        public boolean registerPlugin(BridgePlugin plugin) {
            try {
                plugin.initialize(this);
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to register plugin in migrating bridge", e);
                return false;
            }
        }

        // Method ini tidak ada di interface UnifiedBridge
        public boolean unregisterPlugin(BridgePlugin plugin) {
            try {
                plugin.destroy();
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to unregister plugin from migrating bridge", e);
                return false;
            }
        }

        @Override
        public boolean isValid() {
            return legacyBridge != null && adapter != null;
        }

        @Override
        public void shutdown() {
            LOGGER.info("Migrating bridge shutting down");
            
            // In real implementation, this would clean up legacy bridge resources
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
                } else if (param instanceof java.nio.ByteBuffer) {
                    totalBytes += ((java.nio.ByteBuffer) param).capacity();
                } else if (param instanceof String) {
                    totalBytes += ((String) param).getBytes().length;
                } else if (param instanceof Number) {
                    totalBytes += 8; // Approximate size for numbers
                }
            }
            
            return totalBytes;
        }

        /**
         * Calculate approximate bytes processed from a task.
         * @param task Native task
         * @return Approximate bytes processed
         */
        private long calculateBytesProcessed(NativeTask task) {
            return 0; // Simplified for migration bridge
        }
    }
}