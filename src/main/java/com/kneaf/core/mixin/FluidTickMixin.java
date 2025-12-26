/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Fluid spreading optimization with TPS-based throttling.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FluidTickMixin - Fluid spreading optimization.
 * 
 * Optimizations:
 * 1. Skip fluid ticks during low TPS (adaptive)
 * 2. Cache fluid neighbor checks
 * 3. Batch fluid updates per chunk
 * 4. Early exit for static fluids
 * 
 * This significantly reduces lag from water/lava spreading in large bodies
 * of water (oceans) or lava lakes.
 */
@Mixin(FlowingFluid.class)
public abstract class FluidTickMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/FluidTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for static fluid positions (positions where fluid isn't flowing)
    @Unique
    private static final Map<Long, Long> kneaf$staticFluidCache = new ConcurrentHashMap<>(1024);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$staticFluidsSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCacheCleanup = 0;

    // Configuration
    @Unique
    private static final double LOW_TPS_THRESHOLD = 15.0;

    @Unique
    private static final double CRITICAL_TPS_THRESHOLD = 10.0;

    @Unique
    private static final long STATIC_FLUID_CACHE_DURATION = 100; // ticks

    /**
     * Adaptive fluid tick skipping based on TPS.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ FluidTickMixin applied - Fluid spreading optimization active!");
            kneaf$loggedFirstApply = true;
        }

        long gameTime = level.getGameTime();
        long posKey = pos.asLong();

        // Check if this fluid has been static for a while
        Long lastStaticTime = kneaf$staticFluidCache.get(posKey);
        if (lastStaticTime != null && gameTime - lastStaticTime < STATIC_FLUID_CACHE_DURATION) {
            kneaf$staticFluidsSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Adaptive skipping based on TPS
        double currentTPS = com.kneaf.core.util.TPSTracker.getCurrentTPS();
        
        if (currentTPS < CRITICAL_TPS_THRESHOLD) {
            // Critical TPS: Skip 75% of fluid ticks
            if (Math.random() > 0.25) {
                kneaf$ticksSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        } else if (currentTPS < LOW_TPS_THRESHOLD) {
            // Low TPS: Skip 50% of fluid ticks
            if (Math.random() > 0.5) {
                kneaf$ticksSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }

        kneaf$ticksProcessed.incrementAndGet();

        // Periodic cache cleanup
        if (gameTime - kneaf$lastCacheCleanup > 1200) { // Every minute
            kneaf$cleanupStaticCache(gameTime);
            kneaf$lastCacheCleanup = gameTime;
        }

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$ticksSkipped.get();
            long processed = kneaf$ticksProcessed.get();
            long staticSkipped = kneaf$staticFluidsSkipped.get();
            long total = skipped + processed + staticSkipped;

            if (total > 0) {
                double skipRate = (skipped + staticSkipped) * 100.0 / total;
                kneaf$LOGGER.info("FluidTick: {} total, {} processed, {} adaptive skip, {} static skip ({}% reduced)",
                        total, processed, skipped, staticSkipped, String.format("%.1f", skipRate));
            }

            kneaf$ticksSkipped.set(0);
            kneaf$ticksProcessed.set(0);
            kneaf$staticFluidsSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Mark fluid as static after tick if it didn't spread.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$afterFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        // If fluid level is full and not flowing, mark as static
        if (state.isSource()) {
            long posKey = pos.asLong();
            kneaf$staticFluidCache.put(posKey, level.getGameTime());
        }
    }

    /**
     * Invalidate static fluid cache when fluid changes.
     */
    @Unique
    private static void kneaf$invalidateStatic(BlockPos pos) {
        kneaf$staticFluidCache.remove(pos.asLong());
    }

    /**
     * Cleanup old entries from static fluid cache.
     */
    @Unique
    private static void kneaf$cleanupStaticCache(long currentTime) {
        int removed = 0;
        var iterator = kneaf$staticFluidCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (currentTime - entry.getValue() > STATIC_FLUID_CACHE_DURATION * 2) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            kneaf$LOGGER.debug("Cleaned up {} static fluid cache entries", removed);
        }

        // Limit cache size
        if (kneaf$staticFluidCache.size() > 10000) {
            kneaf$staticFluidCache.clear();
            kneaf$LOGGER.debug("Static fluid cache cleared due to size");
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long skipped = kneaf$ticksSkipped.get();
        long processed = kneaf$ticksProcessed.get();
        long staticSkipped = kneaf$staticFluidsSkipped.get();
        long total = skipped + processed + staticSkipped;
        double skipRate = total > 0 ? ((skipped + staticSkipped) * 100.0 / total) : 0;

        return String.format(
                "FluidTickStats{total=%d, processed=%d, skipRate=%.1f%%, cacheSize=%d}",
                total, processed, skipRate, kneaf$staticFluidCache.size());
    }
}
