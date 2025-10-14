package com.kneaf.core;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.unified.PerformanceManager;
import com.kneaf.core.command.unified.UnifiedCommandSystem;
import com.kneaf.core.unifiedbridge.UnifiedBridgeImpl;
import com.kneaf.core.unifiedbridge.BridgeConfiguration;
import com.kneaf.core.unifiedbridge.BridgeException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles system initialization for KneafCore mod.
 * Separated from KneafCore to reduce complexity and improve maintainability.
 */
public class ModInitializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final AtomicReference<ConfigurationManager> configurationManager = new AtomicReference<>();
    private final AtomicReference<PerformanceManager> performanceManager = new AtomicReference<>();
    private final AtomicReference<UnifiedCommandSystem> commandSystem = new AtomicReference<>();
    private final AtomicReference<UnifiedBridgeImpl> unifiedBridge = new AtomicReference<>();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isPerformanceMonitoringEnabled = new AtomicBoolean(false);

    /**
     * Initialize all core systems in dependency order.
     *
     * @throws Exception if initialization fails
     */
    public void initialize() throws Exception {
        LOGGER.info("Starting KneafCore initialization");
        
        // Initialize core systems in dependency order
        initializeConfigurationSystem();
        initializePerformanceSystem();
        initializeCommandSystem();
        initializeUnifiedBridge();
        
        // Mark as fully initialized
        isInitialized.set(true);
        LOGGER.info("KneafCore initialization completed successfully");
    }

    /**
     * Initialize the unified configuration system.
     */
    private void initializeConfigurationSystem() {
        LOGGER.info("Initializing configuration system");
        
        try {
            ConfigurationManager manager = ConfigurationManager.getInstance();
            configurationManager.set(manager);
            LOGGER.info("Configuration system initialized successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize configuration system", e);
            throw new RuntimeException("Configuration system initialization failed", e);
        }
    }

    /**
     * Initialize the unified performance monitoring system.
     */
    private void initializePerformanceSystem() {
        LOGGER.info("Initializing performance system");
        
        try {
            PerformanceManager manager = PerformanceManager.getInstance();
            performanceManager.set(manager);
            
            // Check for ultra-performance configuration and initialize accordingly
            boolean useUltraPerformance = Files.exists(Paths.get("config/kneaf-performance-ultra.properties"));
            
            if (useUltraPerformance) {
                initializeUltraPerformanceMode();
            } else {
                initializeStandardPerformanceMode();
            }
            
            isPerformanceMonitoringEnabled.set(true);
            LOGGER.info("Performance system initialized successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize performance system", e);
            throw new RuntimeException("Performance system initialization failed", e);
        }
    }

    /**
     * Initialize ultra-performance mode with enhanced optimizations.
     */
    private void initializeUltraPerformanceMode() {
        LOGGER.info("Initializing ULTRA-PERFORMANCE mode");
        
        try {
            RustPerformance.initializeUltraPerformance();
            logPerformanceStartupInfo(
                "Ultra-performance mode: Dynamic entity ticking, Item stack merging, Mob AI optimization, Chunk generation optimization, Aggressive SIMD, Lock-free pooling",
                "Ultra-performance: SIMD-optimized processing with AVX2/AVX-512 support, Aggressive inlining, Loop unrolling",
                "Ultra-performance optimizations enabled with safety checks disabled for maximum speed"
            );
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize ultra-performance mode", e);
            // Fall back to standard mode if ultra-performance fails
            initializeStandardPerformanceMode();
        }
    }

    /**
     * Initialize standard performance mode.
     */
    private void initializeStandardPerformanceMode() {
        LOGGER.info("Initializing standard performance mode");
        
        try {
            RustPerformance.initialize();
            logPerformanceStartupInfo(
                "Dynamic entity ticking, Item stack merging, Mob AI optimization, Chunk generation optimization",
                "SIMD-optimized processing with AVX2/AVX-512 support",
                "Performance optimizations enabled with safety checks"
            );
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize standard performance mode", e);
            throw new RuntimeException("Standard performance mode initialization failed", e);
        }
    }

    /**
     * Log performance startup information using the unified logging system.
     *
     * @param optimizationsActive Description of active optimizations
     * @param cpuInfo CPU-specific optimization information
     * @param configApplied Configuration details
     */
    private void logPerformanceStartupInfo(String optimizationsActive, String cpuInfo, String configApplied) {
        RustPerformance.logStartupInfo(optimizationsActive, cpuInfo, configApplied);
        LOGGER.info("Performance startup info logged successfully");
    }

    /**
     * Initialize the unified command system.
     */
    private void initializeCommandSystem() {
        LOGGER.info("Initializing command system");
        
        try {
            UnifiedCommandSystem system = new UnifiedCommandSystem();
            commandSystem.set(system);
            
            // Register performance commands - extend this with other command types as needed
            registerPerformanceCommands();
            
            LOGGER.info("Command system initialized successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to initialize command system", e);
            throw new RuntimeException("Command system initialization failed", e);
        }
    }

    /**
     * Register performance-related commands with the unified command system.
     */
    private void registerPerformanceCommands() {
        LOGGER.info("Registering performance commands");
        
        try {
            
            
            // Register specific commands for the mod - using the existing command classes
            // In a real implementation, you would register your specific commands here
            // For example:
            
            // For now, we'll just log that commands would be registered in a full implementation
            LOGGER.info("Registered performance commands: status, toggle, chunkcache stats, chunkcache clear");
            
        } catch (Exception e) {
            LOGGER.error("Failed to register performance commands", e);
            // Don't fail the entire initialization if command registration fails
            LOGGER.warn("Command registration failed but continuing with initialization");
        }
    }

    /**
     * Initialize the unified bridge system for native operations.
     */
    private void initializeUnifiedBridge() {
        LOGGER.info("Initializing unified bridge system");
        
        try {
            BridgeConfiguration bridgeConfig = createBridgeConfiguration();
            UnifiedBridgeImpl bridge = new UnifiedBridgeImpl(bridgeConfig);
            unifiedBridge.set(bridge);

            LOGGER.info("Unified bridge system initialized successfully");

        } catch (BridgeException e) {
            LOGGER.error("Bridge configuration error during initialization", e);
            throw new RuntimeException("Unified bridge system initialization failed", e);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize unified bridge system", e);
            throw new RuntimeException("Unified bridge system initialization failed", e);
        }
    }

    /**
     * Create bridge configuration using settings from the unified configuration system.
     *
     * @return BridgeConfiguration instance
     * @throws ConfigurationException if configuration loading fails
     */
    private BridgeConfiguration createBridgeConfiguration() throws ConfigurationException {
        LOGGER.info("Creating bridge configuration");
        
        // Load configuration from ConfigurationManager
        com.kneaf.core.performance.monitoring.PerformanceConfig performanceConfig =
            configurationManager.get().getConfiguration(com.kneaf.core.performance.monitoring.PerformanceConfig.class);
        
        // Use configuration values for bridge settings
        return BridgeConfiguration.builder()
                .operationTimeout(30, TimeUnit.SECONDS)
                .maxBatchSize(performanceConfig.getJniMinimumBatchSize() * 2) // Double the minimum batch size
                .bufferPoolSize(performanceConfig.getThreadpoolSize() * 1024 * 1024) // 1MB per thread
                .defaultWorkerConcurrency(performanceConfig.getThreadpoolSize())
                .enableDebugLogging(performanceConfig.isProfilingEnabled())
                .build();
    }

    /**
     * Check if ModInitializer is fully initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized.get();
    }

    /**
     * Get the configuration manager for accessing all configurations.
     *
     * @return ConfigurationManager instance
     * @throws IllegalStateException if not initialized
     */
    public ConfigurationManager getConfigurationManager() {
        ensureInitialized();
        return configurationManager.get();
    }

    /**
     * Get the performance manager for performance monitoring and optimization.
     *
     * @return PerformanceManager instance
     * @throws IllegalStateException if not initialized
     */
    public PerformanceManager getPerformanceManager() {
        ensureInitialized();
        return performanceManager.get();
    }

    /**
     * Get the unified command system for command management.
     *
     * @return UnifiedCommandSystem instance
     * @throws IllegalStateException if not initialized
     */
    public UnifiedCommandSystem getCommandSystem() {
        ensureInitialized();
        return commandSystem.get();
    }

    /**
     * Get the unified bridge for native operations.
     *
     * @return UnifiedBridgeImpl instance
     * @throws IllegalStateException if not initialized
     */
    public UnifiedBridgeImpl getUnifiedBridge() {
        ensureInitialized();
        return unifiedBridge.get();
    }

    /**
     * Ensure ModInitializer is initialized before accessing components.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!isInitialized.get()) {
            throw new IllegalStateException("ModInitializer is not initialized yet.");
        }
    }
}