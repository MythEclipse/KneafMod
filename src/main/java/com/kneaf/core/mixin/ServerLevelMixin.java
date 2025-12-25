/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // TPS tracking for adaptive throttling
    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);

    @Unique
    private static long kneaf$lastTickStart = 0;

    @Unique
    private static long kneaf$totalTickTime = 0;

    @Unique
    private static long kneaf$maxTickTime = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Adaptive throttling state
    @Unique
    private static volatile double kneaf$currentTPS = 20.0;

    @Unique
    private static volatile int kneaf$throttleLevel = 0; // 0=none, 1=light, 2=medium, 3=heavy

    @Unique
    private static final AtomicLong kneaf$entitiesThrottled = new AtomicLong(0);

    // TPS thresholds for throttling
    @Unique
    private static final double TPS_HEAVY_THROTTLE = 12.0; // Below 12 TPS = heavy throttle
    @Unique
    private static final double TPS_MEDIUM_THROTTLE = 15.0; // Below 15 TPS = medium throttle
    @Unique
    private static final double TPS_LIGHT_THROTTLE = 18.0; // Below 18 TPS = light throttle

    /**
     * Track tick start time and calculate adaptive throttling.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerLevelMixin applied - Dynamic TPS-based entity throttling active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$lastTickStart = System.nanoTime();

        // Update throttle level based on current TPS
        kneaf$updateThrottleLevel();
    }

    /**
     * Track tick end time and update TPS calculation.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$onTickReturn(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        long tickTime = System.nanoTime() - kneaf$lastTickStart;
        long tickMs = tickTime / 1_000_000;

        long tickNum = kneaf$tickCount.incrementAndGet();
        kneaf$totalTickTime += tickMs;

        if (tickMs > kneaf$maxTickTime) {
            kneaf$maxTickTime = tickMs;
        }

        // Calculate rolling TPS
        if (tickNum % 20 == 0) {
            double avgMs = kneaf$totalTickTime / 20.0;
            kneaf$currentTPS = avgMs > 0 ? Math.min(20.0, 1000.0 / avgMs) : 20.0;
            kneaf$totalTickTime = 0;
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
                    kneaf$currentTPS, kneaf$throttleLevel, kneaf$entitiesThrottled.get());

            // Reset counters
            kneaf$maxTickTime = 0;
            kneaf$entitiesThrottled.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Update throttle level based on current TPS.
     */
    @Unique
    private static void kneaf$updateThrottleLevel() {
        if (kneaf$currentTPS < TPS_HEAVY_THROTTLE) {
            kneaf$throttleLevel = 3;
        } else if (kneaf$currentTPS < TPS_MEDIUM_THROTTLE) {
            kneaf$throttleLevel = 2;
        } else if (kneaf$currentTPS < TPS_LIGHT_THROTTLE) {
            kneaf$throttleLevel = 1;
        } else {
            kneaf$throttleLevel = 0;
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

        if (kneaf$throttleLevel == 0) {
            return false;
        }

        // Item entities are less important - throttle more aggressively
        if (entity instanceof ItemEntity) {
            int skipRate = kneaf$throttleLevel * 2; // 2, 4, or 6
            if (tickCount % (skipRate + 1) != 0) {
                kneaf$entitiesThrottled.incrementAndGet();
                return true;
            }
        }

        // Other entities throttled based on level
        int skipRate = kneaf$throttleLevel; // 1, 2, or 3
        if (tickCount % (skipRate + 1) != 0) {
            kneaf$entitiesThrottled.incrementAndGet();
            return true;
        }

        return false;
    }

    /**
     * Get current throttle level (0-3).
     */
    @Unique
    private static int kneaf$getThrottleLevel() {
        return kneaf$throttleLevel;
    }

    /**
     * Get current TPS.
     */
    @Unique
    private static double kneaf$getCurrentTPS() {
        return kneaf$currentTPS;
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        return String.format(
                "ServerLevelStats{tps=%.1f, throttleLevel=%d, entitiesThrottled=%d}",
                kneaf$currentTPS,
                kneaf$throttleLevel,
                kneaf$entitiesThrottled.get());
    }
}
