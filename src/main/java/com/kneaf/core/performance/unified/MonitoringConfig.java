package com.kneaf.core.performance.unified;

import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Configuration class for performance monitoring system.
 * Immutable and thread-safe once constructed.
 */
public final class MonitoringConfig {
    private final MonitoringLevel monitoringLevel;
    private final int logIntervalTicks;
    private final int tpsWindowSize;
    private final double tpsThresholdAsync;
    private final int maxEntitiesToCollect;
    private final double entityDistanceCutoff;
    private final String[] excludedEntityTypes;
    private final long jniCallThresholdMs;
    private final long lockWaitThresholdMs;
    private final double memoryUsageThresholdPct;
    private final long gcDurationThresholdMs;
    private final int lockContentionThreshold;
    private final long highAllocationLatencyThresholdMs;
    private final boolean broadcastToClient;
    private final boolean enableProfiling;
    private final int profilingSampleRate;

    /**
     * Create a new MonitoringConfig with default values.
     */
    public MonitoringConfig() {
        this(UnifiedPerformanceConstants.DEFAULT_MONITORING_LEVEL,
             UnifiedPerformanceConstants.DEFAULT_LOG_INTERVAL_TICKS,
             UnifiedPerformanceConstants.DEFAULT_TPS_WINDOW_SIZE,
             UnifiedPerformanceConstants.DEFAULT_TPS_THRESHOLD_ASYNC,
             UnifiedPerformanceConstants.DEFAULT_MAX_ENTITIES_TO_COLLECT,
             UnifiedPerformanceConstants.DEFAULT_ENTITY_DISTANCE_CUTOFF,
             UnifiedPerformanceConstants.DEFAULT_EXCLUDED_ENTITY_TYPES,
             UnifiedPerformanceConstants.DEFAULT_JNI_CALL_THRESHOLD_MS,
             UnifiedPerformanceConstants.DEFAULT_LOCK_WAIT_THRESHOLD_MS,
             UnifiedPerformanceConstants.DEFAULT_MEMORY_USAGE_THRESHOLD_PCT,
             UnifiedPerformanceConstants.DEFAULT_GC_DURATION_THRESHOLD_MS,
             UnifiedPerformanceConstants.DEFAULT_LOCK_CONTENTION_THRESHOLD,
             UnifiedPerformanceConstants.DEFAULT_HIGH_ALLOCATION_LATENCY_THRESHOLD_MS,
             true,
             true,
             10);
    }

    /**
     * Create a new MonitoringConfig with custom values.
     * @param monitoringLevel the monitoring level
     * @param logIntervalTicks interval between log messages in ticks
     * @param tpsWindowSize size of the TPS rolling window
     * @param tpsThresholdAsync TPS threshold for async operations
     * @param maxEntitiesToCollect maximum entities to collect per tick
     * @param entityDistanceCutoff maximum distance for entity collection
     * @param excludedEntityTypes entity types to exclude from collection
     * @param jniCallThresholdMs JNI call threshold in milliseconds
     * @param lockWaitThresholdMs lock wait threshold in milliseconds
     * @param memoryUsageThresholdPct memory usage threshold percentage
     * @param gcDurationThresholdMs GC duration threshold in milliseconds
     * @param lockContentionThreshold lock contention threshold
     * @param highAllocationLatencyThresholdMs high allocation latency threshold
     * @param broadcastToClient whether to broadcast performance data to clients
     * @param enableProfiling whether to enable profiling
     * @param profilingSampleRate profiling sample rate (1 in N ticks)
     */
    public MonitoringConfig(MonitoringLevel monitoringLevel,
                           int logIntervalTicks,
                           int tpsWindowSize,
                           double tpsThresholdAsync,
                           int maxEntitiesToCollect,
                           double entityDistanceCutoff,
                           String[] excludedEntityTypes,
                           long jniCallThresholdMs,
                           long lockWaitThresholdMs,
                           double memoryUsageThresholdPct,
                           long gcDurationThresholdMs,
                           int lockContentionThreshold,
                           long highAllocationLatencyThresholdMs,
                           boolean broadcastToClient,
                           boolean enableProfiling,
                           int profilingSampleRate) {
        this.monitoringLevel = Objects.requireNonNull(monitoringLevel, "monitoringLevel cannot be null");
        this.logIntervalTicks = Math.max(1, logIntervalTicks);
        this.tpsWindowSize = Math.max(1, tpsWindowSize);
        this.tpsThresholdAsync = Math.max(0.0, tpsThresholdAsync);
        this.maxEntitiesToCollect = Math.max(1, maxEntitiesToCollect);
        this.entityDistanceCutoff = Math.max(0.0, entityDistanceCutoff);
        this.excludedEntityTypes = excludedEntityTypes != null ? Arrays.copyOf(excludedEntityTypes, excludedEntityTypes.length) : new String[0];
        this.jniCallThresholdMs = Math.max(0, jniCallThresholdMs);
        this.lockWaitThresholdMs = Math.max(0, lockWaitThresholdMs);
        this.memoryUsageThresholdPct = Math.max(0.0, memoryUsageThresholdPct);
        this.gcDurationThresholdMs = Math.max(0, gcDurationThresholdMs);
        this.lockContentionThreshold = Math.max(0, lockContentionThreshold);
        this.highAllocationLatencyThresholdMs = Math.max(0, highAllocationLatencyThresholdMs);
        this.broadcastToClient = broadcastToClient;
        this.enableProfiling = enableProfiling;
        this.profilingSampleRate = Math.max(1, profilingSampleRate);
    }

    /**
     * Get the monitoring level.
     * @return monitoring level
     */
    public MonitoringLevel getMonitoringLevel() {
        return monitoringLevel;
    }

    /**
     * Get the log interval in ticks.
     * @return log interval ticks
     */
    public int getLogIntervalTicks() {
        return logIntervalTicks;
    }

    /**
     * Get the TPS window size for rolling average.
     * @return TPS window size
     */
    public int getTpsWindowSize() {
        return tpsWindowSize;
    }

    /**
     * Get the TPS threshold for async operations.
     * @return TPS threshold
     */
    public double getTpsThresholdAsync() {
        return tpsThresholdAsync;
    }

    /**
     * Get the maximum entities to collect per tick.
     * @return max entities to collect
     */
    public int getMaxEntitiesToCollect() {
        return maxEntitiesToCollect;
    }

    /**
     * Get the entity distance cutoff in blocks.
     * @return entity distance cutoff
     */
    public double getEntityDistanceCutoff() {
        return entityDistanceCutoff;
    }

    /**
     * Get the excluded entity types.
     * @return excluded entity types (defensive copy)
     */
    public String[] getExcludedEntityTypes() {
        return Arrays.copyOf(excludedEntityTypes, excludedEntityTypes.length);
    }

    /**
     * Get the JNI call threshold in milliseconds.
     * @return JNI call threshold
     */
    public long getJniCallThresholdMs() {
        return jniCallThresholdMs;
    }

    /**
     * Get the lock wait threshold in milliseconds.
     * @return lock wait threshold
     */
    public long getLockWaitThresholdMs() {
        return lockWaitThresholdMs;
    }

    /**
     * Get the memory usage threshold percentage.
     * @return memory usage threshold
     */
    public double getMemoryUsageThresholdPct() {
        return memoryUsageThresholdPct;
    }

    /**
     * Get the GC duration threshold in milliseconds.
     * @return GC duration threshold
     */
    public long getGcDurationThresholdMs() {
        return gcDurationThresholdMs;
    }

    /**
     * Get the lock contention threshold.
     * @return lock contention threshold
     */
    public int getLockContentionThreshold() {
        return lockContentionThreshold;
    }

    /**
     * Get the high allocation latency threshold in milliseconds.
     * @return high allocation latency threshold
     */
    public long getHighAllocationLatencyThresholdMs() {
        return highAllocationLatencyThresholdMs;
    }

    /**
     * Check if performance data should be broadcast to clients.
     * @return true if broadcasting is enabled
     */
    public boolean isBroadcastToClient() {
        return broadcastToClient;
    }

    /**
     * Check if profiling is enabled.
     * @return true if profiling is enabled
     */
    public boolean isProfilingEnabled() {
        return enableProfiling;
    }

    /**
     * Get the profiling sample rate (1 in N ticks).
     * @return profiling sample rate
     */
    public int getProfilingSampleRate() {
        return profilingSampleRate;
    }

    /**
     * Create a builder for MonitoringConfig.
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for MonitoringConfig.
     */
    public static final class Builder {
        private MonitoringLevel monitoringLevel = UnifiedPerformanceConstants.DEFAULT_MONITORING_LEVEL;
        private int logIntervalTicks = UnifiedPerformanceConstants.DEFAULT_LOG_INTERVAL_TICKS;
        private int tpsWindowSize = UnifiedPerformanceConstants.DEFAULT_TPS_WINDOW_SIZE;
        private double tpsThresholdAsync = UnifiedPerformanceConstants.DEFAULT_TPS_THRESHOLD_ASYNC;
        private int maxEntitiesToCollect = UnifiedPerformanceConstants.DEFAULT_MAX_ENTITIES_TO_COLLECT;
        private double entityDistanceCutoff = UnifiedPerformanceConstants.DEFAULT_ENTITY_DISTANCE_CUTOFF;
        private String[] excludedEntityTypes = UnifiedPerformanceConstants.DEFAULT_EXCLUDED_ENTITY_TYPES;
        private long jniCallThresholdMs = UnifiedPerformanceConstants.DEFAULT_JNI_CALL_THRESHOLD_MS;
        private long lockWaitThresholdMs = UnifiedPerformanceConstants.DEFAULT_LOCK_WAIT_THRESHOLD_MS;
        private double memoryUsageThresholdPct = UnifiedPerformanceConstants.DEFAULT_MEMORY_USAGE_THRESHOLD_PCT;
        private long gcDurationThresholdMs = UnifiedPerformanceConstants.DEFAULT_GC_DURATION_THRESHOLD_MS;
        private int lockContentionThreshold = UnifiedPerformanceConstants.DEFAULT_LOCK_CONTENTION_THRESHOLD;
        private long highAllocationLatencyThresholdMs = UnifiedPerformanceConstants.DEFAULT_HIGH_ALLOCATION_LATENCY_THRESHOLD_MS;
        private boolean broadcastToClient = true;
        private boolean enableProfiling = true;
        private int profilingSampleRate = 10;

        private Builder() {}

        /**
         * Set the monitoring level.
         * @param monitoringLevel the monitoring level
         * @return this builder
         */
        public Builder monitoringLevel(MonitoringLevel monitoringLevel) {
            this.monitoringLevel = Objects.requireNonNull(monitoringLevel);
            return this;
        }

        /**
         * Set the log interval in ticks.
         * @param logIntervalTicks log interval ticks
         * @return this builder
         */
        public Builder logIntervalTicks(int logIntervalTicks) {
            this.logIntervalTicks = Math.max(1, logIntervalTicks);
            return this;
        }

        /**
         * Set the TPS window size.
         * @param tpsWindowSize TPS window size
         * @return this builder
         */
        public Builder tpsWindowSize(int tpsWindowSize) {
            this.tpsWindowSize = Math.max(1, tpsWindowSize);
            return this;
        }

        /**
         * Set the TPS threshold for async operations.
         * @param tpsThresholdAsync TPS threshold
         * @return this builder
         */
        public Builder tpsThresholdAsync(double tpsThresholdAsync) {
            this.tpsThresholdAsync = Math.max(0.0, tpsThresholdAsync);
            return this;
        }

        /**
         * Set the maximum entities to collect per tick.
         * @param maxEntitiesToCollect max entities
         * @return this builder
         */
        public Builder maxEntitiesToCollect(int maxEntitiesToCollect) {
            this.maxEntitiesToCollect = Math.max(1, maxEntitiesToCollect);
            return this;
        }

        /**
         * Set the entity distance cutoff.
         * @param entityDistanceCutoff distance cutoff
         * @return this builder
         */
        public Builder entityDistanceCutoff(double entityDistanceCutoff) {
            this.entityDistanceCutoff = Math.max(0.0, entityDistanceCutoff);
            return this;
        }

        /**
         * Set excluded entity types.
         * @param excludedEntityTypes excluded types
         * @return this builder
         */
        public Builder excludedEntityTypes(String[] excludedEntityTypes) {
            this.excludedEntityTypes = excludedEntityTypes != null ? Arrays.copyOf(excludedEntityTypes, excludedEntityTypes.length) : new String[0];
            return this;
        }

        /**
         * Set JNI call threshold.
         * @param jniCallThresholdMs JNI threshold in ms
         * @return this builder
         */
        public Builder jniCallThresholdMs(long jniCallThresholdMs) {
            this.jniCallThresholdMs = Math.max(0, jniCallThresholdMs);
            return this;
        }

        /**
         * Set lock wait threshold.
         * @param lockWaitThresholdMs lock wait threshold in ms
         * @return this builder
         */
        public Builder lockWaitThresholdMs(long lockWaitThresholdMs) {
            this.lockWaitThresholdMs = Math.max(0, lockWaitThresholdMs);
            return this;
        }

        /**
         * Set memory usage threshold.
         * @param memoryUsageThresholdPct memory threshold percentage
         * @return this builder
         */
        public Builder memoryUsageThresholdPct(double memoryUsageThresholdPct) {
            this.memoryUsageThresholdPct = Math.max(0.0, memoryUsageThresholdPct);
            return this;
        }

        /**
         * Set GC duration threshold.
         * @param gcDurationThresholdMs GC threshold in ms
         * @return this builder
         */
        public Builder gcDurationThresholdMs(long gcDurationThresholdMs) {
            this.gcDurationThresholdMs = Math.max(0, gcDurationThresholdMs);
            return this;
        }

        /**
         * Set lock contention threshold.
         * @param lockContentionThreshold contention threshold
         * @return this builder
         */
        public Builder lockContentionThreshold(int lockContentionThreshold) {
            this.lockContentionThreshold = Math.max(0, lockContentionThreshold);
            return this;
        }

        /**
         * Set high allocation latency threshold.
         * @param highAllocationLatencyThresholdMs allocation latency threshold in ms
         * @return this builder
         */
        public Builder highAllocationLatencyThresholdMs(long highAllocationLatencyThresholdMs) {
            this.highAllocationLatencyThresholdMs = Math.max(0, highAllocationLatencyThresholdMs);
            return this;
        }

        /**
         * Set whether to broadcast to clients.
         * @param broadcastToClient broadcast flag
         * @return this builder
         */
        public Builder broadcastToClient(boolean broadcastToClient) {
            this.broadcastToClient = broadcastToClient;
            return this;
        }

        /**
         * Set whether to enable profiling.
         * @param enableProfiling profiling flag
         * @return this builder
         */
        public Builder enableProfiling(boolean enableProfiling) {
            this.enableProfiling = enableProfiling;
            return this;
        }

        /**
         * Set profiling sample rate.
         * @param profilingSampleRate sample rate (1 in N)
         * @return this builder
         */
        public Builder profilingSampleRate(int profilingSampleRate) {
            this.profilingSampleRate = Math.max(1, profilingSampleRate);
            return this;
        }

        /**
         * Build the MonitoringConfig.
         * @return a new MonitoringConfig
         */
        public MonitoringConfig build() {
            return new MonitoringConfig(
                monitoringLevel,
                logIntervalTicks,
                tpsWindowSize,
                tpsThresholdAsync,
                maxEntitiesToCollect,
                entityDistanceCutoff,
                excludedEntityTypes,
                jniCallThresholdMs,
                lockWaitThresholdMs,
                memoryUsageThresholdPct,
                gcDurationThresholdMs,
                lockContentionThreshold,
                highAllocationLatencyThresholdMs,
                broadcastToClient,
                enableProfiling,
                profilingSampleRate);
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", MonitoringConfig.class.getSimpleName() + "[", "]")
                .add("monitoringLevel=" + monitoringLevel)
                .add("logIntervalTicks=" + logIntervalTicks)
                .add("tpsWindowSize=" + tpsWindowSize)
                .add("tpsThresholdAsync=" + tpsThresholdAsync)
                .add("maxEntitiesToCollect=" + maxEntitiesToCollect)
                .add("entityDistanceCutoff=" + entityDistanceCutoff)
                .add("excludedEntityTypes=" + Arrays.toString(excludedEntityTypes))
                .add("jniCallThresholdMs=" + jniCallThresholdMs)
                .add("lockWaitThresholdMs=" + lockWaitThresholdMs)
                .add("memoryUsageThresholdPct=" + memoryUsageThresholdPct)
                .add("gcDurationThresholdMs=" + gcDurationThresholdMs)
                .add("lockContentionThreshold=" + lockContentionThreshold)
                .add("highAllocationLatencyThresholdMs=" + highAllocationLatencyThresholdMs)
                .add("broadcastToClient=" + broadcastToClient)
                .add("enableProfiling=" + enableProfiling)
                .add("profilingSampleRate=" + profilingSampleRate)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MonitoringConfig that = (MonitoringConfig) o;
        return logIntervalTicks == that.logIntervalTicks &&
                tpsWindowSize == that.tpsWindowSize &&
                Double.compare(that.tpsThresholdAsync, tpsThresholdAsync) == 0 &&
                maxEntitiesToCollect == that.maxEntitiesToCollect &&
                Double.compare(that.entityDistanceCutoff, entityDistanceCutoff) == 0 &&
                jniCallThresholdMs == that.jniCallThresholdMs &&
                lockWaitThresholdMs == that.lockWaitThresholdMs &&
                Double.compare(that.memoryUsageThresholdPct, memoryUsageThresholdPct) == 0 &&
                gcDurationThresholdMs == that.gcDurationThresholdMs &&
                lockContentionThreshold == that.lockContentionThreshold &&
                highAllocationLatencyThresholdMs == that.highAllocationLatencyThresholdMs &&
                broadcastToClient == that.broadcastToClient &&
                enableProfiling == that.enableProfiling &&
                profilingSampleRate == that.profilingSampleRate &&
                monitoringLevel == that.monitoringLevel &&
                Arrays.equals(excludedEntityTypes, that.excludedEntityTypes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(monitoringLevel, logIntervalTicks, tpsWindowSize, tpsThresholdAsync,
                maxEntitiesToCollect, entityDistanceCutoff, jniCallThresholdMs, lockWaitThresholdMs,
                memoryUsageThresholdPct, gcDurationThresholdMs, lockContentionThreshold,
                highAllocationLatencyThresholdMs, broadcastToClient, enableProfiling, profilingSampleRate);
        result = 31 * result + Arrays.hashCode(excludedEntityTypes);
        return result;
    }
}