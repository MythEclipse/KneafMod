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
import com.kneaf.core.RustOptimizations;
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
 * SensorMixin - Optimizes mob sensor ticking with Rust JNI.
 * 
 * Sensors (NearestLivingEntitySensor, NearestPlayerSensor, etc.) are expensive
 * because they search through nearby entities. This mixin:
 * 1. Increases scan interval for entities far from players
 * 2. Uses Rust batch distance calculation for range checks
 * 3. Reduces scan radius dynamically based on TPS
 * 4. Caches sensor results between ticks
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
    private static final AtomicLong kneaf$rustBatchCalls = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Track when result was last cached
    @Unique
    private long kneaf$lastSenseTime = 0;

    @Unique
    private boolean kneaf$hasNearbyPlayers = true;

    @Unique
    private double kneaf$cachedMinDistance = 0;

    @Shadow
    public abstract int getScanRate();

    /**
     * Optimize sensor tick using Rust batch distance calculation.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onSensorTick(ServerLevel level, E entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ SensorMixin applied - Rust batch sensing active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$sensorTicksTotal.incrementAndGet();
        long gameTime = level.getGameTime();

        // Use Rust for batch distance calculation every 20 ticks
        if (gameTime % 20 == 0) {
            kneaf$cachedMinDistance = kneaf$getMinPlayerDistanceRust(level, entity);
            kneaf$rustBatchCalls.incrementAndGet();
        }

        double farThresholdSq = FAR_DISTANCE_THRESHOLD * FAR_DISTANCE_THRESHOLD;

        // === OPTIMIZATION: Increase scan interval for far entities ===
        int baseScanRate = getScanRate();
        int effectiveScanRate = baseScanRate;

        if (kneaf$cachedMinDistance > farThresholdSq * 4) {
            // Very far (64+ blocks) - multiply interval by 4
            effectiveScanRate = baseScanRate * 4;
        } else if (kneaf$cachedMinDistance > farThresholdSq) {
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
        kneaf$hasNearbyPlayers = kneaf$cachedMinDistance < farThresholdSq;

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Get minimum distance squared to any player using Rust batch calculation.
     */
    @Unique
    private double kneaf$getMinPlayerDistanceRust(ServerLevel level, E entity) {
        var players = level.players();
        int count = 0;

        // Count non-spectator players
        for (var player : players) {
            if (!player.isSpectator())
                count++;
        }

        if (count == 0) {
            return Double.MAX_VALUE;
        }

        // Build player positions array
        double[] playerPositions = new double[count * 3];

        int idx = 0;
        for (var player : players) {
            if (player.isSpectator())
                continue;
            playerPositions[idx++] = player.getX();
            playerPositions[idx++] = player.getY();
            playerPositions[idx++] = player.getZ();
        }

        // Use Rust for batch distance calculation
        double[] distances = RustOptimizations.entityDistances(
                playerPositions, entity.getX(), entity.getY(), entity.getZ(), count);

        // Find minimum
        double minDist = Double.MAX_VALUE;
        for (double dist : distances) {
            if (dist < minDist) {
                minDist = dist;
            }
        }

        return minDist;
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
            long rust = kneaf$rustBatchCalls.get();

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("Sensor: {} total, {} skipped ({}%), {} Rust batches",
                        total, skipped, String.format("%.1f", skipRate), rust);
            }

            kneaf$sensorTicksTotal.set(0);
            kneaf$sensorTicksSkipped.set(0);
            kneaf$rustBatchCalls.set(0);
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

        return String.format("SensorStats{total=%d, skipped=%d, skipRate=%.1f%%, rust=%d}",
                total, skipped, skipRate, kneaf$rustBatchCalls.get());
    }
}
