package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
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

            Map<String, Object> stats = new java.util.HashMap<>();
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
            return java.util.Collections.unmodifiableMap(stats);
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