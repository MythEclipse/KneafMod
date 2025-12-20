package com.kneaf.core.performance.model;

/**
 * Base class for vector operations
 */
public abstract class VectorOperation {
    protected long operationId;
    protected final long timestamp;

    public VectorOperation() {
        this.timestamp = System.nanoTime();
    }

    public void setOperationId(long operationId) {
        this.operationId = operationId;
    }

    public long getOperationId() {
        return operationId;
    }

    public abstract VectorOperationResult execute();

    public abstract int getEstimatedWorkload();
}
