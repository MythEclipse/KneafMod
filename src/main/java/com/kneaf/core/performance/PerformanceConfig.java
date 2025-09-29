package com.kneaf.core.performance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads runtime configuration for the performance manager from
 * config/kneaf-performance.properties if present. Falls back to sensible defaults.
 */
public final class PerformanceConfig {
    private static final String DEFAULT_CONFIG_PATH = "config/kneaf-performance.properties";
    private static final Logger LOGGER = Logger.getLogger(PerformanceConfig.class.getName());

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

    // Use a Builder to avoid long constructor parameter lists (satisfies java:S107)
    private PerformanceConfig(Builder b) {
        this.enabled = b.enabled;
        this.threadPoolSize = b.threadPoolSize;
        this.logIntervalTicks = b.logIntervalTicks;
        this.scanIntervalTicks = b.scanIntervalTicks;
        this.tpsThresholdForAsync = b.tpsThresholdForAsync;
        this.maxEntitiesToCollect = b.maxEntitiesToCollect;
        this.entityDistanceCutoff = b.entityDistanceCutoff;
        this.maxLogBytes = b.maxLogBytes;
        this.adaptiveThreadPool = b.adaptiveThreadPool;
        this.maxThreadPoolSize = b.maxThreadPoolSize;
        // Defensively copy the array to prevent external mutation after construction
        this.excludedEntityTypes = b.excludedEntityTypes == null ? new String[0] : b.excludedEntityTypes.clone();
        this.networkExecutorPoolSize = b.networkExecutorPoolSize;
        this.profilingEnabled = b.profilingEnabled;
        this.slowTickThresholdMs = b.slowTickThresholdMs;
        this.profilingSampleRate = b.profilingSampleRate;
        
        // Advanced parallelism configuration
        this.minThreadPoolSize = b.minThreadPoolSize;
        this.dynamicThreadScaling = b.dynamicThreadScaling;
        this.threadScaleUpThreshold = b.threadScaleUpThreshold;
        this.threadScaleDownThreshold = b.threadScaleDownThreshold;
        this.threadScaleUpDelayTicks = b.threadScaleUpDelayTicks;
        this.threadScaleDownDelayTicks = b.threadScaleDownDelayTicks;
        this.workStealingEnabled = b.workStealingEnabled;
        this.workStealingQueueSize = b.workStealingQueueSize;
        this.cpuAwareThreadSizing = b.cpuAwareThreadSizing;
        this.cpuLoadThreshold = b.cpuLoadThreshold;
        this.threadPoolKeepAliveSeconds = b.threadPoolKeepAliveSeconds;
    // Distance & processing optimizations
    this.distanceCalculationInterval = b.distanceCalculationInterval;
    this.distanceApproximationEnabled = b.distanceApproximationEnabled;
    this.distanceCacheSize = b.distanceCacheSize;
    this.itemProcessingIntervalMultiplier = b.itemProcessingIntervalMultiplier;
    this.spatialGridUpdateInterval = b.spatialGridUpdateInterval;
    this.incrementalSpatialUpdates = b.incrementalSpatialUpdates;
        
        // Validate configuration consistency
        validateConfiguration();
    }
    
    /**
     * Validate configuration parameters for consistency and performance.
     */
    private void validateConfiguration() {
        if (minThreadPoolSize > maxThreadPoolSize) {
            throw new IllegalArgumentException("minThreadPoolSize (" + minThreadPoolSize +
                                             ") cannot be greater than maxThreadPoolSize (" + maxThreadPoolSize + ")");
        }
        if (threadScaleUpThreshold <= threadScaleDownThreshold) {
            throw new IllegalArgumentException("threadScaleUpThreshold (" + threadScaleUpThreshold +
                                             ") must be greater than threadScaleDownThreshold (" + threadScaleDownThreshold + ")");
        }
        if (scanIntervalTicks < 1 || scanIntervalTicks > 100) {
            throw new IllegalArgumentException("scanIntervalTicks must be between 1 and 100, got: " + scanIntervalTicks);
        }
        if (tpsThresholdForAsync < 10.0 || tpsThresholdForAsync > 20.0) {
            throw new IllegalArgumentException("tpsThresholdForAsync must be between 10.0 and 20.0, got: " + tpsThresholdForAsync);
        }
    }

    /**
     * Builder for PerformanceConfig to improve readability and avoid long constructors.
     */
    public static final class Builder {
        private boolean enabled;
        private int threadPoolSize;
        private int logIntervalTicks;
        private int scanIntervalTicks;
        private double tpsThresholdForAsync;
        private int maxEntitiesToCollect;
        private double entityDistanceCutoff;
        private long maxLogBytes;
        private boolean adaptiveThreadPool;
        private int maxThreadPoolSize;
        private String[] excludedEntityTypes;
        private int networkExecutorPoolSize;
        private boolean profilingEnabled;
        private long slowTickThresholdMs;
        private int profilingSampleRate;
        
        // Advanced parallelism configuration
        private int minThreadPoolSize;
        private boolean dynamicThreadScaling;
        private double threadScaleUpThreshold;
        private double threadScaleDownThreshold;
        private int threadScaleUpDelayTicks;
        private int threadScaleDownDelayTicks;
        private boolean workStealingEnabled;
        private int workStealingQueueSize;
        private boolean cpuAwareThreadSizing;
        private double cpuLoadThreshold;
        private int threadPoolKeepAliveSeconds;
    // Distance & processing optimizations
    private int distanceCalculationInterval;
    private boolean distanceApproximationEnabled;
    private int distanceCacheSize;
    private int itemProcessingIntervalMultiplier;
    private int spatialGridUpdateInterval;
    private boolean incrementalSpatialUpdates;

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
    
    // Advanced parallelism configuration
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

        public PerformanceConfig build() {
            // Apply same defensive constraints as before and build a config using this Builder
            boolean vEnabled = this.enabled;
            int vThreadPoolSize = Math.max(1, this.threadPoolSize);
            int vLogIntervalTicks = Math.max(1, this.logIntervalTicks);
            int vScanIntervalTicks = Math.max(1, this.scanIntervalTicks);
            double vTpsThresholdForAsync = this.tpsThresholdForAsync;
            int vMaxEntitiesToCollect = Math.max(1, this.maxEntitiesToCollect);
            double vEntityDistanceCutoff = Math.max(0.0, this.entityDistanceCutoff);
            long vMaxLogBytes = Math.max(0L, this.maxLogBytes);
            boolean vAdaptiveThreadPool = this.adaptiveThreadPool;
            int vMaxThreadPoolSize = Math.max(1, this.maxThreadPoolSize);
            String[] vExcludedEntityTypes = this.excludedEntityTypes == null ? new String[0] : this.excludedEntityTypes;
            int vNetworkExecutorPoolSize = Math.max(1, this.networkExecutorPoolSize);
            boolean vProfilingEnabled = this.profilingEnabled;
            long vSlowTickThresholdMs = Math.max(1L, this.slowTickThresholdMs);
            int vProfilingSampleRate = Math.max(1, this.profilingSampleRate);

                // Distance & processing validation/defaulting
                int vDistanceCalculationInterval = Math.max(1, this.distanceCalculationInterval);
                boolean vDistanceApproximationEnabled = this.distanceApproximationEnabled;
                int vDistanceCacheSize = Math.max(100, this.distanceCacheSize);
                int vItemProcessingIntervalMultiplier = Math.max(1, this.itemProcessingIntervalMultiplier);
                int vSpatialGridUpdateInterval = Math.max(1, this.spatialGridUpdateInterval);
                boolean vIncrementalSpatialUpdates = this.incrementalSpatialUpdates;

        Builder validated = new Builder();
        validated.enabled(vEnabled)
                    .threadPoolSize(vThreadPoolSize)
                    .logIntervalTicks(vLogIntervalTicks)
                    .scanIntervalTicks(vScanIntervalTicks)
                    .tpsThresholdForAsync(vTpsThresholdForAsync)
                    .maxEntitiesToCollect(vMaxEntitiesToCollect)
                    .entityDistanceCutoff(vEntityDistanceCutoff)
                    .maxLogBytes(vMaxLogBytes)
                    .adaptiveThreadPool(vAdaptiveThreadPool)
                    .maxThreadPoolSize(vMaxThreadPoolSize)
            .excludedEntityTypes(vExcludedEntityTypes)
            .networkExecutorPoolSize(vNetworkExecutorPoolSize)
            .profilingEnabled(vProfilingEnabled)
            .slowTickThresholdMs(vSlowTickThresholdMs)
            .profilingSampleRate(vProfilingSampleRate);
        // Apply distance & processing validated values
        validated.distanceCalculationInterval(vDistanceCalculationInterval)
            .distanceApproximationEnabled(vDistanceApproximationEnabled)
            .distanceCacheSize(vDistanceCacheSize)
            .itemProcessingIntervalMultiplier(vItemProcessingIntervalMultiplier)
            .spatialGridUpdateInterval(vSpatialGridUpdateInterval)
            .incrementalSpatialUpdates(vIncrementalSpatialUpdates);
            
            // Advanced parallelism configuration validation
            int vMinThreadPoolSize = Math.max(1, this.minThreadPoolSize);
            boolean vDynamicThreadScaling = this.dynamicThreadScaling;
            double vThreadScaleUpThreshold = Math.max(0.1, Math.min(1.0, this.threadScaleUpThreshold));
            double vThreadScaleDownThreshold = Math.max(0.1, Math.min(1.0, this.threadScaleDownThreshold));
            int vThreadScaleUpDelayTicks = Math.max(1, this.threadScaleUpDelayTicks);
            int vThreadScaleDownDelayTicks = Math.max(1, this.threadScaleDownDelayTicks);
            boolean vWorkStealingEnabled = this.workStealingEnabled;
            int vWorkStealingQueueSize = Math.max(1, this.workStealingQueueSize);
            boolean vCpuAwareThreadSizing = this.cpuAwareThreadSizing;
            double vCpuLoadThreshold = Math.max(0.1, Math.min(1.0, this.cpuLoadThreshold));
            int vThreadPoolKeepAliveSeconds = Math.max(1, this.threadPoolKeepAliveSeconds);
            
            validated.minThreadPoolSize(vMinThreadPoolSize)
                    .dynamicThreadScaling(vDynamicThreadScaling)
                    .threadScaleUpThreshold(vThreadScaleUpThreshold)
                    .threadScaleDownThreshold(vThreadScaleDownThreshold)
                    .threadScaleUpDelayTicks(vThreadScaleUpDelayTicks)
                    .threadScaleDownDelayTicks(vThreadScaleDownDelayTicks)
                    .workStealingEnabled(vWorkStealingEnabled)
                    .workStealingQueueSize(vWorkStealingQueueSize)
                    .cpuAwareThreadSizing(vCpuAwareThreadSizing)
                    .cpuLoadThreshold(vCpuLoadThreshold)
                    .threadPoolKeepAliveSeconds(vThreadPoolKeepAliveSeconds);
            return new PerformanceConfig(validated);
        }

        // Default constructor for Builder. Initialize sensible defaults so build() can be used directly.
        public Builder() {
            this.enabled = true;
            this.threadPoolSize = 4;
            this.logIntervalTicks = 100;
            this.scanIntervalTicks = 1;
            this.tpsThresholdForAsync = 19.0;
            this.maxEntitiesToCollect = 20000;
            this.entityDistanceCutoff = 256.0;
            this.maxLogBytes = 10L * 1024 * 1024;
            this.adaptiveThreadPool = false;
            this.maxThreadPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            this.excludedEntityTypes = new String[0];
            // Default network executor pool size: half of available processors (minimum 1)
            this.networkExecutorPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            // Default profiling settings
            this.profilingEnabled = true;
            this.slowTickThresholdMs = 50L;
            this.profilingSampleRate = 100; // 1% sampling rate (1 out of 100 ticks)
            
        // Advanced parallelism defaults
        this.minThreadPoolSize = 2;
        this.dynamicThreadScaling = true;
        this.threadScaleUpThreshold = 0.8;
        this.threadScaleDownThreshold = 0.3;
        this.threadScaleUpDelayTicks = 100;
        this.threadScaleDownDelayTicks = 200;
        this.workStealingEnabled = true;
        this.workStealingQueueSize = 100;
        // Advanced parallelism configuration validation will be applied in build()
        int vThreadPoolKeepAliveSeconds = Math.max(1, this.threadPoolKeepAliveSeconds);

        // Distance calculation optimization defaults
        this.distanceCalculationInterval = 1;
        this.distanceApproximationEnabled = true;
        this.distanceCacheSize = 100;

        // Item processing optimization defaults
        this.itemProcessingIntervalMultiplier = 1;

        // Spatial grid optimization defaults
        this.spatialGridUpdateInterval = 1;
        this.incrementalSpatialUpdates = true;
        }
    }

    public boolean isEnabled() { return enabled; }
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

    public static PerformanceConfig load() {
        Properties props = new Properties();
        Path path = Paths.get(DEFAULT_CONFIG_PATH);
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "Failed to read performance config at {0}, using defaults", path);
                    LOGGER.log(Level.FINE, "Exception reading performance config", e);
                }
            }
        }

    boolean enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
        int threadPoolSize = parseIntOrDefault(props.getProperty("threadPoolSize"), 4);
        int logIntervalTicks = parseIntOrDefault(props.getProperty("logIntervalTicks"), 100);
        int scanIntervalTicks = parseIntOrDefault(props.getProperty("scanIntervalTicks"), 1);
        double tpsThresholdForAsync = parseDoubleOrDefault(props.getProperty("tpsThresholdForAsync"), 19.0);
        int maxEntitiesToCollect = parseIntOrDefault(props.getProperty("maxEntitiesToCollect"), 20000);
        double entityDistanceCutoff = parseDoubleOrDefault(props.getProperty("entityDistanceCutoff"), 256.0);
        boolean adaptiveThreadPool = Boolean.parseBoolean(props.getProperty("adaptiveThreadPool", "false"));
        int maxThreadPoolSize = parseIntOrDefault(props.getProperty("maxThreadPoolSize"), Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        String excluded = props.getProperty("excludedEntityTypes", "");
        String[] excludedEntityTypes = excluded.isBlank() ? new String[0] : excluded.split("\\s*,\\s*");
        int networkExecutorPoolSize = parseIntOrDefault(props.getProperty("networkExecutorPoolSize"), Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        boolean profilingEnabled = Boolean.parseBoolean(props.getProperty("profilingEnabled", "true"));
        long slowTickThresholdMs = parseLongOrDefault(props.getProperty("slowTickThresholdMs"), 50L);
        int profilingSampleRate = parseIntOrDefault(props.getProperty("profilingSampleRate"), 1);

    long maxLogBytes = parseLongOrDefault(props.getProperty("maxLogBytes"), 10L * 1024 * 1024); // 10MB default

    // Build the validated PerformanceConfig via Builder to avoid long constructors
    Builder b = new Builder()
        .enabled(enabled)
        .threadPoolSize(threadPoolSize)
        .logIntervalTicks(logIntervalTicks)
        .scanIntervalTicks(scanIntervalTicks)
        .tpsThresholdForAsync(tpsThresholdForAsync)
        .maxEntitiesToCollect(maxEntitiesToCollect)
        .entityDistanceCutoff(entityDistanceCutoff)
        .maxLogBytes(maxLogBytes)
        .adaptiveThreadPool(adaptiveThreadPool)
        .maxThreadPoolSize(maxThreadPoolSize)
    .excludedEntityTypes(excludedEntityTypes)
    .networkExecutorPoolSize(networkExecutorPoolSize)
    .profilingEnabled(profilingEnabled)
    .slowTickThresholdMs(slowTickThresholdMs)
    .profilingSampleRate(profilingSampleRate);

    return b.build();
    }

    private static long parseLongOrDefault(String v, long def) {
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static int parseIntOrDefault(String v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDoubleOrDefault(String v, double def) {
        if (v == null) return def;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public String toString() {
        return "PerformanceConfig{" +
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
}
