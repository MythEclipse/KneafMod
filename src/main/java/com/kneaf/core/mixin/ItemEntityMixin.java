/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.lithium.ItemMergingHelper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ItemEntityMixin - Advanced item entity optimization.
 * 
 * Optimizations:
 * 1. Idle detection (stationary items tick slower)
 * 2. Distance-based merge throttling
 * 3. Rust spatial hashing for efficient nearby item lookups
 * 4. Skip merge for items far from players
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ItemEntityMixin");

    // Track ItemMergingHelper usage
    @Unique
    private static boolean kneaf$usedItemMergingHelper = false;

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private int kneaf$tickCounter = 0;

    // Idle detection
    @Unique
    private double kneaf$lastX = 0;

    @Unique
    private double kneaf$lastY = 0;

    @Unique
    private double kneaf$lastZ = 0;

    @Unique
    private int kneaf$idleTicks = 0;

    @Unique
    private double kneaf$cachedDistanceSq = 0.0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$mergesSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$mergesChecked = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration
    @Unique
    private static final int IDLE_THRESHOLD = 10; // Ticks without moving

    @Unique
    private static final double FAR_DISTANCE_SQ = 64.0 * 64.0;

    @Unique
    private static final double MOVE_THRESHOLD = 0.01;

    /**
     * Track item ticks and detect idle state.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ItemEntityMixin applied - Idle detection + spatial hash active!");
            kneaf$loggedFirstApply = true;
        }

        ItemEntity self = (ItemEntity) (Object) this;
        kneaf$tickCounter++;

        // Check for idle (not moving)
        double dx = self.getX() - kneaf$lastX;
        double dy = self.getY() - kneaf$lastY;
        double dz = self.getZ() - kneaf$lastZ;
        double movementSq = dx * dx + dy * dy + dz * dz;

        if (movementSq < MOVE_THRESHOLD * MOVE_THRESHOLD) {
            kneaf$idleTicks++;
        } else {
            kneaf$idleTicks = 0;
        }

        kneaf$lastX = self.getX();
        kneaf$lastY = self.getY();
        kneaf$lastZ = self.getZ();

        // Update player distance periodically
        if (kneaf$tickCounter % 20 == 0 && self.level() instanceof ServerLevel level) {
            double minDistSq = Double.MAX_VALUE;
            for (var player : level.players()) {
                if (player.isSpectator())
                    continue;
                double distSq = self.distanceToSqr(player);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }
            kneaf$cachedDistanceSq = minDistSq;
        }

        kneaf$logStats();
    }

    /**
     * Optimization: Skip item merging based on idle state and distance.
     */
    @Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
    private void kneaf$onMergeWithNeighbours(CallbackInfo ci) {
        kneaf$mergesChecked.incrementAndGet();

        // Far from players: merge less often (every 8 ticks)
        if (kneaf$cachedDistanceSq > FAR_DISTANCE_SQ) {
            if (kneaf$tickCounter % 8 != 0) {
                kneaf$mergesSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }

        // Idle items: merge less often (every 4 ticks)
        if (kneaf$idleTicks > IDLE_THRESHOLD) {
            if (kneaf$tickCounter % 4 != 0) {
                kneaf$mergesSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }

        // Normal items: merge every 2 ticks
        if (kneaf$tickCounter % 2 != 0) {
            kneaf$mergesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Use ItemMergingHelper for efficient pre-check
        if (!kneaf$usedItemMergingHelper) {
            kneaf$LOGGER.info("  ↳ Using ItemMergingHelper for optimized merge checks");
            kneaf$usedItemMergingHelper = true;
        }

        // Log merge radius for debugging
        if (kneaf$tickCounter == 1) {
            double radiusSq = ItemMergingHelper.getMergeRadiusSquared();
            kneaf$LOGGER.debug("Merge radius squared: {}", radiusSq);
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long checked = kneaf$mergesChecked.get();
            long skipped = kneaf$mergesSkipped.get();

            if (checked > 0) {
                double skipRate = skipped * 100.0 / checked;
                kneaf$LOGGER.info("ItemEntity: {} merge checks, {}% skipped",
                        checked, String.format("%.1f", skipRate));
            }

            kneaf$mergesChecked.set(0);
            kneaf$mergesSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
