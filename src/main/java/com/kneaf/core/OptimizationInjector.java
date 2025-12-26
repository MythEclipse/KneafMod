package com.kneaf.core;

import com.kneaf.core.async.AsyncMetricsCollector;
import com.kneaf.core.math.VectorMath;
import com.kneaf.core.model.EntityPhysicsData;
import com.kneaf.core.model.EntityProcessingResult;
import com.kneaf.core.util.EntityPhysicsHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized entity processing injector.
 * Orchestrates entity processing by delegating to EntityProcessingService and
 * EntityPhysicsHelper.
 * Follows DRY principles by removing duplicated logic.
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizationInjector {
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationInjector.class);

    // Services
    private static final EntityProcessingService entityProcessingService = EntityProcessingService.getInstance();
    private static final PerformanceManager performanceManager = PerformanceManager.getInstance();
    private static final AsyncMetricsCollector metrics = AsyncMetricsCollector.getInstance();

    // Server Instance
    private static MinecraftServer serverInstance;

    static {
        // Ensure library is loaded on startup
        RustNativeLoader.loadLibrary();
    }

    public static boolean isNativeLibraryLoaded() {
        return RustNativeLoader.isLibraryLoaded();
    }

    /**
     * Track entities that recently took damage to preserve knockback.
     * Delegates state tracking to EntityProcessingService.
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity().level().isClientSide())
            return;

        // Delegate to service
        entityProcessingService.notifyEntityDamaged(event.getEntity().getId());
        LOGGER.trace("Entity {} marked for knockback protection after taking damage", event.getEntity().getId());
    }

    /**
     * Async entity tick handler - non-blocking main thread integration
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        long startTime = System.nanoTime();

        try {
            Entity entity = event.getEntity();
            if (entity == null)
                return;

            // Skip processing if physics optimization is disabled
            if (!performanceManager.isAdvancedPhysicsOptimized())
                return;

            // --- Validation delegated to EntityPhysicsHelper ---
            if (entity.level().isClientSide() || entity.isRemoved())
                return;

            // COMPATIBILITY CHECK
            if (!EntityPhysicsHelper.isEntityOptimizable(entity))
                return;

            // Check for knockback protection
            if (entityProcessingService.checkAndTickProtection(entity.getId())) {
                return; // Skip async processing this tick
            }
            // --- End Validation ---

            // Check if library is ready
            if (!isNativeLibraryLoaded())
                return;

            // Extract physics data using Helper
            EntityPhysicsData physicsData = EntityPhysicsHelper.extractPhysicsData(entity);
            if (physicsData == null)
                return;

            // Calculate adaptive timeout using Helper
            int adaptiveTimeoutMs = EntityPhysicsHelper.calculateAdaptiveTimeout(entity);

            // Submit for async processing via Service
            CompletableFuture<EntityProcessingResult> future = entityProcessingService
                    .processEntityAsync(entity, physicsData);

            // Handle result asynchronously (non-blocking)
            future.thenAccept(result -> {
                long processingTime = System.nanoTime() - startTime;
                if (result.success) {
                    applyPhysicsResult(entity, result);

                    metrics.recordTimer("optimization_injector_process_time", processingTime);
                    metrics.incrementCounter("optimization_injector_hits");
                } else {
                    metrics.incrementCounter("optimization_injector_misses");
                    // Log specific failures if needed, but metrics handle the counts
                }
            }).exceptionally(throwable -> {
                metrics.incrementCounter("optimization_injector_async_errors");
                LOGGER.warn("Async optimization error: {}", throwable.getMessage());
                return null;
            });

            // Timeout handling with adaptive timeout
            future.orTimeout(adaptiveTimeoutMs, TimeUnit.MILLISECONDS).exceptionally(throwable -> {
                // Fallback to synchronous processing for timeout via Service
                EntityProcessingResult syncResult = entityProcessingService.processEntitySync(entity, physicsData);
                if (syncResult.success) {
                    applyPhysicsResult(entity, syncResult);
                    metrics.incrementCounter("optimization_injector_timeout_fallback_success");
                } else {
                    metrics.incrementCounter("optimization_injector_timeout_fallback_failure");
                }
                return null;
            });

        } catch (Exception e) {
            metrics.incrementCounter("optimization_injector_unexpected_errors");
            LOGGER.error("Unexpected error in entity tick handler: {}", e.getMessage(), e);
        }
    }

    private static void applyPhysicsResult(Entity entity, EntityProcessingResult result) {
        if (!result.success || result.processedData == null)
            return;

        try {
            EntityPhysicsData data = result.processedData;

            // Re-validate before applying to be safe (EntityPhysicsHelper can be used here
            // too if public)
            if (EntityPhysicsHelper.isValidDouble(data.motionX) &&
                    EntityPhysicsHelper.isValidDouble(data.motionY) &&
                    EntityPhysicsHelper.isValidDouble(data.motionZ)) {

                entity.setDeltaMovement(data.motionX, data.motionY, data.motionZ);
                metrics.incrementCounter("optimization_injector_applied_updates");
            }
        } catch (Exception e) {
            LOGGER.error("Error applying physics result to entity {}: {}", entity.getId(), e.getMessage(), e);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (serverInstance == null) {
            serverInstance = event.getServer();
        }

        if (performanceManager.isAdvancedPhysicsOptimized()) {
            metrics.incrementCounter("optimization_injector.server_tick");
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (performanceManager.isAdvancedPhysicsOptimized()) {
            metrics.incrementCounter("optimization_injector.level_tick");
        }
    }

    // Removed legacy local metrics methods (recordAsyncOptimizationHit, etc.) in
    // favor of direct Metrics usage.
    // Removed duplicate fallbacks.
}