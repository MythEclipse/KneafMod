package com.kneaf.core.performance.unified;

import java.util.Set;

/**
 * Interface for handling performance alerts from plugins.
 * Implementations should provide thread-safe alert processing.
 */
public interface AlertHandler {

    /**
     * Register an alert listener that will be notified of new alerts.
     *
     * @param listener the listener to register
     */
    void registerAlertListener(AlertListener listener);

    /**
     * Unregister an alert listener.
     *
     * @param listener the listener to unregister
     */
    void unregisterAlertListener(AlertListener listener);

    /**
     * Get the supported alert types by this handler.
     *
     * @return set of supported alert types
     */
    Set<AlertType> getSupportedAlertTypes();

    /**
     * Check if alerts of the given type are currently enabled.
     *
     * @param alertType the alert type to check
     * @return true if enabled, false otherwise
     */
    boolean isAlertTypeEnabled(AlertType alertType);

    /**
     * Enable or disable alerts of the given type.
     *
     * @param alertType the alert type to modify
     * @param enabled   whether to enable or disable
     */
    void setAlertTypeEnabled(AlertType alertType, boolean enabled);

    /**
     * Get the current alert threshold configuration.
     *
     * @return alert threshold configuration
     */
    AlertThresholds getAlertThresholds();

    /**
     * Set the alert threshold configuration.
     *
     * @param thresholds the threshold configuration to set
     */
    void setAlertThresholds(AlertThresholds thresholds);

    /**
     * Available alert types that can be handled by implementations.
     */
    enum AlertType {
        HIGH_CPU_USAGE,
        HIGH_MEMORY_USAGE,
        LOW_TPS,
        HIGH_ENTITY_PROCESSING_LATENCY,
        HIGH_BLOCK_PROCESSING_LATENCY,
        HIGH_JNI_CALL_LATENCY,
        HIGH_LOCK_CONTENTION,
        HIGH_ALLOCATION_RATE,
        HIGH_GC_RATE,
        HIGH_THREAD_COUNT,
        DATABASE_PERFORMANCE_DEGRADATION,
        HIGH_CHUNK_LOADING_LATENCY,
        WORLD_RENDER_LATENCY,
        CUSTOM
    }

    /**
     * Listener interface for alert notifications.
     */
    interface AlertListener {
        /**
         * Called when a new alert is generated.
         *
         * @param alert the new alert
         */
        void onAlertGenerated(Alert alert);
    }

    /**
     * Threshold configuration for alerts.
     */
    interface AlertThresholds {
        /**
         * Get the CPU usage threshold percentage (0-100).
         *
         * @return CPU usage threshold
         */
        double getCpuUsageThreshold();

        /**
         * Get the memory usage threshold percentage (0-100).
         *
         * @return memory usage threshold
         */
        double getMemoryUsageThreshold();

        /**
         * Get the TPS threshold.
         *
         * @return TPS threshold
         */
        double getTpsThreshold();

        /**
         * Get the entity processing latency threshold in milliseconds.
         *
         * @return entity processing latency threshold
         */
        long getEntityProcessingLatencyThresholdMs();

        /**
         * Get the JNI call latency threshold in milliseconds.
         *
         * @return JNI call latency threshold
         */
        long getJniCallLatencyThresholdMs();

        /**
         * Get the lock contention threshold.
         *
         * @return lock contention threshold
         */
        int getLockContentionThreshold();

        /**
         * Get the allocation rate threshold (allocations per second).
         *
         * @return allocation rate threshold
         */
        long getAllocationRateThreshold();

        /**
         * Get the GC rate threshold (collections per minute).
         *
         * @return GC rate threshold
         */
        long getGcRateThreshold();
    }
}