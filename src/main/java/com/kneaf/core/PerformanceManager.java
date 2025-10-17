package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton class that manages performance optimizations for KneafMod.
 * Handles configuration loading, optimization state management, and provides
 * thread-safe access to optimization flags.
 */
public final class PerformanceManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PerformanceManager INSTANCE = new PerformanceManager();
    
    // Optimization flags with thread safety
    private final AtomicBoolean isEntityThrottlingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isAiPathfindingOptimized = new AtomicBoolean(false);
    private final AtomicBoolean isRenderingMathOptimized = new AtomicBoolean(false);
    private final AtomicBoolean isRustIntegrationEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isHorizontalPhysicsOnly = new AtomicBoolean(false);
    
    // Configuration file path
    private static final String CONFIG_PATH = "config/kneaf-performance.properties";
    
    /**
     * Private constructor to enforce singleton pattern.
     * Loads configuration during initialization.
     */
    private PerformanceManager() {
        LOGGER.info("Initializing PerformanceManager - loading configuration");
        loadConfiguration();
    }
    
    /**
     * Get the singleton instance of PerformanceManager.
     *
     * @return the singleton instance
     * @throws IllegalStateException if PerformanceManager is not initialized
     */
    public static PerformanceManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Load configuration from kneaf-performance.properties file.
     * Uses fallback logic if configuration file is missing or invalid.
     */
    public void loadConfiguration() {
        Properties properties = new Properties();
        
        try {
            // Try to load from file system first
            File configFile = new File(CONFIG_PATH);
            if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    properties.load(fis);
                    LOGGER.info("Successfully loaded configuration from {}", CONFIG_PATH);
                }
            } else {
                LOGGER.warn("Configuration file not found at {}. Using default values.", CONFIG_PATH);
            }
            
            // Load optimization flags with fallback defaults
            isEntityThrottlingEnabled.set(parseBooleanProperty(properties, "entityThrottlingEnabled", true));
            isAiPathfindingOptimized.set(parseBooleanProperty(properties, "aiPathfindingOptimized", true));
            isRenderingMathOptimized.set(parseBooleanProperty(properties, "renderingMathOptimized", true));
            isRustIntegrationEnabled.set(parseBooleanProperty(properties, "rustIntegrationEnabled", false));
            isHorizontalPhysicsOnly.set(parseBooleanProperty(properties, "horizontalPhysicsOnly", false));
            
        } catch (IOException e) {
            LOGGER.error("Failed to load performance configuration", e);
            // Set safe defaults on error
            resetToDefaults();
        }
    }
    
    /**
     * Reset all optimization flags to safe default values.
     */
    public void resetToDefaults() {
        isEntityThrottlingEnabled.set(true);
        isAiPathfindingOptimized.set(true);
        isRenderingMathOptimized.set(true);
        isRustIntegrationEnabled.set(false);
        isHorizontalPhysicsOnly.set(false);
        LOGGER.info("PerformanceManager reset to default configuration");
    }
    
    /**
     * Parse boolean property with fallback to default value.
     *
     * @param properties the properties to read from
     * @param key the property key
     * @param defaultValue the default value if property is missing or invalid
     * @return the parsed boolean value
     */
    private boolean parseBooleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            LOGGER.debug("Property {} not found, using default: {}", key, defaultValue);
            return defaultValue;
        }
        
        try {
            return Boolean.parseBoolean(value.trim());
        } catch (Exception e) {
            LOGGER.warn("Invalid boolean value for property {}: {}. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Event listener for NeoForge config reload events.
     * @param event the config event
     */
    @SubscribeEvent
    public void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER && "kneafcore".equals(event.getConfig().getModId())) {
            LOGGER.info("Reloading PerformanceManager configuration");
            loadConfiguration();
        }
    }
    
    // ------------------------------ Getter Methods ------------------------------
    
    /**
     * Check if entity throttling is enabled.
     * @return true if entity throttling is enabled, false otherwise
     */
    public boolean isEntityThrottlingEnabled() {
        return isEntityThrottlingEnabled.get();
    }
    
    /**
     * Check if AI pathfinding optimization is enabled.
     * @return true if AI pathfinding optimization is enabled, false otherwise
     */
    public boolean isAiPathfindingOptimized() {
        return isAiPathfindingOptimized.get();
    }
    
    /**
     * Check if rendering math optimization is enabled.
     * @return true if rendering math optimization is enabled, false otherwise
     */
    public boolean isRenderingMathOptimized() {
        return isRenderingMathOptimized.get();
    }
    
    /**
     * Check if Rust integration is enabled.
     * @return true if Rust integration is enabled, false otherwise
     */
    public boolean isRustIntegrationEnabled() {
        return isRustIntegrationEnabled.get();
    }

    /**
     * Check if horizontal-only physics is enabled.
     * @return true if horizontal-only physics is enabled, false otherwise
     */
    public boolean isHorizontalPhysicsOnly() {
        return isHorizontalPhysicsOnly.get();
    }
    
    // ------------------------------ Setter Methods (Thread-Safe) ------------------------------
    
    /**
     * Set entity throttling state.
     * @param enabled true to enable entity throttling, false to disable
     */
    public void setEntityThrottlingEnabled(boolean enabled) {
        isEntityThrottlingEnabled.set(enabled);
    }
    
    /**
     * Set AI pathfinding optimization state.
     * @param optimized true to enable AI pathfinding optimization, false to disable
     */
    public void setAiPathfindingOptimized(boolean optimized) {
        isAiPathfindingOptimized.set(optimized);
    }
    
    /**
     * Set rendering math optimization state.
     * @param optimized true to enable rendering math optimization, false to disable
     */
    public void setRenderingMathOptimized(boolean optimized) {
        isRenderingMathOptimized.set(optimized);
    }
    
    /**
     * Set Rust integration state.
     * @param enabled true to enable Rust integration, false to disable
     */
    public void setRustIntegrationEnabled(boolean enabled) {
        isRustIntegrationEnabled.set(enabled);
    }

    /**
     * Set horizontal-only physics state.
     * @param enabled true to enable horizontal-only physics, false to disable
     */
    public void setHorizontalPhysicsOnly(boolean enabled) {
        isHorizontalPhysicsOnly.set(enabled);
    }
    
    /**
     * Load configuration asynchronously using virtual threads (Java 21+).
     *
     * @return a CompletableFuture that completes when configuration loading is done
     */
    public CompletableFuture<Void> loadConfigurationAsync() {
        return CompletableFuture.runAsync(this::loadConfiguration, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Get string representation of current optimization state.
     * @return formatted string with current optimization states
     */
    @Override
    public String toString() {
        return String.format("PerformanceManager{entityThrottling=%s, aiPathfinding=%s, renderingMath=%s, rustIntegration=%s}",
                isEntityThrottlingEnabled.get(),
                isAiPathfindingOptimized.get(),
                isRenderingMathOptimized.get(),
                isRustIntegrationEnabled.get());
    }
}