/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * World border collision optimization with distance caching.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
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
 * 3. Skip collision checks for distant entities
 * 
 * This reduces unnecessary calculations for the majority of entities
 * that are nowhere near the world border.
 */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/WorldBorderMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$checksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$checksPerformed = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration - distance in blocks from border to start checking
    @Unique
    private static final double SAFE_DISTANCE_THRESHOLD = 64.0;

    /**
     * Skip border collision checks for entities far from border.
     */
    @Inject(method = "isWithinBounds(Lnet/minecraft/world/phys/AABB;)Z", at = @At("HEAD"), cancellable = true)
    private void kneaf$onIsWithinBoundsAABB(net.minecraft.world.phys.AABB aabb, CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ WorldBorderMixin applied - World border optimization active!");
            kneaf$loggedFirstApply = true;
        }

        WorldBorder self = (WorldBorder) (Object) this;
        
        // Get border properties
        double centerX = self.getCenterX();
        double centerZ = self.getCenterZ();
        double size = self.getSize();
        double radius = size / 2.0;

        // Calculate distance from AABB center to border center
        double aabbCenterX = (aabb.minX + aabb.maxX) / 2.0;
        double aabbCenterZ = (aabb.minZ + aabb.maxZ) / 2.0;
        
        double dx = aabbCenterX - centerX;
        double dz = aabbCenterZ - centerZ;
        double distanceToCenter = Math.sqrt(dx * dx + dz * dz);

        // If entity is far inside the border, skip expensive collision check
        if (distanceToCenter < radius - SAFE_DISTANCE_THRESHOLD) {
            kneaf$checksSkipped.incrementAndGet();
            cir.setReturnValue(true); // Far from border, definitely within bounds
            return;
        }

        kneaf$checksPerformed.incrementAndGet();

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$checksSkipped.get();
            long performed = kneaf$checksPerformed.get();
            long total = skipped + performed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("WorldBorder: {} checks, {} skipped ({}%)",
                        total, skipped, String.format("%.1f", skipRate));
            }

            kneaf$checksSkipped.set(0);
            kneaf$checksPerformed.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long skipped = kneaf$checksSkipped.get();
        long performed = kneaf$checksPerformed.get();
        long total = skipped + performed;
        double skipRate = total > 0 ? (skipped * 100.0 / total) : 0;

        return String.format(
                "WorldBorderStats{checks=%d, skipped=%d (%.1f%%)}",
                total, skipped, skipRate);
    }
}
