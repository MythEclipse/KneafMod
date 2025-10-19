package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

/**
 * Core metrics collector that gathers performance data from various sources.
 * Thread-safe and optimized for high-frequency metric collection.
 */
public final class MetricsCollector {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Metric storage with thread safety
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicDouble> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> timers = new ConcurrentHashMap<>();
    
    // Sampling configuration
    private final AtomicInteger samplingRate = new AtomicInteger(100); // 100% sampling by default
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    
    // Health status
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastCollectionTime = new AtomicLong(System.currentTimeMillis());
    
    public MetricsCollector() {
        LOGGER.info("Initializing MetricsCollector");
    }
    
    /**
     * Record a counter metric
     */
    public void recordCounter(String name, long value) {
        if (!isEnabled.get() || !shouldSample()) {
            return;
        }
        
        try {
            counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(value);
            updateLastCollectionTime();
        } catch (Exception e) {
            LOGGER.error("Error recording counter metric: " + name, e);
            isHealthy.set(false);
        }
    }
    
    /**
     * Record a gauge metric
     */
    public void recordGauge(String name, double value) {
        if (!isEnabled.get() || !shouldSample()) {
            return;
        }
        
        try {
            gauges.computeIfAbsent(name, k -> new AtomicDouble(0.0)).set(value);
            updateLastCollectionTime();
        } catch (Exception e) {
            LOGGER.error("Error recording gauge metric: " + name, e);
            isHealthy.set(false);
        }
    }
    
    /**
     * Record a histogram metric
     */
    public void recordHistogram(String name, long value) {
        if (!isEnabled.get() || !shouldSample()) {
            return;
        }
        
        try {
            histograms.computeIfAbsent(name, k -> new LongAdder()).add(value);
            updateLastCollectionTime();
        } catch (Exception e) {
            LOGGER.error("Error recording histogram metric: " + name, e);
            isHealthy.set(false);
        }
    }
    
    /**
     * Record a timer metric
     */
    public void recordTimer(String name, long durationNs) {
        if (!isEnabled.get() || !shouldSample()) {
            return;
        }
        
        try {
            timers.computeIfAbsent(name, k -> new ConcurrentLinkedQueue<>()).offer(durationNs);
            updateLastCollectionTime();
        } catch (Exception e) {
            LOGGER.error("Error recording timer metric: " + name, e);
            isHealthy.set(false);
        }
    }
    
    /**
     * Increment a counter metric
     */
    public void incrementCounter(String name) {
        recordCounter(name, 1);
    }
    
    /**
     * Get current counter value
     */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }
    
    /**
     * Get current gauge value
     */
    public double getGauge(String name) {
        AtomicDouble gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0.0;
    }
    
    /**
     * Get histogram statistics
     */
    public HistogramStatistics getHistogramStatistics(String name) {
        LongAdder histogram = histograms.get(name);
        if (histogram == null) {
            return new HistogramStatistics(0, 0, 0, 0, 0);
        }
        
        long sum = histogram.sum();
        long count = histogram.sum(); // For LongAdder, sum gives total count
        
        // Calculate min, max, avg (simplified)
        long avg = count > 0 ? sum / count : 0;
        
        return new HistogramStatistics(sum, count, avg, avg, avg); // Simplified for now
    }
    
    /**
     * Get timer statistics
     */
    public TimerStatistics getTimerStatistics(String name) {
        ConcurrentLinkedQueue<Long> timerQueue = timers.get(name);
        if (timerQueue == null || timerQueue.isEmpty()) {
            return new TimerStatistics(0, 0, 0, 0, 0, 0);
        }
        
        List<Long> values = new ArrayList<>(timerQueue);
        if (values.isEmpty()) {
            return new TimerStatistics(0, 0, 0, 0, 0, 0);
        }
        
        // Calculate statistics
        Collections.sort(values);
        long min = values.get(0);
        long max = values.get(values.size() - 1);
        long sum = values.stream().mapToLong(Long::longValue).sum();
        long avg = sum / values.size();
        
        // Calculate percentiles (simplified)
        long p50 = values.get(values.size() / 2);
        long p95 = values.get((int)(values.size() * 0.95));
        long p99 = values.get((int)(values.size() * 0.99));
        
        return new TimerStatistics(min, max, avg, p50, p95, p99);
    }
    
    /**
     * Get all current metrics
     */
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> allMetrics = new HashMap<>();
        
        // Add counters
        counters.forEach((name, counter) -> allMetrics.put(name, counter.get()));
        
        // Add gauges
        gauges.forEach((name, gauge) -> allMetrics.put(name, gauge.get()));
        
        // Add histograms
        histograms.forEach((name, histogram) -> allMetrics.put(name + "_sum", histogram.sum()));
        histograms.forEach((name, histogram) -> allMetrics.put(name + "_count", histogram.sum()));
        
        // Add timer statistics
        timers.keySet().forEach(name -> {
            TimerStatistics stats = getTimerStatistics(name);
            allMetrics.put(name + "_min_ns", stats.getMin());
            allMetrics.put(name + "_max_ns", stats.getMax());
            allMetrics.put(name + "_avg_ns", stats.getAvg());
            allMetrics.put(name + "_p50_ns", stats.getP50());
            allMetrics.put(name + "_p95_ns", stats.getP95());
            allMetrics.put(name + "_p99_ns", stats.getP99());
        });
        
        return allMetrics;
    }
    
    /**
     * Reset specific metric
     */
    public void resetMetric(String name) {
        counters.remove(name);
        gauges.remove(name);
        histograms.remove(name);
        timers.remove(name);
    }
    
    /**
     * Reset all metrics
     */
    public void resetAllMetrics() {
        counters.clear();
        gauges.clear();
        histograms.clear();
        timers.clear();
        isHealthy.set(true);
        LOGGER.info("All metrics reset");
    }
    
    /**
     * Set sampling rate (0-100)
     */
    public void setSamplingRate(int rate) {
        this.samplingRate.set(Math.max(0, Math.min(100, rate)));
        LOGGER.info("Sampling rate set to {}%", rate);
    }
    
    /**
     * Enable/disable metric collection
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        LOGGER.info("MetricsCollector {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Check if collector is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() && 
               (System.currentTimeMillis() - lastCollectionTime.get()) < 30000; // 30 second timeout
    }
    
    /**
     * Shutdown the collector
     */
    public void shutdown() {
        LOGGER.info("Shutting down MetricsCollector");
        setEnabled(false);
        resetAllMetrics();
    }
    
    /**
     * Determine if current operation should be sampled
     */
    private boolean shouldSample() {
        if (samplingRate.get() >= 100) {
            return true;
        }
        if (samplingRate.get() <= 0) {
            return false;
        }
        return ThreadLocalRandom.current().nextInt(100) < samplingRate.get();
    }
    
    /**
     * Update last collection time
     */
    private void updateLastCollectionTime() {
        lastCollectionTime.set(System.currentTimeMillis());
    }
    
    /**
     * Histogram statistics
     */
    public static class HistogramStatistics {
        private final long sum;
        private final long count;
        private final long min;
        private final long max;
        private final long avg;
        
        public HistogramStatistics(long sum, long count, long min, long max, long avg) {
            this.sum = sum;
            this.count = count;
            this.min = min;
            this.max = max;
            this.avg = avg;
        }
        
        public long getSum() { return sum; }
        public long getCount() { return count; }
        public long getMin() { return min; }
        public long getMax() { return max; }
        public long getAvg() { return avg; }
        public double getRate() { return count > 0 ? (double) sum / count : 0.0; }
    }
    
    /**
     * Timer statistics
     */
    public static class TimerStatistics {
        private final long min;
        private final long max;
        private final long avg;
        private final long p50;
        private final long p95;
        private final long p99;
        
        public TimerStatistics(long min, long max, long avg, long p50, long p95, long p99) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
        }
        
        public long getMin() { return min; }
        public long getMax() { return max; }
        public long getAvg() { return avg; }
        public long getP50() { return p50; }
        public long getP95() { return p95; }
        public long getP99() { return p99; }
    }
    
    /**
     * Thread-safe double wrapper
     */
    private static class AtomicDouble {
        private volatile double value;
        
        public AtomicDouble(double initialValue) {
            this.value = initialValue;
        }
        
        public void set(double newValue) {
            this.value = newValue;
        }
        
        public double get() {
            return value;
        }
    }
}