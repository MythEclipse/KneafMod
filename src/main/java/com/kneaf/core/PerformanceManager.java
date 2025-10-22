package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Minecraft-specific imports commented out for test compatibility
// import com.mojang.logging.LogUtils;
// import net.neoforged.fml.config.ModConfig;
// import net.neoforged.fml.event.config.ModConfigEvent;
// import net.neoforged.bus.api.SubscribeEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kneaf.core.performance.PerformanceMonitoringSystem;

/**
 * Singleton class that manages performance optimizations for KneafMod.
 * Handles configuration loading, optimization state management, and provides
 * thread-safe access to optimization flags.
 */
public final class PerformanceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceManager.class);
    private static final PerformanceManager INSTANCE = new PerformanceManager();
    
    // Optimization flags with thread safety
    private final AtomicBoolean isEntityThrottlingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isAiPathfindingOptimized = new AtomicBoolean(false);
    private final AtomicBoolean isRustIntegrationEnabled = new AtomicBoolean(false);
    private final AtomicBoolean isHorizontalPhysicsOnly = new AtomicBoolean(false);
    
    // Combat system optimization flags
    private final AtomicBoolean isCombatSimdOptimized = new AtomicBoolean(true);
    private final AtomicBoolean isCombatParallelProcessingEnabled = new AtomicBoolean(true);
    private final AtomicBoolean isPredictiveLoadBalancingEnabled = new AtomicBoolean(true);
    private final AtomicBoolean isHitDetectionOptimized = new AtomicBoolean(true);
    
    // Performance monitoring for optimizations
    private final AtomicBoolean isOptimizationMonitoringEnabled = new AtomicBoolean(true);
    
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
            isRustIntegrationEnabled.set(parseBooleanProperty(properties, "rustIntegrationEnabled", false));
            isHorizontalPhysicsOnly.set(parseBooleanProperty(properties, "horizontalPhysicsOnly", false));
            
            // Load combat system optimization flags
            isCombatSimdOptimized.set(parseBooleanProperty(properties, "combatSimdOptimized", true));
            isCombatParallelProcessingEnabled.set(parseBooleanProperty(properties, "combatParallelProcessingEnabled", true));
            isPredictiveLoadBalancingEnabled.set(parseBooleanProperty(properties, "predictiveLoadBalancingEnabled", true));
            isHitDetectionOptimized.set(parseBooleanProperty(properties, "hitDetectionOptimized", true));
            
            // Load monitoring flags
            isOptimizationMonitoringEnabled.set(parseBooleanProperty(properties, "optimizationMonitoringEnabled", true));
            
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
        isRustIntegrationEnabled.set(false);
        isHorizontalPhysicsOnly.set(false);
        
        // Combat system defaults
        isCombatSimdOptimized.set(true);
        isCombatParallelProcessingEnabled.set(true);
        isPredictiveLoadBalancingEnabled.set(true);
        isHitDetectionOptimized.set(true);
        
        // Monitoring defaults
        isOptimizationMonitoringEnabled.set(true);
        
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
     * Minecraft-specific event handling disabled for test compatibility
     * @param event the config event
     */
    public void onConfigReload(Object event) { // Changed from ModConfigEvent.Reloading to Object
        LOGGER.info("Reloading PerformanceManager configuration");
        loadConfiguration();
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
        boolean oldValue = isEntityThrottlingEnabled.get();
        isEntityThrottlingEnabled.set(enabled);
        
        // Record configuration change in monitoring system
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().recordMetric(
            "performance_manager.entity_throttling_enabled", enabled ? 1.0 : 0.0);
        
        if (oldValue != enabled) {
            PerformanceMonitoringSystem.getInstance().getEventBus().publishEvent(
                new com.kneaf.core.performance.CrossComponentEvent(
                    "PerformanceManager", "entity_throttling_config_changed",
                    java.time.Instant.now(), 0,
                    java.util.Map.of("old_value", oldValue, "new_value", enabled)
                )
            );
        }
    }
    
    /**
     * Set AI pathfinding optimization state.
     * @param optimized true to enable AI pathfinding optimization, false to disable
     */
    public void setAiPathfindingOptimized(boolean optimized) {
        boolean oldValue = isAiPathfindingOptimized.get();
        isAiPathfindingOptimized.set(optimized);
        
        // Record configuration change in monitoring system
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().recordMetric(
            "performance_manager.ai_pathfinding_optimized", optimized ? 1.0 : 0.0);
        
        if (oldValue != optimized) {
            PerformanceMonitoringSystem.getInstance().getEventBus().publishEvent(
                new com.kneaf.core.performance.CrossComponentEvent(
                    "PerformanceManager", "ai_pathfinding_config_changed",
                    java.time.Instant.now(), 0,
                    java.util.Map.of("old_value", oldValue, "new_value", optimized)
                )
            );
        }
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
    
    // ------------------------------ Combat System Optimization Methods ------------------------------
    
    /**
     * Check if combat SIMD optimization is enabled.
     * @return true if combat SIMD optimization is enabled, false otherwise
     */
    public boolean isCombatSimdOptimized() {
        return isCombatSimdOptimized.get();
    }
    
    /**
     * Set combat SIMD optimization state.
     * @param optimized true to enable combat SIMD optimization, false to disable
     */
    public void setCombatSimdOptimized(boolean optimized) {
        boolean oldValue = isCombatSimdOptimized.get();
        isCombatSimdOptimized.set(optimized);
        
        // Record configuration change in monitoring system
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().recordMetric(
            "performance_manager.combat_simd_optimized", optimized ? 1.0 : 0.0);
        
        if (oldValue != optimized) {
            PerformanceMonitoringSystem.getInstance().getEventBus().publishEvent(
                new com.kneaf.core.performance.CrossComponentEvent(
                    "PerformanceManager", "combat_simd_config_changed",
                    java.time.Instant.now(), 0,
                    java.util.Map.of("old_value", oldValue, "new_value", optimized)
                )
            );
        }
    }
    
    /**
     * Check if combat parallel processing is enabled.
     * @return true if combat parallel processing is enabled, false otherwise
     */
    public boolean isCombatParallelProcessingEnabled() {
        return isCombatParallelProcessingEnabled.get();
    }
    
    /**
     * Set combat parallel processing state.
     * @param enabled true to enable combat parallel processing, false to disable
     */
    public void setCombatParallelProcessingEnabled(boolean enabled) {
        isCombatParallelProcessingEnabled.set(enabled);
    }
    
    /**
     * Check if predictive load balancing is enabled.
     * @return true if predictive load balancing is enabled, false otherwise
     */
    public boolean isPredictiveLoadBalancingEnabled() {
        return isPredictiveLoadBalancingEnabled.get();
    }
    
    /**
     * Set predictive load balancing state.
     * @param enabled true to enable predictive load balancing, false to disable
     */
    public void setPredictiveLoadBalancingEnabled(boolean enabled) {
        isPredictiveLoadBalancingEnabled.set(enabled);
    }
    
    /**
     * Check if hit detection optimization is enabled.
     * @return true if hit detection optimization is enabled, false otherwise
     */
    public boolean isHitDetectionOptimized() {
        return isHitDetectionOptimized.get();
    }
    
    /**
     * Set hit detection optimization state.
     * @param optimized true to enable hit detection optimization, false to disable
     */
    public void setHitDetectionOptimized(boolean optimized) {
        isHitDetectionOptimized.set(optimized);
    }
    
    /**
     * Check if optimization monitoring is enabled.
     * @return true if optimization monitoring is enabled, false otherwise
     */
    public boolean isOptimizationMonitoringEnabled() {
        return isOptimizationMonitoringEnabled.get();
    }
    
    /**
     * Set optimization monitoring state.
     * @param enabled true to enable optimization monitoring, false to disable
     */
    public void setOptimizationMonitoringEnabled(boolean enabled) {
        isOptimizationMonitoringEnabled.set(enabled);
    }
    
    /**
     * Get comprehensive performance metrics for all optimizations.
     * @return map of optimization metrics
     */
    public java.util.Map<String, Object> getOptimizationMetrics() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        
        // Basic optimization flags
        metrics.put("entity_throttling_enabled", isEntityThrottlingEnabled.get());
        metrics.put("ai_pathfinding_optimized", isAiPathfindingOptimized.get());
        metrics.put("rust_integration_enabled", isRustIntegrationEnabled.get());
        metrics.put("horizontal_physics_only", isHorizontalPhysicsOnly.get());
        
        // Combat system optimizations
        metrics.put("combat_simd_optimized", isCombatSimdOptimized.get());
        metrics.put("combat_parallel_processing_enabled", isCombatParallelProcessingEnabled.get());
        metrics.put("predictive_load_balancing_enabled", isPredictiveLoadBalancingEnabled.get());
        metrics.put("hit_detection_optimized", isHitDetectionOptimized.get());
        
        // Monitoring status
        metrics.put("optimization_monitoring_enabled", isOptimizationMonitoringEnabled.get());
        
        return metrics;
    }
    
    /**
     * Get optimization performance summary for monitoring dashboard.
     * @return formatted summary string
     */
    public String getOptimizationSummary() {
        return String.format(
            "PerformanceManager{" +
            "entityThrottling=%s, aiPathfinding=%s, rustIntegration=%s, " +
            "combatSimd=%s, combatParallel=%s, predictiveLoadBalancing=%s, hitDetection=%s, " +
            "optimizationMonitoring=%s}",
            isEntityThrottlingEnabled.get(),
            isAiPathfindingOptimized.get(),
            isRustIntegrationEnabled.get(),
            isCombatSimdOptimized.get(),
            isCombatParallelProcessingEnabled.get(),
            isPredictiveLoadBalancingEnabled.get(),
            isHitDetectionOptimized.get(),
            isOptimizationMonitoringEnabled.get()
        );
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
        return getOptimizationSummary();
    }
}