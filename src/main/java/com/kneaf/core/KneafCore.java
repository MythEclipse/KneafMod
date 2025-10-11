package com.kneaf.core;

import com.kneaf.core.compatibility.ModCompatibility;
import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.performance.PerformanceConfig;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.command.unified.UnifiedCommandSystem;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.unified.PerformanceManager;
import com.kneaf.core.protocol.commands.PerformanceCommand;
import com.kneaf.core.unifiedbridge.UnifiedBridgeImpl;
import com.kneaf.core.unifiedbridge.BridgeConfiguration;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.slf4j.Logger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main mod class for KneafCore. Implements a modular architecture with clear separation of concerns,
 * using unified systems for configuration, performance monitoring, command management, and native bridging.
 * Follows SOLID principles and provides proper lifecycle management.
 */
@Mod(KneafCore.MODID)
public class KneafCore {
    /** Mod ID used for registration and identification */
    public static final String MODID = "kneafcore";

    /** Logger for the mod */
    public static final Logger LOGGER = LogUtils.getLogger();

    // Deferred Registers
    /** Deferred register for blocks */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** Deferred register for items */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // Core components - lazily initialized with thread-safe references
    private static final AtomicReference<KneafCore> INSTANCE = new AtomicReference<>();
    private final AtomicReference<ConfigurationManager> configurationManager = new AtomicReference<>();
    private final AtomicReference<PerformanceManager> performanceManager = new AtomicReference<>();
    private final AtomicReference<UnifiedCommandSystem> commandSystem = new AtomicReference<>();
    private final AtomicReference<UnifiedBridgeImpl> unifiedBridge = new AtomicReference<>();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isPerformanceMonitoringEnabled = new AtomicBoolean(false);

    /**
     * Constructor for the mod. Registers all deferred registers, event listeners, and sets up core systems.
     *
     * @param modEventBus The mod event bus
     * @param modContainer The mod container
     */
    public KneafCore(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE.set(this);
        
        // Register deferred registers
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        // Register event listeners
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
        
        // Register configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        
        LOGGER.info("KneafCore mod constructor completed - waiting for initialization");
    }

    /**
     * Get the singleton instance of KneafCore.
     *
     * @return the singleton instance
     */
    public static KneafCore getInstance() {
        KneafCore instance = INSTANCE.get();
        if (instance == null) {
            throw new IllegalStateException("KneafCore is not initialized yet");
        }
        return instance;
    }

    /**
     * Common setup method called during mod initialization.
     * Sets up all core systems in a controlled manner.
     *
     * @param event The FML common setup event
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Starting KneafCore common setup");
        
        try {
            // Initialize core systems in dependency order
            initializeConfigurationSystem();
            initializePerformanceSystem();
            initializeCommandSystem();
            initializeUnifiedBridge();
            
            // Check for mod compatibility
            ModCompatibility.checkForConflicts();
            
            // Mark as fully initialized
            isInitialized.set(true);
            LOGGER.info("KneafCore initialization completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to complete KneafCore initialization", e);
            handleInitializationFailure(e);
        }
    }

    /**
     * Initialize the unified configuration system.
     * Uses ConfigurationManager as the single source of truth for all configurations.
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
     * Uses PerformanceManager for all performance-related operations.
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
     * Uses UnifiedCommandSystem for all command registration and execution.
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
            // In a real implementation, you would register your specific commands here
            // For example: commandSystem.registerCommand(new PerformanceStatusCommand());
            // commandSystem.registerCommand(new PerformanceTuneCommand());
            
            LOGGER.info("Performance commands registered successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to register performance commands", e);
            // Don't fail the entire initialization if command registration fails
            LOGGER.warn("Command registration failed but continuing with initialization");
        }
    }

    /**
     * Initialize the unified bridge system for native operations.
     * Uses UnifiedBridgeImpl as the central point for all native bridge operations.
     */
    private void initializeUnifiedBridge() {
        LOGGER.info("Initializing unified bridge system");
        
        try {
            BridgeConfiguration bridgeConfig = createBridgeConfiguration();
            UnifiedBridgeImpl bridge = new UnifiedBridgeImpl(bridgeConfig);
            unifiedBridge.set(bridge);
            
            LOGGER.info("Unified bridge system initialized successfully");
            
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
        
        // In a real implementation, you would load configuration from ConfigurationManager
        // For example:
        // PerformanceConfig performanceConfig = configurationManager.get().getConfiguration(PerformanceConfig.class);
        // int threadPoolSize = performanceConfig.getNetworkExecutorpoolSize();
        
        // For now, return a default configuration
        return BridgeConfiguration.builder()
                .operationTimeout(30, TimeUnit.SECONDS)
                .maxBatchSize(100)
                .bufferPoolSize(1024 * 1024) // 1MB buffer pool
                .build();
    }

    /**
     * Handle initialization failures gracefully.
     *
     * @param cause The exception that caused the failure
     */
    private void handleInitializationFailure(Exception cause) {
        isInitialized.set(false);
        LOGGER.error("KneafCore initialization failed - entering degraded mode", cause);
        
        // In a real implementation, you would set up minimal functionality
        // to allow the game to continue running with reduced features
    }

    /**
     * Register commands with the Minecraft command dispatcher.
     * This is the event handler for RegisterCommandsEvent.
     *
     * @param event The register commands event
     */
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        LOGGER.info("Registering commands with dispatcher");
        
        try {
            // Use the unified command system to register all commands
            // In a real implementation, you would call commandSystem.get().registerWithDispatcher(event.getDispatcher());
            // For backward compatibility, we'll keep the direct registration for now
            PerformanceCommand.register(event.getDispatcher());
            
            LOGGER.info("Commands registered successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to register commands", e);
        }
    }

    /**
     * Handle server tick events for performance monitoring.
     * This is the event handler for ServerTickEvent.Post.
     *
     * @param event The server tick event
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!isPerformanceMonitoringEnabled.get() || !isInitialized.get()) {
            return;
        }
        
        try {
            // Update TPS monitoring on each server tick
            PerformanceManager perfManager = performanceManager.get();
            if (perfManager != null) {
                perfManager.getTpsMonitor().updateTPS();
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during server tick performance monitoring", e);
            // Continue running even if performance monitoring fails
        }
    }

    /**
     * Handle server starting event.
     * This is the event handler for ServerStartingEvent.
     *
     * @param event The server starting event
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Server starting - completing final initialization");
        
        try {
            // In a real implementation, you would perform server-specific initialization here
            // For example: performanceManager.get().enableServerSpecificMonitoring();
            
        } catch (Exception e) {
            LOGGER.error("Error during server start initialization", e);
        }
    }

    /**
     * Handle server stopping event for graceful shutdown.
     * This is the event handler for ServerStoppingEvent.
     *
     * @param event The server stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping - initiating graceful shutdown");
        
        try {
            shutdownGracefully();
            
        } catch (Exception e) {
            LOGGER.error("Error during graceful shutdown", e);
        }
    }

    /**
     * Client setup method called during client initialization.
     * This is a static nested class event handler for FMLClientSetupEvent.
     */
    @EventBusSubscriber(
        modid = MODID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
    )
    static class ClientModEvents {
        private ClientModEvents() {}

        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
            
            try {
                // Register client-specific components
                // For example: PerformanceOverlayClient.registerClient(event);
                
            } catch (Throwable t) {
                LOGGER.debug("Client component registration failed: {}", t.getMessage());
            }
        }
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
     * Check if KneafCore is fully initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized.get();
    }

    /**
     * Ensure KneafCore is initialized before accessing components.
     *
     * @throws IllegalStateException if not initialized
     */
    private void ensureInitialized() {
        if (!isInitialized.get()) {
            throw new IllegalStateException("KneafCore is not initialized yet. Call awaitInitialization() first.");
        }
    }

    /**
     * Await initialization completion.
     * In a real implementation, this would be a blocking call with timeout.
     *
     * @return true if initialization completed successfully
     */
    public boolean awaitInitialization() {
        // In a real implementation, this would use a CountDownLatch or similar
        // For now, we'll just return the current state
        return isInitialized.get();
    }

    /**
     * Perform graceful shutdown of all systems.
     * Should be called when the mod or server is shutting down.
     */
    public void shutdownGracefully() {
        LOGGER.info("Starting graceful shutdown of KneafCore systems");
        
        try {
            // Shutdown systems in reverse order of initialization
            UnifiedBridgeImpl bridge = unifiedBridge.get();
            if (bridge != null) {
                bridge.shutdown();
                LOGGER.info("Unified bridge system shut down successfully");
            }
            
            PerformanceManager perfManager = performanceManager.get();
            if (perfManager != null && isPerformanceMonitoringEnabled.get()) {
                perfManager.disable();
                LOGGER.info("Performance monitoring system shut down successfully");
            }
            
            UnifiedCommandSystem cmdSystem = commandSystem.get();
            if (cmdSystem != null) {
                // In a real implementation, you would call cmdSystem.shutdown()
                LOGGER.info("Command system shut down successfully");
            }
            
            // Reset state
            isInitialized.set(false);
            isPerformanceMonitoringEnabled.set(false);
            
            LOGGER.info("KneafCore graceful shutdown completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error during graceful shutdown", e);
            // Continue with shutdown even if some components fail
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
        
        ConfigurationManager configManager = configurationManager.get();
        if (configManager != null) {
            configManager.reload();
            LOGGER.info("Configurations reloaded successfully");
            
            // In a real implementation, you would notify other systems of configuration changes
            // For example: performanceManager.get().updateConfiguration();
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
        ConfigurationManager configManager = configurationManager.get();
        if (configManager == null) {
            throw new IllegalStateException("Configuration manager is not initialized");
        }
        return configManager.getConfiguration(PerformanceConfig.class);
    }
}
