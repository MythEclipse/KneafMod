package com.kneaf.core.performance.monitoring;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive performance monitoring system for tracking system metrics,
 * memory usage, thread performance, and custom performance indicators.
 */
public class PerformanceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceManager.class);
    private static final PerformanceManager instance = new PerformanceManager();

    // Core monitoring components
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    private final ScheduledExecutorService monitoringExecutor;
    private final AtomicBoolean enabled;
    private final AtomicBoolean monitoringActive;

    // Performance metrics storage
    private final ConcurrentHashMap<String, PerformanceMetric> metrics;
    private final AtomicLong totalMonitoringCycles;
    private final AtomicLong totalErrors;

    // Memory pressure tracking
    private volatile double currentMemoryPressure;
    private volatile long lastMemoryCheck;

    // Thread performance tracking
    private volatile int currentThreadCount;
    private volatile long totalCpuTime;

    public PerformanceManager() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "Performance-Monitor");
                t.setDaemon(true);
                return t;
            });
        this.enabled = new AtomicBoolean(true);
        this.monitoringActive = new AtomicBoolean(false);
        this.metrics = new ConcurrentHashMap<>();
        this.totalMonitoringCycles = new AtomicLong(0);
        this.totalErrors = new AtomicLong(0);
        this.currentMemoryPressure = 0.0;
        this.lastMemoryCheck = System.currentTimeMillis();

        initializeDefaultMetrics();
        startMonitoring();
    }

    /**
     * Initialize default performance metrics.
     */
    private void initializeDefaultMetrics() {
        // Memory metrics
        registerMetric("memory.heap.used", () -> memoryBean.getHeapMemoryUsage().getUsed());
        registerMetric("memory.heap.max", () -> memoryBean.getHeapMemoryUsage().getMax());
        registerMetric("memory.heap.pressure", () -> (long)(getMemoryPressure() * 100));

        // Thread metrics
        registerMetric("threads.active", () -> (long) threadBean.getThreadCount());
        registerMetric("threads.daemon", () -> (long) threadBean.getDaemonThreadCount());
        registerMetric("threads.peak", () -> (long) threadBean.getPeakThreadCount());

        // CPU metrics (if available)
        if (threadBean.isThreadCpuTimeSupported()) {
            registerMetric("cpu.total.time", () -> getTotalCpuTime());
        }

        // System metrics
        registerMetric("system.uptime", () -> ManagementFactory.getRuntimeMXBean().getUptime());
        registerMetric("monitoring.cycles", () -> totalMonitoringCycles.get());
        registerMetric("monitoring.errors", () -> totalErrors.get());
    }

    /**
     * Start the monitoring system.
     */
    private void startMonitoring() {
        if (!enabled.get() || monitoringActive.get()) {
            return;
        }

        monitoringActive.set(true);
        monitoringExecutor.scheduleAtFixedRate(this::performMonitoringCycle,
                                             0, 5, TimeUnit.SECONDS);

        LOGGER.info("Performance monitoring started");
    }

    /**
     * Stop the monitoring system.
     */
    private void stopMonitoring() {
        monitoringActive.set(false);
        monitoringExecutor.shutdown();
        try {
            if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitoringExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Performance monitoring stopped");
    }

    /**
     * Perform a complete monitoring cycle.
     */
    private void performMonitoringCycle() {
        try {
            totalMonitoringCycles.incrementAndGet();

            // Update memory pressure
            updateMemoryPressure();

            // Update thread metrics
            updateThreadMetrics();

            // Update all registered metrics
            updateAllMetrics();

            // Log critical conditions
            checkCriticalConditions();

        } catch (Exception e) {
            totalErrors.incrementAndGet();
            LOGGER.warn("Error during monitoring cycle: {}", e.getMessage());
        }
    }

    /**
     * Update memory pressure calculation.
     */
    private void updateMemoryPressure() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long maxMemory = heapUsage.getMax();
        long usedMemory = heapUsage.getUsed();

        if (maxMemory > 0) {
            currentMemoryPressure = (double) usedMemory / maxMemory;
        }
        lastMemoryCheck = System.currentTimeMillis();
    }

    /**
     * Update thread performance metrics.
     */
    private void updateThreadMetrics() {
        currentThreadCount = threadBean.getThreadCount();

        if (threadBean.isThreadCpuTimeSupported()) {
            long[] threadIds = threadBean.getAllThreadIds();
            long totalCpu = 0;
            for (long threadId : threadIds) {
                totalCpu += threadBean.getThreadCpuTime(threadId);
            }
            totalCpuTime = totalCpu;
        }
    }

    /**
     * Update all registered performance metrics.
     */
    private void updateAllMetrics() {
        for (PerformanceMetric metric : metrics.values()) {
            try {
                metric.update();
            } catch (Exception e) {
                LOGGER.warn("Failed to update metric {}: {}", metric.getName(), e.getMessage());
            }
        }
    }

    /**
     * Check for critical system conditions and log warnings.
     */
    private void checkCriticalConditions() {
        if (currentMemoryPressure > 0.95) {
            LOGGER.warn("Critical memory pressure detected: {:.2f}%", currentMemoryPressure * 100);
        } else if (currentMemoryPressure > 0.85) {
            LOGGER.info("High memory pressure detected: {:.2f}%", currentMemoryPressure * 100);
        }

        if (currentThreadCount > 200) {
            LOGGER.warn("High thread count detected: {}", currentThreadCount);
        }
    }

    /**
     * Register a new performance metric.
     */
    public void registerMetric(String name, Supplier<Long> valueSupplier) {
        metrics.put(name, new PerformanceMetric(name, valueSupplier));
        LOGGER.debug("Registered performance metric: {}", name);
    }

    /**
     * Get the current value of a metric.
     */
    public long getMetricValue(String name) {
        PerformanceMetric metric = metrics.get(name);
        return metric != null ? metric.getCurrentValue() : 0;
    }

    /**
     * Get memory pressure as a percentage (0.0 to 1.0).
     */
    public double getMemoryPressure() {
        return currentMemoryPressure;
    }

    /**
     * Get total CPU time across all threads.
     */
    public long getTotalCpuTime() {
        return totalCpuTime;
    }

    /**
     * Get current thread count.
     */
    public int getThreadCount() {
        return currentThreadCount;
    }

    /**
     * Get monitoring statistics.
     */
    public MonitoringStatistics getStatistics() {
        return new MonitoringStatistics(
            totalMonitoringCycles.get(),
            totalErrors.get(),
            metrics.size(),
            currentMemoryPressure,
            currentThreadCount
        );
    }

    /**
     * Get singleton instance.
     */
    public static PerformanceManager getInstance() {
        return instance;
    }

    /**
     * Check if monitoring is enabled.
     */
    public static boolean isEnabled() {
        return instance.enabled.get();
    }

    /**
     * Enable or disable monitoring.
     */
    public static void setEnabled(boolean enabled) {
        if (instance.enabled.getAndSet(enabled) != enabled) {
            if (enabled) {
                instance.startMonitoring();
            } else {
                instance.stopMonitoring();
            }
            LOGGER.info("Performance monitoring {}", enabled ? "enabled" : "disabled");
        }
    }

    /**
     * Shutdown the performance manager.
     */
    public void shutdown() {
        setEnabled(false);
    }

    /**
     * Performance metric container.
     */
    private static class PerformanceMetric {
        private final String name;
        private final Supplier<Long> valueSupplier;
        private volatile long currentValue;
        private volatile long lastUpdateTime;

        public PerformanceMetric(String name, Supplier<Long> valueSupplier) {
            this.name = name;
            this.valueSupplier = valueSupplier;
            this.currentValue = 0;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public void update() {
            currentValue = valueSupplier.get();
            lastUpdateTime = System.currentTimeMillis();
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

    /**
     * Monitoring statistics container.
     */
    public static class MonitoringStatistics {
        public final long totalCycles;
        public final long totalErrors;
        public final int metricCount;
        public final double memoryPressure;
        public final int threadCount;

        public MonitoringStatistics(long totalCycles, long totalErrors, int metricCount,
                                   double memoryPressure, int threadCount) {
            this.totalCycles = totalCycles;
            this.totalErrors = totalErrors;
            this.metricCount = metricCount;
            this.memoryPressure = memoryPressure;
            this.threadCount = threadCount;
        }

        @Override
        public String toString() {
            return String.format(
                "MonitoringStatistics{cycles=%d, errors=%d, metrics=%d, memoryPressure=%.2f%%, threads=%d}",
                totalCycles, totalErrors, metricCount, memoryPressure * 100, threadCount);
        }
    }
}