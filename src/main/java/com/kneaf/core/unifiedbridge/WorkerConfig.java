package com.kneaf.core.unifiedbridge;

/**
 * Configuration for worker threads in the bridge.
 */
public class WorkerConfig {
    private final int concurrency;
    private final long timeoutMillis;
    private final int maxQueueSize;
    private final String workerName;

    public WorkerConfig(int concurrency, long timeoutMillis, int maxQueueSize, String workerName) {
        this.concurrency = concurrency;
        this.timeoutMillis = timeoutMillis;
        this.maxQueueSize = maxQueueSize;
        this.workerName = workerName;
    }

    public WorkerConfig(int concurrency) {
        this(concurrency, 30000, 1000, "default-worker");
    }

    public int getConcurrency() {
        return concurrency;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public String getWorkerName() {
        return workerName;
    }

    public int getThreadCount() {
        return concurrency;
    }
}