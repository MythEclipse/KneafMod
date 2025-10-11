package com.kneaf.core.performance.unified;

import java.util.Optional;

/**
 * Interface for performance monitoring plugins.
 * Defines the core contract for all performance plugins that can be loaded
 * by the PerformancePluginRegistry.
 */
public interface PerformancePlugin {

    /**
     * Initialize the plugin with the given context.
     * Called once when the plugin is loaded.
     *
     * @param context the plugin context containing configuration and services
     * @throws PerformancePluginException if initialization fails
     */
    void initialize(PluginContext context) throws PerformancePluginException;

    /**
     * Start the plugin.
     * Called when performance monitoring is enabled.
     *
     * @throws PerformancePluginException if startup fails
     */
    void start() throws PerformancePluginException;

    /**
     * Stop the plugin.
     * Called when performance monitoring is disabled or the plugin is unloaded.
     *
     * @throws PerformancePluginException if shutdown fails
     */
    void stop() throws PerformancePluginException;

    /**
     * Get the plugin metadata.
     *
     * @return plugin metadata
     */
    PluginMetadata getMetadata();

    /**
     * Get the unique plugin ID.
     *
     * @return plugin ID
     */
    default String getPluginId() {
        return getMetadata().getPluginId();
    }

    /**
     * Get the plugin author.
     *
     * @return plugin author
     */
    default String getAuthor() {
        return getMetadata().getAuthor();
    }

    /**
     * Get the plugin display name.
     *
     * @return plugin display name
     */
    default String getDisplayName() {
        return getMetadata().getDisplayName();
    }

    /**
     * Get the plugin version.
     *
     * @return plugin version
     */
    default String getVersion() {
        return getMetadata().getVersion();
    }

    /**
     * Get the minimum required system version for this plugin.
     *
     * @return minimum required version
     */
    default String getMinimumRequiredVersion() {
        return getMetadata().getMinimumRequiredVersion();
    }

    /**
     * Check if this plugin is compatible with the given system version.
     *
     * @param systemVersion the system version to check against
     * @return true if compatible, false otherwise
     */
    default boolean isCompatible(String systemVersion) {
        return getMetadata().isCompatible(systemVersion);
    }

    /**
     * Get the monitoring level supported by this plugin.
     *
     * @return monitoring level
     */
    MonitoringLevel getMonitoringLevel();

    /**
     * Check if the plugin is currently running.
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();

    /**
     * Get the metrics provider for this plugin, if available.
     *
     * @return optional metrics provider
     */
    default Optional<MetricsProvider> getMetricsProvider() {
        return Optional.empty();
    }

    /**
     * Get the alert handler for this plugin, if available.
     *
     * @return optional alert handler
     */
    default Optional<AlertHandler> getAlertHandler() {
        return Optional.empty();
    }

    /**
     * Exception thrown by performance plugins.
     */
    class PerformancePluginException extends Exception {
        public PerformancePluginException(String message) {
            super(message);
        }

        public PerformancePluginException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}