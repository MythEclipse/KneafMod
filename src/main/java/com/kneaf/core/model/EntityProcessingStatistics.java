package com.kneaf.core.model;

/**
 * Entity processing statistics
 */
public class EntityProcessingStatistics {
    public final long processedEntities;
    public final long queuedEntities;
    public final int activeProcessors;
    public final int queueSize;
    public final int activeFutures;
    public final boolean isRunning;
    public final int gridCells;
    public final int poolSize;
    public final double cpuUsage;
    public final double averageProcessingTimeMs;

    public EntityProcessingStatistics(long processedEntities, long queuedEntities, int activeProcessors,
            int queueSize, int activeFutures, boolean isRunning, int gridCells, int poolSize,
            double cpuUsage, double averageProcessingTimeMs) {
        this.processedEntities = processedEntities;
        this.queuedEntities = queuedEntities;
        this.activeProcessors = activeProcessors;
        this.queueSize = queueSize;
        this.activeFutures = activeFutures;
        this.isRunning = isRunning;
        this.gridCells = gridCells;
        this.poolSize = poolSize;
        this.cpuUsage = cpuUsage;
        this.averageProcessingTimeMs = averageProcessingTimeMs;
    }
}
