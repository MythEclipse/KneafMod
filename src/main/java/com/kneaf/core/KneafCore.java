package com.kneaf.core;

import com.kneaf.entities.ModEntities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main mod class for KneafCore. Refactored to use modular architecture with
 * clear separation of concerns.
 * Delegates responsibilities to specialized classes: ModInitializer,
 * EventHandler, SystemManager, LifecycleManager.
 * Follows SOLID principles and provides proper lifecycle management.
 * Uses JOML math types for vector and quaternion operations.
 */
@Mod(KneafCore.MODID)
public class KneafCore {
    static {
        // Native library removed; implementations provided in Java for portability
    }

    /** Mod ID used for registration and identification */
    public static final String MODID = "kneafcore";

    /** Logger for the mod */
    public static final Logger LOGGER = LoggerFactory.getLogger(KneafCore.class);

    // Deferred Registers
    /** Deferred register for blocks */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** Deferred register for items */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // Core components - refactored to use modular architecture
    private static final AtomicReference<KneafCore> INSTANCE = new AtomicReference<>();

    /**
     * Constructor for the mod. Registers all deferred registers, event listeners,
     * and sets up core systems.
     *
     * @param modEventBus  The mod event bus
     * @param modContainer The mod container
     */
    @SuppressWarnings("null")
    public KneafCore(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE.set(this);

        // Initialize centralized WorkerThreadPool
        WorkerThreadPool.initialize();
        LOGGER.info("WorkerThreadPool initialized - Centralized thread management active");

        // Initialize modular components

        // Register deferred registers
        ModEntities.ENTITIES.register(modEventBus);
        ModEntities.ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        // Register event listeners
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::buildCreativeTabContents);

        LOGGER.info("KneafCore mod constructor completed - waiting for initialization");
    }

    /**
     * Common setup method called during mod initialization.
     * Delegates initialization to SystemManager.
     *
     * @param event The FML common setup event
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Starting KneafCore common setup");

        try {
            // Log runtime mode
            LOGGER.info("Runtime mode: {}", ModeDetector.getCurrentMode());

            // Initialize PerformanceManager
            PerformanceManager performanceManager = PerformanceManager.getInstance();
            performanceManager.loadConfiguration();
            LOGGER.info("PerformanceManager initialized: {}", performanceManager);

            // Initialize Mod Compatibility
            ModCompatibility.init();

            // Initialize Rust Optimizations (triggers native load)
            RustOptimizations.initialize();

            // Initialize ChunkGeneratorOptimizer (includes RustNoise warmup)
            ChunkGeneratorOptimizer.init();
            boolean rustAvailable = ChunkGeneratorOptimizer.isRustAccelerationAvailable();
            LOGGER.info("ChunkGeneratorOptimizer initialized, Rust acceleration: {}", rustAvailable);

            if (!rustAvailable) {
                LOGGER.error("""


                        ============================================================
                        CRITICAL WARNING: NATIVE RUST LIBRARY NOT LOADED
                        ============================================================
                        The native Rust optimization library could not be loaded.
                        KneafCore will strictly operate in JAVA FALLBACK MODE.

                        Performance will be SIGNIFICANTLY reduced compared to native mode.
                        Advanced features like SIMD fluid simulation and batched lighting
                        will use slower Java implementations or be disabled.

                        Please verify your OS/Architecture compatibility and check logs.
                        ============================================================
                        """);
            }

            // Initialize ParallelEntityTicker
            LOGGER.info("ParallelEntityTicker parallelism: {}", ParallelEntityTicker.getParallelism());

            // Initialize ObjectPool for common objects
            @SuppressWarnings("unused") // Initialized for side-effect registration
            com.kneaf.core.util.ObjectPool<double[]> vectorPool = new com.kneaf.core.util.ObjectPool<>(
                    () -> new double[3], 64);
            LOGGER.info("ObjectPool initialized for vector reuse");

            // Register OptimizationInjector event listeners
            LOGGER.info("Registering OptimizationInjector event listeners");

            // Register commands on the game bus
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::registerCommands);

            LOGGER.info("KneafCore initialization completed successfully");
            LOGGER.info("=== Integrated Systems ===");
            LOGGER.info("  - 42 Mixins (auto-inject via Mixin system)");
            LOGGER.info("  - ChunkProcessor (parallel chunk processing)");
            LOGGER.info("  - ParallelEntityTicker (batch entity ops)");
            LOGGER.info("  - RustOptimizations (SIMD via Rust/Rayon)");
            LOGGER.info("  - ObjectPool (GC reduction)");

        } catch (Exception e) {
            LOGGER.error("Failed to complete KneafCore initialization", e);
        }
    }

    /**
     * Client setup method called during mod initialization on the client side.
     * Registers entity renderers and other client-side components.
     *
     * @param event The FML client setup event
     */
    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Starting KneafCore client setup");
        LOGGER.info("KneafCore client setup completed successfully");
    }

    /**
     * Registers entity attributes for custom entities.
     *
     * @param event The entity attribute creation event
     */
    @SuppressWarnings("unused")
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        // No custom entities to register - focusing on performance mod
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
     * Performs A* pathfinding asynchronously on a 2D grid.
     */
    public static CompletableFuture<int[]> aStarPathfindAsync(boolean[] grid, int width, int height, int startX,
            int startY, int goalX, int goalY) {
        return com.kneaf.core.pathfinding.DefaultPathfindingService.getInstance().findPathAsync(grid, width, height,
                startX, startY, goalX, goalY);
    }

    /**
     * Builds the contents of creative mode tabs.
     *
     * @param event The build creative mode tab contents event
     */
    private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // No custom spawn eggs - focusing on performance mod
    }

    /**
     * Registers commands for the mod.
     *
     * @param event The register commands event
     */

    /**
     * Registers entity renderers for the mod.
     *
     * @param event The entity renderers registration event
     */
    @SuppressWarnings("unused")
    private void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // No custom entities to render - focusing on performance mod
    }

    /**
     * Registers commands for the mod.
     *
     * @param event The register commands event
     */
    private void registerCommands(RegisterCommandsEvent event) {
        // Register async monitoring commands
        com.kneaf.commands.AsyncMonitorCommands.register(event.getDispatcher());

        // Register chunk pre-generation commands
        com.kneaf.commands.PregenCommands.register(event.getDispatcher());

        // Additional commands can be registered here as they are implemented
        // MetricsCommand is currently integrated into AsyncMonitorCommands
    }

}
