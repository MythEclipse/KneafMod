package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridge metrics and monitoring system for performance tracking and diagnostics.
 * Collects and exposes metrics about bridge operations, worker performance, and resource usage.
 */
public final class BridgeMetrics {
    private static final Logger LOGGER = Logger.getLogger(BridgeMetrics.class.getName());
    private static final BridgeMetrics INSTANCE = new BridgeMetrics();
    
    private BridgeConfiguration config;
    private final ConcurrentMap<String, MetricSeries> metricSeries = new ConcurrentHashMap<>();
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong totalSuccessOperations = new AtomicLong(0);
    private final AtomicLong totalFailedOperations = new AtomicLong(0);
    private final AtomicLong totalBytesProcessed = new AtomicLong(0);
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private final AtomicLong operationCount = new AtomicLong(0);
    private final long metricsResetIntervalMs;

    private BridgeMetrics() {
        this.config = BridgeConfiguration.getDefault();
        this.metricsResetIntervalMs = TimeUnit.MINUTES.toMillis(5); // Reset metrics every 5 minutes
        LOGGER.info("BridgeMetrics initialized with default configuration");
        
        // Initialize default metric series
        initializeDefaultMetrics();
        startMetricsResetTimer();
    }

    /**
     * Get the singleton instance of BridgeMetrics.
     * @return BridgeMetrics instance
     */
    public static BridgeMetrics getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return BridgeMetrics instance
     */
    public static BridgeMetrics getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("BridgeMetrics reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Record a bridge operation for metrics collection.
     * @param operationName Name of the operation
     * @param startTimeNanos Start time in nanoseconds
     * @param endTimeNanos End time in nanoseconds
     * @param bytesProcessed Bytes processed during operation
     * @param success Whether the operation succeeded
     */
    public void recordOperation(String operationName, long startTimeNanos, long endTimeNanos,
                              long bytesProcessed, boolean success) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        try {
            totalOperations.incrementAndGet();
            if (success) {
                totalSuccessOperations.incrementAndGet();
            } else {
                totalFailedOperations.incrementAndGet();
            }
            totalBytesProcessed.addAndGet(bytesProcessed);
            totalLatencyNanos.addAndGet(endTimeNanos - startTimeNanos);
            operationCount.incrementAndGet();
            
            // Record operation in specific metric series
            MetricSeries series = metricSeries.computeIfAbsent(operationName, 
                    k -> new MetricSeries(operationName, config.isEnableDetailedMetrics()));
            series.recordOperation(startTimeNanos, endTimeNanos, bytesProcessed, success);
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to record operation metrics", e);
            // Don't throw exception - we want to fail softly for metrics collection
        }
    }

    /**
     * Record a batch operation for metrics collection.
     * @param batchId Batch identifier
     * @param operationName Name of the operation
     * @param startTimeNanos Start time in nanoseconds
     * @param endTimeNanos End time in nanoseconds
     * @param totalTasks Total tasks in batch
     * @param successfulTasks Successful tasks
     * @param failedTasks Failed tasks
     * @param totalBytesProcessed Bytes processed during batch
     * @param success Whether the batch succeeded
     */
    public void recordBatchOperation(long batchId, String operationName, long startTimeNanos, 
                                   long endTimeNanos, int totalTasks, int successfulTasks, 
                                   int failedTasks, long totalBytesProcessed, boolean success) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        try {
            // Record batch as a special operation
            recordOperation(operationName + ".batch", startTimeNanos, endTimeNanos, 
                    totalBytesProcessed, success);
            
            // Record individual task metrics
            for (int i = 0; i < totalTasks; i++) {
                boolean taskSuccess = (i < successfulTasks);
                recordOperation(operationName + ".task", startTimeNanos, endTimeNanos, 
                        totalBytesProcessed / Math.max(1, totalTasks), taskSuccess);
            }
            
            // Record batch-specific metrics
            MetricSeries batchSeries = metricSeries.computeIfAbsent(operationName + ".batch",
                    k -> new MetricSeries(operationName + ".batch", config.isEnableDetailedMetrics()));
            batchSeries.recordBatchOperation(batchId, startTimeNanos, endTimeNanos, totalTasks,
                    successfulTasks, failedTasks, totalBytesProcessed, success);
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to record batch operation metrics", e);
            // Don't throw exception - we want to fail softly for metrics collection
        }
    }

    /**
     * Get overall bridge metrics.
     * @return Map containing overall bridge metrics
     */
    public Map<String, Object> getOverallMetrics() {
        long operationCount = this.operationCount.getAndSet(0); // Reset counter
        double averageLatencyNanos = operationCount > 0 
                ? (double) totalLatencyNanos.getAndSet(0) / operationCount 
                : 0;
        
        return Map.of(
                "timestamp", System.currentTimeMillis(),
                "totalOperations", totalOperations.get(),
                "totalSuccessOperations", totalSuccessOperations.get(),
                "totalFailedOperations", totalFailedOperations.get(),
                "successRate", operationCount > 0 ? 
                        (double) totalSuccessOperations.get() / operationCount : 0,
                "totalBytesProcessed", totalBytesProcessed.get(),
                "averageLatencyNanos", averageLatencyNanos,
                "averageLatencyMs", TimeUnit.NANOSECONDS.toMillis((long) averageLatencyNanos),
                "operationCount", operationCount,
                "detailedMetricsEnabled", config.isEnableDetailedMetrics()
        );
    }

    /**
     * Get metrics for a specific operation.
     * @param operationName Name of the operation
     * @return Map containing operation-specific metrics or empty map if not found
     */
    public Map<String, Object> getOperationMetrics(String operationName) {
        MetricSeries series = metricSeries.get(operationName);
        return series != null ? series.getMetrics() : Map.of();
    }

    /**
     * Get all metric series.
     * @return Map containing all metric series
     */
    public Map<String, MetricSeries> getAllMetricSeries() {
        return new ConcurrentHashMap<>(metricSeries);
    }

    /**
     * Reset all metrics (called automatically at intervals).
     */
    public void resetMetrics() {
        LOGGER.info("Resetting BridgeMetrics");
        
        // Reset overall metrics
        totalOperations.set(0);
        totalSuccessOperations.set(0);
        totalFailedOperations.set(0);
        totalBytesProcessed.set(0);
        totalLatencyNanos.set(0);
        operationCount.set(0);
        
        // Reset individual metric series
        for (MetricSeries series : metricSeries.values()) {
            series.reset();
        }
        
        LOGGER.info("BridgeMetrics reset complete");
    }

    /**
     * Initialize default metric series.
     */
    private void initializeDefaultMetrics() {
        // Common bridge operations
        metricSeries.put("worker.create", new MetricSeries("worker.create", config.isEnableDetailedMetrics()));
        metricSeries.put("worker.destroy", new MetricSeries("worker.destroy", config.isEnableDetailedMetrics()));
        metricSeries.put("task.push", new MetricSeries("task.push", config.isEnableDetailedMetrics()));
        metricSeries.put("task.poll", new MetricSeries("task.poll", config.isEnableDetailedMetrics()));
        metricSeries.put("batch.push", new MetricSeries("batch.push", config.isEnableDetailedMetrics()));
        metricSeries.put("batch.poll", new MetricSeries("batch.poll", config.isEnableDetailedMetrics()));
        metricSeries.put("buffer.allocate", new MetricSeries("buffer.allocate", config.isEnableDetailedMetrics()));
        metricSeries.put("buffer.free", new MetricSeries("buffer.free", config.isEnableDetailedMetrics()));
        metricSeries.put("zeroCopy.submit", new MetricSeries("zeroCopy.submit", config.isEnableDetailedMetrics()));
        metricSeries.put("zeroCopy.poll", new MetricSeries("zeroCopy.poll", config.isEnableDetailedMetrics()));
    }

    /**
     * Start a background timer to reset metrics at intervals.
     */
    private void startMetricsResetTimer() {
        new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(metricsResetIntervalMs);
                    resetMetrics();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info("BridgeMetrics reset timer interrupted");
            }
        }, "BridgeMetrics-ResetTimer").start();
    }

    /**
     * Internal class representing a time-series of metrics for a specific operation.
     */
    public static final class MetricSeries {
        private final String operationName;
        private final boolean enableDetailedMetrics;
        private final ConcurrentMap<Long, OperationMetric> operationMetrics = new ConcurrentHashMap<>();
        private final AtomicLong seriesOperations = new AtomicLong(0);
        private final AtomicLong seriesSuccessOperations = new AtomicLong(0);
        private final AtomicLong seriesFailedOperations = new AtomicLong(0);
        private final AtomicLong seriesBytesProcessed = new AtomicLong(0);
        private final AtomicLong seriesLatencyNanos = new AtomicLong(0);
        private final AtomicLong lastOperationTime = new AtomicLong(System.currentTimeMillis());

        MetricSeries(String operationName, boolean enableDetailedMetrics) {
            this.operationName = operationName;
            this.enableDetailedMetrics = enableDetailedMetrics;
        }

        /**
         * Record a single operation in the series.
         * @param startTimeNanos Start time in nanoseconds
         * @param endTimeNanos End time in nanoseconds
         * @param bytesProcessed Bytes processed
         * @param success Whether the operation succeeded
         */
        public void recordOperation(long startTimeNanos, long endTimeNanos, 
                                  long bytesProcessed, boolean success) {
            long operationId = System.nanoTime();
            OperationMetric metric = new OperationMetric(operationId, startTimeNanos, endTimeNanos,
                    bytesProcessed, success);
            
            operationMetrics.put(operationId, metric);
            seriesOperations.incrementAndGet();
            if (success) {
                seriesSuccessOperations.incrementAndGet();
            } else {
                seriesFailedOperations.incrementAndGet();
            }
            seriesBytesProcessed.addAndGet(bytesProcessed);
            seriesLatencyNanos.addAndGet(endTimeNanos - startTimeNanos);
            lastOperationTime.set(System.currentTimeMillis());
        }

        /**
         * Record a batch operation in the series.
         * @param batchId Batch identifier
         * @param startTimeNanos Start time in nanoseconds
         * @param endTimeNanos End time in nanoseconds
         * @param totalTasks Total tasks
         * @param successfulTasks Successful tasks
         * @param failedTasks Failed tasks
         * @param totalBytesProcessed Bytes processed
         * @param success Whether the batch succeeded
         */
        public void recordBatchOperation(long batchId, long startTimeNanos, long endTimeNanos,
                                       int totalTasks, int successfulTasks, int failedTasks,
                                       long totalBytesProcessed, boolean success) {
            long operationId = batchId;
            BatchOperationMetric metric = new BatchOperationMetric(operationId, startTimeNanos, endTimeNanos,
                    totalBytesProcessed, success, totalTasks, successfulTasks, failedTasks);
            
            operationMetrics.put(operationId, metric);
            seriesOperations.incrementAndGet();
            if (success) {
                seriesSuccessOperations.incrementAndGet();
            } else {
                seriesFailedOperations.incrementAndGet();
            }
            seriesBytesProcessed.addAndGet(totalBytesProcessed);
            seriesLatencyNanos.addAndGet(endTimeNanos - startTimeNanos);
            lastOperationTime.set(System.currentTimeMillis());
        }

        /**
         * Get metrics for this series.
         * @return Map containing series metrics
         */
        public Map<String, Object> getMetrics() {
            long operationCount = seriesOperations.get();
            double successRate = operationCount > 0 
                    ? (double) seriesSuccessOperations.get() / operationCount : 0;
            double averageLatencyNanos = operationCount > 0 
                    ? (double) seriesLatencyNanos.get() / operationCount : 0;
            
            Map<String, Object> baseMetrics = Map.of(
                    "operationName", operationName,
                    "operationCount", operationCount,
                    "successRate", successRate,
                    "totalSuccessOperations", seriesSuccessOperations.get(),
                    "totalFailedOperations", seriesFailedOperations.get(),
                    "totalBytesProcessed", seriesBytesProcessed.get(),
                    "averageLatencyNanos", averageLatencyNanos,
                    "averageLatencyMs", TimeUnit.NANOSECONDS.toMillis((long) averageLatencyNanos),
                    "lastOperationTime", lastOperationTime.get(),
                    "enableDetailedMetrics", enableDetailedMetrics
            );
            
            if (enableDetailedMetrics) {
                // For detailed metrics, we could return more information about individual operations
                // but for brevity, we'll just return the base metrics
                return baseMetrics;
            } else {
                return baseMetrics;
            }
        }

        /**
         * Reset this metric series.
         */
        public void reset() {
            operationMetrics.clear();
            seriesOperations.set(0);
            seriesSuccessOperations.set(0);
            seriesFailedOperations.set(0);
            seriesBytesProcessed.set(0);
            seriesLatencyNanos.set(0);
            lastOperationTime.set(System.currentTimeMillis());
        }

        /**
         * Get all operation metrics in this series.
         * @return Map containing all operation metrics (only if detailed metrics enabled)
         */
        public Map<Long, OperationMetric> getOperationMetrics() {
            return enableDetailedMetrics ? new ConcurrentHashMap<>(operationMetrics) : Map.of();
        }
    }

    /**
     * Internal class representing a single operation metric.
     */
    public static class OperationMetric {
        private final long operationId;
        private final long startTimeNanos;
        private final long endTimeNanos;
        private final long bytesProcessed;
        private final boolean success;

        OperationMetric(long operationId, long startTimeNanos, long endTimeNanos,
                      long bytesProcessed, boolean success) {
            this.operationId = operationId;
            this.startTimeNanos = startTimeNanos;
            this.endTimeNanos = endTimeNanos;
            this.bytesProcessed = bytesProcessed;
            this.success = success;
        }

        // Getters
        public long getOperationId() { return operationId; }
        public long getStartTimeNanos() { return startTimeNanos; }
        public long getEndTimeNanos() { return endTimeNanos; }
        public long getDurationNanos() { return endTimeNanos - startTimeNanos; }
        public long getDurationMs() { return TimeUnit.NANOSECONDS.toMillis(getDurationNanos()); }
        public long getBytesProcessed() { return bytesProcessed; }
        public boolean isSuccess() { return success; }
    }

    /**
     * Internal class representing a batch operation metric.
     */
    public static class BatchOperationMetric extends OperationMetric {
        private final int totalTasks;
        private final int successfulTasks;
        private final int failedTasks;

        BatchOperationMetric(long operationId, long startTimeNanos, long endTimeNanos,
                           long bytesProcessed, boolean success, int totalTasks,
                           int successfulTasks, int failedTasks) {
            super(operationId, startTimeNanos, endTimeNanos, bytesProcessed, success);
            this.totalTasks = totalTasks;
            this.successfulTasks = successfulTasks;
            this.failedTasks = failedTasks;
        }

        // Additional getters for batch operations
        public int getTotalTasks() { return totalTasks; }
        public int getSuccessfulTasks() { return successfulTasks; }
        public int getFailedTasks() { return failedTasks; }
        public double getTaskSuccessRate() {
            return totalTasks > 0 ? (double) successfulTasks / totalTasks : 0;
        }
    }
}