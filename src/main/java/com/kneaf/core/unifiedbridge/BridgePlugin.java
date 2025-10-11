package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extensible plugin architecture for bridge operations.
 * Allows third-party extensions to hook into bridge functionality.
 */
public abstract class BridgePlugin {
    private static final Logger LOGGER = Logger.getLogger(BridgePlugin.class.getName());
    private static final ConcurrentMap<String, BridgePlugin> registeredPlugins = new ConcurrentHashMap<>();
    
    private UnifiedBridge bridge;
    private boolean isInitialized = false;
    private String pluginId;

    /**
     * Initialize the plugin with a bridge instance.
     * @param bridge Bridge instance to use
     * @throws Exception If initialization fails
     */
    public final void initialize(UnifiedBridge bridge) throws Exception {
        Objects.requireNonNull(bridge, "Bridge cannot be null");
        
        if (isInitialized) {
            throw new IllegalStateException("Plugin already initialized");
        }
        
        this.bridge = bridge;
        this.pluginId = generatePluginId();
        
        LOGGER.fine(() -> "Initializing plugin: " + getClass().getName());
        
        try {
            doInitialize();
            isInitialized = true;
            registeredPlugins.put(pluginId, this);
            LOGGER.fine(() -> "Plugin initialized successfully: " + pluginId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize plugin: " + getClass().getName(), e);
            throw e;
        }
    }

    /**
     * Destroy the plugin and clean up resources.
     * @throws Exception If destruction fails
     */
    public final void destroy() throws Exception {
        if (!isInitialized) {
            return; // Nothing to destroy
        }
        
        LOGGER.fine(() -> "Destroying plugin: " + pluginId);
        
        try {
            doDestroy();
            isInitialized = false;
            registeredPlugins.remove(pluginId);
            this.bridge = null;
            LOGGER.fine(() -> "Plugin destroyed successfully: " + pluginId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to destroy plugin: " + pluginId, e);
            throw e;
        }
    }

    /**
     * Get the bridge instance associated with this plugin.
     * @return Bridge instance
     * @throws IllegalStateException If plugin is not initialized
     */
    protected final UnifiedBridge getBridge() {
        ensureInitialized();
        return bridge;
    }

    /**
     * Get the unique plugin ID.
     * @return Plugin ID
     */
    public final String getPluginId() {
        ensureInitialized();
        return pluginId;
    }

    /**
     * Check if the plugin is initialized.
     * @return true if initialized, false otherwise
     */
    public final boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Get statistics about the plugin.
     * @return Map containing plugin statistics
     */
    public Map<String, Object> getPluginStats() {
        ensureInitialized();
        return Map.of(
                "pluginId", pluginId,
                "pluginType", getClass().getSimpleName(),
                "isInitialized", isInitialized,
                "bridgeType", bridge.getClass().getSimpleName()
        );
    }

    /**
     * Get all registered plugins.
     * @return Map of plugin IDs to plugins
     */
    public static Map<String, BridgePlugin> getRegisteredPlugins() {
        return new ConcurrentHashMap<>(registeredPlugins);
    }

    /**
     * Get a plugin by ID.
     * @param pluginId Plugin ID
     * @return Plugin instance or null if not found
     */
    public static BridgePlugin getPlugin(String pluginId) {
        return registeredPlugins.get(pluginId);
    }

    /**
     * Implementation-specific initialization logic.
     * @throws Exception If initialization fails
     */
    protected abstract void doInitialize() throws Exception;

    /**
     * Implementation-specific destruction logic.
     * @throws Exception If destruction fails
     */
    protected abstract void doDestroy() throws Exception;

    /**
     * Generate a unique plugin ID.
     * @return Unique plugin ID
     */
    private String generatePluginId() {
        return getClass().getName() + "-" + System.nanoTime();
    }

    /**
     * Ensure the plugin is initialized before accessing bridge functionality.
     * @throws IllegalStateException If plugin is not initialized
     */
    private void ensureInitialized() {
        if (!isInitialized) {
            throw new IllegalStateException("Plugin not initialized: " + getClass().getName());
        }
    }

    /**
     * Example plugin that logs all bridge operations.
     */
    public static class OperationLoggingPlugin extends BridgePlugin {
        private BridgeMetrics metrics;

        @Override
        protected void doInitialize() throws Exception {
            metrics = getBridge().getMetrics();
            LOGGER.info("OperationLoggingPlugin initialized - logging all bridge operations");
            
            // In real implementation, you would register hooks for various bridge operations
        }

        @Override
        protected void doDestroy() throws Exception {
            LOGGER.info("OperationLoggingPlugin destroyed");
            metrics = null;
        }

        @Override
        public Map<String, Object> getPluginStats() {
            return Map.of(
                    "pluginType", "OperationLoggingPlugin",
                    "isLogging", true,
                    "metricsEnabled", metrics != null && metrics.getOverallMetrics().containsKey("totalOperations")
            );
        }
    }

    /**
     * Example plugin that provides performance monitoring.
     */
    public static class PerformanceMonitoringPlugin extends BridgePlugin {
        private long totalOperationsMonitored = 0;

        @Override
        protected void doInitialize() throws Exception {
            LOGGER.info("PerformanceMonitoringPlugin initialized - monitoring bridge performance");
            
            // In real implementation, you would register hooks for performance monitoring
        }

        @Override
        protected void doDestroy() throws Exception {
            LOGGER.info("PerformanceMonitoringPlugin destroyed - total operations monitored: " + totalOperationsMonitored);
            totalOperationsMonitored = 0;
        }

        @Override
        public Map<String, Object> getPluginStats() {
            return Map.of(
                    "pluginType", "PerformanceMonitoringPlugin",
                    "totalOperationsMonitored", totalOperationsMonitored,
                    "isMonitoring", true
            );
        }

        /**
         * Record an operation for monitoring.
         * @param operationName Name of the operation
         */
        public void recordOperation(String operationName) {
            totalOperationsMonitored++;
            LOGGER.fine(() -> "Recorded operation: " + operationName + " (total: " + totalOperationsMonitored + ")");
        }
    }
}