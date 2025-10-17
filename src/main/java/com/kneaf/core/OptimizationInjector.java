package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final ExecutorService ENTITY_EXECUTOR = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private static final AtomicInteger optimizationHits = new AtomicInteger(0);
    private static final AtomicInteger optimizationMisses = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessed = new AtomicLong(0);

    private static final String RUST_PERF_LIBRARY_PATH = System.getProperty("user.dir") + "/src/main/resources/natives/rustperf.dll";
    private static boolean isNativeLibraryLoaded = false;

    static {
        try {
            System.load(RUST_PERF_LIBRARY_PATH);
            isNativeLibraryLoaded = true;
            LOGGER.info("Successfully loaded Rust performance native library from {}", RUST_PERF_LIBRARY_PATH);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("Failed to load Rust performance native library from {}: {}", RUST_PERF_LIBRARY_PATH, e.getMessage());
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

        long startTime = System.nanoTime();

        // Prepare data for the native function
        double[] entityData = new double[]{
                 entity.getX(), entity.getY(), entity.getZ(),
                 entity.getDeltaMovement().x, entity.getDeltaMovement().y, entity.getDeltaMovement().z
        };

        try {
            // Call native function synchronously to replace vanilla calculations
            double[] resultData = rustperf_tick_entity(entityData, entity.onGround());

            totalEntitiesProcessed.incrementAndGet();

            if (resultData != null && resultData.length == 6) {
                // Apply results immediately on the same thread
                entity.setPos(resultData[0], resultData[1], resultData[2]);
                entity.setDeltaMovement(resultData[3], resultData[4], resultData[5]);

                long duration = System.nanoTime() - startTime;
                recordOptimizationHit(String.format("Synchronous native tick for entity %d in %dns", entity.getId(), duration));
            } else {
                recordOptimizationMiss("Synchronous native tick failed for entity " + entity.getId() + ", falling back to vanilla.");
                // Do not cancel event, let vanilla handle it
                return;
            }
        } catch (Exception e) {
            LOGGER.error("Unrecoverable error during synchronous native entity tick for entity " + entity.getId() + ". Falling back to vanilla.", e);
            recordOptimizationMiss("Synchronous native tick threw an error for entity " + entity.getId());
            // Do not cancel event, let vanilla handle it
            return;
        }

        // Cancel the event to prevent vanilla tick since native succeeded
        event.setCanceled(true);
    }

    /**
     * Intercepts server tick events to inject native optimizations for server-side calculations.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (!isNativeLibraryLoaded || !PERFORMANCE_MANAGER.isAiPathfindingOptimized()) {
            return;
        }

        int entityCount = 0;
        String dimension = "server";
        rustperf_calculate_entity_performance(entityCount, dimension);
        recordOptimizationHit("Server tick native optimization applied");
    }

    /**
     * Intercepts level tick events to inject native optimizations for level-specific calculations.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (!isNativeLibraryLoaded || !PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            return;
        }

        var level = event.getLevel();
        int entityCount = 0; // Placeholder for entity count calculation
        String dimension = level.dimension().location().toString();
        rustperf_calculate_entity_performance(entityCount, dimension);
        recordOptimizationHit("Level tick native optimization applied for " + dimension);
    }

    // --- Native Method Declarations ---
    private static native int rustperf_calculate_entity_performance(int entityCount, String levelDimension);
    private static native double[] rustperf_tick_entity(double[] entityData, boolean onGround);
    private static native String rustperf_get_performance_stats();
    private static native void rustperf_reset_performance_stats();
    private static native String rustperf_parallel_a_star(String gridJson, String queriesJson);

    // --- Metrics Methods ---
    private static void recordOptimizationHit(String details) {
        optimizationHits.incrementAndGet();
        LOGGER.info("Native optimization applied: {}", details);
        if (optimizationHits.get() % 100 == 0) {
            logPerformanceStats();
        }
    }

    private static void recordOptimizationMiss(String details) {
        optimizationMisses.incrementAndGet();
        LOGGER.info("Optimization fallback: {}", details);
    }
    private static void logPerformanceStats() {
        if (isNativeLibraryLoaded) {
            CompletableFuture.supplyAsync(() -> rustperf_get_performance_stats(), ENTITY_EXECUTOR)
                .thenAccept(nativeStats -> LOGGER.info("Native performance stats: {}", nativeStats))
                .exceptionally(t -> {
                    LOGGER.warn("Failed to retrieve async native performance stats: {}", t.getMessage());
                    return null;
                });
        }
        LOGGER.info("Java optimization metrics: {}", getOptimizationMetrics());
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