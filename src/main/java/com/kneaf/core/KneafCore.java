package com.kneaf.core;

import com.kneaf.core.compatibility.ModCompatibility;
import com.kneaf.core.performance.integration.NeoForgeEventIntegration;
import com.kneaf.core.performance.monitoring.PerformanceManager;
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

/**
 * Main mod class for KneafCore. Handles mod initialization, registration of blocks, items, and
 * creative tabs. Also manages server-side performance optimizations.
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

  /**
   * Constructor for the mod. Registers all deferred registers, event listeners, and configuration.
   *
   * @param modEventBus The mod event bus
   * @param modContainer The mod container
   */
  public KneafCore(IEventBus modEventBus, ModContainer modContainer) {
    // Register the commonSetup method for modloading
    modEventBus.addListener(this::commonSetup);

    // Register the Deferred Register to the mod event bus so blocks get registered
    BLOCKS.register(modEventBus);
    // Register the Deferred Register to the mod event bus so items get registered
    ITEMS.register(modEventBus);

    // Register ourselves for server and other game events we are interested in.
    NeoForge.EVENT_BUS.register(this);

    // Register the new NeoForge event integration for Rust optimizations
    NeoForge.EVENT_BUS.register(NeoForgeEventIntegration.class);

    // Register commands - handled by @SubscribeEvent since class is registered
    LOGGER.info("Commands listener will be registered via @SubscribeEvent on class registration");

    // Register our mod's ModConfigSpec so that FML can create and load the config file for us
    modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
  }

  private void commonSetup(FMLCommonSetupEvent event) {
    // Some common setup code
    LOGGER.info("HELLO FROM COMMON SETUP");
    LOGGER.info("{ }{ }", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

    Config.ITEM_STRINGS.get().forEach(item -> LOGGER.info("ITEM >> { }", item));

    // Check for mod compatibility and log warnings
    ModCompatibility.checkForConflicts();
    
    // Initialize Rust performance system with enhanced logging
    initializeRustPerformanceSystem();
  }
  
  private void initializeRustPerformanceSystem() {
    try {
      // Initialize Rust performance system
      com.kneaf.core.performance.RustPerformance.initialize();
      
      // Log startup information
      String optimizationsActive = "Dynamic entity ticking, Item stack merging, Mob AI optimization, Chunk generation optimization";
      String cpuInfo = "SIMD-optimized processing with AVX2/AVX-512 support";
      String configApplied = "Performance optimizations enabled with safety checks";
      
      com.kneaf.core.performance.RustPerformance.logStartupInfo(optimizationsActive, cpuInfo, configApplied);
      
      LOGGER.info("Rust performance system initialized with enhanced logging");
    } catch (Exception e) {
      LOGGER.error("Failed to initialize Rust performance system", e);
    }
  }

  // Register commands
  @SubscribeEvent
  private void registerCommands(RegisterCommandsEvent event) {
    // Consolidated commands: expose all performance related commands under `kneaf` namespace.
    com.kneaf.core.command.PerformanceCommand.register(event.getDispatcher());
  }

  /** Handles server tick events for performance optimizations. */
  @SubscribeEvent
  public void onServerTick(ServerTickEvent.Post event) {
    PerformanceManager.onServerTick(event.getServer());
  }

  // You can use SubscribeEvent and let the Event Bus discover methods to call
  @SubscribeEvent
  public void onServerStarting(ServerStartingEvent event) {
    // Do something when the server starts
    LOGGER.info("HELLO from server starting");
    // Initialize Valence integration for performance optimizations
    // Server start logic continues; PerformanceManager initialized lazily
  }

  // Prefer event-based shutdown when the mod loader provides it
  @SubscribeEvent
  public void onServerStopping(ServerStoppingEvent event) {
    LOGGER.info("Server stopping - shutting down PerformanceManager");
    try {
      com.kneaf.core.performance.monitoring.PerformanceManager.shutdown();
    } catch (Exception e) {
      LOGGER.warn("Error while shutting down PerformanceManager", e);
    }
  }

  // You can use EventBusSubscriber to automatically register all static methods in the class
  // annotated with @SubscribeEvent
  @EventBusSubscriber(
      modid = KneafCore.MODID,
      bus = EventBusSubscriber.Bus.MOD,
      value = Dist.CLIENT)
  static class ClientModEvents {
    private ClientModEvents() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
      // Some client setup code
      LOGGER.info("HELLO FROM CLIENT SETUP");
      LOGGER.info("MINECRAFT NAME >> { }", Minecraft.getInstance().getUser().getName());
      // Register the performance overlay client receiver and renderer
      try {
        com.kneaf.core.client.PerformanceOverlayClient.registerClient(event);
      } catch (Throwable t) {
        LOGGER.debug("Performance overlay registration failed: { }", t.getMessage());
      }
    }
  }
}
