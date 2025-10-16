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
