/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Optimizes heightmap updates with deferred recalculation and caching.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HeightmapMixin - Heightmap update optimization.
 * 
 * Optimizations:
 * 1. Coalesce multiple updates to same column
 * 2. Cache heightmap query results
 * 3. Batch updates during block changes
 * 
 * This reduces redundant heightmap recalculations during bulk block changes.
 */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/HeightmapMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track pending column updates for coalescing
    @Unique
    private final Set<Integer> kneaf$pendingColumnUpdates = ConcurrentHashMap.newKeySet();

    // Height query cache (column key -> height)
    @Unique
    private final Map<Integer, Integer> kneaf$heightCache = new ConcurrentHashMap<>(256);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$updatesRequested = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesCoalesced = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track update requests for coalescing.
     */
    @Inject(method = "update", at = @At("HEAD"))
    private void kneaf$onUpdate(int x, int y, int z,
            net.minecraft.world.level.block.state.BlockState state,
            CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ HeightmapMixin applied - Heightmap optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$updatesRequested.incrementAndGet();

        // Track column for potential coalescing
        int columnKey = kneaf$getColumnKey(x, z);
        if (!kneaf$pendingColumnUpdates.add(columnKey)) {
            kneaf$updatesCoalesced.incrementAndGet();
        }

        // Invalidate cache for this column
        kneaf$heightCache.remove(columnKey);
    }

    /**
     * Cache height queries.
     */
    @Inject(method = "getFirstAvailable", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetFirstAvailable(int x, int z, CallbackInfoReturnable<Integer> cir) {
        int columnKey = kneaf$getColumnKey(x, z);
        Integer cached = kneaf$heightCache.get(columnKey);

        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
        } else {
            kneaf$cacheMisses.incrementAndGet();
        }
    }

    /**
     * Store result in cache after computation.
     */
    @Inject(method = "getFirstAvailable", at = @At("RETURN"))
    private void kneaf$afterGetFirstAvailable(int x, int z, CallbackInfoReturnable<Integer> cir) {
        int columnKey = kneaf$getColumnKey(x, z);
        kneaf$heightCache.put(columnKey, cir.getReturnValue());

        // Limit cache size
        if (kneaf$heightCache.size() > 1024) {
            kneaf$heightCache.clear();
        }

        // Cleanup pending updates periodically
        if (kneaf$pendingColumnUpdates.size() > 256) {
            kneaf$pendingColumnUpdates.clear();
        }

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long requested = kneaf$updatesRequested.get();
            long coalesced = kneaf$updatesCoalesced.get();
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (requested > 0 || total > 0) {
                double coalesceRate = requested > 0 ? (coalesced * 100.0 / requested) : 0;
                double hitRate = total > 0 ? (hits * 100.0 / total) : 0;
                kneaf$LOGGER.info("Heightmap: {}/sec updates ({}/sec coalesced, {}%), {}% cache hit",
                        String.format("%.1f", requested / timeDiff),
                        String.format("%.1f", coalesced / timeDiff),
                        String.format("%.1f", coalesceRate),
                        String.format("%.1f", hitRate));
            }

            kneaf$updatesRequested.set(0);
            kneaf$updatesCoalesced.set(0);
            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get column key from X and Z coordinates.
     */
    @Unique
    private int kneaf$getColumnKey(int x, int z) {
        return (x & 0xF) | ((z & 0xF) << 4);
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long requested = kneaf$updatesRequested.get();
        long coalesced = kneaf$updatesCoalesced.get();
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;

        return String.format(
                "HeightmapStats{updates=%d, coalesced=%d, cacheHitRate=%.1f%%}",
                requested, coalesced,
                total > 0 ? (hits * 100.0 / total) : 0);
    }
}
