package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * Optimized JNI call management with batching and call optimization.
 * Manages native method calls efficiently with batching, caching, and error handling.
 */
public final class JniCallManager {
    private static final Logger LOGGER = Logger.getLogger(JniCallManager.class.getName());
    private static final JniCallManager INSTANCE = new JniCallManager();
    
    private BridgeConfiguration config;
    private final ConcurrentMap<String, JniMethod> registeredMethods = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CallStats> methodStats = new ConcurrentHashMap<>();
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong totalBatchedCalls = new AtomicLong(0);
    private final AtomicLong totalFailedCalls = new AtomicLong(0);
    private final AtomicLong totalCacheHits = new AtomicLong(0);
    
    private JniCallManager() {
        this.config = BridgeConfiguration.getDefault();
        LOGGER.info("JniCallManager initialized with default configuration");
    }

    /**
     * Get the singleton instance of JniCallManager.
     * @return JniCallManager instance
     */
    public static JniCallManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return JniCallManager instance
     */
    public static JniCallManager getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("JniCallManager reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Register a native method for later calls.
     * @param methodName Name of the method to register
     * @param callFunction Function that performs the actual JNI call
     * @return true if registration succeeded, false if method already exists
     */
    public boolean registerMethod(String methodName, JniCallFunction callFunction) {
        Objects.requireNonNull(methodName, "Method name cannot be null");
        Objects.requireNonNull(callFunction, "Call function cannot be null");
        
        return registeredMethods.putIfAbsent(methodName, new JniMethod(methodName, callFunction)) != null;
    }

    /**
     * Unregister a native method.
     * @param methodName Name of the method to unregister
     * @return true if unregistration succeeded, false if method didn't exist
     */
    public boolean unregisterMethod(String methodName) {
        Objects.requireNonNull(methodName, "Method name cannot be null");
        return registeredMethods.remove(methodName) != null;
    }

    /**
     * Execute a native method call directly.
     * @param methodName Name of the method to call
     * @param parameters Parameters for the method call
     * @return Result of the method call
     * @throws BridgeException If method call fails
     */
    public Object callMethod(String methodName, Object... parameters) throws BridgeException {
        return callMethod(methodName, false, parameters);
    }

    /**
     * Execute a native method call with batching support.
     * @param methodName Name of the method to call
     * @param batch Whether to batch this call
     * @param parameters Parameters for the method call
     * @return Result of the method call
     * @throws BridgeException If method call fails
     */
    public Object callMethod(String methodName, boolean batch, Object... parameters) throws BridgeException {
        Objects.requireNonNull(methodName, "Method name cannot be null");
        
        JniMethod method = registeredMethods.get(methodName);
        if (method == null) {
            throw new BridgeException("Method not found: " + methodName, 
                    BridgeException.BridgeErrorType.NATIVE_CALL_FAILED);
        }

        totalCalls.incrementAndGet();
        long startTime = System.nanoTime();
        
        try {
            Object result;
            
            if (batch && config.isEnableBatching()) {
                result = executeBatchedCall(method, parameters);
                totalBatchedCalls.incrementAndGet();
            } else {
                result = executeDirectCall(method, parameters);
            }
            
            recordCallStats(methodName, true, parameters.length, System.nanoTime() - startTime);
            return result;
            
        } catch (Exception e) {
            recordCallStats(methodName, false, parameters.length, System.nanoTime() - startTime);
            totalFailedCalls.incrementAndGet();
            
            LOGGER.log(WARNING, "JNI call failed for method: " + methodName, e);
            throw new BridgeException("JNI call failed for method: " + methodName, 
                    BridgeException.BridgeErrorType.NATIVE_CALL_FAILED, e);
        }
    }

    /**
     * Execute a batch of native method calls.
     * @param batchName Name of the batch
     * @param calls List of method calls to execute
     * @return Map of method names to results
     * @throws BridgeException If batch execution fails
     */
    public Map<String, Object> callBatch(String batchName, Map<String, Object[]> calls) throws BridgeException {
        Objects.requireNonNull(batchName, "Batch name cannot be null");
        Objects.requireNonNull(calls, "Calls cannot be null");
        
        if (calls.isEmpty()) {
            return Map.of();
        }

        long batchStartTime = System.nanoTime();
        Map<String, Object> results = new ConcurrentHashMap<>();
        int totalOperations = calls.size();
        int successfulOperations = 0;

        try {
            for (Map.Entry<String, Object[]> entry : calls.entrySet()) {
                String methodName = entry.getKey();
                Object[] parameters = entry.getValue();
                
                try {
                    Object result = callMethod(methodName, true, parameters);
                    results.put(methodName, result);
                    successfulOperations++;
                } catch (BridgeException e) {
                    results.put(methodName, e.getMessage());
                    LOGGER.log(WARNING, "Failed to execute batch call for method: " + methodName, e);
                }
            }

            recordBatchStats(batchName, totalOperations, successfulOperations, 
                    System.nanoTime() - batchStartTime);
            
            return results;
            
        } catch (Exception e) {
            LOGGER.log(WARNING, "Batch execution failed: " + batchName, e);
            throw new BridgeException("Batch execution failed: " + batchName, 
                    BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED, e);
        }
    }

    /**
     * Get statistics about JNI method calls.
     * @return Map containing JNI call statistics
     */
    public Map<String, Object> getCallStats() {
        return Map.of(
                "totalCalls", totalCalls.get(),
                "totalBatchedCalls", totalBatchedCalls.get(),
                "totalFailedCalls", totalFailedCalls.get(),
                "totalCacheHits", totalCacheHits.get(),
                "registeredMethods", registeredMethods.size(),
                "batchingEnabled", config.isEnableBatching()
        );
    }

    /**
     * Get statistics for a specific method.
     * @param methodName Name of the method
     * @return Map containing method-specific statistics or empty map if not found
     */
    public Map<String, Object> getMethodStats(String methodName) {
        CallStats stats = methodStats.get(methodName);
        return stats != null ? stats.getStats() : Map.of();
    }

    /**
     * Push a task to a worker for processing.
     * @param workerHandle Worker handle
     * @param payload Task payload
     * @param timeout Timeout value
     * @param unit Time unit
     * @throws BridgeException If task push fails
     */
    public void pushTask(long workerHandle, byte[] payload, long timeout, TimeUnit unit) throws BridgeException {
        Objects.requireNonNull(payload, "Payload cannot be null");
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        long startTime = System.nanoTime();
        try {
            // Simulate JNI call to native layer
            LOGGER.log(FINE, "Pushing task to worker {0} with payload size {1}",
                    new Object[]{workerHandle, payload.length});
            
            // In real implementation, this would call native code
            // For simulation, we'll just log and simulate processing time
            Thread.sleep(10); // Simulate processing time
            
            recordCallStats("pushTask", true, payload.length, System.nanoTime() - startTime);
            
        } catch (Exception e) {
            recordCallStats("pushTask", false, payload.length, System.nanoTime() - startTime);
            throw new BridgeException("Failed to push task to worker",
                    BridgeException.BridgeErrorType.TASK_PROCESSING_FAILED, e);
        }
    }

    /**
     * Poll for task results from a worker.
     * @param workerHandle Worker handle
     * @param timeout Timeout value
     * @param unit Time unit
     * @return Task result payload
     * @throws BridgeException If result polling fails
     */
    public byte[] pollResult(long workerHandle, long timeout, TimeUnit unit) throws BridgeException {
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        long startTime = System.nanoTime();
        try {
            // Simulate JNI call to native layer
            LOGGER.log(FINE, "Polling result from worker {0}", workerHandle);
            
            // In real implementation, this would call native code
            // For simulation, return empty result
            Thread.sleep(5); // Simulate processing time
            
            recordCallStats("pollResult", true, 0, System.nanoTime() - startTime);
            return new byte[0]; // Empty result for simulation
            
        } catch (Exception e) {
            recordCallStats("pollResult", false, 0, System.nanoTime() - startTime);
            throw new BridgeException("Failed to poll result from worker",
                    BridgeException.BridgeErrorType.RESULT_POLLING_FAILED, e);
        }
    }

    /**
     * Submit a zero-copy operation to a worker.
     * @param workerHandle Worker handle
     * @param buffer Direct ByteBuffer for zero-copy operation
     * @param operationType Type of operation to perform
     * @param timeout Timeout value
     * @param unit Time unit
     * @return Operation handle
     * @throws BridgeException If operation submission fails
     */
    public long submitZeroCopyOperation(long workerHandle, ByteBuffer buffer, int operationType,
                                      long timeout, TimeUnit unit) throws BridgeException {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        if (!buffer.isDirect()) {
            throw new BridgeException("Zero-copy operations require direct ByteBuffer",
                    BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
        }
        
        long startTime = System.nanoTime();
        try {
            LOGGER.log(FINE, "Submitting zero-copy operation to worker {0}", workerHandle);
            
            // Simulate operation submission
            long operationId = System.nanoTime(); // Generate unique operation ID
            Thread.sleep(5); // Simulate processing time
            
            recordCallStats("submitZeroCopyOperation", true, 1, System.nanoTime() - startTime);
            return operationId;
            
        } catch (Exception e) {
            recordCallStats("submitZeroCopyOperation", false, 1, System.nanoTime() - startTime);
            throw new BridgeException("Failed to submit zero-copy operation",
                    BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED, e);
        }
    }

    /**
     * Poll for result of a zero-copy operation.
     * @param operationId Operation handle
     * @param timeout Timeout value
     * @param unit Time unit
     * @return Result as ByteBuffer
     * @throws BridgeException If result polling fails
     */
    public ByteBuffer pollZeroCopyResult(long operationId, long timeout, TimeUnit unit) throws BridgeException {
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        long startTime = System.nanoTime();
        try {
            LOGGER.log(FINE, "Polling zero-copy result for operation {0}", operationId);
            
            // Simulate result polling
            Thread.sleep(5); // Simulate processing time
            
            recordCallStats("pollZeroCopyResult", true, 0, System.nanoTime() - startTime);
            return ByteBuffer.allocate(0); // Empty result for simulation
            
        } catch (Exception e) {
            recordCallStats("pollZeroCopyResult", false, 0, System.nanoTime() - startTime);
            throw new BridgeException("Failed to poll zero-copy result",
                    BridgeException.BridgeErrorType.RESULT_POLLING_FAILED, e);
        }
    }

    /**
     * Cleanup resources for a zero-copy operation.
     * @param operationId Operation handle
     */
    public void cleanupZeroCopyOperation(long operationId) {
        try {
            LOGGER.log(FINE, "Cleaning up zero-copy operation {0}", operationId);
            // Simulate cleanup operation
        } catch (Exception e) {
            LOGGER.log(WARNING, "Failed to cleanup zero-copy operation " + operationId, e);
        }
    }

    /**
     * Check if native functionality is available.
     * @return true if native functionality is available, false otherwise
     */
    public boolean isNativeAvailable() {
        // In real implementation, this would check if native library is loaded
        // For simulation, assume native is available
        return true;
    }

    /**
     * Push a batch of tasks to a worker.
     * @param workerHandle Worker handle
     * @param payloads Array of task payloads
     * @param optimalSize Optimal batch size
     * @param timeout Timeout value
     * @param unit Time unit
     * @throws BridgeException If batch push fails
     */
    public void pushBatch(long workerHandle, byte[][] payloads, int optimalSize,
                         long timeout, TimeUnit unit) throws BridgeException {
        Objects.requireNonNull(payloads, "Payloads cannot be null");
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        long startTime = System.nanoTime();
        try {
            LOGGER.log(FINE, "Pushing batch of {0} tasks to worker {1} with optimal size {2}",
                    new Object[]{payloads.length, workerHandle, optimalSize});
            
            // Simulate batch processing
            for (byte[] payload : payloads) {
                pushTask(workerHandle, payload, timeout, unit);
            }
            
            recordCallStats("pushBatch", true, payloads.length, System.nanoTime() - startTime);
            
        } catch (Exception e) {
            recordCallStats("pushBatch", false, payloads.length, System.nanoTime() - startTime);
            throw new BridgeException("Failed to push batch to worker",
                    BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED, e);
        }
    }

    /**
     * Flush pending operations.
     */
    public void flush() {
        try {
            LOGGER.log(FINE, "Flushing pending JNI operations");
            // Simulate flush operation - no-op in simulation
        } catch (Exception e) {
            LOGGER.log(WARNING, "Failed to flush operations", e);
        }
    }

    /**
     * Get optimal batch size for performance.
     * @param requestedSize Requested batch size
     * @return Optimal batch size
     */
    public int getOptimalBatchSize(int requestedSize) {
        // Calculate optimal batch size based on configuration
        int optimalSize = Math.max(config.getMinBatchSize(),
                                  Math.min(config.getMaxBatchSize(), requestedSize));
        
        LOGGER.log(FINE, "Optimal batch size calculated: {0} (requested: {1})",
                new Object[]{optimalSize, requestedSize});
        
        return optimalSize;
    }

    /**
     * Shutdown the JNI call manager and clean up resources.
     */
    public void shutdown() {
        LOGGER.info("JniCallManager shutting down - unregistering all methods");
        
        registeredMethods.clear();
        methodStats.clear();
        
        LOGGER.info("JniCallManager shutdown complete");
    }

    /**
     * Execute a direct JNI call without batching.
     * @param method JNI method to execute
     * @param parameters Method parameters
     * @return Method result
     * @throws Exception If call fails
     */
    private Object executeDirectCall(JniMethod method, Object[] parameters) throws Exception {
        // In real implementation, this would call native code directly
        // For simulation, we'll just call the registered function
        return method.callFunction.apply(parameters);
    }

    /**
     * Execute a batched JNI call.
     * @param method JNI method to execute
     * @param parameters Method parameters
     * @return Method result
     * @throws Exception If call fails
     */
    private Object executeBatchedCall(JniMethod method, Object[] parameters) throws Exception {
        // In real implementation, this would queue the call for batch processing
        // For simulation, we'll just call the registered function but log it as batched
        LOGGER.log(FINE, "Executing batched JNI call: {0} with {1} parameters", 
                new Object[]{method.getMethodName(), parameters.length});
        
        return method.callFunction.apply(parameters);
    }

    /**
     * Record statistics for a method call.
     * @param methodName Name of the method
     * @param success Whether the call succeeded
     * @param paramCount Number of parameters
     * @param durationNanos Call duration in nanoseconds
     */
    private void recordCallStats(String methodName, boolean success, int paramCount, long durationNanos) {
        methodStats.computeIfAbsent(methodName, k -> new CallStats(methodName))
                .recordCall(success, paramCount, durationNanos);
    }

    /**
     * Record statistics for a batch call.
     * @param batchName Name of the batch
     * @param totalOperations Total operations in batch
     * @param successfulOperations Successful operations
     * @param durationNanos Batch duration in nanoseconds
     */
    private void recordBatchStats(String batchName, int totalOperations, 
                                 int successfulOperations, long durationNanos) {
        methodStats.computeIfAbsent(batchName + ".batch", k -> new CallStats(batchName + ".batch"))
                .recordBatch(totalOperations, successfulOperations, durationNanos);
    }

    /**
     * Functional interface for JNI call implementations.
     */
    @FunctionalInterface
    public interface JniCallFunction {
        /**
         * Execute the JNI call.
         * @param parameters Method parameters
         * @return Call result
         * @throws Exception If call fails
         */
        Object apply(Object[] parameters) throws Exception;
    }

    /**
     * Internal representation of a registered JNI method.
     */
    static final class JniMethod {
        private final String methodName;
        private final JniCallFunction callFunction;

        JniMethod(String methodName, JniCallFunction callFunction) {
            this.methodName = methodName;
            this.callFunction = callFunction;
        }

        // Getters
        public String getMethodName() { return methodName; }
        public JniCallFunction getCallFunction() { return callFunction; }
    }

    /**
     * Internal class for tracking call statistics.
     */
    static final class CallStats {
        private final String methodName;
        private final AtomicLong callCount = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failCount = new AtomicLong(0);
        private final AtomicLong totalParams = new AtomicLong(0);
        private final AtomicLong totalDurationNanos = new AtomicLong(0);
        private final AtomicLong lastCallTime = new AtomicLong(System.currentTimeMillis());

        CallStats(String methodName) {
            this.methodName = methodName;
        }

        /**
         * Record a single method call.
         * @param success Whether the call succeeded
         * @param paramCount Number of parameters
         * @param durationNanos Call duration in nanoseconds
         */
        public void recordCall(boolean success, int paramCount, long durationNanos) {
            callCount.incrementAndGet();
            if (success) {
                successCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
            }
            totalParams.addAndGet(paramCount);
            totalDurationNanos.addAndGet(durationNanos);
            lastCallTime.set(System.currentTimeMillis());
        }

        /**
         * Record a batch method call.
         * @param totalOperations Total operations
         * @param successfulOperations Successful operations
         * @param durationNanos Batch duration in nanoseconds
         */
        public void recordBatch(int totalOperations, int successfulOperations, long durationNanos) {
            callCount.incrementAndGet();
            successCount.addAndGet(successfulOperations);
            failCount.addAndGet(totalOperations - successfulOperations);
            totalParams.addAndGet(totalOperations); // Simplified - each operation counted as 1 param
            totalDurationNanos.addAndGet(durationNanos);
            lastCallTime.set(System.currentTimeMillis());
        }

        /**
         * Get statistics for this method.
         * @return Map containing method statistics
         */
        public Map<String, Object> getStats() {
            long calls = callCount.get();
            double successRate = calls > 0 ? (double) successCount.get() / calls : 0;
            double averageDurationNanos = calls > 0 ? (double) totalDurationNanos.get() / calls : 0;
            double averageParams = calls > 0 ? (double) totalParams.get() / calls : 0;

            Map<String, Object> stats = new HashMap<>();
            stats.put("methodName", methodName);
            stats.put("callCount", calls);
            stats.put("successRate", successRate);
            stats.put("successCount", successCount.get());
            stats.put("failCount", failCount.get());
            stats.put("totalParams", totalParams.get());
            stats.put("averageParamsPerCall", averageParams);
            stats.put("totalDurationNanos", totalDurationNanos.get());
            stats.put("averageDurationNanos", averageDurationNanos);
            stats.put("averageDurationMs", durationNanosToMillis(averageDurationNanos));
            stats.put("lastCallTime", lastCallTime.get());
            return Collections.unmodifiableMap(stats);
        }

        /**
         * Convert nanoseconds to milliseconds.
         * @param nanos Nanoseconds
         * @return Milliseconds
         */
        private double durationNanosToMillis(double nanos) {
            return nanos / 1_000_000.0;
        }
    }
}