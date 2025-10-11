package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.HashMap;

/**
 * Factory class for creating BridgeResult instances to reduce boilerplate code.
 * Provides common factory methods for creating success, failure, and pending results.
 */
public final class BridgeResultFactory {

    private BridgeResultFactory() {
        // Prevent instantiation
    }

    /**
     * Create a successful BridgeResult for an operation.
     *
     * @param operationName the name of the operation
     * @param resultData the result data (can be null)
     * @param metadata additional metadata (can be null)
     * @return a successful BridgeResult
     */
    public static BridgeResult createSuccess(String operationName, byte[] resultData, Map<String, Object> metadata) {
        long taskId = System.nanoTime();
        long startTime = System.nanoTime();
        long endTime = System.nanoTime();
        
        Map<String, Object> finalMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        if (operationName != null) {
            finalMetadata = addOperationName(finalMetadata, operationName);
        }
        
        return new BridgeResult.Builder()
                .taskId(taskId)
                .startTimeNanos(startTime)
                .endTimeNanos(endTime)
                .resultData(resultData)
                .metadata(finalMetadata)
                .status("SUCCESS")
                .build();
    }

    /**
     * Create a successful BridgeResult with object result.
     *
     * @param operationName the name of the operation
     * @param resultObject the result object (will be converted to string bytes)
     * @param metadata additional metadata (can be null)
     * @return a successful BridgeResult
     */
    public static BridgeResult createSuccess(String operationName, Object resultObject, Map<String, Object> metadata) {
        byte[] resultData = resultObject != null ? resultObject.toString().getBytes() : null;
        return createSuccess(operationName, resultData, metadata);
    }

    /**
     * Create a successful BridgeResult with minimal parameters.
     *
     * @param operationName the name of the operation
     * @return a successful BridgeResult
     */
    public static BridgeResult createSuccess(String operationName) {
        return createSuccess(operationName, (byte[]) null, Map.of("message", "Operation completed successfully"));
    }

    /**
     * Create a failed BridgeResult for an operation.
     *
     * @param operationName the name of the operation
     * @param errorMessage the error message
     * @param metadata additional metadata (can be null)
     * @return a failed BridgeResult
     */
    public static BridgeResult createFailure(String operationName, String errorMessage, Map<String, Object> metadata) {
        long taskId = System.nanoTime();
        long startTime = System.nanoTime();
        long endTime = System.nanoTime();
        
        Map<String, Object> finalMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        if (operationName != null) {
            finalMetadata = addOperationName(finalMetadata, operationName);
        }
        
        return new BridgeResult.Builder()
                .taskId(taskId)
                .startTimeNanos(startTime)
                .endTimeNanos(endTime)
                .metadata(finalMetadata)
                .status("FAILURE")
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a failed BridgeResult with minimal parameters.
     *
     * @param operationName the name of the operation
     * @param errorMessage the error message
     * @return a failed BridgeResult
     */
    public static BridgeResult createFailure(String operationName, String errorMessage) {
        return createFailure(operationName, errorMessage, Map.of("message", errorMessage));
    }

    /**
     * Create a failed BridgeResult from an exception.
     *
     * @param operationName the name of the operation
     * @param exception the exception that caused the failure
     * @param metadata additional metadata (can be null)
     * @return a failed BridgeResult
     */
    public static BridgeResult createFailure(String operationName, Exception exception, Map<String, Object> metadata) {
        String errorMessage = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        Map<String, Object> finalMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        finalMetadata = addExceptionInfo(finalMetadata, exception);
        
        return createFailure(operationName, errorMessage, finalMetadata);
    }

    /**
     * Create a failed BridgeResult from an exception with minimal parameters.
     *
     * @param operationName the name of the operation
     * @param exception the exception that caused the failure
     * @return a failed BridgeResult
     */
    public static BridgeResult createFailure(String operationName, Exception exception) {
        return createFailure(operationName, exception, Map.of());
    }

    /**
     * Create a pending BridgeResult for an operation.
     *
     * @param operationName the name of the operation
     * @param metadata additional metadata (can be null)
     * @return a pending BridgeResult
     */
    public static BridgeResult createPending(String operationName, Map<String, Object> metadata) {
        long taskId = System.nanoTime();
        long startTime = System.nanoTime();
        
        Map<String, Object> finalMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        if (operationName != null) {
            finalMetadata = addOperationName(finalMetadata, operationName);
        }
        
        return new BridgeResult.Builder()
                .taskId(taskId)
                .startTimeNanos(startTime)
                .endTimeNanos(startTime) // Same as start time for pending operations
                .metadata(finalMetadata)
                .status("PENDING")
                .build();
    }

    /**
     * Create a pending BridgeResult with minimal parameters.
     *
     * @param operationName the name of the operation
     * @return a pending BridgeResult
     */
    public static BridgeResult createPending(String operationName) {
        return createPending(operationName, Map.of("message", "Operation is pending execution"));
    }

    /**
     * Convert a BridgeResult to a BatchResult for batch operations.
     *
     * @param bridgeResult the BridgeResult to convert
     * @param batchId the batch ID
     * @return a BatchResult representing the BridgeResult
     */
    public static BatchResult toBatchResult(BridgeResult bridgeResult, long batchId) {
        int successfulTasks = bridgeResult.isSuccess() ? 1 : 0;
        int failedTasks = bridgeResult.isFailure() ? 1 : 0;
        int totalTasks = 1;
        
        Map<String, Object> detailedStats = Map.of(
            "operationName", bridgeResult.getMetadata().getOrDefault("operationName", "unknown"),
            "durationMillis", bridgeResult.getDurationMillis(),
            "resultSize", bridgeResult.getResultData() != null ? bridgeResult.getResultData().length : 0
        );
        
        String status = bridgeResult.isSuccess() ? "COMPLETED" : 
                       bridgeResult.isFailure() ? "FAILED" : "PENDING";
        
        return new BatchResult.Builder()
                .batchId(batchId)
                .startTimeNanos(bridgeResult.getStartTimeNanos())
                .endTimeNanos(bridgeResult.getEndTimeNanos())
                .totalTasks(totalTasks)
                .successfulTasks(successfulTasks)
                .failedTasks(failedTasks)
                .totalBytesProcessed(bridgeResult.getResultData() != null ? bridgeResult.getResultData().length : 0)
                .detailedStats(detailedStats)
                .status(status)
                .errorMessage(bridgeResult.getErrorMessage())
                .build();
    }

    private static Map<String, Object> addOperationName(Map<String, Object> metadata, String operationName) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put("operationName", operationName);
        return Map.copyOf(newMetadata);
    }

    private static Map<String, Object> addExceptionInfo(Map<String, Object> metadata, Exception exception) {
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put("exceptionType", exception.getClass().getName());
        newMetadata.put("exceptionMessage", exception.getMessage());
        if (exception.getCause() != null) {
            newMetadata.put("cause", exception.getCause().getMessage());
        }
        return Map.copyOf(newMetadata);
    }
}