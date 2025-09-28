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
                    .excludedEntityTypes(vExcludedEntityTypes);
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
        .excludedEntityTypes(excludedEntityTypes);

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
                '}';
    }
}
