package com.kneaf.core;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.config.performance.PerformanceConfig;
import com.kneaf.core.performance.unified.PerformanceManager;
import com.kneaf.core.command.unified.UnifiedCommandSystem;
import com.kneaf.core.unifiedbridge.UnifiedBridgeImpl;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates various subsystems for KneafCore mod.
 * Acts as a facade for accessing different system components.
 */
public class SystemManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final ModInitializer modInitializer;
    private final LifecycleManager lifecycleManager;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isPerformanceMonitoringEnabled = new AtomicBoolean(false);

    public SystemManager() {
        this.modInitializer = new ModInitializer();
        this.lifecycleManager = new LifecycleManager();
    }

    /**
     * Initialize all systems through the ModInitializer.
     *
     * @throws Exception if initialization fails
     */
    public void initialize() throws Exception {
        LOGGER.info("Initializing SystemManager");
        
        modInitializer.initialize();
        isInitialized.set(true);
        isPerformanceMonitoringEnabled.set(true);
        
        LOGGER.info("SystemManager initialized successfully");
    }

    /**
     * Check if SystemManager is fully initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized.get();
    }

    /**
     * Check if performance monitoring is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isPerformanceMonitoringEnabled() {
        return isPerformanceMonitoringEnabled.get();
    }

    /**
     * Get the configuration manager for accessing all configurations.
     *
     * @return ConfigurationManager instance
     * @throws IllegalStateException if not initialized
     */
    public ConfigurationManager getConfigurationManager() {
        ensureInitialized();
        return modInitializer.getConfigurationManager();
    }

    /**
     * Get the performance manager for performance monitoring and optimization.
     *
     * @return PerformanceManager instance
     * @throws IllegalStateException if not initialized
     */
    public PerformanceManager getPerformanceManager() {
        ensureInitialized();
        return modInitializer.getPerformanceManager();
    }

    /**
     * Get the unified command system for command management.
     *
     * @return UnifiedCommandSystem instance
     * @throws IllegalStateException if not initialized
     */
    public UnifiedCommandSystem getCommandSystem() {
        ensureInitialized();
        return modInitializer.getCommandSystem();
    }

    /**
     * Get the unified bridge for native operations.
     *
     * @return UnifiedBridgeImpl instance
     * @throws IllegalStateException if not initialized
     */
    public UnifiedBridgeImpl getUnifiedBridge() {
        ensureInitialized();
        return modInitializer.getUnifiedBridge();
    }

    /**
     * Get the lifecycle manager for handling startup/shutdown.
     *
     * @return LifecycleManager instance
     */
    public LifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Perform graceful shutdown of all systems.
     */
    public void shutdownGracefully() {
        LOGGER.info("Starting graceful shutdown of SystemManager");
        
        try {
            lifecycleManager.shutdownGracefully(modInitializer);
            isInitialized.set(false);
            isPerformanceMonitoringEnabled.set(false);
            
            LOGGER.info("SystemManager graceful shutdown completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error during SystemManager graceful shutdown", e);
        }
    }

    /**
     * Reload all configurations from the source.
     *
     * @throws ConfigurationException if reloading fails
     */
    public void reloadConfigurations() throws ConfigurationException {
        ensureInitialized();
        LOGGER.info("Reloading all configurations");
        
        ConfigurationManager configManager = modInitializer.getConfigurationManager();
        if (configManager != null) {
            configManager.reload();
            LOGGER.info("Configurations reloaded successfully");
        }
    }

    /**
     * Get performance configuration.
     *
     * @return PerformanceConfig instance
     * @throws ConfigurationException if configuration loading fails
     */
    public PerformanceConfig getPerformanceConfiguration() throws ConfigurationException {
        ensureInitialized();
        ConfigurationManager configManager = modInitializer.getConfigurationManager();
        if (configManager == null) {
            throw new IllegalStateException("Configuration manager is not initialized");
        }
        return configManager.getConfiguration(PerformanceConfig.class);
    }

    /**
     * Ensure SystemManager is initialized before accessing components.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!isInitialized.get()) {
            throw new IllegalStateException("SystemManager is not initialized yet.");
        }
    }
}