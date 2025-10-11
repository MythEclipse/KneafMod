package com.kneaf.core.unifiedbridge;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Core interface defining all bridge operations for unified native integration.
 * This interface consolidates all native bridge functionality into a single contract
 * while maintaining backward compatibility with existing implementations.
 */
public interface UnifiedBridge {
    /**
     * Execute a synchronous native operation.
     * @param operationName Name of the operation to execute
     * @param parameters Operation parameters (varargs of supported types)
     * @return Operation result
     * @throws BridgeException If operation fails
     */
    BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException;

    /**
     * Execute an asynchronous native operation.
     * @param operationName Name of the operation to execute
     * @param parameters Operation parameters (varargs of supported types)
     * @return CompletableFuture containing the operation result
     */
    CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters);

    /**
     * Execute a batch of native operations.
     * @param batchName Name of the batch operation
     * @param operations List of operations to execute in batch
     * @return Batch result containing results of all operations
     * @throws BridgeException If batch operation fails
     */
    BatchResult executeBatch(String batchName, List<BridgeOperation> operations) throws BridgeException;


    /**
     * Get the bridge configuration.
     * @return Current bridge configuration
     */
    BridgeConfiguration getConfiguration();

    /**
     * Set the bridge configuration.
     * @param config New bridge configuration
     */
    void setConfiguration(BridgeConfiguration config);

    /**
     * Get the bridge metrics collector.
     * @return Bridge metrics collector
     */
    BridgeMetrics getMetrics();

    /**
     * Get the error handler for this bridge.
     * @return Bridge error handler
     */
    BridgeErrorHandler getErrorHandler();


    /**
     * Check if the bridge is in a valid state.
     * @return true if bridge is valid, false otherwise
     */
    boolean isValid();

    /**
     * Shutdown the bridge and release all resources.
     */
    void shutdown();

    /**
     * Inner class representing a native operation for batch processing.
     */
    class BridgeOperation {
        private final String operationName;
        private final Object[] parameters;
        private BridgeResult result;

        public BridgeOperation(String operationName, Object... parameters) {
            this.operationName = operationName;
            this.parameters = parameters;
        }

        public String getOperationName() {
            return operationName;
        }

        public Object[] getParameters() {
            return parameters;
        }
        
        public BridgeResult getResult() {
            return result;
        }
        
        public void setResult(BridgeResult result) {
            this.result = result;
        }
    }

    /**
     * Enum representing different types of buffers that can be allocated.
     */
    enum BufferType {
        /**
         * Direct ByteBuffer allocated outside the Java heap.
         */
        DIRECT,
        
        /**
         * Heap ByteBuffer allocated inside the Java heap.
         */
        HEAP,
        
        /**
         * Memory-mapped buffer for file operations.
         */
        MAPPED,
        
        /**
         * Zero-copy buffer for high-performance operations.
         */
        ZERO_COPY,
        
        /**
         * Pooled buffer managed by buffer pool.
         */
        POOLED
    }
}