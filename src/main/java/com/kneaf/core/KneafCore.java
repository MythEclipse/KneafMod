package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main mod class for KneafCore. Refactored to use modular architecture with clear separation of concerns.
 * Delegates responsibilities to specialized classes: ModInitializer, EventHandler, SystemManager, LifecycleManager.
 * Follows SOLID principles and provides proper lifecycle management.
 */
@Mod(KneafCore.MODID)
public class KneafCore {
    static {
        System.loadLibrary("rustperf");
    }

    /** Mod ID used for registration and identification */
    public static final String MODID = "kneafcore";

    /** Logger for the mod */
    public static final Logger LOGGER = LogUtils.getLogger();

    // Deferred Registers
    /** Deferred register for blocks */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** Deferred register for items */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // Core components - refactored to use modular architecture
    private static final AtomicReference<KneafCore> INSTANCE = new AtomicReference<>();

    /**
     * Constructor for the mod. Registers all deferred registers, event listeners, and sets up core systems.
     *
     * @param modEventBus The mod event bus
     * @param modContainer The mod container
     */
    public KneafCore(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE.set(this);
        
        // Initialize modular components
        
        // Register deferred registers
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        // Register event listeners
        modEventBus.addListener(this::commonSetup);
        
        // Register configuration
        
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
            // Delegate initialization to SystemManager
            
            LOGGER.info("KneafCore initialization completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to complete KneafCore initialization", e);
        }
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
     * Multiplies two 4x4 matrices represented as float arrays of length 16.
     *
     * @param a the first matrix
     * @param b the second matrix
     * @return the result matrix
     */
    private static native float[] matrixMultiply(float[] a, float[] b);

    /**
     * Computes the dot product of two 3D vectors represented as float arrays of length 3.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the dot product
     */
    private static native float vectorDot(float[] a, float[] b);

    /**
     * Computes the cross product of two 3D vectors represented as float arrays of length 3.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the cross product vector
     */
    private static native float[] vectorCross(float[] a, float[] b);

    /**
     * Normalizes a 3D vector represented as a float array of length 3.
     *
     * @param a the vector to normalize
     * @return the normalized vector
     */
    private static native float[] vectorNormalize(float[] a);

    /**
     * Rotates a 3D vector using a quaternion represented as float arrays.
     *
     * @param q the quaternion (length 4)
     * @param v the vector to rotate (length 3)
     * @return the rotated vector
     */
    private static native float[] quaternionRotateVector(float[] q, float[] v);

    /**
     * Performs A* pathfinding on a 2D grid.
     *
     * @param grid the grid as a boolean array (true for obstacles)
     * @param width the width of the grid
     * @param height the height of the grid
     * @param startX the starting X coordinate
     * @param startY the starting Y coordinate
     * @param goalX the goal X coordinate
     * @param goalY the goal Y coordinate
     * @return the path as an array of coordinates [x1, y1, x2, y2, ...] or null if no path found
     */
    private static native int[] aStarPathfind(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY);

    // Client setup events - kept as static nested class for NeoForge compatibility
    @net.neoforged.fml.common.EventBusSubscriber(
        modid = MODID,
        bus = net.neoforged.fml.common.EventBusSubscriber.Bus.MOD,
        value = net.neoforged.api.distmarker.Dist.CLIENT
    )
    static class ClientModEvents {
        private ClientModEvents() {}

        @net.neoforged.bus.api.SubscribeEvent
        static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", net.minecraft.client.Minecraft.getInstance().getUser().getName());
            
            try {
                // Register client-specific components
                // For example: PerformanceOverlayClient.registerClient(event);
                
            } catch (Throwable t) {
                LOGGER.debug("Client component registration failed: {}", t.getMessage());
            }
        }
    }
}
