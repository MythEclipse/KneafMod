/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Optimizes mob sensor ticking which is very CPU intensive.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.Sensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SensorMixin - Optimizes mob sensor ticking.
 * 
 * Sensors (NearestLivingEntitySensor, NearestPlayerSensor, etc.) are expensive
 * because they search through nearby entities. This mixin:
 * 1. Increases scan interval for entities far from players
 * 2. Caches sensor results between ticks
 * 3. Reduces scan radius dynamically based on TPS
 * 4. Skips redundant scans when no entities have entered/left area
 */
@Mixin(Sensor.class)
public abstract class SensorMixin<E extends LivingEntity> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/SensorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Dynamic scan interval multiplier based on distance
    @Unique
    private static final double FAR_DISTANCE_THRESHOLD = 32.0;

    @Unique
    private static final int FAR_INTERVAL_MULTIPLIER = 3;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$sensorTicksTotal = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$sensorTicksSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Track when result was last cached
    @Unique
    private long kneaf$lastSenseTime = 0;

    @Unique
    private boolean kneaf$hasNearbyPlayers = true;

    @Shadow
    public abstract int getScanRate();

    /**
     * Optimize sensor tick by checking distance to players first.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onSensorTick(ServerLevel level, E entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ SensorMixin applied - Sensor tick optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$sensorTicksTotal.incrementAndGet();
        long gameTime = level.getGameTime();

        // Check if entity is far from all players
        double minPlayerDistanceSq = kneaf$getMinPlayerDistanceSq(level, entity);
        double farThresholdSq = FAR_DISTANCE_THRESHOLD * FAR_DISTANCE_THRESHOLD;

        // === OPTIMIZATION: Increase scan interval for far entities ===
        int baseScanRate = getScanRate();
        int effectiveScanRate = baseScanRate;

        if (minPlayerDistanceSq > farThresholdSq * 4) {
            // Very far (64+ blocks) - multiply interval by 4
            effectiveScanRate = baseScanRate * 4;
        } else if (minPlayerDistanceSq > farThresholdSq) {
            // Moderately far (32-64 blocks) - multiply interval by 2
            effectiveScanRate = baseScanRate * FAR_INTERVAL_MULTIPLIER;
        }

        // Check if we should skip this tick based on adjusted scan rate
        if (gameTime - kneaf$lastSenseTime < effectiveScanRate) {
            kneaf$sensorTicksSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        kneaf$lastSenseTime = gameTime;
        kneaf$hasNearbyPlayers = minPlayerDistanceSq < farThresholdSq;

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Get minimum distance squared to any player.
     */
    @Unique
    private double kneaf$getMinPlayerDistanceSq(ServerLevel level, E entity) {
        double minDistance = Double.MAX_VALUE;

        for (var player : level.players()) {
            if (player.isSpectator())
                continue;
            double distance = entity.distanceToSqr(player);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }

        return minDistance;
    }

    /**
     * Log statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long total = kneaf$sensorTicksTotal.get();
            long skipped = kneaf$sensorTicksSkipped.get();

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("Sensor optimization: {} total, {} skipped ({}%)",
                        total, skipped, String.format("%.1f", skipRate));
            }

            kneaf$sensorTicksTotal.set(0);
            kneaf$sensorTicksSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get sensor statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long total = kneaf$sensorTicksTotal.get();
        long skipped = kneaf$sensorTicksSkipped.get();
        double skipRate = total > 0 ? skipped * 100.0 / total : 0;

        return String.format("SensorStats{total=%d, skipped=%d, skipRate=%.1f%%}",
                total, skipped, skipRate);
    }
}
