package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

/**
 * Thread-safe metric aggregator that processes and aggregates metrics from multiple sources.
 * Provides low-overhead aggregation with lock-free data structures where possible.
 */
public final class ThreadSafeMetricAggregator {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Current metrics storage
    private final ConcurrentHashMap<String, AtomicDouble> currentMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> histograms = new ConcurrentHashMap<>();
    
    // Aggregated metrics storage
    private final ConcurrentHashMap<String, AggregatedMetric> aggregatedMetrics = new ConcurrentHashMap<>();
    
    // Rate limiting and sampling
    private final AtomicInteger aggregationIntervalMs = new AtomicInteger(1000); // 1 second default
    private final AtomicLong lastAggregationTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    
    // Statistics tracking
    private final AtomicLong totalMetricsRecorded = new AtomicLong(0);
    private final AtomicLong totalMetricsAggregated = new AtomicLong(0);
    private final AtomicLong aggregationErrors = new AtomicLong(0);
    
    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulAggregation = new AtomicLong(System.currentTimeMillis());
    
    public ThreadSafeMetricAggregator() {
        LOGGER.info("Initializing ThreadSafeMetricAggregator");
    }
    
    /**
     * Record a metric value (thread-safe)
     */
    public void recordMetric(String name, double value) {
        if (!isEnabled.get()) {
            return;
        }
        
        try {
            currentMetrics.computeIfAbsent(name, k -> new AtomicDouble(0.0)).set(value);
            totalMetricsRecorded.incrementAndGet();
            updateHealthStatus();
        } catch (Exception e) {
            LOGGER.error("Error recording metric: " + name, e);
            aggregationErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Increment a counter metric (thread-safe)
     */
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }
    
    /**
     * Increment a counter metric by specified amount (thread-safe)
     */
    public void incrementCounter(String name, long delta) {
        if (!isEnabled.get()) {
            return;
        }
        
        try {
            counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(delta);
            totalMetricsRecorded.incrementAndGet();
            updateHealthStatus();
        } catch (Exception e) {
            LOGGER.error("Error incrementing counter: " + name, e);
            aggregationErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Record a histogram value (thread-safe)
     */
    public void recordHistogram(String name, long value) {
        if (!isEnabled.get()) {
            return;
        }
        
        try {
            histograms.computeIfAbsent(name, k -> new LongAdder()).add(value);
            totalMetricsRecorded.incrementAndGet();
            updateHealthStatus();
        } catch (Exception e) {
            LOGGER.error("Error recording histogram: " + name, e);
            aggregationErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Aggregate all current metrics
     */
    public void aggregateMetrics() {
        if (!isEnabled.get()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Check if enough time has passed since last aggregation
            if (startTime - lastAggregationTime.get() < aggregationIntervalMs.get()) {
                return;
            }
            
            // Aggregate current metrics
            aggregateCurrentMetrics();
            
            // Aggregate counters
            aggregateCounters();
            
            // Aggregate histograms
            aggregateHistograms();
            
            // Calculate derived metrics
            calculateDerivedMetrics();
            
            // Update statistics
            totalMetricsAggregated.addAndGet(currentMetrics.size() + counters.size() + histograms.size());
            lastAggregationTime.set(startTime);
            lastSuccessfulAggregation.set(startTime);
            
            updateHealthStatus();
            
        } catch (Exception e) {
            LOGGER.error("Error during metric aggregation", e);
            aggregationErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Aggregate current gauge metrics
     */
    private void aggregateCurrentMetrics() {
        for (Map.Entry<String, AtomicDouble> entry : currentMetrics.entrySet()) {
            String name = entry.getKey();
            double value = entry.getValue().get();
            
            AggregatedMetric metric = aggregatedMetrics.computeIfAbsent(name, k -> new AggregatedMetric(name));
            metric.update(value);
        }
    }
    
    /**
     * Aggregate counter metrics
     */
    private void aggregateCounters() {
        for (Map.Entry<String, AtomicLong> entry : counters.entrySet()) {
            String name = entry.getKey();
            long value = entry.getValue().get();
            
            AggregatedMetric metric = aggregatedMetrics.computeIfAbsent(name, k -> new AggregatedMetric(name));
            metric.update(value);
        }
    }
    
    /**
     * Aggregate histogram metrics
     */
    private void aggregateHistograms() {
        for (Map.Entry<String, LongAdder> entry : histograms.entrySet()) {
            String name = entry.getKey();
            long sum = entry.getValue().sum();
            long count = entry.getValue().sum(); // For LongAdder, sum gives total
            
            AggregatedMetric metric = aggregatedMetrics.computeIfAbsent(name, k -> new AggregatedMetric(name));
            metric.updateHistogram(sum, count);
        }
    }
    
    /**
     * Calculate derived metrics (rates, ratios, etc.)
     */
    private void calculateDerivedMetrics() {
        // Calculate system-level derived metrics
        calculateSystemMetrics();
        
        // Calculate component-specific derived metrics
        calculateComponentMetrics();
        
        // Calculate performance ratios
        calculatePerformanceRatios();
    }
    
    /**
     * Calculate system-level metrics
     */
    private void calculateSystemMetrics() {
        // Calculate average latency across all components
        double totalLatency = 0;
        int latencyCount = 0;
        
        for (Map.Entry<String, AggregatedMetric> entry : aggregatedMetrics.entrySet()) {
            String name = entry.getKey();
            AggregatedMetric metric = entry.getValue();
            
            if (name.contains("duration_ms") || name.contains("latency_ms")) {
                totalLatency += metric.getAverage();
                latencyCount++;
            }
        }
        
        if (latencyCount > 0) {
            double avgLatency = totalLatency / latencyCount;
            AggregatedMetric systemLatency = aggregatedMetrics.computeIfAbsent("system.avg_latency_ms", 
                k -> new AggregatedMetric("system.avg_latency_ms"));
            systemLatency.update(avgLatency);
        }
        
        // Calculate total throughput
        long totalOperations = 0;
        for (Map.Entry<String, AggregatedMetric> entry : aggregatedMetrics.entrySet()) {
            String name = entry.getKey();
            AggregatedMetric metric = entry.getValue();
            
            if (name.contains("operations") || name.contains("processed")) {
                totalOperations += (long) metric.getSum();
            }
        }
        
        double throughput = totalOperations / (aggregationIntervalMs.get() / 1000.0);
        AggregatedMetric systemThroughput = aggregatedMetrics.computeIfAbsent("system.throughput_ops_per_sec", 
            k -> new AggregatedMetric("system.throughput_ops_per_sec"));
        systemThroughput.update(throughput);
    }
    
    /**
     * Calculate component-specific metrics
     */
    private void calculateComponentMetrics() {
        // Calculate optimization hit rates
        calculateHitRate("optimization_injector", "async_hits", "async_misses", "async_errors");
        
        // Calculate library utilization
        calculateUtilization("rust_vector_library", "pending_operations", "total_operations");
        
        // Calculate entity processing efficiency
        calculateEfficiency("entity_processing", "processed_entities", "queued_entities");
    }
    
    /**
     * Calculate performance ratios
     */
    private void calculatePerformanceRatios() {
        // Calculate error rates
        calculateErrorRate();
        
        // Calculate success rates
        calculateSuccessRate();
        
        // Calculate resource utilization
        calculateResourceUtilization();
    }
    
    /**
     * Calculate hit rate for a component
     */
    private void calculateHitRate(String component, String hitsMetric, String missesMetric, String errorsMetric) {
        AggregatedMetric hits = aggregatedMetrics.get(component + "." + hitsMetric);
        AggregatedMetric misses = aggregatedMetrics.get(component + "." + missesMetric);
        AggregatedMetric errors = aggregatedMetrics.get(component + "." + errorsMetric);
        
        if (hits != null && misses != null) {
            long total = (long) hits.getSum() + (long) misses.getSum() + (errors != null ? (long) errors.getSum() : 0);
            if (total > 0) {
                double hitRate = (long) hits.getSum() / (double) total;
                AggregatedMetric rateMetric = aggregatedMetrics.computeIfAbsent(component + ".hit_rate", 
                    k -> new AggregatedMetric(component + ".hit_rate"));
                rateMetric.update(hitRate);
            }
        }
    }
    
    /**
     * Calculate utilization for a component
     */
    private void calculateUtilization(String component, String currentMetric, String totalMetric) {
        AggregatedMetric current = aggregatedMetrics.get(component + "." + currentMetric);
        AggregatedMetric total = aggregatedMetrics.get(component + "." + totalMetric);
        
        if (current != null && total != null && total.getSum() > 0) {
            double utilization = current.getSum() / total.getSum();
            AggregatedMetric utilMetric = aggregatedMetrics.computeIfAbsent(component + ".utilization", 
                k -> new AggregatedMetric(component + ".utilization"));
            utilMetric.update(utilization);
        }
    }
    
    /**
     * Calculate efficiency for a component
     */
    private void calculateEfficiency(String component, String processedMetric, String queuedMetric) {
        AggregatedMetric processed = aggregatedMetrics.get(component + "." + processedMetric);
        AggregatedMetric queued = aggregatedMetrics.get(component + "." + queuedMetric);
        
        if (processed != null && queued != null) {
            long processedCount = (long) processed.getSum();
            long queuedCount = (long) queued.getSum();
            long total = processedCount + queuedCount;
            
            if (total > 0) {
                double efficiency = processedCount / (double) total;
                AggregatedMetric effMetric = aggregatedMetrics.computeIfAbsent(component + ".efficiency", 
                    k -> new AggregatedMetric(component + ".efficiency"));
                effMetric.update(efficiency);
            }
        }
    }
    
    /**
     * Calculate system error rate
     */
    private void calculateErrorRate() {
        AggregatedMetric errors = aggregatedMetrics.get("system.errors.total");
        AggregatedMetric operations = aggregatedMetrics.get("system.throughput_ops_per_sec");
        
        if (errors != null && operations != null) {
            double errorRate = errors.getSum() / operations.getSum();
            AggregatedMetric errorRateMetric = aggregatedMetrics.computeIfAbsent("system.error_rate", 
                k -> new AggregatedMetric("system.error_rate"));
            errorRateMetric.update(errorRate);
        }
    }
    
    /**
     * Calculate system success rate
     */
    private void calculateSuccessRate() {
        AggregatedMetric hitRate = aggregatedMetrics.get("optimization_injector.hit_rate");
        
        if (hitRate != null) {
            AggregatedMetric successRateMetric = aggregatedMetrics.computeIfAbsent("system.success_rate", 
                k -> new AggregatedMetric("system.success_rate"));
            successRateMetric.update(hitRate.getAverage());
        }
    }
    
    /**
     * Calculate resource utilization
     */
    private void calculateResourceUtilization() {
        AggregatedMetric activeThreads = aggregatedMetrics.get("rust_vector_library.active_threads");
        AggregatedMetric activeProcessors = aggregatedMetrics.get("entity_processing.active_processors");
        
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        double totalUtilization = 0;
        int utilizationCount = 0;
        
        if (activeThreads != null) {
            totalUtilization += activeThreads.getAverage() / availableProcessors;
            utilizationCount++;
        }
        
        if (activeProcessors != null) {
            totalUtilization += activeProcessors.getAverage() / availableProcessors;
            utilizationCount++;
        }
        
        if (utilizationCount > 0) {
            double avgUtilization = totalUtilization / utilizationCount;
            AggregatedMetric utilMetric = aggregatedMetrics.computeIfAbsent("system.resource_utilization", 
                k -> new AggregatedMetric("system.resource_utilization"));
            utilMetric.update(avgUtilization);
        }
    }
    
    /**
     * Get current aggregated metrics
     */
    public Map<String, Double> getCurrentMetrics() {
        Map<String, Double> metrics = new HashMap<>();
        
        for (Map.Entry<String, AggregatedMetric> entry : aggregatedMetrics.entrySet()) {
            metrics.put(entry.getKey(), entry.getValue().getAverage());
        }
        
        return metrics;
    }
    
    /**
     * Get aggregated metric by name
     */
    public AggregatedMetric getAggregatedMetric(String name) {
        return aggregatedMetrics.get(name);
    }
    
    /**
     * Get all aggregated metrics
     */
    public Map<String, AggregatedMetric> getAllAggregatedMetrics() {
        return new HashMap<>(aggregatedMetrics);
    }
    
    /**
     * Reset specific metric
     */
    public void resetMetric(String name) {
        currentMetrics.remove(name);
        counters.remove(name);
        histograms.remove(name);
        aggregatedMetrics.remove(name);
    }
    
    /**
     * Reset all metrics
     */
    public void resetAllMetrics() {
        currentMetrics.clear();
        counters.clear();
        histograms.clear();
        aggregatedMetrics.clear();
        totalMetricsRecorded.set(0);
        totalMetricsAggregated.set(0);
        aggregationErrors.set(0);
        isHealthy.set(true);
        LOGGER.info("All metrics reset");
    }
    
    /**
     * Set aggregation interval in milliseconds
     */
    public void setAggregationInterval(int intervalMs) {
        this.aggregationIntervalMs.set(Math.max(100, intervalMs)); // Minimum 100ms
        LOGGER.info("Aggregation interval set to {}ms", intervalMs);
    }
    
    /**
     * Enable/disable metric aggregation
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        LOGGER.info("ThreadSafeMetricAggregator {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Check if aggregator is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() && 
               (System.currentTimeMillis() - lastSuccessfulAggregation.get()) < 30000; // 30 second timeout
    }
    
    /**
     * Get aggregation statistics
     */
    public AggregationStatistics getStatistics() {
        return new AggregationStatistics(
            totalMetricsRecorded.get(),
            totalMetricsAggregated.get(),
            aggregationErrors.get(),
            aggregatedMetrics.size(),
            System.currentTimeMillis() - lastAggregationTime.get()
        );
    }
    
    /**
     * Shutdown the aggregator
     */
    public void shutdown() {
        LOGGER.info("Shutting down ThreadSafeMetricAggregator");
        setEnabled(false);
        resetAllMetrics();
    }
    
    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = aggregationErrors.get() < 10 && // Less than 10 errors
                         (System.currentTimeMillis() - lastSuccessfulAggregation.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }
    
    /**
     * Aggregated metric data
     */
    public static class AggregatedMetric {
        private final String name;
        private final AtomicDouble sum = new AtomicDouble(0.0);
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicDouble min = new AtomicDouble(Double.MAX_VALUE);
        private final AtomicDouble max = new AtomicDouble(Double.MIN_VALUE);
        private final AtomicDouble lastValue = new AtomicDouble(0.0);
        private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
        
        public AggregatedMetric(String name) {
            this.name = name;
        }
        
        public void update(double value) {
            sum.addAndGet(value);
            count.incrementAndGet();
            lastValue.set(value);
            lastUpdateTime.set(System.currentTimeMillis());
            
            // Update min and max
            synchronized (this) {
                if (value < min.get()) {
                    min.set(value);
                }
                if (value > max.get()) {
                    max.set(value);
                }
            }
        }
        
        public void updateHistogram(double sum, long count) {
            this.sum.addAndGet(sum);
            this.count.addAndGet(count);
            lastUpdateTime.set(System.currentTimeMillis());
        }
        
        public double getSum() {
            return sum.get();
        }
        
        public long getCount() {
            return count.get();
        }
        
        public double getAverage() {
            return count.get() > 0 ? sum.get() / count.get() : 0.0;
        }
        
        public double getMin() {
            return min.get() == Double.MAX_VALUE ? 0.0 : min.get();
        }
        
        public double getMax() {
            return max.get() == Double.MIN_VALUE ? 0.0 : max.get();
        }
        
        public double getLastValue() {
            return lastValue.get();
        }
        
        public long getLastUpdateTime() {
            return lastUpdateTime.get();
        }
        
        public String getName() {
            return name;
        }
    }
    
    /**
     * Aggregation statistics
     */
    public static class AggregationStatistics {
        private final long totalMetricsRecorded;
        private final long totalMetricsAggregated;
        private final long aggregationErrors;
        private final int aggregatedMetricsCount;
        private final long timeSinceLastAggregation;
        
        public AggregationStatistics(long totalMetricsRecorded, long totalMetricsAggregated, 
                                    long aggregationErrors, int aggregatedMetricsCount, 
                                    long timeSinceLastAggregation) {
            this.totalMetricsRecorded = totalMetricsRecorded;
            this.totalMetricsAggregated = totalMetricsAggregated;
            this.aggregationErrors = aggregationErrors;
            this.aggregatedMetricsCount = aggregatedMetricsCount;
            this.timeSinceLastAggregation = timeSinceLastAggregation;
        }
        
        public long getTotalMetricsRecorded() { return totalMetricsRecorded; }
        public long getTotalMetricsAggregated() { return totalMetricsAggregated; }
        public long getAggregationErrors() { return aggregationErrors; }
        public int getAggregatedMetricsCount() { return aggregatedMetricsCount; }
        public long getTimeSinceLastAggregation() { return timeSinceLastAggregation; }
        public double getAggregationRatio() { 
            return totalMetricsRecorded > 0 ? (double) totalMetricsAggregated / totalMetricsRecorded : 0.0; 
        }
    }
}