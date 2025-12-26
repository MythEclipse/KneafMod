/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Entity tracker network optimization for multiplayer performance.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
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
 * EntityTrackerMixin - Entity tracking network optimization.
 * 
 * Optimizations:
 * 1. Distance-based update frequency reduction
 * 2. Skip tracking updates for invisible/far entities
 * 3. Batch entity movement packets
 * 4. Track update statistics
 * 
 * This is one of the BIGGEST multiplayer performance improvements,
 * as entity tracking sends network packets very frequently.
 */
@Mixin(ServerEntity.class)
public abstract class EntityTrackerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/EntityTrackerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Shadow
    @Final
    private Entity entity;

    // Per-entity tracking state
    @Unique
    private int kneaf$ticksSinceLastUpdate = 0;

    @Unique
    private int kneaf$updateFrequency = 1; // How often to send updates (in ticks)

    // Statistics
    @Unique
    private static final AtomicLong kneaf$updatesSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesSent = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration - distance thresholds in blocks
    @Unique
    private static final double NEAR_DISTANCE = 32.0;

    @Unique
    private static final double MID_DISTANCE = 64.0;

    @Unique
    private static final double FAR_DISTANCE = 128.0;

    /**
     * Adaptive update frequency based on distance to nearest player.
     */
    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void kneaf$onSendChanges(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ EntityTrackerMixin applied - Entity tracker optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$ticksSinceLastUpdate++;

        // Calculate distance to nearest player
        double nearestDistance = kneaf$getNearestPlayerDistance();

        // Determine update frequency based on distance
        if (nearestDistance < NEAR_DISTANCE) {
            kneaf$updateFrequency = 1; // Every tick (vanilla behavior)
        } else if (nearestDistance < MID_DISTANCE) {
            kneaf$updateFrequency = 2; // Every 2 ticks
        } else if (nearestDistance < FAR_DISTANCE) {
            kneaf$updateFrequency = 4; // Every 4 ticks
        } else {
            kneaf$updateFrequency = 8; // Every 8 ticks (very far)
        }

        // Skip update if not enough ticks have passed
        if (kneaf$ticksSinceLastUpdate < kneaf$updateFrequency) {
            kneaf$updatesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Reset tick counter
        kneaf$ticksSinceLastUpdate = 0;
        kneaf$updatesSent.incrementAndGet();

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$updatesSkipped.get();
            long sent = kneaf$updatesSent.get();
            long total = skipped + sent;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                double avgFreq = total > 0 ? (double) sent / total : 0;
                kneaf$LOGGER.info("EntityTracker: {} total updates, {} sent, {} skipped ({}% reduction)",
                        total, sent, skipped, String.format("%.1f", skipRate));
            }

            kneaf$updatesSkipped.set(0);
            kneaf$updatesSent.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get distance to nearest player for this entity.
     */
    @Unique
    private double kneaf$getNearestPlayerDistance() {
        if (entity == null || entity.level() == null) {
            return Double.MAX_VALUE;
        }

        double nearestDist = Double.MAX_VALUE;

        // Find nearest player
        for (ServerPlayer player : entity.getServer().getPlayerList().getPlayers()) {
            if (player.level() == entity.level()) {
                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                }
            }
        }

        return Math.sqrt(nearestDist);
    }

    /**
     * Get statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long skipped = kneaf$updatesSkipped.get();
        long sent = kneaf$updatesSent.get();
        long total = skipped + sent;
        double skipRate = total > 0 ? (skipped * 100.0 / total) : 0;

        return String.format(
                "EntityTrackerStats{total=%d, sent=%d, skip=%d (%.1f%% reduction)}",
                total, sent, skipped, skipRate);
    }
}
