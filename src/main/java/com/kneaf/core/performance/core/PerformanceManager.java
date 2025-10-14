package com.kneaf.core.performance.core;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core performance management system for monitoring and controlling
 * server performance metrics and optimization settings.
 */
public class PerformanceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceManager.class);

    private final AtomicBoolean enabled;
    private final AtomicReference<PerformanceConfig> config;
    private final TpsMonitor tpsMonitor;
    private final MemoryMonitor memoryMonitor;
    private final ThreadMonitor threadMonitor;

    // Performance thresholds
    private static final double HIGH_TPS_THRESHOLD = 18.0;
    private static final double LOW_TPS_THRESHOLD = 15.0;
    private static final double HIGH_MEMORY_THRESHOLD = 0.85;
    private static final long MONITORING_INTERVAL_MS = 5000; // 5 seconds

    // Monitoring thread
    private volatile boolean monitoringActive;
    private Thread monitoringThread;

    public PerformanceManager() {
        this.enabled = new AtomicBoolean(false);
        this.config = new AtomicReference<>(new PerformanceConfig());
        this.tpsMonitor = new TpsMonitor();
        this.memoryMonitor = new MemoryMonitor();
        this.threadMonitor = new ThreadMonitor();
        this.monitoringActive = false;
    }

    /**
     * Enable performance monitoring and optimization.
     */
    public void enable() {
        if (enabled.compareAndSet(false, true)) {
            startMonitoring();
            LOGGER.info("Performance monitoring enabled");
        }
    }

    /**
     * Disable performance monitoring and optimization.
     */
    public void disable() {
        if (enabled.compareAndSet(true, false)) {
            stopMonitoring();
            LOGGER.info("Performance monitoring disabled");
        }
    }

    /**
     * Check if performance monitoring is enabled.
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Get the TPS monitor.
     */
    public TpsMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    /**
     * Get current performance statistics.
     */
    public PerformanceStats getStats() {
        return new PerformanceStats(
            tpsMonitor.getCurrentTPS(),
            memoryMonitor.getMemoryPressure(),
            threadMonitor.getActiveThreadCount(),
            config.get()
        );
    }

    /**
     * Update performance configuration.
     */
    public void updateConfig(PerformanceConfig newConfig) {
        config.set(newConfig);
        LOGGER.info("Performance configuration updated");
    }

    /**
     * Start the monitoring thread.
     */
    private void startMonitoring() {
        monitoringActive = true;
        monitoringThread = new Thread(this::monitoringLoop, "Performance-Monitor-Core");
        monitoringThread.setDaemon(true);
        monitoringThread.start();
    }

    /**
     * Stop the monitoring thread.
     */
    private void stopMonitoring() {
        monitoringActive = false;
        if (monitoringThread != null) {
            monitoringThread.interrupt();
            try {
                monitoringThread.join(2000); // Wait up to 2 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Main monitoring loop.
     */
    private void monitoringLoop() {
        while (monitoringActive && !Thread.currentThread().isInterrupted()) {
            try {
                // Update all monitors
                tpsMonitor.update();
                memoryMonitor.update();
                threadMonitor.update();

                // Check for performance issues and apply optimizations
                checkPerformanceIssues();

                // Sleep for monitoring interval
                Thread.sleep(MONITORING_INTERVAL_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("Error in performance monitoring loop: {}", e.getMessage());
            }
        }
    }

    /**
     * Check for performance issues and apply corrective actions.
     */
    private void checkPerformanceIssues() {
        double currentTPS = tpsMonitor.getCurrentTPS();
        double memoryPressure = memoryMonitor.getMemoryPressure();

        // TPS-based optimizations
        if (currentTPS < LOW_TPS_THRESHOLD) {
            applyLowTPsOptimizations();
        } else if (currentTPS > HIGH_TPS_THRESHOLD) {
            applyHighTPsOptimizations();
        }

        // Memory-based optimizations
        if (memoryPressure > HIGH_MEMORY_THRESHOLD) {
            applyHighMemoryOptimizations();
        }
    }

    /**
     * Apply optimizations when TPS is low.
     */
    private void applyLowTPsOptimizations() {
        LOGGER.debug("Applying low TPS optimizations");
        // Implementation would include:
        // - Reduce entity processing frequency
        // - Increase chunk loading priorities
        // - Optimize AI calculations
    }

    /**
     * Apply optimizations when TPS is high.
     */
    private void applyHighTPsOptimizations() {
        LOGGER.debug("Applying high TPS optimizations");
        // Implementation would include:
        // - Increase entity processing frequency
        // - Pre-load chunks
        // - Enable advanced AI features
    }

    /**
     * Apply optimizations when memory usage is high.
     */
    private void applyHighMemoryOptimizations() {
        LOGGER.debug("Applying high memory optimizations");
        // Implementation would include:
        // - Force garbage collection
        // - Clear unused caches
        // - Reduce buffer sizes
        System.gc(); // Force garbage collection as immediate action
    }

    /**
     * TPS monitoring component.
     */
    public static class TpsMonitor {
        private final AtomicLong tickCount;
        private final AtomicLong lastTickTime;
        private volatile double currentTPS;
        private static final long TPS_CALCULATION_WINDOW_MS = 60000; // 1 minute

        public TpsMonitor() {
            this.tickCount = new AtomicLong(0);
            this.lastTickTime = new AtomicLong(System.currentTimeMillis());
            this.currentTPS = 20.0; // Default TPS
        }

        /**
         * Record a game tick.
         */
        public void recordTick() {
            tickCount.incrementAndGet();
        }

        /**
         * Update TPS calculation.
         */
        public void update() {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - lastTickTime.get();

            if (timeDiff >= 1000) { // Update at least every second
                long ticks = tickCount.get();
                currentTPS = (ticks * 1000.0) / Math.max(timeDiff, 1);

                // Reset counters
                tickCount.set(0);
                lastTickTime.set(currentTime);
            }
        }

        /**
         * Update TPS (legacy method for compatibility).
         */
        public void updateTPS() {
            update();
        }

        /**
         * Get current TPS.
         */
        public double getCurrentTPS() {
            return currentTPS;
        }
    }

    /**
     * Memory monitoring component.
     */
    private static class MemoryMonitor {
        private volatile double memoryPressure;

        public void update() {
            var memoryMXBean = ManagementFactory.getMemoryMXBean();
            var heapUsage = memoryMXBean.getHeapMemoryUsage();

            long used = heapUsage.getUsed();
            long max = heapUsage.getMax();

            if (max > 0) {
                memoryPressure = (double) used / max;
            } else {
                memoryPressure = 0.0;
            }
        }

        public double getMemoryPressure() {
            return memoryPressure;
        }
    }

    /**
     * Thread monitoring component.
     */
    private static class ThreadMonitor {
        private volatile int activeThreadCount;

        public void update() {
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            activeThreadCount = threadMXBean.getThreadCount();
        }

        public int getActiveThreadCount() {
            return activeThreadCount;
        }
    }

    /**
     * Performance configuration.
     */
    public static class PerformanceConfig {
        private boolean enableTpsMonitoring = true;
        private boolean enableMemoryMonitoring = true;
        private boolean enableAutoOptimization = true;
        private int targetTPS = 20;
        private double maxMemoryPressure = 0.8;

        // Getters and setters
        public boolean isEnableTpsMonitoring() { return enableTpsMonitoring; }
        public void setEnableTpsMonitoring(boolean enableTpsMonitoring) { this.enableTpsMonitoring = enableTpsMonitoring; }

        public boolean isEnableMemoryMonitoring() { return enableMemoryMonitoring; }
        public void setEnableMemoryMonitoring(boolean enableMemoryMonitoring) { this.enableMemoryMonitoring = enableMemoryMonitoring; }

        public boolean isEnableAutoOptimization() { return enableAutoOptimization; }
        public void setEnableAutoOptimization(boolean enableAutoOptimization) { this.enableAutoOptimization = enableAutoOptimization; }

        public int getTargetTPS() { return targetTPS; }
        public void setTargetTPS(int targetTPS) { this.targetTPS = targetTPS; }

        public double getMaxMemoryPressure() { return maxMemoryPressure; }
        public void setMaxMemoryPressure(double maxMemoryPressure) { this.maxMemoryPressure = maxMemoryPressure; }
    }

    /**
     * Performance statistics container.
     */
    public static class PerformanceStats {
        public final double currentTPS;
        public final double memoryPressure;
        public final int activeThreads;
        public final PerformanceConfig config;

        public PerformanceStats(double currentTPS, double memoryPressure, int activeThreads, PerformanceConfig config) {
            this.currentTPS = currentTPS;
            this.memoryPressure = memoryPressure;
            this.activeThreads = activeThreads;
            this.config = config;
        }

        @Override
        public String toString() {
            return String.format(
                "PerformanceStats{TPS=%.2f, memory=%.2f%%, threads=%d}",
                currentTPS, memoryPressure * 100, activeThreads);
        }
    }
}