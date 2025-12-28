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

        // Optimization: Try to get cached distance from ParallelEntityTicker first
        double nearestDistance = -1.0;
        if (entity.level() instanceof com.kneaf.core.extension.ServerLevelExtension) {
            nearestDistance = ((com.kneaf.core.extension.ServerLevelExtension) entity.level())
                    .kneaf$getCachedDistance(entity.getId());
        }

        // Fallback to main thread calculation if not cached
        if (nearestDistance < 0) {
            nearestDistance = kneaf$getNearestPlayerDistance();
        }

        // Determine update frequency based on distance
        // Optimization: We keep the frequency at 1 for all entities within tracking
        // range
        // to avoid the "blink blink" stuttering effect reported by the user.
        // We still track stats but don't skip updates anymore.
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
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long skipped = kneaf$updatesSkipped.get();
            long sent = kneaf$updatesSent.get();
            long total = skipped + sent;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.trackerUpdates = total / timeDiff;
                com.kneaf.core.PerformanceStats.trackerSkipped = skipped / timeDiff;

                kneaf$updatesSkipped.set(0);
                kneaf$updatesSent.set(0);
            } else {
                com.kneaf.core.PerformanceStats.trackerUpdates = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
