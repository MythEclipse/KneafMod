package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.kneaf.core.math.VectorMath;

/**
 * Optimized entity processing injector with async processing and hash-based
 * entity type lookup.
 * 
 * Replaced legacy OptimizationInjector with this optimized version.
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizationInjector {
    private static final String RUST_PERF_LIBRARY_NAME = "rustperf"; // Kept for reference
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationInjector.class);

    // Services
    private static final EntityProcessingService entityProcessingService = EntityProcessingService.getInstance();
    private static final PerformanceManager performanceManager = PerformanceManager.getInstance();

    // Performance metrics
    private static final AtomicInteger asyncOptimizationHits = new AtomicInteger(0);
    private static final AtomicInteger asyncOptimizationMisses = new AtomicInteger(0);
    private static final AtomicInteger asyncOptimizationErrors = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessedAsync = new AtomicLong(0);
    private static final AtomicLong totalProcessingTimeNs = new AtomicLong(0);

    // Configuration
    private static final int BASE_ASYNC_TIMEOUT_MS = 50;
    private static final int MAX_CONCURRENT_ENTITIES = 1000;
    private static final int MIN_ASYNC_TIMEOUT_MS = 25;
    private static final int MAX_ASYNC_TIMEOUT_MS = 200;

    // Knockback protection
    private static final Map<Integer, Integer> recentlyDamagedEntities = new HashMap<>();
    private static final int KNOCKBACK_PROTECTION_TICKS = 6;

    // Server Instance
    private static MinecraftServer serverInstance;

    // CPU Load Bean
    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory
            .getPlatformMXBean(OperatingSystemMXBean.class);

    static {
        // Ensure library is loaded on startup
        RustNativeLoader.loadLibrary();
    }

    public static boolean isNativeLibraryLoaded() {
        return RustNativeLoader.isLibraryLoaded();
    }

    /**
     * Track entities that recently took damage to preserve knockback
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity().level().isClientSide())
            return;

        // Mark entity as recently damaged to skip optimization temporarily
        recentlyDamagedEntities.put(event.getEntity().getId(), KNOCKBACK_PROTECTION_TICKS);

        LOGGER.trace("Entity {} marked for knockback protection after taking damage",
                event.getEntity().getId());
    }

    /**
     * Async entity tick handler - non-blocking main thread integration
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        long startTime = System.nanoTime();

        try {
            Entity entity = event.getEntity();
            if (entity == null) {
                return;
            }

            // Skip processing if throttling is disabled
            if (!performanceManager.isEntityThrottlingEnabled()) {
                return;
            }

            // --- Real Entity Validation ---
            if (entity.level().isClientSide() || entity.isRemoved()) {
                return;
            }

            // COMPATIBILITY CHECK: Only optimize vanilla and our mod entities
            if (!isEntityOptimizable(entity)) {
                return; // Skip other mod entities for compatibility
            }

            // Check for knockback protection
            Integer protectionTicks = recentlyDamagedEntities.get(entity.getId());
            if (protectionTicks != null) {
                if (protectionTicks > 0) {
                    recentlyDamagedEntities.put(entity.getId(), protectionTicks - 1);
                    return; // Skip async processing this tick
                } else {
                    recentlyDamagedEntities.remove(entity.getId()); // Protection expired
                }
            }
            // --- End Validation ---

            // Check if library is ready
            if (!isNativeLibraryLoaded()) {
                return;
            }

            // Extract physics data
            EntityProcessingService.EntityPhysicsData physicsData = extractPhysicsData(entity);
            if (physicsData == null) {
                return;
            }

            // Calculate adaptive timeout
            int adaptiveTimeoutMs = calculateAdaptiveTimeout(entity);

            // Submit for async processing with adaptive timeout
            CompletableFuture<EntityProcessingService.EntityProcessingResult> future = entityProcessingService
                    .processEntityAsync(entity, physicsData);

            // Handle result asynchronously (non-blocking)
            future.thenAccept(result -> {
                long processingTime = System.nanoTime() - startTime;
                if (result.success) {
                    applyPhysicsResult(entity, result);
                    // Record successful processing metrics silently
                    Map<String, Object> context = new HashMap<>();
                    context.put("operation", "entity_processing");
                    context.put("result", "success");
                    PerformanceMonitoringSystem.getInstance().recordEvent(
                            "OptimizationInjector", "entity_process", processingTime, context);
                } else {
                    recordAsyncOptimizationMiss("Async processing failed: " + result.message);

                    // Record failed processing metrics
                    Map<String, Object> errorContext = new HashMap<>();
                    errorContext.put("operation", "entity_processing");
                    errorContext.put("result", "failure");
                    errorContext.put("reason", result.message);
                    PerformanceMonitoringSystem.getInstance().recordError(
                            "OptimizationInjector",
                            new RuntimeException("Entity processing failed: " + result.message), errorContext);
                }
            }).exceptionally(throwable -> {
                recordAsyncOptimizationError("Async processing error: " + throwable.getMessage());

                // Record error metrics
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("operation", "entity_processing");
                errorContext.put("result", "error");
                errorContext.put("error_type", throwable.getClass().getSimpleName());
                PerformanceMonitoringSystem.getInstance().recordError(
                        "OptimizationInjector", throwable, errorContext);

                return null;
            });

            // Timeout handling with adaptive timeout
            future.orTimeout(adaptiveTimeoutMs, TimeUnit.MILLISECONDS).exceptionally(throwable -> {
                // Fallback to synchronous processing for timeout
                performSynchronousFallback(entity, physicsData);
                return null;
            });

        } catch (Exception e) {
            // Record unexpected error
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("operation", "entity_tick");
            errorContext.put("error_type", "unexpected");
            PerformanceMonitoringSystem.getInstance().recordError(
                    "OptimizationInjector", e, errorContext);

            LOGGER.error("Unexpected error in entity tick handler: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if entity should be optimized for compatibility with other mods
     */
    private static boolean isEntityOptimizable(Entity entity) {
        if (entity == null)
            return false;

        try {
            String entityClassName = entity.getClass().getName();

            // EXCLUDE ITEMS
            if (entityClassName.contains(".item.") ||
                    entityClassName.contains("ItemEntity") ||
                    entityClassName.contains("ItemFrame") ||
                    entityClassName.contains("ItemStack")) {
                return false;
            }

            // WHITELIST APPROACH
            boolean isVanillaMinecraftEntity = entityClassName.startsWith("net.minecraft.world.entity.") ||
                    entityClassName.startsWith("net.minecraft.client.player.") ||
                    entityClassName.startsWith("net.minecraft.server.level.");

            boolean isKneafModEntity = entityClassName.startsWith("com.kneaf.entities.");

            // Only allow vanilla or our mod's entities
            if (!isVanillaMinecraftEntity && !isKneafModEntity) {
                return false;
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculate adaptive timeout for async processing based on entity complexity
     */
    private static int calculateAdaptiveTimeout(Entity entity) {
        int timeout = BASE_ASYNC_TIMEOUT_MS;

        // Use a simple range constraint for now
        return Math.max(MIN_ASYNC_TIMEOUT_MS, Math.min(timeout, MAX_ASYNC_TIMEOUT_MS));
    }

    private static EntityProcessingService.EntityPhysicsData extractPhysicsData(Entity entity) {
        try {
            Vec3 deltaMovement = entity.getDeltaMovement();
            double x = deltaMovement.x();
            double y = deltaMovement.y();
            double z = deltaMovement.z();

            if (Double.isNaN(x) || Double.isInfinite(x) ||
                    Double.isNaN(y) || Double.isInfinite(y) ||
                    Double.isNaN(z) || Double.isInfinite(z)) {
                return null;
            }

            return new EntityProcessingService.EntityPhysicsData(x, y, z);
        } catch (Exception e) {
            LOGGER.error("Error extracting physics data: {}", e.getMessage());
            return null;
        }
    }

    private static void applyPhysicsResult(Entity entity,
            EntityProcessingService.EntityProcessingResult result) {
        if (!result.success || result.processedData == null) {
            return;
        }

        try {
            EntityProcessingService.EntityPhysicsData data = result.processedData;

            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                    Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                    Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return;
            }

            entity.setDeltaMovement(data.motionX, data.motionY, data.motionZ);

            totalEntitiesProcessedAsync.incrementAndGet();

        } catch (Exception e) {
            LOGGER.error("Error applying physics result to entity {}: {}", entity.getId(), e.getMessage(), e);
        }
    }

    private static void performSynchronousFallback(Entity entity,
            EntityProcessingService.EntityPhysicsData physicsData) {
        try {
            boolean useAdvancedPhysics = PerformanceManager.getInstance().isAdvancedPhysicsOptimized();

            if (useAdvancedPhysics) {
                try {
                    // Use VectorMath for fast vector normalization (handles native fallback
                    // automatically)
                    double[] rustOptimized = VectorMath.normalize(
                            physicsData.motionX,
                            physicsData.motionY,
                            physicsData.motionZ);

                    double magnitude = VectorMath.length(
                            physicsData.motionX,
                            physicsData.motionY,
                            physicsData.motionZ);

                    double newX = rustOptimized[0] * magnitude;
                    double newY = rustOptimized[1] * magnitude;
                    double newZ = rustOptimized[2] * magnitude;

                    if (!Double.isNaN(newX) && !Double.isInfinite(newX) &&
                            !Double.isNaN(newY) && !Double.isInfinite(newY) &&
                            !Double.isNaN(newZ) && !Double.isInfinite(newZ)) {
                        entity.setDeltaMovement(newX, newY, newZ);
                    } else {
                        entity.setDeltaMovement(physicsData.motionX, physicsData.motionY, physicsData.motionZ);
                    }

                } catch (Exception e) {
                    entity.setDeltaMovement(physicsData.motionX, physicsData.motionY, physicsData.motionZ);
                }
            } else {
                entity.setDeltaMovement(physicsData.motionX, physicsData.motionY, physicsData.motionZ);
            }
        } catch (Exception e) {
            recordAsyncOptimizationMiss("Synchronous fallback failed: " + e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (serverInstance == null) {
            serverInstance = event.getServer();
        }

        if (performanceManager.isEntityThrottlingEnabled()) {
            try {
                PerformanceMonitoringSystem.getInstance().getMetricAggregator()
                        .incrementCounter("optimization_injector.server_tick");
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (performanceManager.isEntityThrottlingEnabled()) {
            try {
                PerformanceMonitoringSystem.getInstance().getMetricAggregator()
                        .incrementCounter("optimization_injector.level_tick");
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // Metrics helpers
    private static void recordAsyncOptimizationHit(String details) {
        asyncOptimizationHits.incrementAndGet();
        PerformanceMonitoringSystem.getInstance().getMetricAggregator()
                .incrementCounter("optimization_injector.async_hits");
    }

    private static void recordAsyncOptimizationMiss(String details) {
        asyncOptimizationMisses.incrementAndGet();
        PerformanceMonitoringSystem.getInstance().getMetricAggregator()
                .incrementCounter("optimization_injector.async_misses");
    }

    private static void recordAsyncOptimizationError(String details) {
        asyncOptimizationErrors.incrementAndGet();
        PerformanceMonitoringSystem.getInstance().getMetricAggregator()
                .incrementCounter("optimization_injector.async_errors");
        LOGGER.warn("Async optimization error: {}", details);
    }

    public static void logAsyncPerformanceStats() {
        LOGGER.info("Async Metrics - Hits: {}, Misses: {}, Errors: {}, Total: {}",
                asyncOptimizationHits.get(),
                asyncOptimizationMisses.get(),
                asyncOptimizationErrors.get(),
                totalEntitiesProcessedAsync.get());
    }
}