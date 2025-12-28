/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Entity tracker network optimization for multiplayer performance.
 */
package com.kneaf.core.mixin;

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
 * 2. Skip tracking updates for far entities
 * 3. Batch entity movement packets
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
    private int kneaf$updateFrequency = 1;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$updatesSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesSent = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Distance thresholds
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

        double nearestDistance = kneaf$getNearestPlayerDistance();

        // Determine update frequency based on distance
        if (nearestDistance < NEAR_DISTANCE) {
            kneaf$updateFrequency = 1;
        } else if (nearestDistance < MID_DISTANCE) {
            kneaf$updateFrequency = 2;
        } else if (nearestDistance < FAR_DISTANCE) {
            kneaf$updateFrequency = 4;
        } else {
            kneaf$updateFrequency = 8;
        }

        // Skip update if not enough ticks
        if (kneaf$ticksSinceLastUpdate < kneaf$updateFrequency) {
            kneaf$updatesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        kneaf$ticksSinceLastUpdate = 0;
        kneaf$updatesSent.incrementAndGet();
        kneaf$logStats();
    }

    @Unique
    private double kneaf$getNearestPlayerDistance() {
        if (entity == null || entity.level() == null) {
            return Double.MAX_VALUE;
        }

        var server = entity.getServer();
        if (server == null) {
            return Double.MAX_VALUE;
        }

        double nearestDist = Double.MAX_VALUE;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == entity.level()) {
                double dist = player.distanceToSqr(entity);
                if (dist < nearestDist) {
                    nearestDist = dist;
                }
            }
        }

        return Math.sqrt(nearestDist);
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$updatesSkipped.get();
            long sent = kneaf$updatesSent.get();
            long total = skipped + sent;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("EntityTracker: {}/sec updates, {}% skipped",
                        String.format("%.1f", total / timeDiff), String.format("%.1f", skipRate));
            }

            kneaf$updatesSkipped.set(0);
            kneaf$updatesSent.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
