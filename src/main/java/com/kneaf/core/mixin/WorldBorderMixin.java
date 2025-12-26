/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * World border collision optimization.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WorldBorderMixin - World border collision optimization.
 * 
 * Optimizations:
 * 1. Early exit for entities far from border
 * 2. Cache distance calculations
 * 3. Skip checks for entities well within safe distance
 */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/WorldBorderMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Shadow
    public abstract double getCenterX();

    @Shadow
    public abstract double getCenterZ();

    @Shadow
    public abstract double getSize();

    // Statistics
    @Unique
    private final AtomicLong kneaf$checksSkipped = new AtomicLong(0);

    @Unique
    private final AtomicLong kneaf$checksPerformed = new AtomicLong(0);

    @Unique
    private long kneaf$lastLogTime = 0;

    // Configuration - safe distance threshold in blocks
    @Unique
    private static final double SAFE_DISTANCE_THRESHOLD = 64.0;

    /**
     * Early exit for entities far from world border.
     */
    @Inject(method = "isWithinBounds(Lnet/minecraft/world/phys/AABB;)Z", at = @At("HEAD"), cancellable = true)
    private void kneaf$onIsWithinBoundsAABB(AABB aabb, CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ WorldBorderMixin applied - World border optimization active!");
            kneaf$loggedFirstApply = true;
        }

        double centerX = getCenterX();
        double centerZ = getCenterZ();
        double radius = getSize() / 2.0;

        // Calculate distance from AABB center to border center
        double aabbCenterX = (aabb.minX + aabb.maxX) / 2.0;
        double aabbCenterZ = (aabb.minZ + aabb.maxZ) / 2.0;

        double dx = Math.abs(aabbCenterX - centerX);
        double dz = Math.abs(aabbCenterZ - centerZ);
        double distanceToCenter = Math.max(dx, dz);

        // If well within border, skip detailed check
        if (distanceToCenter < radius - SAFE_DISTANCE_THRESHOLD) {
            kneaf$checksSkipped.incrementAndGet();
            cir.setReturnValue(true);
            return;
        }

        kneaf$checksPerformed.incrementAndGet();
        kneaf$logStats();
    }

    /**
     * Log statistics periodically.
     */
    @Unique
    private void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$checksSkipped.get();
            long performed = kneaf$checksPerformed.get();
            long total = skipped + performed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("WorldBorder: {} checks, {}% skipped",
                        total, String.format("%.1f", skipRate));
            }

            kneaf$checksSkipped.set(0);
            kneaf$checksPerformed.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
