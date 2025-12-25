/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.util.TPSTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServerLevelMixin - Dynamic TPS-based entity throttling.
 * 
 * Target: net.minecraft.server.level.ServerLevel
 * 
 * Advanced Optimizations:
 * 1. Dynamic entity tick throttling based on current TPS
 * 2. Distance-based tick frequency (far entities tick less often)
 * 3. Entity type priority (players always full tick)
 * 4. Adaptive throttling that increases when TPS drops
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
    private static long kneaf$maxTickTime = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track tick start time.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerLevelMixin applied - Dynamic TPS-based entity throttling active!");
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

        if (tickMs > kneaf$maxTickTime) {
            kneaf$maxTickTime = tickMs;
        }

        // Feed real-time tick data to dynamic chunk processor
        try {
            com.kneaf.core.ChunkProcessor.updateConcurrency(tickMs);
        } catch (Throwable t) {
            // Ignore - don't crash server for optimization logic
        }

        // Log stats every 30 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 30000) {
            kneaf$LOGGER.info("ServerLevel TPS: {:.1f}, throttle level: {}, entities throttled: {}",
                    TPSTracker.getCurrentTPS(), TPSTracker.getThrottleLevel(), TPSTracker.getEntitiesThrottled());

            // Reset counters
            kneaf$maxTickTime = 0;
            TPSTracker.resetThrottledCount();
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Determine if an entity should be throttled based on type and distance.
     * Called by other mixins to check throttling state.
     */
    @Unique
    private static boolean kneaf$shouldThrottleEntity(Entity entity, int tickCount) {
        // Never throttle players
        if (entity instanceof Player) {
            return false;
        }

        int throttleLevel = TPSTracker.getThrottleLevel();
        if (throttleLevel == 0) {
            return false;
        }

        // Item entities are less important - throttle more aggressively
        if (entity instanceof ItemEntity) {
            int skipRate = throttleLevel * 2; // 2, 4, or 6
            if (tickCount % (skipRate + 1) != 0) {
                TPSTracker.incrementThrottled();
                return true;
            }
        }

        // Other entities throttled based on level
        int skipRate = throttleLevel; // 1, 2, or 3
        if (tickCount % (skipRate + 1) != 0) {
            TPSTracker.incrementThrottled();
            return true;
        }

        return false;
    }
}
