package com.kneaf.core.performance.unified;

import com.kneaf.core.config.UnifiedPerformanceConfig;
import com.kneaf.core.performance.RustPerformance;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full implementation of unified performance management system.
 * Provides comprehensive monitoring, optimization, and adaptive performance controls.
 */
public class PerformanceManager {
    private static final Logger LOGGER = Logger.getLogger(PerformanceManager.class.getName());
    private static final PerformanceManager instance = new PerformanceManager();

    // Core monitoring components
    private final TPSMonitor tpsMonitor;
    private final MemoryMonitor memoryMonitor;
    private final CPUMonitor cpuMonitor;
    private final ChunkMonitor chunkMonitor;

    // Adaptive optimization
    private final AdaptiveOptimizer optimizer;
    private final UnifiedPerformanceConfig config;

    // Management state
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean adaptiveMode = new AtomicBoolean(true);

    // Monitoring threads
    private final ScheduledExecutorService monitorScheduler = Executors.newScheduledThreadPool(
        4, r -> {
            Thread t = new Thread(r, "performance-monitor-" + r.hashCode());
            t.setDaemon(true);
            return t;
        }
    );

    // Performance metrics storage
    private final ConcurrentHashMap<String, PerformanceMetrics> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CustomMetric> customMetrics = new ConcurrentHashMap<>();
    private final Deque<PerformanceSnapshot> history = new ConcurrentLinkedDeque<>();

    // Thresholds and alerts
    private final Map<String, Threshold> thresholds = new ConcurrentHashMap<>();
    private final List<PerformanceAlert> activeAlerts = new CopyOnWriteArrayList<>();

    private PerformanceManager() {
        this.tpsMonitor = new TPSMonitor();
        this.memoryMonitor = new MemoryMonitor();
        this.cpuMonitor = new CPUMonitor();
        this.chunkMonitor = new ChunkMonitor();
        this.optimizer = new AdaptiveOptimizer();
        this.config = UnifiedPerformanceConfig.builder().build(); // Use default config

        initializeDefaultThresholds();
        startMonitoring();
    }

    public static PerformanceManager getInstance() {
        return instance;
    }

    /**
     * Enable performance monitoring and optimization.
     */
    public void enable() {
        if (enabled.compareAndSet(false, true)) {
            LOGGER.info("Enabling unified performance management");
            // Initialize Rust performance system
            try {
                RustPerformance.initialize();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to initialize Rust performance system, continuing without it", e);
            }
            startMonitoring();
            optimizer.enable();
        }
    }

    /**
     * Disable performance monitoring and optimization.
     */
    public void disable() {
        if (enabled.compareAndSet(true, false)) {
            LOGGER.info("Disabling unified performance management");
            stopMonitoring();
            optimizer.disable();
        }
    }

    /**
     * Check if performance management is enabled.
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Enable or disable adaptive optimization mode.
     */
    public void setAdaptiveMode(boolean adaptive) {
        adaptiveMode.set(adaptive);
        if (adaptive) {
            optimizer.enable();
        } else {
            optimizer.disable();
        }
    }

    /**
     * Get the TPS monitor.
     */
    public TPSMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    /**
     * Get current performance metrics.
     */
    public PerformanceMetrics getCurrentMetrics() {
        return new PerformanceMetrics(
            RustPerformance.getCurrentTPS(),
            memoryMonitor.getUsedMemoryMB(),
            memoryMonitor.getTotalMemoryMB(),
            cpuMonitor.getCpuUsage(),
            chunkMonitor.getLoadedChunks(),
            chunkMonitor.getEntitiesInChunks(),
            System.currentTimeMillis()
        );
    }

    /**
     * Get performance metrics for a specific component.
     */
    public PerformanceMetrics getMetrics(String component) {
        return metrics.get(component);
    }

    /**
     * Set a performance threshold for alerting.
     */
    public void setThreshold(String metric, double warningThreshold, double criticalThreshold) {
        thresholds.put(metric, new Threshold(warningThreshold, criticalThreshold));
    }

    /**
     * Get active performance alerts.
     */
    public List<PerformanceAlert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts);
    }

    /**
     * Register a custom performance metric.
     */
    public void registerMetric(String name, java.util.function.Supplier<Long> valueSupplier) {
        customMetrics.put(name, new CustomMetric(name, valueSupplier));
        LOGGER.fine("Registered custom performance metric: " + name);
    }

    /**
     * Get the current value of a custom metric.
     */
    public long getCustomMetricValue(String name) {
        CustomMetric metric = customMetrics.get(name);
        return metric != null ? metric.getCurrentValue() : 0;
    }

    /**
     * Force garbage collection and memory optimization.
     */
    public void optimizeMemory() {
        LOGGER.info("Forcing garbage collection and memory optimization");
        System.gc();

        // Trigger native memory optimization
        try {
            boolean nativeOptimized = RustPerformance.optimizeMemory();
            if (nativeOptimized) {
                LOGGER.info("Native memory optimization completed successfully");
            } else {
                LOGGER.info("Native memory optimization not available or failed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to optimize native memory", e);
        }
    }

    /**
     * Optimize chunk loading and entity processing.
     */
    public void optimizeChunks() {
        LOGGER.info("Optimizing chunk loading and processing");

        try {
            boolean nativeOptimized = RustPerformance.optimizeChunks();
            if (nativeOptimized) {
                LOGGER.info("Native chunk optimization completed successfully");
            } else {
                LOGGER.info("Native chunk optimization not available or failed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to optimize chunks", e);
        }
    }

    /**
     * Get performance optimization recommendations.
     */
    public List<String> getOptimizationRecommendations() {
        List<String> recommendations = new ArrayList<>();
        PerformanceMetrics current = getCurrentMetrics();

        if (current.memoryUsagePercent > 85) {
            recommendations.add("High memory usage detected. Consider increasing heap size or optimizing memory allocation.");
        }

        if (current.cpuUsage > 90) {
            recommendations.add("High CPU usage detected. Consider optimizing CPU-intensive operations or increasing thread pool sizes.");
        }

        if (current.tps < 15) {
            recommendations.add("Low TPS detected. Consider enabling performance optimizations or reducing server load.");
        }

        if (current.loadedChunks > 10000) {
            recommendations.add("High chunk count detected. Consider implementing chunk unloading or view distance optimization.");
        }

        return recommendations;
    }

    /**
     * Take a performance snapshot for historical analysis.
     */
    public PerformanceSnapshot takeSnapshot() {
        PerformanceSnapshot snapshot = new PerformanceSnapshot(getCurrentMetrics());
        history.addFirst(snapshot);

        // Keep only last 100 snapshots
        while (history.size() > 100) {
            history.removeLast();
        }

        return snapshot;
    }

    /**
     * Get performance history.
     */
    public List<PerformanceSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Export performance data to a map for serialization.
     */
    public Map<String, Object> exportData() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", enabled.get());
        data.put("adaptiveMode", adaptiveMode.get());
        data.put("currentMetrics", getCurrentMetrics());
        data.put("thresholds", new HashMap<>(thresholds));
        data.put("activeAlerts", new ArrayList<>(activeAlerts));
        data.put("history", new ArrayList<>(history));
        return data;
    }

    private void initializeDefaultThresholds() {
        // TPS thresholds
        setThreshold("tps", 15.0, 10.0);

        // Memory thresholds (percentage)
        setThreshold("memoryPercent", 80.0, 90.0);

        // CPU thresholds (percentage)
        setThreshold("cpuPercent", 80.0, 95.0);

        // Chunk thresholds
        setThreshold("loadedChunks", 8000.0, 12000.0);
    }

    private void startMonitoring() {
        // TPS monitoring - every second
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                // TPS is now handled by RustPerformance.getCurrentTPS()
                // Update custom metrics
                for (CustomMetric metric : customMetrics.values()) {
                    metric.update();
                }
                checkThresholds();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in TPS monitoring", e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Memory monitoring - every 5 seconds
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                memoryMonitor.updateMemoryStats();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in memory monitoring", e);
            }
        }, 0, 5, TimeUnit.SECONDS);

        // CPU monitoring - every 10 seconds
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                cpuMonitor.updateCpuStats();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in CPU monitoring", e);
            }
        }, 0, 10, TimeUnit.SECONDS);

        // Chunk monitoring - every 30 seconds
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                chunkMonitor.updateChunkStats();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in chunk monitoring", e);
            }
        }, 0, 30, TimeUnit.SECONDS);

        // Adaptive optimization - every minute
        monitorScheduler.scheduleAtFixedRate(() -> {
            if (adaptiveMode.get()) {
                try {
                    optimizer.optimize();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in adaptive optimization", e);
                }
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private void stopMonitoring() {
        monitorScheduler.shutdown();
        try {
            if (!monitorScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                monitorScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void checkThresholds() {
        PerformanceMetrics current = getCurrentMetrics();

        // Check TPS
        Threshold tpsThreshold = thresholds.get("tps");
        if (tpsThreshold != null) {
            if (current.tps <= tpsThreshold.critical) {
                addAlert("CRITICAL", "TPS critically low: " + current.tps);
            } else if (current.tps <= tpsThreshold.warning) {
                addAlert("WARNING", "TPS low: " + current.tps);
            }
        }

        // Check memory
        Threshold memThreshold = thresholds.get("memoryPercent");
        if (memThreshold != null) {
            if (current.memoryUsagePercent >= memThreshold.critical) {
                addAlert("CRITICAL", "Memory usage critically high: " + current.memoryUsagePercent + "%");
            } else if (current.memoryUsagePercent >= memThreshold.warning) {
                addAlert("WARNING", "Memory usage high: " + current.memoryUsagePercent + "%");
            }
        }

        // Check CPU
        Threshold cpuThreshold = thresholds.get("cpuPercent");
        if (cpuThreshold != null) {
            if (current.cpuUsage >= cpuThreshold.critical) {
                addAlert("CRITICAL", "CPU usage critically high: " + current.cpuUsage + "%");
            } else if (current.cpuUsage >= cpuThreshold.warning) {
                addAlert("WARNING", "CPU usage high: " + current.cpuUsage + "%");
            }
        }

        // Check chunks
        Threshold chunkThreshold = thresholds.get("loadedChunks");
        if (chunkThreshold != null) {
            if (current.loadedChunks >= chunkThreshold.critical) {
                addAlert("CRITICAL", "Loaded chunks critically high: " + current.loadedChunks);
            } else if (current.loadedChunks >= chunkThreshold.warning) {
                addAlert("WARNING", "Loaded chunks high: " + current.loadedChunks);
            }
        }
    }

    private void addAlert(String level, String message) {
        PerformanceAlert alert = new PerformanceAlert(level, message, System.currentTimeMillis());
        activeAlerts.add(alert);

        // Keep only last 50 alerts
        while (activeAlerts.size() > 50) {
            activeAlerts.remove(0);
        }

        LOGGER.warning("Performance alert: " + message);
    }

    /**
     * Performance metrics container.
     */
    public static class PerformanceMetrics {
        public final double tps;
        public final long usedMemoryMB;
        public final long totalMemoryMB;
        public final double memoryUsagePercent;
        public final double cpuUsage;
        public final int loadedChunks;
        public final int entitiesInChunks;
        public final long timestamp;

        public PerformanceMetrics(double tps, long usedMemoryMB, long totalMemoryMB,
                                double cpuUsage, int loadedChunks, int entitiesInChunks, long timestamp) {
            this.tps = tps;
            this.usedMemoryMB = usedMemoryMB;
            this.totalMemoryMB = totalMemoryMB;
            this.memoryUsagePercent = totalMemoryMB > 0 ? (usedMemoryMB * 100.0 / totalMemoryMB) : 0;
            this.cpuUsage = cpuUsage;
            this.loadedChunks = loadedChunks;
            this.entitiesInChunks = entitiesInChunks;
            this.timestamp = timestamp;
        }
    }

    /**
     * Performance snapshot for historical data.
     */
    public static class PerformanceSnapshot {
        public final PerformanceMetrics metrics;
        public final long snapshotTime;

        public PerformanceSnapshot(PerformanceMetrics metrics) {
            this.metrics = metrics;
            this.snapshotTime = System.currentTimeMillis();
        }
    }

    /**
     * Performance alert container.
     */
    public static class PerformanceAlert {
        public final String level;
        public final String message;
        public final long timestamp;

        public PerformanceAlert(String level, String message, long timestamp) {
            this.level = level;
            this.message = message;
            this.timestamp = timestamp;
        }
    }

    /**
     * Threshold configuration.
     */
    private static class Threshold {
        final double warning;
        final double critical;

        Threshold(double warning, double critical) {
            this.warning = warning;
            this.critical = critical;
        }
    }

    /**
     * TPS Monitor implementation.
     */
    public static class TPSMonitor {
        private final AtomicReference<Double> currentTPS = new AtomicReference<>(20.0); // Default TPS
        private final AtomicLong tickCount = new AtomicLong(0);
        private final AtomicLong lastTickTime = new AtomicLong(System.nanoTime());

        public void updateTPS() {
            long now = System.nanoTime();
            long last = lastTickTime.getAndSet(now);
            tickCount.incrementAndGet();

            // Calculate TPS over the last second
            double elapsedSeconds = (now - last) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                currentTPS.set(1.0 / elapsedSeconds);
            }
        }

        public double getCurrentTPS() {
            return currentTPS.get();
        }
    }

    /**
     * Memory monitor implementation.
     */
    private static class MemoryMonitor {
        private final AtomicLong usedMemory = new AtomicLong(0);
        private final AtomicLong totalMemory = new AtomicLong(0);

        public void updateMemoryStats() {
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            usedMemory.set(memoryMXBean.getHeapMemoryUsage().getUsed() / (1024 * 1024));
            totalMemory.set(memoryMXBean.getHeapMemoryUsage().getCommitted() / (1024 * 1024));
        }

        public long getUsedMemoryMB() {
            return usedMemory.get();
        }

        public long getTotalMemoryMB() {
            return totalMemory.get();
        }
    }

    /**
     * CPU monitor implementation.
     */
    private static class CPUMonitor {
        private final AtomicReference<Double> cpuUsage = new AtomicReference<>(0.0);
        private long lastCpuTime = 0;
        private long lastSystemTime = System.nanoTime();

        public void updateCpuStats() {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;

                long currentCpuTime = sunOsBean.getProcessCpuTime();
                long currentSystemTime = System.nanoTime();

                if (lastCpuTime > 0) {
                    long cpuTimeDiff = currentCpuTime - lastCpuTime;
                    long systemTimeDiff = currentSystemTime - lastSystemTime;

                    if (systemTimeDiff > 0) {
                        double cpuUsagePercent = (cpuTimeDiff * 100.0) / systemTimeDiff / Runtime.getRuntime().availableProcessors();
                        cpuUsage.set(Math.min(100.0, cpuUsagePercent));
                    }
                }

                lastCpuTime = currentCpuTime;
                lastSystemTime = currentSystemTime;
            }
        }

        public double getCpuUsage() {
            return cpuUsage.get();
        }
    }

    /**
     * Chunk monitor implementation.
     */
    private static class ChunkMonitor {
        private final AtomicInteger loadedChunks = new AtomicInteger(0);
        private final AtomicInteger entitiesInChunks = new AtomicInteger(0);

        public void updateChunkStats() {
            // This would integrate with Minecraft's chunk system
            // For now, use placeholder values
            loadedChunks.set(1000);
            entitiesInChunks.set(5000);
        }

        public int getLoadedChunks() {
            return loadedChunks.get();
        }

        public int getEntitiesInChunks() {
            return entitiesInChunks.get();
        }
    }

    /**
     * Adaptive optimizer implementation.
     */
    private static class AdaptiveOptimizer {
        private boolean enabled = false;

        public void enable() {
            enabled = true;
        }

        public void disable() {
            enabled = false;
        }

        public void optimize() {
            if (!enabled) return;

            try {
                // Trigger various optimizations based on current performance
                boolean memoryOptimized = RustPerformance.optimizeMemory();
                boolean chunksOptimized = RustPerformance.optimizeChunks();

                if (memoryOptimized || chunksOptimized) {
                    LOGGER.fine("Adaptive optimization cycle completed successfully");
                } else {
                    LOGGER.fine("Adaptive optimization cycle completed (some optimizations not available)");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during adaptive optimization", e);
            }
        }
    }

    /**
     * Custom metric container for flexible performance monitoring.
     */
    private static class CustomMetric {
        private final String name;
        private final java.util.function.Supplier<Long> valueSupplier;
        private volatile long currentValue;
        private volatile long lastUpdateTime;

        public CustomMetric(String name, java.util.function.Supplier<Long> valueSupplier) {
            this.name = name;
            this.valueSupplier = valueSupplier;
            this.currentValue = 0;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void update() {
            try {
                currentValue = valueSupplier.get();
                lastUpdateTime = System.currentTimeMillis();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error updating custom metric " + name, e);
            }
        }

        public String getName() {
            return name;
        }

        public long getCurrentValue() {
            return currentValue;
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }
}