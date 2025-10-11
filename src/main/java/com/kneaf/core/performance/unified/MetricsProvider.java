package com.kneaf.core.performance.unified;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Interface for providing performance metrics from plugins.
 * Implementations should provide thread-safe access to performance data.
 */
public interface MetricsProvider {

    /**
     * Get the current metric snapshot.
     *
     * @return current metric snapshot
     */
    MetricSnapshot getCurrentSnapshot();

    /**
     * Get historical metrics for the given time range.
     *
     * @param start start time
     * @param end   end time
     * @return map of timestamps to metric snapshots
     */
    Map<Instant, MetricSnapshot> getHistoricalMetrics(Instant start, Instant end);

    /**
     * Get the supported metric types by this provider.
     *
     * @return set of supported metric types
     */
    Set<MetricType> getSupportedMetricTypes();

    /**
     * Reset all metrics to their initial state.
     */
    void resetMetrics();

    /**
     * Register a metric listener that will be notified of metric updates.
     *
     * @param listener the listener to register
     */
    void registerMetricListener(MetricListener listener);

    /**
     * Unregister a metric listener.
     *
     * @param listener the listener to unregister
     */
    void unregisterMetricListener(MetricListener listener);

    /**
     * Available metric types that can be provided by implementations.
     */
    enum MetricType {
        CPU_USAGE,
        MEMORY_USAGE,
        TPS,
        ENTITY_PROCESSING,
        BLOCK_PROCESSING,
        ITEM_PROCESSING,
        MOB_PROCESSING,
        NETWORK_LATENCY,
        JNI_CALLS,
        LOCK_CONTENTION,
        ALLOCATION_STATS,
        GC_STATS,
        THREAD_USAGE,
        DATABASE_PERFORMANCE,
        CHUNK_LOADING,
        PLAYER_COUNT,
        WORLD_RENDER,
        CUSTOM
    }

    /**
     * Listener interface for metric updates.
     */
    interface MetricListener {
        /**
         * Called when metrics are updated.
         *
         * @param snapshot the new metric snapshot
         */
        void onMetricsUpdated(MetricSnapshot snapshot);
    }
}