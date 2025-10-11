package com.kneaf.core.config.performance;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.core.ConfigurationUtils;
import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable performance configuration that consolidates all performance-related settings.
 */
public final class PerformanceConfig implements ConfigurationProvider {
    private final boolean enabled;
    private final int threadpoolSize;
    private final int logIntervalTicks;
    private final int scanIntervalTicks;
    private final double tpsThresholdForAsync;
    private final int maxEntitiesToCollect;
    private final double entityDistanceCutoff;
    private final long maxLogBytes;
    private final boolean adaptiveThreadPool;
    private final int maxThreadpoolSize;
    private final String[] excludedEntityTypes;
    private final int networkExecutorpoolSize;
    private final boolean profilingEnabled;
    private final long slowTickThresholdMs;
    private final int profilingSampleRate;

    // Advanced parallelism configuration
    private final int minThreadpoolSize;
    private final boolean dynamicThreadScaling;
    private final double threadScaleUpThreshold;
    private final double threadScaleDownThreshold;
    private final int threadScaleUpDelayTicks;
    private final int threadScaleDownDelayTicks;
    private final boolean workStealingEnabled;
    private final int workStealingQueueSize;
    private final boolean cpuAwareThreadSizing;
    private final double cpuLoadThreshold;
    private final int threadPoolKeepAliveSeconds;

    // Distance & processing optimizations
    private final int distanceCalculationInterval;
    private final boolean distanceApproximationEnabled;
    private final int distanceCacheSize;
    private final int itemProcessingIntervalMultiplier;
    private final int spatialGridUpdateInterval;
    private final boolean incrementalSpatialUpdates;

    /**
     * Returns a new Builder for constructing PerformanceConfig instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Private constructor to enforce immutability (use Builder instead).
     *
     * @param builder the builder to construct from
     * @throws ConfigurationException if validation fails
     */
    private PerformanceConfig(Builder builder) throws ConfigurationException {
        this.enabled = builder.enabled;
        this.threadpoolSize = builder.threadpoolSize;
        this.logIntervalTicks = builder.logIntervalTicks;
        this.scanIntervalTicks = builder.scanIntervalTicks;
        this.tpsThresholdForAsync = builder.tpsThresholdForAsync;
        this.maxEntitiesToCollect = builder.maxEntitiesToCollect;
        this.entityDistanceCutoff = builder.entityDistanceCutoff;
        this.maxLogBytes = builder.maxLogBytes;
        this.adaptiveThreadPool = builder.adaptiveThreadPool;
        this.maxThreadpoolSize = builder.maxThreadpoolSize;
        this.excludedEntityTypes = builder.excludedEntityTypes == null ? new String[0] : builder.excludedEntityTypes.clone();
        this.networkExecutorpoolSize = builder.networkExecutorpoolSize;
        this.profilingEnabled = builder.profilingEnabled;
        this.slowTickThresholdMs = builder.slowTickThresholdMs;
        this.profilingSampleRate = builder.profilingSampleRate;

        // Advanced parallelism configuration
        this.minThreadpoolSize = builder.minThreadpoolSize;
        this.dynamicThreadScaling = builder.dynamicThreadScaling;
        this.threadScaleUpThreshold = builder.threadScaleUpThreshold;
        this.threadScaleDownThreshold = builder.threadScaleDownThreshold;
        this.threadScaleUpDelayTicks = builder.threadScaleUpDelayTicks;
        this.threadScaleDownDelayTicks = builder.threadScaleDownDelayTicks;
        this.workStealingEnabled = builder.workStealingEnabled;
        this.workStealingQueueSize = builder.workStealingQueueSize;
        this.cpuAwareThreadSizing = builder.cpuAwareThreadSizing;
        this.cpuLoadThreshold = builder.cpuLoadThreshold;
        this.threadPoolKeepAliveSeconds = builder.threadPoolKeepAliveSeconds;

        // Distance & processing optimizations
        this.distanceCalculationInterval = builder.distanceCalculationInterval;
        this.distanceApproximationEnabled = builder.distanceApproximationEnabled;
        this.distanceCacheSize = builder.distanceCacheSize;
        this.itemProcessingIntervalMultiplier = builder.itemProcessingIntervalMultiplier;
        this.spatialGridUpdateInterval = builder.spatialGridUpdateInterval;
        this.incrementalSpatialUpdates = builder.incrementalSpatialUpdates;

        // Perform validation during construction
        validate();
    }

    @Override
    public void validate() throws ConfigurationException {
        ConfigurationUtils.validateMinMax(
                minThreadpoolSize, maxThreadpoolSize, "minThreadpoolSize", "maxThreadpoolSize");
        ConfigurationUtils.validateMinMax(
                threadScaleDownThreshold,
                threadScaleUpThreshold,
                "threadScaleDownThreshold",
                "threadScaleUpThreshold");
        ConfigurationUtils.validateRange(scanIntervalTicks, 1, 100, "scanIntervalTicks");
        ConfigurationUtils.validateRange(
                tpsThresholdForAsync,
                ConfigurationConstants.MIN_TPS_THRESHOLD,
                ConfigurationConstants.MAX_TPS_THRESHOLD,
                "tpsThresholdForAsync");
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // Getters
    public int getThreadpoolSize() {
        return threadpoolSize;
    }

    public int getLogIntervalTicks() {
        return logIntervalTicks;
    }

    public int getScanIntervalTicks() {
        return scanIntervalTicks;
    }

    public double getTpsThresholdForAsync() {
        return tpsThresholdForAsync;
    }

    public int getMaxEntitiesToCollect() {
        return maxEntitiesToCollect;
    }

    public double getEntityDistanceCutoff() {
        return entityDistanceCutoff;
    }

    public long getMaxLogBytes() {
        return maxLogBytes;
    }

    public boolean isAdaptiveThreadPool() {
        return adaptiveThreadPool;
    }

    public int getMaxThreadpoolSize() {
        return maxThreadpoolSize;
    }

    public String[] getExcludedEntityTypes() {
        return excludedEntityTypes.clone();
    }

    public int getNetworkExecutorpoolSize() {
        return networkExecutorpoolSize;
    }

    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }

    public long getSlowTickThresholdMs() {
        return slowTickThresholdMs;
    }

    public int getProfilingSampleRate() {
        return profilingSampleRate;
    }

    // Advanced parallelism getters
    public int getMinThreadpoolSize() {
        return minThreadpoolSize;
    }

    public boolean isDynamicThreadScaling() {
        return dynamicThreadScaling;
    }

    public double getThreadScaleUpThreshold() {
        return threadScaleUpThreshold;
    }

    public double getThreadScaleDownThreshold() {
        return threadScaleDownThreshold;
    }

    public int getThreadScaleUpDelayTicks() {
        return threadScaleUpDelayTicks;
    }

    public int getThreadScaleDownDelayTicks() {
        return threadScaleDownDelayTicks;
    }

    public boolean isWorkStealingEnabled() {
        return workStealingEnabled;
    }

    public int getWorkStealingQueueSize() {
        return workStealingQueueSize;
    }

    public boolean isCpuAwareThreadSizing() {
        return cpuAwareThreadSizing;
    }

    public double getCpuLoadThreshold() {
        return cpuLoadThreshold;
    }

    public int getThreadPoolKeepAliveSeconds() {
        return threadPoolKeepAliveSeconds;
    }

    // Distance calculation optimization getters
    public int getDistanceCalculationInterval() {
        return distanceCalculationInterval;
    }

    public boolean isDistanceApproximationEnabled() {
        return distanceApproximationEnabled;
    }

    public int getDistanceCacheSize() {
        return distanceCacheSize;
    }

    public int getItemProcessingIntervalMultiplier() {
        return itemProcessingIntervalMultiplier;
    }

    // Spatial grid optimization getters
    public int getSpatialGridUpdateInterval() {
        return spatialGridUpdateInterval;
    }

    public boolean isIncrementalSpatialUpdates() {
        return incrementalSpatialUpdates;
    }

    @Override
    public String toString() {
        return "PerformanceConfig{" +
                "enabled=" + enabled +
                ", threadpoolSize=" + threadpoolSize +
                ", logIntervalTicks=" + logIntervalTicks +
                ", scanIntervalTicks=" + scanIntervalTicks +
                ", tpsThresholdForAsync=" + tpsThresholdForAsync +
                ", maxEntitiesToCollect=" + maxEntitiesToCollect +
                ", entityDistanceCutoff=" + entityDistanceCutoff +
                ", maxLogBytes=" + maxLogBytes +
                ", adaptiveThreadPool=" + adaptiveThreadPool +
                ", maxThreadpoolSize=" + maxThreadpoolSize +
                ", excludedEntityTypes=" + Arrays.toString(excludedEntityTypes) +
                ", profilingEnabled=" + profilingEnabled +
                ", slowTickThresholdMs=" + slowTickThresholdMs +
                ", profilingSampleRate=" + profilingSampleRate +
                ", minThreadpoolSize=" + minThreadpoolSize +
                ", dynamicThreadScaling=" + dynamicThreadScaling +
                ", threadScaleUpThreshold=" + threadScaleUpThreshold +
                ", threadScaleDownThreshold=" + threadScaleDownThreshold +
                ", threadScaleUpDelayTicks=" + threadScaleUpDelayTicks +
                ", threadScaleDownDelayTicks=" + threadScaleDownDelayTicks +
                ", workStealingEnabled=" + workStealingEnabled +
                ", workStealingQueueSize=" + workStealingQueueSize +
                ", cpuAwareThreadSizing=" + cpuAwareThreadSizing +
                ", cpuLoadThreshold=" + cpuLoadThreshold +
                ", threadPoolKeepAliveSeconds=" + threadPoolKeepAliveSeconds +
                ", distanceCalculationInterval=" + distanceCalculationInterval +
                ", distanceApproximationEnabled=" + distanceApproximationEnabled +
                ", distanceCacheSize=" + distanceCacheSize +
                ", itemProcessingIntervalMultiplier=" + itemProcessingIntervalMultiplier +
                ", spatialGridUpdateInterval=" + spatialGridUpdateInterval +
                ", incrementalSpatialUpdates=" + incrementalSpatialUpdates +
                '}';
    }

    /**
     * Builder class for constructing immutable PerformanceConfig instances.
     */
    public static final class Builder {
        // Basic performance settings
        private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
        private int threadpoolSize = ConfigurationConstants.DEFAULT_THREAD_POOL_SIZE;
        private int logIntervalTicks = ConfigurationConstants.DEFAULT_LOG_INTERVAL_TICKS;
        private int scanIntervalTicks = ConfigurationConstants.DEFAULT_SCAN_INTERVAL_TICKS;
        private double tpsThresholdForAsync = ConfigurationConstants.DEFAULT_TPS_THRESHOLD;
        private int maxEntitiesToCollect = ConfigurationConstants.DEFAULT_MAX_ENTITIES_TO_COLLECT;
        private double entityDistanceCutoff = ConfigurationConstants.DEFAULT_DISTANCE_CUTOFF;
        private long maxLogBytes = ConfigurationConstants.DEFAULT_MAX_LOG_BYTES;
        private boolean adaptiveThreadPool = ConfigurationConstants.DEFAULT_ADAPTIVE_THREAD_POOL;
        private int maxThreadpoolSize = ConfigurationConstants.DEFAULT_MAX_THREAD_POOL_SIZE;
        private String[] excludedEntityTypes = new String[0];
        private int networkExecutorpoolSize = ConfigurationConstants.DEFAULT_NETWORK_EXECUTOR_POOL_SIZE;
        private boolean profilingEnabled = ConfigurationConstants.DEFAULT_PROFILING_ENABLED;
        private long slowTickThresholdMs = ConfigurationConstants.DEFAULT_SLOW_TICK_THRESHOLD_MS;
        private int profilingSampleRate = ConfigurationConstants.DEFAULT_PROFILING_SAMPLE_RATE;

        // Advanced parallelism configuration
        private int minThreadpoolSize = ConfigurationConstants.DEFAULT_MIN_THREAD_POOL_SIZE;
        private boolean dynamicThreadScaling = ConfigurationConstants.DEFAULT_DYNAMIC_THREAD_SCALING;
        private double threadScaleUpThreshold = ConfigurationConstants.DEFAULT_THREAD_SCALE_UP_THRESHOLD;
        private double threadScaleDownThreshold = ConfigurationConstants.DEFAULT_THREAD_SCALE_DOWN_THRESHOLD;
        private int threadScaleUpDelayTicks = ConfigurationConstants.DEFAULT_THREAD_SCALE_UP_DELAY_TICKS;
        private int threadScaleDownDelayTicks = ConfigurationConstants.DEFAULT_THREAD_SCALE_DOWN_DELAY_TICKS;
        private boolean workStealingEnabled = ConfigurationConstants.DEFAULT_WORK_STEALING_ENABLED;
        private int workStealingQueueSize = ConfigurationConstants.DEFAULT_WORK_STEALING_QUEUE_SIZE;
        private boolean cpuAwareThreadSizing = ConfigurationConstants.DEFAULT_CPU_AWARE_THREAD_SIZING;
        private double cpuLoadThreshold = ConfigurationConstants.DEFAULT_CPU_LOAD_THRESHOLD;
        private int threadPoolKeepAliveSeconds = ConfigurationConstants.DEFAULT_THREAD_POOL_KEEP_ALIVE_SECONDS;

        // Distance & processing optimizations
        private int distanceCalculationInterval = ConfigurationConstants.DEFAULT_DISTANCE_CALCULATION_INTERVAL;
        private boolean distanceApproximationEnabled = ConfigurationConstants.DEFAULT_DISTANCE_APPROXIMATION_ENABLED;
        private int distanceCacheSize = ConfigurationConstants.DEFAULT_DISTANCE_CACHE_SIZE;
        private int itemProcessingIntervalMultiplier = ConfigurationConstants.DEFAULT_ITEM_PROCESSING_INTERVAL_MULTIPLIER;
        private int spatialGridUpdateInterval = ConfigurationConstants.DEFAULT_SPATIAL_GRID_UPDATE_INTERVAL;
        private boolean incrementalSpatialUpdates = ConfigurationConstants.DEFAULT_INCREMENTAL_SPATIAL_UPDATES;

        // Basic performance setters
        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder threadpoolSize(int v) {
            this.threadpoolSize = v;
            return this;
        }

        public Builder logIntervalTicks(int v) {
            this.logIntervalTicks = v;
            return this;
        }

        public Builder scanIntervalTicks(int v) {
            this.scanIntervalTicks = v;
            return this;
        }

        public Builder tpsThresholdForAsync(double v) {
            this.tpsThresholdForAsync = v;
            return this;
        }

        public Builder maxEntitiesToCollect(int v) {
            this.maxEntitiesToCollect = v;
            return this;
        }

        public Builder entityDistanceCutoff(double v) {
            this.entityDistanceCutoff = v;
            return this;
        }

        public Builder maxLogBytes(long v) {
            this.maxLogBytes = v;
            return this;
        }

        public Builder adaptiveThreadPool(boolean v) {
            this.adaptiveThreadPool = v;
            return this;
        }

        public Builder maxThreadpoolSize(int v) {
            this.maxThreadpoolSize = v;
            return this;
        }

        public Builder excludedEntityTypes(String[] v) {
            this.excludedEntityTypes = v == null ? new String[0] : v.clone();
            return this;
        }

        public Builder networkExecutorpoolSize(int v) {
            this.networkExecutorpoolSize = v;
            return this;
        }

        public Builder profilingEnabled(boolean v) {
            this.profilingEnabled = v;
            return this;
        }

        public Builder slowTickThresholdMs(long v) {
            this.slowTickThresholdMs = v;
            return this;
        }

        public Builder profilingSampleRate(int v) {
            this.profilingSampleRate = v;
            return this;
        }

        // Advanced parallelism setters
        public Builder minThreadpoolSize(int v) {
            this.minThreadpoolSize = v;
            return this;
        }

        public Builder dynamicThreadScaling(boolean v) {
            this.dynamicThreadScaling = v;
            return this;
        }

        public Builder threadScaleUpThreshold(double v) {
            this.threadScaleUpThreshold = v;
            return this;
        }

        public Builder threadScaleDownThreshold(double v) {
            this.threadScaleDownThreshold = v;
            return this;
        }

        public Builder threadScaleUpDelayTicks(int v) {
            this.threadScaleUpDelayTicks = v;
            return this;
        }

        public Builder threadScaleDownDelayTicks(int v) {
            this.threadScaleDownDelayTicks = v;
            return this;
        }

        public Builder workStealingEnabled(boolean v) {
            this.workStealingEnabled = v;
            return this;
        }

        public Builder workStealingQueueSize(int v) {
            this.workStealingQueueSize = v;
            return this;
        }

        public Builder cpuAwareThreadSizing(boolean v) {
            this.cpuAwareThreadSizing = v;
            return this;
        }

        public Builder cpuLoadThreshold(double v) {
            this.cpuLoadThreshold = v;
            return this;
        }

        public Builder threadPoolKeepAliveSeconds(int v) {
            this.threadPoolKeepAliveSeconds = v;
            return this;
        }

        // Distance & processing optimization setters
        public Builder distanceCalculationInterval(int v) {
            this.distanceCalculationInterval = v;
            return this;
        }

        public Builder distanceApproximationEnabled(boolean v) {
            this.distanceApproximationEnabled = v;
            return this;
        }

        public Builder distanceCacheSize(int v) {
            this.distanceCacheSize = v;
            return this;
        }

        public Builder itemProcessingIntervalMultiplier(int v) {
            this.itemProcessingIntervalMultiplier = v;
            return this;
        }

        public Builder spatialGridUpdateInterval(int v) {
            this.spatialGridUpdateInterval = v;
            return this;
        }

        public Builder incrementalSpatialUpdates(boolean v) {
            this.incrementalSpatialUpdates = v;
            return this;
        }

        /**
         * Builds and returns a new PerformanceConfig instance.
         *
         * @return the constructed PerformanceConfig
         * @throws ConfigurationException if validation fails
         */
        public PerformanceConfig build() throws ConfigurationException {
            return new PerformanceConfig(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PerformanceConfig that = (PerformanceConfig) o;
        return enabled == that.enabled &&
                threadpoolSize == that.threadpoolSize &&
                logIntervalTicks == that.logIntervalTicks &&
                scanIntervalTicks == that.scanIntervalTicks &&
                Double.compare(that.tpsThresholdForAsync, tpsThresholdForAsync) == 0 &&
                maxEntitiesToCollect == that.maxEntitiesToCollect &&
                Double.compare(that.entityDistanceCutoff, entityDistanceCutoff) == 0 &&
                maxLogBytes == that.maxLogBytes &&
                adaptiveThreadPool == that.adaptiveThreadPool &&
                maxThreadpoolSize == that.maxThreadpoolSize &&
                networkExecutorpoolSize == that.networkExecutorpoolSize &&
                profilingEnabled == that.profilingEnabled &&
                slowTickThresholdMs == that.slowTickThresholdMs &&
                profilingSampleRate == that.profilingSampleRate &&
                minThreadpoolSize == that.minThreadpoolSize &&
                dynamicThreadScaling == that.dynamicThreadScaling &&
                Double.compare(that.threadScaleUpThreshold, threadScaleUpThreshold) == 0 &&
                Double.compare(that.threadScaleDownThreshold, threadScaleDownThreshold) == 0 &&
                threadScaleUpDelayTicks == that.threadScaleUpDelayTicks &&
                threadScaleDownDelayTicks == that.threadScaleDownDelayTicks &&
                workStealingEnabled == that.workStealingEnabled &&
                workStealingQueueSize == that.workStealingQueueSize &&
                cpuAwareThreadSizing == that.cpuAwareThreadSizing &&
                Double.compare(that.cpuLoadThreshold, cpuLoadThreshold) == 0 &&
                threadPoolKeepAliveSeconds == that.threadPoolKeepAliveSeconds &&
                distanceCalculationInterval == that.distanceCalculationInterval &&
                distanceApproximationEnabled == that.distanceApproximationEnabled &&
                distanceCacheSize == that.distanceCacheSize &&
                itemProcessingIntervalMultiplier == that.itemProcessingIntervalMultiplier &&
                spatialGridUpdateInterval == that.spatialGridUpdateInterval &&
                incrementalSpatialUpdates == that.incrementalSpatialUpdates &&
                Arrays.equals(excludedEntityTypes, that.excludedEntityTypes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(enabled, threadpoolSize, logIntervalTicks, scanIntervalTicks, tpsThresholdForAsync, maxEntitiesToCollect, entityDistanceCutoff, maxLogBytes, adaptiveThreadPool, maxThreadpoolSize, networkExecutorpoolSize, profilingEnabled, slowTickThresholdMs, profilingSampleRate, minThreadpoolSize, dynamicThreadScaling, threadScaleUpThreshold, threadScaleDownThreshold, threadScaleUpDelayTicks, threadScaleDownDelayTicks, workStealingEnabled, workStealingQueueSize, cpuAwareThreadSizing, cpuLoadThreshold, threadPoolKeepAliveSeconds, distanceCalculationInterval, distanceApproximationEnabled, distanceCacheSize, itemProcessingIntervalMultiplier, spatialGridUpdateInterval, incrementalSpatialUpdates);
        result = 31 * result + Arrays.hashCode(excludedEntityTypes);
        return result;
    }
}