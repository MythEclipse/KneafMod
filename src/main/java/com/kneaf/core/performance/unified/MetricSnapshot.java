package com.kneaf.core.performance.unified;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of performance metrics at a specific point in time.
 * Contains all performance-related data collected during a measurement interval.
 */
public final class MetricSnapshot {
    private final Instant timestamp;
    private final Map<MetricsProvider.MetricType, Map<String, Object>> metrics;
    private final MonitoringLevel monitoringLevel;
    private final String source;

    /**
     * Create a new metric snapshot.
     *
     * @param builder the builder to create snapshot from
     */
    private MetricSnapshot(Builder builder) {
        this.timestamp = Instant.now();
        this.metrics = Collections.unmodifiableMap(new HashMap<>(builder.metrics));
        this.monitoringLevel = builder.monitoringLevel != null ? builder.monitoringLevel : MonitoringLevel.BASIC;
        this.source = builder.source;
    }

    /**
     * Get the timestamp when this snapshot was created.
     *
     * @return snapshot timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get all metrics in this snapshot.
     *
     * @return unmodifiable map of all metrics
     */
    public Map<MetricsProvider.MetricType, Map<String, Object>> getAllMetrics() {
        return metrics;
    }

    /**
     * Get metrics of a specific type.
     *
     * @param type the metric type to get
     * @return unmodifiable map of metrics for the given type, or empty map if none
     */
    public Map<String, Object> getMetrics(MetricsProvider.MetricType type) {
        return metrics.getOrDefault(type, Collections.emptyMap());
    }

    /**
     * Get a specific metric value.
     *
     * @param type  the metric type
     * @param key   the metric key
     * @param clazz the expected return type class
     * @param <T>   the expected return type
     * @return the metric value, or null if not found or type mismatch
     */
    public <T> T getMetric(MetricsProvider.MetricType type, String key, Class<T> clazz) {
        Map<String, Object> typeMetrics = getMetrics(type);
        if (typeMetrics == null) {
            return null;
        }
        
        Object value = typeMetrics.get(key);
        if (value == null) {
            return null;
        }
        
        try {
            return clazz.cast(value);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /**
     * Get the monitoring level at which this snapshot was collected.
     *
     * @return monitoring level
     */
    public MonitoringLevel getMonitoringLevel() {
        return monitoringLevel;
    }

    /**
     * Get the source of this snapshot (e.g., plugin ID, system component).
     *
     * @return source identifier
     */
    public String getSource() {
        return source;
    }

    /**
     * Get all metric types present in this snapshot.
     *
     * @return set of metric types
     */
    public Set<MetricsProvider.MetricType> getMetricTypes() {
        return metrics.keySet();
    }

    /**
     * Builder for MetricSnapshot.
     */
    public static class Builder {
        private final Map<MetricsProvider.MetricType, Map<String, Object>> metrics = new HashMap<>();
        private MonitoringLevel monitoringLevel;
        private String source;

        /**
         * Set the monitoring level for this snapshot.
         *
         * @param monitoringLevel the monitoring level
         * @return builder
         */
        public Builder monitoringLevel(MonitoringLevel monitoringLevel) {
            this.monitoringLevel = monitoringLevel;
            return this;
        }

        /**
         * Set the source of this snapshot.
         *
         * @param source the source identifier
         * @return builder
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Add a metric to the snapshot.
         *
         * @param type the metric type
         * @param key  the metric key
         * @param value the metric value
         * @return builder
         */
        public Builder addMetric(MetricsProvider.MetricType type, String key, Object value) {
            Objects.requireNonNull(type, "Metric type must not be null");
            Objects.requireNonNull(key, "Metric key must not be null");
            Objects.requireNonNull(value, "Metric value must not be null");

            metrics.computeIfAbsent(type, k -> new HashMap<>()).put(key, value);
            return this;
        }

        /**
         * Add multiple metrics of the same type to the snapshot.
         *
         * @param type   the metric type
         * @param metrics the metrics to add
         * @return builder
         */
        public Builder addMetrics(MetricsProvider.MetricType type, Map<String, Object> metrics) {
            Objects.requireNonNull(type, "Metric type must not be null");
            Objects.requireNonNull(metrics, "Metrics map must not be null");

            metrics.forEach((key, value) -> addMetric(type, key, value));
            return this;
        }

        /**
         * Build the MetricSnapshot instance.
         *
         * @return metric snapshot
         */
        public MetricSnapshot build() {
            return new MetricSnapshot(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetricSnapshot that = (MetricSnapshot) o;
        return timestamp.equals(that.timestamp) &&
                metrics.equals(that.metrics) &&
                monitoringLevel == that.monitoringLevel &&
                Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, metrics, monitoringLevel, source);
    }

    @Override
    public String toString() {
        return "MetricSnapshot{" +
                "timestamp=" + timestamp +
                ", monitoringLevel=" + monitoringLevel +
                ", source='" + source + '\'' +
                ", metricTypes=" + metrics.keySet() +
                '}';
    }
}