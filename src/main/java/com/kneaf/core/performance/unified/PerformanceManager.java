package com.kneaf.core.performance.unified;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.performance.monitoring.PerformanceConfig;
import com.kneaf.core.performance.monitoring.PerformanceMetricsLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for unified performance monitoring that coordinates plugin-based
 * performance monitoring and provides a consistent interface to performance data.
 */
public class PerformanceManager {
    public static final String SYSTEM_VERSION = "1.0.0";
    private static final PerformanceManager INSTANCE = new PerformanceManager();

    private static final ConfigurationManager CONFIG_MANAGER = ConfigurationManager.getInstance();
    private final PerformancePluginRegistry pluginRegistry;
    private final Map<String, MetricsProvider> metricsProviders = new ConcurrentHashMap<>();
    private final Map<String, AlertHandler> alertHandlers = new ConcurrentHashMap<>();
    private final TPSMonitor tpsMonitor;
    private final MetricsCollector metricsCollector;
    private PerformanceConfig performanceConfig;
    private boolean enabled = false;
    private MonitoringLevel currentMonitoringLevel = MonitoringLevel.BASIC;

    /**
     * Private constructor to prevent instantiation.
     */
    private PerformanceManager() {
        this.pluginRegistry = PerformancePluginRegistry.getInstance();
        this.metricsCollector = new MetricsCollector();
        
        // Always start with a default configuration
        PerformanceConfig defaultConfig = new PerformanceConfig.Builder().build();
        
        // Try to load from configuration manager
        this.performanceConfig = defaultConfig;
        try {
            PerformanceConfig loadedConfig = CONFIG_MANAGER.getConfiguration(PerformanceConfig.class);
            if (loadedConfig != null) {
                this.performanceConfig = loadedConfig;
                getLogger().debug("Loaded performance configuration from manager");
            } else if (defaultConfig == null) {
                getLogger().warn("No performance configuration available");
            }
        } catch (Exception e) {
            getLogger().warn("Failed to load performance configuration from manager, using default", e);
        }
        
        // Only create TPSMonitor if we have a valid configuration
        if (this.performanceConfig != null) {
            this.tpsMonitor = new TPSMonitor(this.metricsCollector);
        } else {
            this.tpsMonitor = null;
            getLogger().warn("TPSMonitor not created due to missing configuration");
        }
    }

    /**
     * Get the singleton instance of PerformanceManager.
     *
     * @return singleton instance
     */
    public static PerformanceManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize the performance manager and all plugins.
     *
     * @throws PerformancePlugin.PerformancePluginException if initialization fails
     */
    public synchronized void initialize() throws PerformancePlugin.PerformancePluginException {
        if (enabled) {
            return;
        }

        try {
            // Initialize plugin registry
            pluginRegistry.initialize(this);

            // Start plugins that are compatible with current monitoring level
            startPluginsForCurrentLevel();

            // Register metrics providers and alert handlers from plugins
            registerPluginServices();

            enabled = true;
            getLogger().info("Performance manager initialized with monitoring level: {}", currentMonitoringLevel);

        } catch (Exception e) {
            throw new PerformancePlugin.PerformancePluginException("Failed to initialize performance manager", e);
        }
    }

    /**
     * Start plugins that are compatible with the current monitoring level.
     * This replaces the generic startPlugins call with level-aware startup.
     *
     * @throws PerformancePlugin.PerformancePluginException if starting plugins fails
     */
    private void startPluginsForCurrentLevel() throws PerformancePlugin.PerformancePluginException {
        int startedCount = 0;
        
        for (PerformancePlugin plugin : pluginRegistry.getRegisteredPlugins()) {
            if (plugin.isCompatible(SYSTEM_VERSION) &&
                plugin.getMonitoringLevel().getLevel() <= currentMonitoringLevel.getLevel()) {
                try {
                    plugin.start();
                    startedCount++;
                    getLogger().debug("Started plugin: {} (level: {})",
                        plugin.getPluginId(), plugin.getMonitoringLevel());

                } catch (Exception e) {
                    getLogger().error("Failed to start plugin {}: {}",
                        plugin.getPluginId(), e.getMessage());
                    // Don't fail the entire operation if one plugin fails
                }
            } else {
                getLogger().debug("Skipped plugin {} (incompatible or level {} > {})",
                    plugin.getPluginId(), plugin.getMonitoringLevel(), currentMonitoringLevel);
            }
        }
        
        getLogger().info("Started {} plugins for monitoring level {}", startedCount, currentMonitoringLevel);
    }

    /**
     * Register metrics providers and alert handlers from all plugins.
     * Only registers services from plugins that are active at the current monitoring level.
     */
    private void registerPluginServices() {
        metricsProviders.clear();
        alertHandlers.clear();

        for (PerformancePlugin plugin : pluginRegistry.getRegisteredPlugins()) {
            // Only register services from plugins that should be active at current level
            if (plugin.getMonitoringLevel().getLevel() <= currentMonitoringLevel.getLevel() && plugin.isRunning()) {
                plugin.getMetricsProvider().ifPresent(provider -> {
                    String pluginId = plugin.getPluginId();
                    metricsProviders.put(pluginId, provider);
                    getLogger().debug("Registered metrics provider from plugin: {} (level: {})",
                        pluginId, plugin.getMonitoringLevel());
                });

                plugin.getAlertHandler().ifPresent(handler -> {
                    String pluginId = plugin.getPluginId();
                    alertHandlers.put(pluginId, handler);
                    getLogger().debug("Registered alert handler from plugin: {} (level: {})",
                        pluginId, plugin.getMonitoringLevel());
                });
            }
        }
    }

    /**
     * Enable performance monitoring.
     */
    public synchronized void enable() {
        if (enabled) {
            return;
        }

        try {
            initialize();
            enabled = true;
            getLogger().info("Performance monitoring enabled");

        } catch (PerformancePlugin.PerformancePluginException e) {
            getLogger().error("Failed to enable performance monitoring: {}", e.getMessage());
            enabled = false;
        }
    }

    /**
     * Disable performance monitoring.
     */
    public synchronized void disable() {
        if (!enabled) {
            return;
        }

        try {
            pluginRegistry.stopPlugins();
            metricsProviders.clear();
            alertHandlers.clear();
            enabled = false;
            getLogger().info("Performance monitoring disabled");

        } catch (PerformancePlugin.PerformancePluginException e) {
            getLogger().error("Failed to disable performance monitoring: {}", e.getMessage());
            // Keep enabled = true since we couldn't properly disable
        }
    }

    /**
     * Check if performance monitoring is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the current TPS (Ticks Per Second).
     *
     * @return current TPS
     */
    public double getCurrentTPS() {
        if (!enabled) {
            return 0.0;
        }
        return tpsMonitor.getAverageTPS();
    }

    /**
     * Get the average TPS over the last minute.
     *
     * @return average TPS
     */
    public double getAverageTPS() {
        if (!enabled) {
            return 0.0;
        }
        return tpsMonitor.getAverageTPS();
    }

    /**
     * Get the used memory percentage.
     *
     * @return used memory percentage (0-100)
     */
    public double getUsedMemoryPercentage() {
        if (!enabled) {
            return 0.0;
        }
        Map<String, Object> memoryMetrics = metricsCollector.getMemoryMetrics();
        long total = ((Number) memoryMetrics.get("totalHeapBytes")).longValue();
        long used = ((Number) memoryMetrics.get("usedHeapBytes")).longValue();
        return total > 0 ? (used * 100.0) / total : 0.0;
    }

    /**
     * Get the current monitoring level.
     *
     * @return current monitoring level
     */
    public MonitoringLevel getCurrentMonitoringLevel() {
        return currentMonitoringLevel;
    }

    /**
     * Set the monitoring level.
     *
     * @param level the monitoring level to set
     */
    public synchronized void setMonitoringLevel(MonitoringLevel level) {
        Objects.requireNonNull(level, "Monitoring level must not be null");

        if (currentMonitoringLevel == level) {
            return;
        }

        MonitoringLevel previousLevel = currentMonitoringLevel;
        currentMonitoringLevel = level;
        getLogger().info("Monitoring level changed from {} to: {}", previousLevel, level);

        // Implement level-specific plugin management
        updatePluginStatesForLevel(level);
    }

    /**
     * Update plugin states based on the new monitoring level.
     * This method will start plugins that are compatible with the new level
     * and stop plugins that are no longer compatible.
     *
     * @param newLevel the new monitoring level
     */
    private void updatePluginStatesForLevel(MonitoringLevel newLevel) {
        if (!enabled) {
            getLogger().debug("Performance manager is disabled, skipping plugin level update");
            return;
        }

        getLogger().debug("Updating plugin states for monitoring level: {}", newLevel);

        // Track plugins that need to be started or stopped
        List<PerformancePlugin> pluginsToStart = new ArrayList<>();
        List<PerformancePlugin> pluginsToStop = new ArrayList<>();

        // Analyze current plugin states
        for (PerformancePlugin plugin : pluginRegistry.getRegisteredPlugins()) {
            MonitoringLevel pluginLevel = plugin.getMonitoringLevel();
            String pluginId = plugin.getPluginId();

            // Check if plugin should be active at the new level
            boolean shouldBeActive = pluginLevel.getLevel() <= newLevel.getLevel();

            // Check current plugin state
            boolean isCurrentlyActive = plugin.isRunning();

            if (shouldBeActive && !isCurrentlyActive) {
                // Plugin should be active but isn't - start it
                pluginsToStart.add(plugin);
                getLogger().debug("Plugin {} will be started (level {} <= {})",
                    pluginId, pluginLevel, newLevel);

            } else if (!shouldBeActive && isCurrentlyActive) {
                // Plugin is active but shouldn't be - stop it
                pluginsToStop.add(plugin);
                getLogger().debug("Plugin {} will be stopped (level {} > {})",
                    pluginId, pluginLevel, newLevel);
            }
        }

        // Stop plugins that are no longer compatible
        for (PerformancePlugin plugin : pluginsToStop) {
            try {
                plugin.stop();
                getLogger().info("Stopped plugin {} due to monitoring level change", plugin.getPluginId());

            } catch (PerformancePlugin.PerformancePluginException e) {
                getLogger().error("Failed to stop plugin {} during level change: {}",
                    plugin.getPluginId(), e.getMessage(), e);
            }
        }

        // Start plugins that are now compatible
        for (PerformancePlugin plugin : pluginsToStart) {
            try {
                plugin.start();
                getLogger().info("Started plugin {} due to monitoring level change", plugin.getPluginId());

            } catch (PerformancePlugin.PerformancePluginException e) {
                getLogger().error("Failed to start plugin {} during level change: {}",
                    plugin.getPluginId(), e.getMessage(), e);
            }
        }

        // Update registered services (metrics providers and alert handlers)
        registerPluginServices();

        getLogger().info("Plugin level update completed. Started: {}, Stopped: {}",
            pluginsToStart.size(), pluginsToStop.size());
    }

    /**
     * Get a metric snapshot from all registered providers.
     *
     * @return combined metric snapshot
     */
    public MetricSnapshot getCombinedMetricSnapshot() {
        if (!enabled) {
            return createEmptySnapshot();
        }

        MetricSnapshot.Builder builder = new MetricSnapshot.Builder()
                .monitoringLevel(currentMonitoringLevel)
                .source("PerformanceManager");

        // Add system metrics
        addSystemMetrics(builder);

        // Add plugin metrics
        addPluginMetrics(builder);

        return builder.build();
    }

    /**
     * Add system metrics to the snapshot builder.
     *
     * @param builder the snapshot builder
     */
    private void addSystemMetrics(MetricSnapshot.Builder builder) {
        Map<String, Object> memoryMetrics = metricsCollector.getMemoryMetrics();
        builder.addMetric(MetricsProvider.MetricType.CPU_USAGE, "percentage", 0.0) // CPU usage not directly available
                .addMetric(MetricsProvider.MetricType.MEMORY_USAGE, "usedPercentage", getUsedMemoryPercentage())
                .addMetric(MetricsProvider.MetricType.MEMORY_USAGE, "totalBytes", memoryMetrics.get("totalHeapBytes"))
                .addMetric(MetricsProvider.MetricType.MEMORY_USAGE, "usedBytes", memoryMetrics.get("usedHeapBytes"))
                .addMetric(MetricsProvider.MetricType.TPS, "current", getCurrentTPS())
                .addMetric(MetricsProvider.MetricType.TPS, "average", getAverageTPS())
                .addMetric(MetricsProvider.MetricType.JNI_CALLS, "totalCalls", metricsCollector.getJniCallMetrics().get("totalCalls"))
                .addMetric(MetricsProvider.MetricType.JNI_CALLS, "totalDurationMs", metricsCollector.getJniCallMetrics().get("totalDurationMs"))
                .addMetric(MetricsProvider.MetricType.LOCK_CONTENTION, "totalEvents", metricsCollector.getLockContentionMetrics().get("totalContentionEvents"))
                .addMetric(MetricsProvider.MetricType.ALLOCATION_STATS, "totalAllocations", metricsCollector.getAllocationMetrics().get("totalAllocations"));
    }

    /**
     * Add plugin metrics to the snapshot builder.
     *
     * @param builder the snapshot builder
     */
    private void addPluginMetrics(MetricSnapshot.Builder builder) {
        for (Map.Entry<String, MetricsProvider> entry : metricsProviders.entrySet()) {
            String pluginId = entry.getKey();
            MetricSnapshot pluginSnapshot = entry.getValue().getCurrentSnapshot();

            if (pluginSnapshot != null) {
                for (Map.Entry<MetricsProvider.MetricType, Map<String, Object>> metricEntry : pluginSnapshot.getAllMetrics().entrySet()) {
                    MetricsProvider.MetricType type = metricEntry.getKey();
                    Map<String, Object> metrics = metricEntry.getValue();

                    for (Map.Entry<String, Object> metric : metrics.entrySet()) {
                        String key = String.format("%s.%s", pluginId, metric.getKey());
                        builder.addMetric(type, key, metric.getValue());
                    }
                }
            }
        }
    }

    /**
     * Create an empty metric snapshot.
     *
     * @return empty snapshot
     */
    private MetricSnapshot createEmptySnapshot() {
        return new MetricSnapshot.Builder()
                .monitoringLevel(MonitoringLevel.OFF)
                .source("PerformanceManager")
                .build();
    }

    /**
     * Rotate the performance log.
     */
    public void rotateLog() {
        PerformanceMetricsLogger.rotateNow();
        getLogger().info("Performance log rotated");
    }

    /**
     * Get the performance configuration.
     *
     * @return performance configuration
     */
    public PerformanceConfig getPerformanceConfig() {
        return performanceConfig;
    }

    /**
     * Get the TPS monitor.
     *
     * @return TPS monitor
     */
    public TPSMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    /**
     * Get the metrics collector.
     *
     * @return metrics collector
     */
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    /**

    /**
     * Get the plugin registry.
     *
     * @return plugin registry
     */
    public PerformancePluginRegistry getPluginRegistry() {
        return pluginRegistry;
    }

    /**
     * Get logger for performance manager operations.
     *
     * @return logger instance
     */
    private static org.apache.logging.log4j.Logger getLogger() {
        return org.apache.logging.log4j.LogManager.getLogger(PerformanceManager.class);
    }
}