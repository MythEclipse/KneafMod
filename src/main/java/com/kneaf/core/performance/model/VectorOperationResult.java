package com.kneaf.core.performance.model;

/**
 * Result of vector operation
 */
public class VectorOperationResult {
    public final long operationId;
    public final Object result;
    public final long executionTimeNs;
    public final Exception error;

    public VectorOperationResult(long operationId, Object result, long executionTimeNs) {
        this.operationId = operationId;
        this.result = result;
        this.executionTimeNs = executionTimeNs;
        this.error = null;
    }

    public VectorOperationResult(long operationId, Exception error) {
        this.operationId = operationId;
        this.result = null;
        this.executionTimeNs = 0;
        this.error = error;
    }
}
