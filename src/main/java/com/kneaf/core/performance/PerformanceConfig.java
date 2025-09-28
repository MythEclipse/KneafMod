package com.kneaf.core.performance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads runtime configuration for the performance manager from
 * config/kneaf-performance.properties if present. Falls back to sensible defaults.
 */
public final class PerformanceConfig {
    private static final String DEFAULT_CONFIG_PATH = "config/kneaf-performance.properties";

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

    private PerformanceConfig(boolean enabled, int threadPoolSize, int logIntervalTicks, int scanIntervalTicks, double tpsThresholdForAsync, int maxEntitiesToCollect, double entityDistanceCutoff, long maxLogBytes, boolean adaptiveThreadPool, int maxThreadPoolSize, String[] excludedEntityTypes) {
        this.enabled = enabled;
        this.threadPoolSize = threadPoolSize;
        this.logIntervalTicks = logIntervalTicks;
        this.scanIntervalTicks = scanIntervalTicks;
        this.tpsThresholdForAsync = tpsThresholdForAsync;
        this.maxEntitiesToCollect = maxEntitiesToCollect;
        this.entityDistanceCutoff = entityDistanceCutoff;
        this.maxLogBytes = maxLogBytes;
        this.adaptiveThreadPool = adaptiveThreadPool;
        this.maxThreadPoolSize = maxThreadPoolSize;
        this.excludedEntityTypes = excludedEntityTypes;
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
                // ignore and use defaults
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

        return new PerformanceConfig(enabled, Math.max(1, threadPoolSize), Math.max(1, logIntervalTicks), Math.max(1, scanIntervalTicks), tpsThresholdForAsync, Math.max(1, maxEntitiesToCollect), Math.max(0.0, entityDistanceCutoff), Math.max(0L, maxLogBytes), adaptiveThreadPool, Math.max(1, maxThreadPoolSize), excludedEntityTypes);
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
}
