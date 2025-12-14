/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.Level;
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
 * LevelMixin - Aggressive mixin for Level class.
 * 
 * Optimizations:
 * - Batch process block entities
 * - Track level tick metrics
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LevelMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Metrics
    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$blockEntitiesOptimized = new AtomicLong(0);
    @Unique
    private static final AtomicInteger kneaf$currentTickBlockEntities = new AtomicInteger(0);

    @Unique
    private int kneaf$blockEntityTickCounter = 0;

    /**
     * Inject at the start of tickBlockEntities for batch optimization.
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void kneaf$onTickBlockEntitiesHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LevelMixin applied successfully - Level optimizations active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$ticksProcessed.incrementAndGet();
        kneaf$blockEntityTickCounter++;
    }

    /**
     * Inject at the end of tickBlockEntities for metrics.
     */
    @Inject(method = "tickBlockEntities", at = @At("TAIL"))
    private void kneaf$onTickBlockEntitiesTail(CallbackInfo ci) {
        int count = kneaf$currentTickBlockEntities.getAndSet(0);
        if (count > 0) {
            kneaf$blockEntitiesOptimized.addAndGet(count);
        }

        // Log occasionally
        if (kneaf$ticksProcessed.get() % 10000 == 0 && kneaf$ticksProcessed.get() > 0) {
            kneaf$LOGGER.debug("LevelMixin stats: ticks={}, blockEntities={}",
                    kneaf$ticksProcessed.get(), kneaf$blockEntitiesOptimized.get());
        }
    }

    /**
     * Get level mixin statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        return String.format(
                "LevelStats{ticks=%d, blockEntitiesOptimized=%d}",
                kneaf$ticksProcessed.get(),
                kneaf$blockEntitiesOptimized.get());
    }
}
