package com.kneaf.core.performance.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe operation queue for managing parallel tasks
 */
public class OperationQueue {
    private final ConcurrentLinkedQueue<VectorOperation> pendingOperations;
    private final ConcurrentHashMap<Long, CompletableFuture<VectorOperationResult>> activeFutures;
    private final AtomicLong operationIdGenerator;

    public OperationQueue() {
        this.pendingOperations = new ConcurrentLinkedQueue<>();
        this.activeFutures = new ConcurrentHashMap<>();
        this.operationIdGenerator = new AtomicLong(0);
    }

    public long submitOperation(VectorOperation operation) {
        long operationId = operationIdGenerator.incrementAndGet();
        operation.setOperationId(operationId);
        pendingOperations.offer(operation);
        return operationId;
    }

    public VectorOperation pollOperation() {
        return pendingOperations.poll();
    }

    public void completeOperation(long operationId, VectorOperationResult result) {
        CompletableFuture<VectorOperationResult> future = activeFutures.remove(operationId);
        if (future != null) {
            future.complete(result);
        }
    }

    public CompletableFuture<VectorOperationResult> getFuture(long operationId) {
        CompletableFuture<VectorOperationResult> future = new CompletableFuture<>();
        activeFutures.put(operationId, future);
        return future;
    }

    public int getPendingOperationCount() {
        return pendingOperations.size();
    }
}
