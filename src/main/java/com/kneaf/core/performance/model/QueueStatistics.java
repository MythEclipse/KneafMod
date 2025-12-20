package com.kneaf.core.performance.model;

/**
 * Queue statistics data class
 */
public class QueueStatistics {
    public final int pendingOperations;
    public final long totalOperations;
    public final int activeThreads;
    public final long queuedTasks;

    public QueueStatistics(int pendingOperations, long totalOperations, int activeThreads, long queuedTasks) {
        this.pendingOperations = pendingOperations;
        this.totalOperations = totalOperations;
        this.activeThreads = activeThreads;
        this.queuedTasks = queuedTasks;
    }
}
