/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Dedicated server optimizations with TPS monitoring and adaptive control.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import com.kneaf.core.util.TPSTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DedicatedServerMixin - Server-wide optimization control.
 * 
 * Features:
 * 1. TPS monitoring and tracking
 * 2. Adaptive optimization levels based on TPS
 * 3. Server startup optimization summary
 * 4. Performance statistics collection
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/DedicatedServerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track server tick times for TPS
    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);

    @Unique
    private static long kneaf$lastTickTime = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Optimization level (0=normal, 1=moderate, 2=aggressive)
    @Unique
    private static int kneaf$optimizationLevel = 0;

    /**
     * Track server tick for TPS calculation.
     */
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void kneaf$onTickServer(CallbackInfo ci) {
        long now = System.nanoTime();

        if (kneaf$lastTickTime > 0) {
            // Calculate tick time and update TPS tracker
            long tickNanos = now - kneaf$lastTickTime;
            double tickMs = tickNanos / 1_000_000.0;

            // Update TPSTracker with current tick time
            TPSTracker.recordTick(tickMs);
        }

        kneaf$lastTickTime = now;
        kneaf$tickCount.incrementAndGet();

        // Update optimization level based on TPS every 100 ticks
        if (kneaf$tickCount.get() % 100 == 0) {
            kneaf$updateOptimizationLevel();
        }

        // Log server stats every 60 seconds
        kneaf$logServerStats();
    }

    /**
     * Update optimization level based on current TPS.
     */
    @Unique
    private static void kneaf$updateOptimizationLevel() {
        double tps = TPSTracker.getCurrentTPS();

        int newLevel;
        if (tps < 12.0) {
            newLevel = 2; // Aggressive
        } else if (tps < 17.0) {
            newLevel = 1; // Moderate
        } else {
            newLevel = 0; // Normal
        }

        if (newLevel != kneaf$optimizationLevel) {
            kneaf$optimizationLevel = newLevel;
            String levelName = switch (newLevel) {
                case 2 -> "AGGRESSIVE (TPS critical)";
                case 1 -> "MODERATE (TPS low)";
                default -> "NORMAL";
            };
            kneaf$LOGGER.info("Optimization level: {}", levelName);
        }
    }

    /**
     * Get current optimization level.
     */
    @Unique
    private static int kneaf$getOptimizationLevel() {
        return kneaf$optimizationLevel;
    }

    @Unique
    private static void kneaf$logServerStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            double tps = TPSTracker.getCurrentTPS();
            long ticks = kneaf$tickCount.get();

            kneaf$LOGGER.info("Server: TPS={}, ticks={}, optLevel={}",
                    String.format("%.1f", tps), ticks, kneaf$optimizationLevel);

            kneaf$tickCount.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Log when server initializes.
     */
    @Inject(method = "initServer", at = @At("HEAD"))
    private void kneaf$onInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ DedicatedServerMixin applied - TPS monitoring active!");
            kneaf$loggedFirstApply = true;
        }
    }

    /**
     * Log optimization summary on startup.
     */
    @Inject(method = "initServer", at = @At("RETURN"))
    private void kneaf$afterInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            kneaf$LOGGER.info("============================================================");
            kneaf$LOGGER.info("KneafMod Server Optimizations Active:");
            kneaf$LOGGER.info("  - Entity: Tick culling, collision optimization, sleep state");
            kneaf$LOGGER.info("  - Chunk: Generation, loading, caching, packet optimization");
            kneaf$LOGGER.info("  - Light: Batch updates, section caching");
            kneaf$LOGGER.info("  - AI: Goal priority caching, TPS-aware throttling");
            kneaf$LOGGER.info("  - Fluid: TPS-based throttling, static caching");
            kneaf$LOGGER.info("  - NBT: Key interning, empty tag fast-path");
            kneaf$LOGGER.info("  - VoxelShape: Collision caching, full block fast-path");
            kneaf$LOGGER.info("  - Rust JNI: Batch entity processing, distance calc");
            kneaf$LOGGER.info("============================================================");
            kneaf$LOGGER.info("Total optimizations: 46 mixins + {} Rust JNI functions", 10);
            kneaf$LOGGER.info("Server ready for optimal performance!");
            kneaf$LOGGER.info("============================================================");
        }
    }
}
