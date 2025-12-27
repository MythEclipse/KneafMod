/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Scheduled tick optimization with ACTUAL duplicate skip.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ScheduledTickMixin - REAL optimization for scheduled ticks.
 * 
 * ACTUAL OPTIMIZATIONS:
 * 1. Skip scheduling duplicate ticks for same position+type within short time
 * 2. Coalesce rapid tick requests to reduce overhead
 */
@Mixin(LevelTicks.class)
public abstract class ScheduledTickMixin<T> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ScheduledTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track recently scheduled ticks to skip duplicates
    // Key = position + type hash, Value = game tick when scheduled
    @Unique
    private Map<Long, Long> kneaf$recentlyScheduled = new ConcurrentHashMap<>(256);

    // Minimum ticks between same position+type scheduling
    @Unique
    private static final int MIN_SCHEDULE_INTERVAL = 2;

    // Current game tick (updated externally or estimated)
    @Unique
    private long kneaf$currentTick = 0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksScheduled = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$duplicatesSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * OPTIMIZATION: Skip duplicate tick scheduling.
     */
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void kneaf$onSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ScheduledTickMixin applied - Duplicate tick skip active!");
            kneaf$loggedFirstApply = true;
        }

        // Ensure map exists
        if (kneaf$recentlyScheduled == null) {
            kneaf$recentlyScheduled = new ConcurrentHashMap<>(256);
        }

        kneaf$ticksScheduled.incrementAndGet();

        // Create key from position + type
        BlockPos pos = tick.pos();
        long key = pos.asLong() ^ ((long) tick.type().hashCode() << 32);

        // Check if recently scheduled
        Long lastScheduled = kneaf$recentlyScheduled.get(key);
        if (lastScheduled != null && (kneaf$currentTick - lastScheduled) < MIN_SCHEDULE_INTERVAL) {
            // Duplicate - skip this schedule
            kneaf$duplicatesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Record this schedule
        kneaf$recentlyScheduled.put(key, kneaf$currentTick);

        kneaf$logStats();
    }

    /**
     * Update tick counter and cleanup.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        kneaf$currentTick++;

        // Periodic cleanup
        if (kneaf$currentTick % 100 == 0 && kneaf$recentlyScheduled != null) {
            // Remove old entries
            long threshold = kneaf$currentTick - 20;
            kneaf$recentlyScheduled.entrySet().removeIf(e -> e.getValue() < threshold);

            // Limit size
            if (kneaf$recentlyScheduled.size() > 1000) {
                kneaf$recentlyScheduled.clear();
            }
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long scheduled = kneaf$ticksScheduled.get();
            long skipped = kneaf$duplicatesSkipped.get();

            if (scheduled > 0) {
                double skipRate = skipped * 100.0 / scheduled;
                kneaf$LOGGER.info("ScheduledTick: {} scheduled, {} duplicates skipped ({}%)",
                        scheduled, skipped, String.format("%.1f", skipRate));
            }

            kneaf$ticksScheduled.set(0);
            kneaf$duplicatesSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        return String.format("ScheduledTickStats{scheduled=%d, skipped=%d}",
                kneaf$ticksScheduled.get(), kneaf$duplicatesSkipped.get());
    }
}
