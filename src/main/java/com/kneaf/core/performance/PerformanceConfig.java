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
    private final double tpsThresholdForAsync;

    private PerformanceConfig(boolean enabled, int threadPoolSize, int logIntervalTicks, double tpsThresholdForAsync) {
        this.enabled = enabled;
        this.threadPoolSize = threadPoolSize;
        this.logIntervalTicks = logIntervalTicks;
        this.tpsThresholdForAsync = tpsThresholdForAsync;
    }

    public boolean isEnabled() { return enabled; }
    public int getThreadPoolSize() { return threadPoolSize; }
    public int getLogIntervalTicks() { return logIntervalTicks; }
    public double getTpsThresholdForAsync() { return tpsThresholdForAsync; }

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
        double tpsThresholdForAsync = parseDoubleOrDefault(props.getProperty("tpsThresholdForAsync"), 19.0);

        return new PerformanceConfig(enabled, Math.max(1, threadPoolSize), Math.max(1, logIntervalTicks), tpsThresholdForAsync);
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
