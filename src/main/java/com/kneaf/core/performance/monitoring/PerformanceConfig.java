package com.kneaf.core.performance.monitoring;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Comprehensive performance configuration with dynamic loading, validation, and hot-reloading capabilities.
 * Supports configuration files, environment variables, and runtime updates.
 */
public class PerformanceConfig {
    private static final String CONFIG_FILE = "config/kneaf-performance.properties";
    private static final String ULTRA_CONFIG_FILE = "config/kneaf-performance-ultra.properties";
    private static final String EXTREME_CONFIG_FILE = "config/kneaf-performance-extreme.properties";

    // Configuration properties with atomic updates for thread safety
    private final AtomicInteger threadpoolSize = new AtomicInteger(4);
    private final AtomicInteger logIntervalTicks = new AtomicInteger(20);
    private final AtomicInteger scanIntervalTicks = new AtomicInteger(10);
    private final AtomicReference<Double> tpsThresholdForAsync = new AtomicReference<>(15.0);
    private final AtomicInteger maxEntitiesToCollect = new AtomicInteger(1000);
    private final AtomicInteger maxLogBytes = new AtomicInteger(1048576); // 1MB
    private final AtomicInteger networkExecutorpoolSize = new AtomicInteger(2);
    private final AtomicInteger profilingSampleRate = new AtomicInteger(100);
    private final AtomicInteger minThreadpoolSize = new AtomicInteger(1);
    private final AtomicInteger maxThreadpoolSize = new AtomicInteger(16);
    private final AtomicReference<Double> threadScaleUpThreshold = new AtomicReference<>(0.8);
    private final AtomicReference<Double> threadScaleDownThreshold = new AtomicReference<>(0.2);
    private final AtomicInteger threadScaleUpDelayTicks = new AtomicInteger(100);
    private final AtomicInteger threadScaleDownDelayTicks = new AtomicInteger(200);
    private final AtomicInteger workStealingQueueSize = new AtomicInteger(100);
    private final AtomicReference<Double> cpuLoadThreshold = new AtomicReference<>(0.9);
    private final AtomicInteger threadPoolKeepAliveSeconds = new AtomicInteger(60);
    private final AtomicInteger distanceCalculationInterval = new AtomicInteger(10);
    private final AtomicInteger distanceCacheSize = new AtomicInteger(10000);
    private final AtomicInteger itemProcessingIntervalMultiplier = new AtomicInteger(2);
    private final AtomicInteger spatialGridUpdateInterval = new AtomicInteger(20);
    private final AtomicInteger jniMinimumBatchSize = new AtomicInteger(32);
    private final AtomicBoolean profilingEnabled = new AtomicBoolean(false);

    // Configuration change listeners
    private final List<Consumer<PerformanceConfig>> changeListeners = new CopyOnWriteArrayList<>();

    // File watcher for hot-reloading
    private final ScheduledExecutorService configWatcher = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "performance-config-watcher");
            t.setDaemon(true);
            return t;
        }
    );

    private volatile long lastModified = 0;
    private volatile boolean autoReload = true;

    public PerformanceConfig() {
        loadDefaults();
        loadFromFile();
        startFileWatcher();
    }

    /**
     * Load default configuration values optimized for typical server loads.
     */
    private void loadDefaults() {
        // Adaptive defaults based on available CPU cores
        int cores = Runtime.getRuntime().availableProcessors();
        threadpoolSize.set(Math.max(2, cores / 2));
        maxThreadpoolSize.set(Math.max(4, cores * 2));
        networkExecutorpoolSize.set(Math.max(1, cores / 4));

        // Memory-based defaults
        long maxMemoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        maxEntitiesToCollect.set((int) Math.min(5000, maxMemoryMB / 10));
        distanceCacheSize.set((int) Math.min(50000, maxMemoryMB * 10));
    }

    /**
     * Load configuration from properties files with priority order.
     */
    private void loadFromFile() {
        Properties props = new Properties();

        // Load base configuration
        loadPropertiesFile(CONFIG_FILE, props);

        // Load ultra configuration if exists (overrides base)
        if (Files.exists(Paths.get(ULTRA_CONFIG_FILE))) {
            loadPropertiesFile(ULTRA_CONFIG_FILE, props);
        }

        // Load extreme configuration if exists (highest priority)
        if (Files.exists(Paths.get(EXTREME_CONFIG_FILE))) {
            loadPropertiesFile(EXTREME_CONFIG_FILE, props);
        }

        // Load environment variable overrides
        loadEnvironmentOverrides(props);

        // Apply loaded properties
        applyProperties(props);
    }

    private void loadPropertiesFile(String filePath, Properties props) {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            try (InputStream is = Files.newInputStream(path)) {
                props.load(is);
                lastModified = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                System.err.println("Failed to load performance config from " + filePath + ": " + e.getMessage());
            }
        }
    }

    private void loadEnvironmentOverrides(Properties props) {
        // Environment variable overrides with KNEAF_ prefix
        Map<String, String> env = System.getenv();
        env.forEach((key, value) -> {
            if (key.startsWith("KNEAF_")) {
                String configKey = key.substring(6).toLowerCase().replace('_', '.');
                props.setProperty(configKey, value);
            }
        });
    }

    private void applyProperties(Properties props) {
        // Apply all properties with validation
        setProperty(props, "threadpool.size", threadpoolSize, 1, 64);
        setProperty(props, "log.interval.ticks", logIntervalTicks, 1, 1200);
        setProperty(props, "scan.interval.ticks", scanIntervalTicks, 1, 200);
        setProperty(props, "tps.threshold.async", tpsThresholdForAsync, 0.0, 20.0);
        setProperty(props, "max.entities.collect", maxEntitiesToCollect, 10, 10000);
        setProperty(props, "max.log.bytes", maxLogBytes, 1024, 10485760); // 1KB to 10MB
        setProperty(props, "network.executor.pool.size", networkExecutorpoolSize, 1, 16);
        setProperty(props, "profiling.sample.rate", profilingSampleRate, 1, 1000);
        setProperty(props, "threadpool.min.size", minThreadpoolSize, 1, 32);
        setProperty(props, "threadpool.max.size", maxThreadpoolSize, 2, 128);
        setProperty(props, "thread.scale.up.threshold", threadScaleUpThreshold, 0.1, 0.95);
        setProperty(props, "thread.scale.down.threshold", threadScaleDownThreshold, 0.05, 0.8);
        setProperty(props, "thread.scale.up.delay.ticks", threadScaleUpDelayTicks, 10, 600);
        setProperty(props, "thread.scale.down.delay.ticks", threadScaleDownDelayTicks, 20, 1200);
        setProperty(props, "work.stealing.queue.size", workStealingQueueSize, 10, 1000);
        setProperty(props, "cpu.load.threshold", cpuLoadThreshold, 0.1, 0.95);
        setProperty(props, "thread.pool.keep.alive.seconds", threadPoolKeepAliveSeconds, 10, 300);
        setProperty(props, "distance.calculation.interval", distanceCalculationInterval, 1, 100);
        setProperty(props, "distance.cache.size", distanceCacheSize, 100, 100000);
        setProperty(props, "item.processing.interval.multiplier", itemProcessingIntervalMultiplier, 1, 10);
        setProperty(props, "spatial.grid.update.interval", spatialGridUpdateInterval, 1, 200);
        setProperty(props, "jni.minimum.batch.size", jniMinimumBatchSize, 8, 1024);
        setProperty(props, "profiling.enabled", profilingEnabled);

        // Notify listeners of configuration changes
        notifyChangeListeners();
    }

    private void setProperty(Properties props, String key, AtomicInteger target, int min, int max) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                int intValue = Integer.parseInt(value);
                target.set(Math.max(min, Math.min(max, intValue)));
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer value for " + key + ": " + value);
            }
        }
    }

    private void setProperty(Properties props, String key, AtomicReference<Double> target, double min, double max) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                double doubleValue = Double.parseDouble(value);
                target.set(Math.max(min, Math.min(max, doubleValue)));
            } catch (NumberFormatException e) {
                System.err.println("Invalid double value for " + key + ": " + value);
            }
        }
    }

    private void setProperty(Properties props, String key, AtomicBoolean target) {
        String value = props.getProperty(key);
        if (value != null) {
            target.set(Boolean.parseBoolean(value));
        }
    }

    /**
     * Start file watcher for hot-reloading configuration.
     */
    private void startFileWatcher() {
        configWatcher.scheduleWithFixedDelay(() -> {
            if (!autoReload) return;

            try {
                Path configPath = Paths.get(CONFIG_FILE);
                if (Files.exists(configPath)) {
                    long currentModified = Files.getLastModifiedTime(configPath).toMillis();
                    if (currentModified > lastModified) {
                        System.out.println("Performance configuration changed, reloading...");
                        loadFromFile();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error checking config file modification: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Add a listener for configuration changes.
     */
    public void addChangeListener(Consumer<PerformanceConfig> listener) {
        changeListeners.add(listener);
    }

    /**
     * Remove a configuration change listener.
     */
    public void removeChangeListener(Consumer<PerformanceConfig> listener) {
        changeListeners.remove(listener);
    }

    /**
     * Notify all listeners of configuration changes.
     */
    private void notifyChangeListeners() {
        for (Consumer<PerformanceConfig> listener : changeListeners) {
            try {
                listener.accept(this);
            } catch (Exception e) {
                System.err.println("Error notifying config change listener: " + e.getMessage());
            }
        }
    }

    /**
     * Save current configuration to file.
     */
    public void saveToFile(String filePath) throws IOException {
        Properties props = new Properties();

        // Collect all current values
        props.setProperty("threadpool.size", String.valueOf(threadpoolSize.get()));
        props.setProperty("log.interval.ticks", String.valueOf(logIntervalTicks.get()));
        props.setProperty("scan.interval.ticks", String.valueOf(scanIntervalTicks.get()));
        props.setProperty("tps.threshold.async", String.valueOf(tpsThresholdForAsync.get()));
        props.setProperty("max.entities.collect", String.valueOf(maxEntitiesToCollect.get()));
        props.setProperty("max.log.bytes", String.valueOf(maxLogBytes.get()));
        props.setProperty("network.executor.pool.size", String.valueOf(networkExecutorpoolSize.get()));
        props.setProperty("profiling.sample.rate", String.valueOf(profilingSampleRate.get()));
        props.setProperty("threadpool.min.size", String.valueOf(minThreadpoolSize.get()));
        props.setProperty("threadpool.max.size", String.valueOf(maxThreadpoolSize.get()));
        props.setProperty("thread.scale.up.threshold", String.valueOf(threadScaleUpThreshold.get()));
        props.setProperty("thread.scale.down.threshold", String.valueOf(threadScaleDownThreshold.get()));
        props.setProperty("thread.scale.up.delay.ticks", String.valueOf(threadScaleUpDelayTicks.get()));
        props.setProperty("thread.scale.down.delay.ticks", String.valueOf(threadScaleDownDelayTicks.get()));
        props.setProperty("work.stealing.queue.size", String.valueOf(workStealingQueueSize.get()));
        props.setProperty("cpu.load.threshold", String.valueOf(cpuLoadThreshold.get()));
        props.setProperty("thread.pool.keep.alive.seconds", String.valueOf(threadPoolKeepAliveSeconds.get()));
        props.setProperty("distance.calculation.interval", String.valueOf(distanceCalculationInterval.get()));
        props.setProperty("distance.cache.size", String.valueOf(distanceCacheSize.get()));
        props.setProperty("item.processing.interval.multiplier", String.valueOf(itemProcessingIntervalMultiplier.get()));
        props.setProperty("spatial.grid.update.interval", String.valueOf(spatialGridUpdateInterval.get()));
        props.setProperty("jni.minimum.batch.size", String.valueOf(jniMinimumBatchSize.get()));
        props.setProperty("profiling.enabled", String.valueOf(profilingEnabled.get()));

        // Save to file
        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());
        try (OutputStream os = Files.newOutputStream(path)) {
            props.store(os, "Kneaf Performance Configuration - Generated on " + new Date());
        }
    }

    /**
     * Enable or disable automatic configuration reloading.
     */
    public void setAutoReload(boolean enabled) {
        this.autoReload = enabled;
    }

    /**
     * Manually reload configuration from files.
     */
    public void reload() {
        loadFromFile();
    }

    /**
     * Shutdown the configuration watcher.
     */
    public void shutdown() {
        configWatcher.shutdown();
        try {
            if (!configWatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                configWatcher.shutdownNow();
            }
        } catch (InterruptedException e) {
            configWatcher.shutdownNow();
        }
    }

    // Getter methods with current values
    public int getThreadpoolSize() { return threadpoolSize.get(); }
    public int getLogIntervalTicks() { return logIntervalTicks.get(); }
    public int getScanIntervalTicks() { return scanIntervalTicks.get(); }
    public double getTpsThresholdForAsync() { return tpsThresholdForAsync.get(); }
    public int getMaxEntitiesToCollect() { return maxEntitiesToCollect.get(); }
    public int getMaxLogBytes() { return maxLogBytes.get(); }
    public int getNetworkExecutorpoolSize() { return networkExecutorpoolSize.get(); }
    public int getProfilingSampleRate() { return profilingSampleRate.get(); }
    public int getMinThreadpoolSize() { return minThreadpoolSize.get(); }
    public int getMaxThreadpoolSize() { return maxThreadpoolSize.get(); }
    public double getThreadScaleUpThreshold() { return threadScaleUpThreshold.get(); }
    public double getThreadScaleDownThreshold() { return threadScaleDownThreshold.get(); }
    public int getThreadScaleUpDelayTicks() { return threadScaleUpDelayTicks.get(); }
    public int getThreadScaleDownDelayTicks() { return threadScaleDownDelayTicks.get(); }
    public int getWorkStealingQueueSize() { return workStealingQueueSize.get(); }
    public double getCpuLoadThreshold() { return cpuLoadThreshold.get(); }
    public int getThreadPoolKeepAliveSeconds() { return threadPoolKeepAliveSeconds.get(); }
    public int getDistanceCalculationInterval() { return distanceCalculationInterval.get(); }
    public int getDistanceCacheSize() { return distanceCacheSize.get(); }
    public int getItemProcessingIntervalMultiplier() { return itemProcessingIntervalMultiplier.get(); }
    public int getSpatialGridUpdateInterval() { return spatialGridUpdateInterval.get(); }
    public int getJniMinimumBatchSize() { return jniMinimumBatchSize.get(); }
    public boolean isProfilingEnabled() { return profilingEnabled.get(); }

    // Static factory methods
    public static PerformanceConfig load() {
        return new PerformanceConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating PerformanceConfig instances with custom settings.
     */
    public static class Builder {
        private final Properties overrides = new Properties();

        public Builder threadpoolSize(int size) {
            overrides.setProperty("threadpool.size", String.valueOf(size));
            return this;
        }

        public Builder logIntervalTicks(int ticks) {
            overrides.setProperty("log.interval.ticks", String.valueOf(ticks));
            return this;
        }

        public Builder profilingEnabled(boolean enabled) {
            overrides.setProperty("profiling.enabled", String.valueOf(enabled));
            return this;
        }

        public Builder autoReload(boolean enabled) {
            // This would be stored and applied after construction
            return this;
        }

        public PerformanceConfig build() {
            PerformanceConfig config = new PerformanceConfig();
            config.applyProperties(overrides);
            return config;
        }
    }

    @Override
    public String toString() {
        return "PerformanceConfig{" +
                "threadpoolSize=" + threadpoolSize.get() +
                ", profilingEnabled=" + profilingEnabled.get() +
                ", maxEntitiesToCollect=" + maxEntitiesToCollect.get() +
                ", autoReload=" + autoReload +
                '}';
    }
}