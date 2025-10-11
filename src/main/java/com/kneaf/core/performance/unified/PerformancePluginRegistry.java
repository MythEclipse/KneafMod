package com.kneaf.core.performance.unified;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ServiceLoader-based registry for performance plugins.
 * Manages the discovery, loading, and lifecycle of performance plugins.
 */
public final class PerformancePluginRegistry {
    private static final PerformancePluginRegistry INSTANCE = new PerformancePluginRegistry();
    private final Map<String, PerformancePlugin> registeredPlugins = new ConcurrentHashMap<>();
    private final Map<String, PerformancePlugin> activePlugins = new ConcurrentHashMap<>();
    private boolean initialized = false;

    /**
     * Private constructor to prevent instantiation.
     */
    private PerformancePluginRegistry() {}

    /**
     * Get the singleton instance of PerformancePluginRegistry.
     *
     * @return singleton instance
     */
    public static PerformancePluginRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize the plugin registry by loading all available plugins.
     *
     * @param performanceManager the performance manager instance
     * @throws PerformancePluginException if initialization fails
     */
    public synchronized void initialize(PerformanceManager performanceManager) throws PerformancePlugin.PerformancePluginException {
        if (initialized) {
            return;
        }

        try {
            // Load plugins using ServiceLoader
            ServiceLoader<PerformancePlugin> loader = ServiceLoader.load(PerformancePlugin.class);
            
            for (PerformancePlugin plugin : loader) {
                registerPlugin(plugin, performanceManager);
            }

            initialized = true;
            getLogger().info("Performance plugin registry initialized with {} plugins", registeredPlugins.size());

        } catch (Exception e) {
            throw new PerformancePlugin.PerformancePluginException("Failed to initialize plugin registry", e);
        }
    }

    /**
     * Register a plugin manually (for testing/debugging).
     *
     * @param plugin the plugin to register
     * @param performanceManager the performance manager instance
     * @throws PerformancePluginException if registration fails
     */
    public synchronized void registerPlugin(PerformancePlugin plugin, PerformanceManager performanceManager) 
            throws PerformancePlugin.PerformancePluginException {
        Objects.requireNonNull(plugin, "Plugin must not be null");

        String pluginId = plugin.getPluginId();
        if (registeredPlugins.containsKey(pluginId)) {
            throw new PerformancePlugin.PerformancePluginException("Plugin with ID '" + pluginId + "' is already registered");
        }

        // Create plugin context
        PluginContext context = new PluginContext.Builder()
                .pluginId(pluginId)
                .performanceManager(performanceManager)
                .build();

        try {
            // Initialize the plugin
            plugin.initialize(context);
            registeredPlugins.put(pluginId, plugin);
            getLogger().info("Registered plugin: {} ({})", plugin.getDisplayName(), pluginId);

        } catch (Exception e) {
            throw new PerformancePlugin.PerformancePluginException("Failed to register plugin '" + pluginId + "'", e);
        }
    }

    /**
     * Unregister a plugin.
     *
     * @param pluginId the plugin ID to unregister
     * @return true if plugin was found and unregistered, false otherwise
     * @throws PerformancePluginException if unregistration fails
     */
    public synchronized boolean unregisterPlugin(String pluginId) throws PerformancePlugin.PerformancePluginException {
        Objects.requireNonNull(pluginId, "Plugin ID must not be null");

        PerformancePlugin plugin = registeredPlugins.remove(pluginId);
        if (plugin == null) {
            return false;
        }

        try {
            // Stop the plugin if it was active
            if (activePlugins.containsKey(pluginId)) {
                plugin.stop();
                activePlugins.remove(pluginId);
            }

            getLogger().info("Unregistered plugin: {}", pluginId);
            return true;

        } catch (Exception e) {
            throw new PerformancePlugin.PerformancePluginException("Failed to unregister plugin '" + pluginId + "'", e);
        }
    }

    /**
     * Start all registered plugins that are compatible with the current system version.
     *
     * @param systemVersion the current system version
     * @throws PerformancePluginException if starting plugins fails
     */
    public synchronized void startPlugins(String systemVersion) throws PerformancePlugin.PerformancePluginException {
        Objects.requireNonNull(systemVersion, "System version must not be null");

        for (PerformancePlugin plugin : registeredPlugins.values()) {
            if (plugin.isCompatible(systemVersion) && !plugin.isRunning()) {
                try {
                    plugin.start();
                    activePlugins.put(plugin.getPluginId(), plugin);
                    getLogger().info("Started plugin: {} ({})", plugin.getDisplayName(), plugin.getPluginId());

                } catch (Exception e) {
                    getLogger().error("Failed to start plugin {}: {}", plugin.getPluginId(), e.getMessage());
                    // Don't fail the entire operation if one plugin fails
                }
            }
        }
    }

    /**
     * Stop all active plugins.
     *
     * @throws PerformancePluginException if stopping plugins fails
     */
    public synchronized void stopPlugins() throws PerformancePlugin.PerformancePluginException {
        List<PerformancePlugin> pluginsToStop = new ArrayList<>(activePlugins.values());
        
        for (PerformancePlugin plugin : pluginsToStop) {
            try {
                plugin.stop();
                activePlugins.remove(plugin.getPluginId());
                getLogger().info("Stopped plugin: {} ({})", plugin.getDisplayName(), plugin.getPluginId());

            } catch (Exception e) {
                getLogger().error("Failed to stop plugin {}: {}", plugin.getPluginId(), e.getMessage());
                // Don't fail the entire operation if one plugin fails
            }
        }
    }

    /**
     * Get all registered plugins.
     *
     * @return unmodifiable collection of registered plugins
     */
    public Collection<PerformancePlugin> getRegisteredPlugins() {
        return Collections.unmodifiableCollection(registeredPlugins.values());
    }

    /**
     * Get all active plugins.
     *
     * @return unmodifiable collection of active plugins
     */
    public Collection<PerformancePlugin> getActivePlugins() {
        return Collections.unmodifiableCollection(activePlugins.values());
    }

    /**
     * Find a plugin by ID.
     *
     * @param pluginId the plugin ID to find
     * @return optional plugin instance
     */
    public Optional<PerformancePlugin> findPluginById(String pluginId) {
        Objects.requireNonNull(pluginId, "Plugin ID must not be null");
        return Optional.ofNullable(registeredPlugins.get(pluginId));
    }

    /**
     * Find plugins by author.
     *
     * @param author the author to filter by
     * @return unmodifiable list of matching plugins
     */
    public List<PerformancePlugin> findPluginsByAuthor(String author) {
        Objects.requireNonNull(author, "Author must not be null");
        return registeredPlugins.values().stream()
                .filter(plugin -> plugin.getAuthor().equals(author))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Find plugins compatible with the given system version.
     *
     * @param systemVersion the system version to check
     * @return unmodifiable list of compatible plugins
     */
    public List<PerformancePlugin> findCompatiblePlugins(String systemVersion) {
        Objects.requireNonNull(systemVersion, "System version must not be null");
        return registeredPlugins.values().stream()
                .filter(plugin -> plugin.isCompatible(systemVersion))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Check if the registry is initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the logger for plugin registry operations.
     *
     * @return logger instance
     */
    private static org.apache.logging.log4j.Logger getLogger() {
        return org.apache.logging.log4j.LogManager.getLogger(PerformancePluginRegistry.class);
    }
}