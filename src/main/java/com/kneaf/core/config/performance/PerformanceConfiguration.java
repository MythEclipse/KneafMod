package com.kneaf.core.config.performance;

import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationUtils;

import java.util.Arrays;

/**
 * Unified performance configuration that consolidates all performance-related settings.
 */
public class PerformanceConfiguration implements ConfigurationProvider {
    private final boolean enabled;
    private final int threadPoolSize;
    private final int logIntervalTicks;
    private final int scanIntervalTicks;
    private final double tpsThresholdForAsync;
    private final int maxEntitiesToCollect;
    private final double entityDistanceCutoff;
    private final long maxLogBytes;
    private final boolean adaptiveThreadPool;
    private final int maxThreadPoolSize;
    private final String[] excludedEntityTypes;
    private final int networkExecutorPoolSize;
    private final boolean profilingEnabled;
    private final long slowTickThresholdMs;
    private final int profilingSampleRate;
    
    // Advanced parallelism configuration
    private final int minThreadPoolSize;
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

    private PerformanceConfiguration(Builder builder) {
        this.enabled = builder.enabled;
        this.threadPoolSize = builder.threadPoolSize;
        this.logIntervalTicks = builder.logIntervalTicks;
        this.scanIntervalTicks = builder.scanIntervalTicks;
        this.tpsThresholdForAsync = builder.tpsThresholdForAsync;
        this.maxEntitiesToCollect = builder.maxEntitiesToCollect;
        this.entityDistanceCutoff = builder.entityDistanceCutoff;
        this.maxLogBytes = builder.maxLogBytes;
        this.adaptiveThreadPool = builder.adaptiveThreadPool;
        this.maxThreadPoolSize = builder.maxThreadPoolSize;
        this.excludedEntityTypes = builder.excludedEntityTypes == null ? new String[0] : builder.excludedEntityTypes.clone();
        this.networkExecutorPoolSize = builder.networkExecutorPoolSize;
        this.profilingEnabled = builder.profilingEnabled;
        this.slowTickThresholdMs = builder.slowTickThresholdMs;
        this.profilingSampleRate = builder.profilingSampleRate;
        
        // Advanced parallelism configuration
        this.minThreadPoolSize = builder.minThreadPoolSize;
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
        
        validate();
    }
    
    @Override
    public void validate() {
        ConfigurationUtils.validateMinMax(minThreadPoolSize, maxThreadPoolSize, "minThreadPoolSize", "maxThreadPoolSize");
        ConfigurationUtils.validateMinMax(threadScaleDownThreshold, threadScaleUpThreshold, "threadScaleDownThreshold", "threadScaleUpThreshold");
        ConfigurationUtils.validateRange(scanIntervalTicks, 1, 100, "scanIntervalTicks");
        ConfigurationUtils.validateRange(tpsThresholdForAsync, ConfigurationConstants.MIN_TPS_THRESHOLD, ConfigurationConstants.MAX_TPS_THRESHOLD, "tpsThresholdForAsync");
    }
    
    @Override
    public boolean isEnabled() { return enabled; }
    
    // Getters
    public int getThreadPoolSize() { return threadPoolSize; }
    public int getLogIntervalTicks() { return logIntervalTicks; }
    public int getScanIntervalTicks() { return scanIntervalTicks; }
    public double getTpsThresholdForAsync() { return tpsThresholdForAsync; }
    public int getMaxEntitiesToCollect() { return maxEntitiesToCollect; }
    public double getEntityDistanceCutoff() { return entityDistanceCutoff; }
    public long getMaxLogBytes() { return maxLogBytes; }
    public boolean isAdaptiveThreadPool() { return adaptiveThreadPool; }
    public int getMaxThreadPoolSize() { return maxThreadPoolSize; }
    public String[] getExcludedEntityTypes() { return excludedEntityTypes.clone(); }
    public int getNetworkExecutorPoolSize() { return networkExecutorPoolSize; }
    public boolean isProfilingEnabled() { return profilingEnabled; }
    public long getSlowTickThresholdMs() { return slowTickThresholdMs; }
    public int getProfilingSampleRate() { return profilingSampleRate; }
    
    // Advanced parallelism getters
    public int getMinThreadPoolSize() { return minThreadPoolSize; }
    public boolean isDynamicThreadScaling() { return dynamicThreadScaling; }
    public double getThreadScaleUpThreshold() { return threadScaleUpThreshold; }
    public double getThreadScaleDownThreshold() { return threadScaleDownThreshold; }
    public int getThreadScaleUpDelayTicks() { return threadScaleUpDelayTicks; }
    public int getThreadScaleDownDelayTicks() { return threadScaleDownDelayTicks; }
    public boolean isWorkStealingEnabled() { return workStealingEnabled; }
    public int getWorkStealingQueueSize() { return workStealingQueueSize; }
    public boolean isCpuAwareThreadSizing() { return cpuAwareThreadSizing; }
    public double getCpuLoadThreshold() { return cpuLoadThreshold; }
    public int getThreadPoolKeepAliveSeconds() { return threadPoolKeepAliveSeconds; }
    
    // Distance calculation optimization getters
    public int getDistanceCalculationInterval() { return distanceCalculationInterval; }
    public boolean isDistanceApproximationEnabled() { return distanceApproximationEnabled; }
    public int getDistanceCacheSize() { return distanceCacheSize; }
    
    // Item processing optimization getters
    public int getItemProcessingIntervalMultiplier() { return itemProcessingIntervalMultiplier; }
    
    // Spatial grid optimization getters
    public int getSpatialGridUpdateInterval() { return spatialGridUpdateInterval; }
    public boolean isIncrementalSpatialUpdates() { return incrementalSpatialUpdates; }
    
    @Override
    public String toString() {
        return "PerformanceConfiguration{" +
                "enabled=" + enabled +
                ", threadPoolSize=" + threadPoolSize +
                ", logIntervalTicks=" + logIntervalTicks +
                ", scanIntervalTicks=" + scanIntervalTicks +
                ", tpsThresholdForAsync=" + tpsThresholdForAsync +
                ", maxEntitiesToCollect=" + maxEntitiesToCollect +
                ", entityDistanceCutoff=" + entityDistanceCutoff +
                ", maxLogBytes=" + maxLogBytes +
                ", adaptiveThreadPool=" + adaptiveThreadPool +
                ", maxThreadPoolSize=" + maxThreadPoolSize +
                ", excludedEntityTypes=" + Arrays.toString(excludedEntityTypes) +
                ", profilingEnabled=" + profilingEnabled +
                ", slowTickThresholdMs=" + slowTickThresholdMs +
                ", profilingSampleRate=" + profilingSampleRate +
                '}';
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        // Basic performance settings
        private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
        private int threadPoolSize = ConfigurationConstants.DEFAULT_THREAD_POOL_SIZE;
        private int logIntervalTicks = ConfigurationConstants.DEFAULT_LOG_INTERVAL_TICKS;
        private int scanIntervalTicks = ConfigurationConstants.DEFAULT_SCAN_INTERVAL_TICKS;
        private double tpsThresholdForAsync = ConfigurationConstants.DEFAULT_TPS_THRESHOLD;
        private int maxEntitiesToCollect = ConfigurationConstants.DEFAULT_MAX_ENTITIES_TO_COLLECT;
        private double entityDistanceCutoff = ConfigurationConstants.DEFAULT_DISTANCE_CUTOFF;
        private long maxLogBytes = ConfigurationConstants.DEFAULT_MAX_LOG_BYTES;
        private boolean adaptiveThreadPool = ConfigurationConstants.DEFAULT_ADAPTIVE_THREAD_POOL;
        private int maxThreadPoolSize = ConfigurationConstants.DEFAULT_MAX_THREAD_POOL_SIZE;
        private String[] excludedEntityTypes = new String[0];
        private int networkExecutorPoolSize = ConfigurationConstants.DEFAULT_NETWORK_EXECUTOR_POOL_SIZE;
        private boolean profilingEnabled = ConfigurationConstants.DEFAULT_PROFILING_ENABLED;
        private long slowTickThresholdMs = ConfigurationConstants.DEFAULT_SLOW_TICK_THRESHOLD_MS;
        private int profilingSampleRate = ConfigurationConstants.DEFAULT_PROFILING_SAMPLE_RATE;
        
        // Advanced parallelism configuration
        private int minThreadPoolSize = ConfigurationConstants.DEFAULT_MIN_THREAD_POOL_SIZE;
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
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder threadPoolSize(int v) { this.threadPoolSize = v; return this; }
        public Builder logIntervalTicks(int v) { this.logIntervalTicks = v; return this; }
        public Builder scanIntervalTicks(int v) { this.scanIntervalTicks = v; return this; }
        public Builder tpsThresholdForAsync(double v) { this.tpsThresholdForAsync = v; return this; }
        public Builder maxEntitiesToCollect(int v) { this.maxEntitiesToCollect = v; return this; }
        public Builder entityDistanceCutoff(double v) { this.entityDistanceCutoff = v; return this; }
        public Builder maxLogBytes(long v) { this.maxLogBytes = v; return this; }
        public Builder adaptiveThreadPool(boolean v) { this.adaptiveThreadPool = v; return this; }
        public Builder maxThreadPoolSize(int v) { this.maxThreadPoolSize = v; return this; }
        public Builder excludedEntityTypes(String[] v) { this.excludedEntityTypes = v == null ? new String[0] : v.clone(); return this; }
        public Builder networkExecutorPoolSize(int v) { this.networkExecutorPoolSize = v; return this; }
        public Builder profilingEnabled(boolean v) { this.profilingEnabled = v; return this; }
        public Builder slowTickThresholdMs(long v) { this.slowTickThresholdMs = v; return this; }
        public Builder profilingSampleRate(int v) { this.profilingSampleRate = v; return this; }
        
        // Advanced parallelism setters
        public Builder minThreadPoolSize(int v) { this.minThreadPoolSize = v; return this; }
        public Builder dynamicThreadScaling(boolean v) { this.dynamicThreadScaling = v; return this; }
        public Builder threadScaleUpThreshold(double v) { this.threadScaleUpThreshold = v; return this; }
        public Builder threadScaleDownThreshold(double v) { this.threadScaleDownThreshold = v; return this; }
        public Builder threadScaleUpDelayTicks(int v) { this.threadScaleUpDelayTicks = v; return this; }
        public Builder threadScaleDownDelayTicks(int v) { this.threadScaleDownDelayTicks = v; return this; }
        public Builder workStealingEnabled(boolean v) { this.workStealingEnabled = v; return this; }
        public Builder workStealingQueueSize(int v) { this.workStealingQueueSize = v; return this; }
        public Builder cpuAwareThreadSizing(boolean v) { this.cpuAwareThreadSizing = v; return this; }
        public Builder cpuLoadThreshold(double v) { this.cpuLoadThreshold = v; return this; }
        public Builder threadPoolKeepAliveSeconds(int v) { this.threadPoolKeepAliveSeconds = v; return this; }
        
        // Distance & processing optimization setters
        public Builder distanceCalculationInterval(int v) { this.distanceCalculationInterval = v; return this; }
        public Builder distanceApproximationEnabled(boolean v) { this.distanceApproximationEnabled = v; return this; }
        public Builder distanceCacheSize(int v) { this.distanceCacheSize = v; return this; }
        public Builder itemProcessingIntervalMultiplier(int v) { this.itemProcessingIntervalMultiplier = v; return this; }
        public Builder spatialGridUpdateInterval(int v) { this.spatialGridUpdateInterval = v; return this; }
        public Builder incrementalSpatialUpdates(boolean v) { this.incrementalSpatialUpdates = v; return this; }
        
        public PerformanceConfiguration build() {
            return new PerformanceConfiguration(this);
        }
    }
}