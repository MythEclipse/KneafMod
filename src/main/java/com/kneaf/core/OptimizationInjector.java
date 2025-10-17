package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core component that intercepts Minecraft lifecycle events and redirects them to optimized implementations.
 * Focuses on replacing entity ticking with a native Rust implementation by hooking into the EntityTickEvent.
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizationInjector {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final PerformanceManager PERFORMANCE_MANAGER = PerformanceManager.getInstance();

    private static final AtomicInteger optimizationHits = new AtomicInteger(0);
    private static final AtomicInteger optimizationMisses = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessed = new AtomicLong(0);

    private static final String RUST_PERF_LIBRARY = "rustperf";
    private static boolean isNativeLibraryLoaded = false;

    static {
        try {
            System.loadLibrary(RUST_PERF_LIBRARY);
            isNativeLibraryLoaded = true;
            LOGGER.info("Successfully loaded Rust performance native library");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Failed to load Rust performance native library: {}", e.getMessage());
            isNativeLibraryLoaded = false;
        }
    }

    private OptimizationInjector() {}

    /**
     * Intercepts the ticking of each individual entity before it occurs.
     * If conditions are met, it replaces the vanilla tick with a native implementation and cancels the event.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();

        // Ensure we are on the server side and the feature is enabled.
        if (entity.level().isClientSide() || !PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            return;
        }
        
        // Skip optimization if native integration is disabled. Let vanilla handle it.
        if (!isNativeLibraryLoaded || !PERFORMANCE_MANAGER.isRustIntegrationEnabled()) {
            return;
        }

        // For safety, players are always ticked normally by vanilla.
        if (entity instanceof Player) {
            return;
        }

        try {
            long startTime = System.nanoTime();
            
            // Prepare data for the native function
            double[] entityData = new double[]{
                    entity.getX(), entity.getY(), entity.getZ(),
                    entity.getDeltaMovement().x, entity.getDeltaMovement().y, entity.getDeltaMovement().z
            };

            // Call the native Rust function to perform the tick logic
            double[] resultData = rustperf_tick_entity(entityData, entity.onGround());
            
            totalEntitiesProcessed.incrementAndGet();

            if (resultData != null && resultData.length == 6) {
                // If the native function succeeds, apply the results
                entity.setPos(resultData[0], resultData[1], resultData[2]);
                entity.setDeltaMovement(resultData[3], resultData[4], resultData[5]);
                
                // CRUCIAL: Cancel the event to prevent the vanilla tick logic from running.
                event.setCanceled(true);

                long duration = System.nanoTime() - startTime;
                recordOptimizationHit(String.format("Native tick for entity %d in %dns", entity.getId(), duration));
            } else {
                // If native fails, do nothing and let the original vanilla tick proceed.
                recordOptimizationMiss("Native tick failed for entity " + entity.getId() + ", falling back to vanilla.");
            }
        } catch (Throwable t) { // Catch Throwable to include potential LinkageErrors
            LOGGER.error("Unrecoverable error during native entity tick for entity " + entity.getId() + ". Falling back to vanilla.", t);
            recordOptimizationMiss("Native tick threw an error for entity " + entity.getId());
            // Do not cancel the event, let vanilla handle it as a fallback.
        }
    }

    // --- Native Method Declarations ---
    private static native int rustperf_calculate_entity_performance(int entityCount, String levelDimension);
    private static native double[] rustperf_tick_entity(double[] entityData, boolean onGround);
    private static native String rustperf_get_performance_stats();
    private static native void rustperf_reset_performance_stats();

    // --- Metrics Methods ---
    private static void recordOptimizationHit(String details) {
        optimizationHits.incrementAndGet();
        LOGGER.debug("Optimization hit: {}", details);
    }

    private static void recordOptimizationMiss(String details) {
        optimizationMisses.incrementAndGet();
        LOGGER.debug("Optimization miss: {}", details);
    }

    public static String getOptimizationMetrics() {
        long totalOps = optimizationHits.get() + optimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) optimizationHits.get() / totalOps * 100 : 0.0;
        return String.format("OptimizationMetrics{hits=%d, misses=%d, totalProcessed=%d, hitRate=%.2f%%, nativeLoaded=%b}",
                optimizationHits.get(), optimizationMisses.get(), totalEntitiesProcessed.get(), hitRate, isNativeLibraryLoaded);
    }

    public static void resetMetrics() {
        optimizationHits.set(0);
        optimizationMisses.set(0);
        totalEntitiesProcessed.set(0);
        if(isNativeLibraryLoaded) {
            rustperf_reset_performance_stats(); // Also reset stats on the native side
        }
        LOGGER.info("Optimization metrics have been reset.");
    }
}