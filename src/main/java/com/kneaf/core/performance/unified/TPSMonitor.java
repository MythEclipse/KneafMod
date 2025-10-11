package com.kneaf.core.performance.unified;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;

/**
 * Thread-safe TPS (Ticks Per Second) monitor with rolling average calculation
 * and threshold alerting capabilities.
 */
public class TPSMonitor {
    private final double[] tpsWindow;
    private final int tpsWindowSize;
    private final AtomicLong tpsWindowIndex = new AtomicLong(0);
    private final AtomicLong lastTickTime = new AtomicLong(0);
    private final AtomicLong lastTickDurationNanos = new AtomicLong(0);
    private final AtomicReference<Double> currentTpsThreshold = new AtomicReference<>(18.0);
    private final MetricsCollector metricsCollector;
    private final MonitoringConfig monitoringConfig;

    /**
     * Create a new TPSMonitor with default configuration.
     * @param metricsCollector metrics collector for recording TPS-related metrics
     */
    public TPSMonitor(MetricsCollector metricsCollector) {
        this(metricsCollector, MonitoringConfig.builder().build());
    }

    /**
     * Create a new TPSMonitor with custom configuration.
     * @param metricsCollector metrics collector for recording TPS-related metrics
     * @param monitoringConfig monitoring configuration
     */
    public TPSMonitor(MetricsCollector metricsCollector, MonitoringConfig monitoringConfig) {
        this.metricsCollector = metricsCollector;
        this.monitoringConfig = monitoringConfig;
        this.tpsWindowSize = monitoringConfig.getTpsWindowSize();
        this.tpsWindow = new double[tpsWindowSize];
        Arrays.fill(this.tpsWindow, 20.0); // Initialize with normal Minecraft TPS
        this.currentTpsThreshold.set(monitoringConfig.getTpsThresholdAsync());
    }

    /**
     * Update TPS based on current tick timing.
     * Should be called once per server tick.
     */
    public void updateTPS() {
        long currentTime = System.nanoTime();
        long lastTime = lastTickTime.get();
        
        if (lastTime != 0) {
            long delta = currentTime - lastTime;
            lastTickDurationNanos.set(delta);
            
            // Calculate actual TPS based on tick duration
            double tps = 1_000_000_000.0 / delta;
            
            // Apply TPS cap logic
            if (delta > 100_000_000L) { // More than 100ms per tick indicates significant lag
                tps = Math.min(20.0, 1000.0 / (delta / 1_000_000.0));
            }
            
            // Update rolling window
            int index = (int) (tpsWindowIndex.getAndIncrement() % tpsWindowSize);
            tpsWindow[index] = tps;
            
            // Record TPS for external consumers
            com.kneaf.core.performance.RustPerformance.setCurrentTPS(tps);
            
            // Check for threshold violations and record alerts if needed
            checkTpsThreshold(tps);
        } else {
            lastTickDurationNanos.set(0);
        }
        
        lastTickTime.set(currentTime);
    }

    /**
     * Get the most recently measured tick duration in milliseconds.
     * @return last tick duration in milliseconds
     */
    public long getLastTickDurationMs() {
        return lastTickDurationNanos.get() / 1_000_000L;
    }

    /**
     * Get the rolling average TPS.
     * @return rolling average TPS
     */
    public double getAverageTPS() {
        return getRollingAvgTPS();
    }

    /**
     * Get the current TPS threshold for async operations.
     * @return current TPS threshold
     */
    public double getCurrentTpsThreshold() {
        return currentTpsThreshold.get();
    }

    /**
     * Set the TPS threshold for async operations.
     * @param threshold new TPS threshold
     */
    public void setCurrentTpsThreshold(double threshold) {
        currentTpsThreshold.set(threshold);
    }

    /**
     * Check if current TPS is below the threshold and record alert if needed.
     * @param currentTps current TPS value to check
     */
    private void checkTpsThreshold(double currentTps) {
        double threshold = currentTpsThreshold.get();
        if (currentTps < threshold && monitoringConfig.getMonitoringLevel().isAlertsEnabled()) {
            String alert = String.format("TPS below threshold: %.2f < %.2f", currentTps, threshold);
            metricsCollector.addThresholdAlert(alert);
        }
    }

    /**
     * Calculate rolling average TPS from the window.
     * @return rolling average TPS
     */
    private double getRollingAvgTPS() {
        double sum = 0.0;
        int count = 0;
        
        for (double tps : tpsWindow) {
            if (tps > 0) {
                sum += tps;
                count++;
            }
        }
        
        return count > 0 ? sum / count : 20.0;
    }

    /**
     * Reset TPS monitoring state.
     */
    public void reset() {
        Arrays.fill(tpsWindow, 20.0);
        tpsWindowIndex.set(0);
        lastTickTime.set(0);
        lastTickDurationNanos.set(0);
        currentTpsThreshold.set(monitoringConfig.getTpsThresholdAsync());
    }

    /**
     * Get the raw TPS window for debugging purposes.
     * @return copy of the TPS window array
     */
    public double[] getTpsWindow() {
        return Arrays.copyOf(tpsWindow, tpsWindowSize);
    }

    /**
     * Get the current TPS window index.
     * @return current window index
     */
    public long getTpsWindowIndex() {
        return tpsWindowIndex.get();
    }
}