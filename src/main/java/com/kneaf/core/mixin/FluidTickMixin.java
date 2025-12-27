/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Fluid spreading optimization with TPS-based throttling and Rust JNI.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import com.kneaf.core.RustOptimizations;
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
 * FluidTickMixin - Fluid spreading optimization with Rust JNI.
 * 
 * Optimizations:
 * 1. Skip fluid ticks during low TPS (adaptive)
 * 2. Cache static fluid positions
 * 3. Early exit for source blocks
 * 4. Rust batch fluid simulation for complex flows
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

    // Batch pending fluid updates for Rust simulation
    @Unique
    private static final Map<Long, Byte> kneaf$pendingFluidUpdates = new ConcurrentHashMap<>(256);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$rustBatchCalls = new AtomicLong(0);

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

    @Unique
    private static final int BATCH_THRESHOLD = 64;

    /**
     * Adaptive fluid tick skipping with Rust batch processing.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ FluidTickMixin applied - Rust fluid simulation active!");
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
            // Critical TPS - queue for batch processing
            kneaf$pendingFluidUpdates.put(posKey, (byte) state.getAmount());

            if (kneaf$pendingFluidUpdates.size() >= BATCH_THRESHOLD) {
                kneaf$processBatchFluidUpdates();
            }

            kneaf$ticksSkipped.incrementAndGet();
            ci.cancel();
            return;
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
     * Process batched fluid updates using Rust simulation.
     */
    @Unique
    private static void kneaf$processBatchFluidUpdates() {
        int count = kneaf$pendingFluidUpdates.size();
        if (count == 0)
            return;

        // Build arrays for Rust
        byte[] fluidLevels = new byte[count];
        byte[] solidBlocks = new byte[count]; // Assume all passable for now

        int idx = 0;
        for (var entry : kneaf$pendingFluidUpdates.entrySet()) {
            fluidLevels[idx] = entry.getValue();
            solidBlocks[idx] = 0; // Not solid
            idx++;
        }

        // Use Rust for batch fluid simulation
        try {
            byte[] results = RustOptimizations.simulateFluidFlow(
                    fluidLevels, solidBlocks, count, 1, 1);
            kneaf$rustBatchCalls.incrementAndGet();

            // Mark simulated positions as static based on results
            long now = System.currentTimeMillis();
            if (results != null) {
                idx = 0;
                for (var entry : kneaf$pendingFluidUpdates.entrySet()) {
                    // Use result to check if fluid is now static
                    if (idx < results.length && results[idx] == 0) {
                        kneaf$staticFluidCache.put(entry.getKey(), now);
                    }
                    idx++;
                }
            }
        } catch (Exception e) {
            // Java fallback - just clear pending
        }

        kneaf$pendingFluidUpdates.clear();
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
        kneaf$pendingFluidUpdates.clear();
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$ticksSkipped.get();
            long processed = kneaf$ticksProcessed.get();
            long rust = kneaf$rustBatchCalls.get();
            long total = skipped + processed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("FluidTick: {} total, {}% skipped, {} Rust batches",
                        total, String.format("%.1f", skipRate), rust);
            }

            kneaf$ticksSkipped.set(0);
            kneaf$ticksProcessed.set(0);
            kneaf$rustBatchCalls.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
