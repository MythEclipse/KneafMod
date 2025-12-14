/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.PerformanceManager;
import com.kneaf.core.RustNativeLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MixinHelper - Central utility class for all mixin operations.
 * Provides distance checking, throttle decisions, and native library
 * management.
 */
public final class MixinHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinHelper.class);

    // Configuration thresholds
    private static final double THROTTLE_DISTANCE_SQUARED = 64 * 64; // 64 blocks
    private static final double AGGRESSIVE_THROTTLE_DISTANCE_SQUARED = 128 * 128; // 128 blocks
    private static final int THROTTLE_TICK_INTERVAL = 4; // Process every 4th tick for distant entities
    private static final int AGGRESSIVE_THROTTLE_TICK_INTERVAL = 8; // Process every 8th tick for very distant entities

    // Metrics tracking
    private static final AtomicLong throttledTicks = new AtomicLong(0);
    private static final AtomicLong processedTicks = new AtomicLong(0);
    private static final AtomicLong rustOptimizedTicks = new AtomicLong(0);
    private static final AtomicLong vanillaFallbackTicks = new AtomicLong(0);

    // Entity tick tracking for throttling
    private static final ConcurrentHashMap<Integer, Integer> entityTickCounters = new ConcurrentHashMap<>();

    private static boolean initialized = false;
    private static boolean nativeAvailable = false;

    private MixinHelper() {
        // No instantiation
    }

    /**
     * Initialize MixinHelper. Called once on first use.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        nativeAvailable = RustNativeLoader.isLoaded();
        initialized = true;

        if (nativeAvailable) {
            LOGGER.info("✅ MixinHelper initialized with Rust native library support");
        } else {
            LOGGER.warn("⚠️ MixinHelper initialized WITHOUT Rust native library - using fallback");
        }
    }

    /**
     * Check if Rust native library is available.
     */
    public static boolean isNativeAvailable() {
        if (!initialized)
            initialize();
        return nativeAvailable;
    }

    /**
     * Check if entity optimizations are enabled in config.
     */
    public static boolean isOptimizationEnabled() {
        return PerformanceManager.getInstance().isEntityThrottlingEnabled();
    }

    /**
     * Check if AI optimization is enabled.
     */
    public static boolean isAIOptimizationEnabled() {
        return PerformanceManager.getInstance().isAiPathfindingOptimized();
    }

    /**
     * Check if physics optimization is enabled.
     */
    public static boolean isPhysicsOptimizationEnabled() {
        return PerformanceManager.getInstance().isAdvancedPhysicsOptimized();
    }

    /**
     * Get squared distance from entity to nearest player.
     * Returns Double.MAX_VALUE if no players are present.
     */
    public static double getDistanceToNearestPlayerSq(Entity entity) {
        Level level = entity.level();
        if (level == null || level.isClientSide()) {
            return 0; // Don't throttle on client
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }

        Vec3 entityPos = entity.position();
        double minDistSq = Double.MAX_VALUE;

        for (ServerPlayer player : serverLevel.players()) {
            double distSq = player.position().distanceToSqr(entityPos);
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }

        return minDistSq;
    }

    /**
     * Determine if this entity should be throttled this tick.
     * Returns true if the entity should SKIP processing this tick.
     */
    public static boolean shouldThrottleTick(Entity entity) {
        if (!isOptimizationEnabled()) {
            return false;
        }

        double distSq = getDistanceToNearestPlayerSq(entity);

        if (distSq < THROTTLE_DISTANCE_SQUARED) {
            // Close to player - never throttle
            return false;
        }

        int entityId = entity.getId();
        int tickCount = entityTickCounters.compute(entityId, (k, v) -> (v == null) ? 0 : v + 1);

        int throttleInterval;
        if (distSq >= AGGRESSIVE_THROTTLE_DISTANCE_SQUARED) {
            throttleInterval = AGGRESSIVE_THROTTLE_TICK_INTERVAL;
        } else {
            throttleInterval = THROTTLE_TICK_INTERVAL;
        }

        boolean shouldThrottle = (tickCount % throttleInterval) != 0;

        if (shouldThrottle) {
            throttledTicks.incrementAndGet();
        } else {
            processedTicks.incrementAndGet();
        }

        return shouldThrottle;
    }

    /**
     * Apply Rust-optimized physics damping to velocity.
     * Falls back to vanilla if native library is unavailable.
     */
    public static Vec3 applyOptimizedDamping(Vec3 velocity, double damping) {
        if (!isNativeAvailable() || !isPhysicsOptimizationEnabled()) {
            vanillaFallbackTicks.incrementAndGet();
            return velocity.scale(damping);
        }

        try {
            double[] result = RustNativeLoader.rustperf_vector_damp(
                    velocity.x, velocity.y, velocity.z, damping);

            if (result != null && result.length == 3) {
                rustOptimizedTicks.incrementAndGet();
                return new Vec3(result[0], result[1], result[2]);
            }
        } catch (Exception e) {
            LOGGER.debug("Rust damping failed, using fallback: {}", e.getMessage());
        }

        vanillaFallbackTicks.incrementAndGet();
        return velocity.scale(damping);
    }

    /**
     * Apply Rust-optimized vector multiplication.
     */
    public static Vec3 applyOptimizedMultiply(Vec3 velocity, double scalar) {
        if (!isNativeAvailable()) {
            return velocity.scale(scalar);
        }

        try {
            double[] result = RustNativeLoader.rustperf_vector_multiply(
                    velocity.x, velocity.y, velocity.z, scalar);

            if (result != null && result.length == 3) {
                return new Vec3(result[0], result[1], result[2]);
            }
        } catch (Exception e) {
            LOGGER.debug("Rust multiply failed, using fallback: {}", e.getMessage());
        }

        return velocity.scale(scalar);
    }

    /**
     * Apply Rust-optimized vector addition.
     */
    public static Vec3 applyOptimizedAdd(Vec3 a, Vec3 b) {
        if (!isNativeAvailable()) {
            return a.add(b);
        }

        try {
            double[] result = RustNativeLoader.rustperf_vector_add(
                    a.x, a.y, a.z, b.x, b.y, b.z);

            if (result != null && result.length == 3) {
                return new Vec3(result[0], result[1], result[2]);
            }
        } catch (Exception e) {
            LOGGER.debug("Rust add failed, using fallback: {}", e.getMessage());
        }

        return a.add(b);
    }

    /**
     * Clean up tick counter for removed entity.
     */
    public static void onEntityRemoved(int entityId) {
        entityTickCounters.remove(entityId);
    }

    /**
     * Get statistics for monitoring.
     */
    public static String getStatistics() {
        return String.format(
                "MixinStats{throttled=%d, processed=%d, rust=%d, fallback=%d, ratio=%.1f%%}",
                throttledTicks.get(),
                processedTicks.get(),
                rustOptimizedTicks.get(),
                vanillaFallbackTicks.get(),
                processedTicks.get() > 0
                        ? (rustOptimizedTicks.get() * 100.0 / (rustOptimizedTicks.get() + vanillaFallbackTicks.get()))
                        : 0);
    }

    /**
     * Reset statistics (for testing).
     */
    public static void resetStatistics() {
        throttledTicks.set(0);
        processedTicks.set(0);
        rustOptimizedTicks.set(0);
        vanillaFallbackTicks.set(0);
        entityTickCounters.clear();
    }
}
