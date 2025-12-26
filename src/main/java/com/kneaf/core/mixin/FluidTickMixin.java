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
 * 2. Cache static fluid positions
 * 3. Early exit for source blocks
 */
@Mixin(FlowingFluid.class)
public abstract class FluidTickMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/FluidTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for static fluid positions
    @Unique
    private static final Map<Long, Long> kneaf$staticFluidCache = new ConcurrentHashMap<>(1024);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

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
    private static final long STATIC_CACHE_DURATION = 100;

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

        // Check static fluid cache
        Long lastStaticTime = kneaf$staticFluidCache.get(posKey);
        if (lastStaticTime != null && gameTime - lastStaticTime < STATIC_CACHE_DURATION) {
            kneaf$ticksSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // TPS-based throttling
        double currentTPS = com.kneaf.core.util.TPSTracker.getCurrentTPS();

        if (currentTPS < CRITICAL_TPS_THRESHOLD) {
            if (Math.random() > 0.25) {
                kneaf$ticksSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        } else if (currentTPS < LOW_TPS_THRESHOLD) {
            if (Math.random() > 0.5) {
                kneaf$ticksSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }

        kneaf$ticksProcessed.incrementAndGet();

        // Periodic cleanup
        if (gameTime - kneaf$lastCacheCleanup > 1200) {
            kneaf$cleanupCache(gameTime);
            kneaf$lastCacheCleanup = gameTime;
        }

        kneaf$logStats();
    }

    /**
     * Mark source blocks as static.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$afterFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (state.isSource()) {
            kneaf$staticFluidCache.put(pos.asLong(), level.getGameTime());
        }
    }

    @Unique
    private static void kneaf$cleanupCache(long currentTime) {
        if (kneaf$staticFluidCache.size() > 10000) {
            kneaf$staticFluidCache.clear();
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$ticksSkipped.get();
            long processed = kneaf$ticksProcessed.get();
            long total = skipped + processed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("FluidTick: {} total, {}% skipped",
                        total, String.format("%.1f", skipRate));
            }

            kneaf$ticksSkipped.set(0);
            kneaf$ticksProcessed.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
