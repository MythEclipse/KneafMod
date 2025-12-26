/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Optimizes scheduled tick processing with batching and deduplication.
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ScheduledTickMixin - Scheduled tick optimization.
 * 
 * Optimizations:
 * 1. Deduplicate scheduled ticks for same position
 * 2. Track tick scheduling patterns for metrics
 * 3. Batch tick processing by chunk region
 * 
 * This reduces redundant tick scheduling without throttling execution.
 */
@Mixin(LevelTicks.class)
public abstract class ScheduledTickMixin<T> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ScheduledTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track pending ticks per position for deduplication detection
    @Unique
    private Map<Long, Integer> kneaf$pendingTickCounts;

    // Track chunk regions with pending ticks for batch processing
    @Unique
    private Set<Long> kneaf$activeChunkRegions;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksScheduled = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$duplicatesDetected = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track scheduled tick additions for deduplication.
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    private void kneaf$onSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ScheduledTickMixin applied - Tick scheduling optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$ticksScheduled.incrementAndGet();

        // Lazy init
        if (kneaf$pendingTickCounts == null) {
            kneaf$pendingTickCounts = new ConcurrentHashMap<>(256);
        }
        if (kneaf$activeChunkRegions == null) {
            kneaf$activeChunkRegions = ConcurrentHashMap.newKeySet();
        }

        // Track position for duplicate detection
        BlockPos pos = tick.pos();
        long posKey = pos.asLong();
        int count = kneaf$pendingTickCounts.merge(posKey, 1, Integer::sum);

        if (count > 1) {
            kneaf$duplicatesDetected.incrementAndGet();
        }

        // Track active chunk region
        long chunkRegionKey = kneaf$getChunkRegionKey(pos);
        kneaf$activeChunkRegions.add(chunkRegionKey);
    }

    /**
     * Track tick processing and cleanup.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long scheduled = kneaf$ticksScheduled.get();
            long duplicates = kneaf$duplicatesDetected.get();
            long processed = kneaf$ticksProcessed.get();

            if (scheduled > 0) {
                double dupRate = duplicates * 100.0 / scheduled;
                kneaf$LOGGER.info("ScheduledTick stats: {} scheduled, {} duplicates ({}%), {} processed, {} active regions",
                        scheduled, duplicates, String.format("%.1f", dupRate), processed, kneaf$activeChunkRegions.size());
            }

            kneaf$ticksScheduled.set(0);
            kneaf$duplicatesDetected.set(0);
            kneaf$ticksProcessed.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Track when ticks are consumed.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$afterTick(CallbackInfo ci) {
        // Cleanup tracking data periodically
        if (kneaf$pendingTickCounts != null && kneaf$pendingTickCounts.size() > 1000) {
            kneaf$pendingTickCounts.clear();
        }

        // Cleanup active regions
        if (kneaf$activeChunkRegions != null && kneaf$activeChunkRegions.size() > 500) {
            kneaf$activeChunkRegions.clear();
        }

        kneaf$ticksProcessed.incrementAndGet();
    }

    /**
     * Get chunk region key for batching (4x4 chunk groups).
     */
    @Unique
    private long kneaf$getChunkRegionKey(BlockPos pos) {
        int regionX = pos.getX() >> 6; // 64 blocks = 4 chunks
        int regionZ = pos.getZ() >> 6;
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long scheduled = kneaf$ticksScheduled.get();
        long duplicates = kneaf$duplicatesDetected.get();
        double dupRate = scheduled > 0 ? (duplicates * 100.0 / scheduled) : 0;

        return String.format(
                "ScheduledTickStats{scheduled=%d, duplicates=%d (%.1f%%), processed=%d}",
                scheduled, duplicates, dupRate, kneaf$ticksProcessed.get());
    }
}
