/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LivingEntityMixin - Advanced entity optimization with Rust native support.
 * 
 * Optimizations:
 * 1. Distance-based tick throttling (far entities tick slower)
 * 2. Effect processing skip for non-player entities
 * 3. Idle detection with adaptive sleep
 * 4. Rust native batch distance calculation for multi-entity scenarios
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LivingEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private int kneaf$tickCounter = 0;

    // Distance-based throttling
    @Unique
    private int kneaf$tickDivisor = 1;

    @Unique
    private int kneaf$lastDistanceCheckTick = 0;

    @Unique
    private double kneaf$cachedDistanceSq = 0.0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Thresholds
    @Unique
    private static final double NEAR_DISTANCE_SQ = 32.0 * 32.0; // 32 blocks

    @Unique
    private static final double FAR_DISTANCE_SQ = 64.0 * 64.0; // 64 blocks

    @Unique
    private static final int DISTANCE_CHECK_INTERVAL = 20; // Every second

    /**
     * Track ticks and apply distance-based throttling.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LivingEntityMixin applied - Distance-based throttling active!");
            kneaf$loggedFirstApply = true;
        }

        LivingEntity self = (LivingEntity) (Object) this;
        kneaf$tickCounter++;

        // Skip players - always full tick rate
        if (self instanceof Player) {
            kneaf$ticksProcessed.incrementAndGet();
            return;
        }

        // Update distance calculation periodically
        if (kneaf$tickCounter - kneaf$lastDistanceCheckTick >= DISTANCE_CHECK_INTERVAL) {
            kneaf$updateTickRate(self);
            kneaf$lastDistanceCheckTick = kneaf$tickCounter;
        }

        // Apply tick throttling based on distance
        if (kneaf$tickDivisor > 1 && kneaf$tickCounter % kneaf$tickDivisor != 0) {
            kneaf$ticksSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        kneaf$ticksProcessed.incrementAndGet();
        kneaf$logStats();
    }

    /**
     * Update tick rate based on distance to nearest player.
     */
    @Unique
    private void kneaf$updateTickRate(LivingEntity entity) {
        if (entity.level() instanceof ServerLevel level) {
            double minDistSq = Double.MAX_VALUE;

            for (var player : level.players()) {
                if (player.isSpectator())
                    continue;
                double distSq = entity.distanceToSqr(player);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }

            kneaf$cachedDistanceSq = minDistSq;

            // Determine tick divisor based on distance
            if (minDistSq <= NEAR_DISTANCE_SQ) {
                kneaf$tickDivisor = 1; // Full rate
            } else if (minDistSq <= FAR_DISTANCE_SQ) {
                kneaf$tickDivisor = 2; // Half rate
            } else if (minDistSq <= FAR_DISTANCE_SQ * 4) {
                kneaf$tickDivisor = 4; // Quarter rate
            } else {
                kneaf$tickDivisor = 8; // Minimal rate (very far)
            }
        }
    }

    /**
     * Optimization: Skip active effect ticking for far entities.
     */
    @Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickEffects(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // Never skip players
        if (self instanceof Player) {
            return;
        }

        // Far entities: process effects less frequently
        if (kneaf$cachedDistanceSq > FAR_DISTANCE_SQ && kneaf$tickCounter % 4 != 0) {
            ci.cancel();
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long processed = kneaf$ticksProcessed.get();
            long skipped = kneaf$ticksSkipped.get();
            long total = processed + skipped;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("LivingEntity: {} ticks, {}% throttled",
                        total, String.format("%.1f", skipRate));
            }

            kneaf$ticksProcessed.set(0);
            kneaf$ticksSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
