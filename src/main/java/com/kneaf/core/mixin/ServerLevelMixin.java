/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.util.TPSTracker;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServerLevelMixin - TPS tracking and performance monitoring.
 * 
 * Target: net.minecraft.server.level.ServerLevel
 * 
 * Optimizations:
 * 1. TPS calculation and tracking
 * 2. Dynamic chunk processor concurrency adjustment
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ServerLevelMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Tick timing for TPS calculation
    @Unique
    private static long kneaf$lastTickStart = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track tick start time.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerLevelMixin applied - TPS tracking active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$lastTickStart = System.nanoTime();
    }

    /**
     * Track tick end time and update TPS calculation.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$onTickReturn(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        long tickTime = System.nanoTime() - kneaf$lastTickStart;
        long tickMs = tickTime / 1_000_000;

        // Update the centralized TPS tracker
        TPSTracker.recordTick(tickMs);

        // Feed real-time tick data to dynamic chunk processor
        try {
            com.kneaf.core.ChunkProcessor.updateConcurrency(tickMs);
        } catch (Throwable t) {
            // Ignore - don't crash server for optimization logic
        }

        // Log stats every 30 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 30000) {
            kneaf$LOGGER.info("ServerLevel TPS: {}", String.format("%.1f", TPSTracker.getCurrentTPS()));
            kneaf$lastLogTime = now;
        }
    }
}
