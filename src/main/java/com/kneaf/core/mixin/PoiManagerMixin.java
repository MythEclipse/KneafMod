/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Lithium's POI optimizations.
 * Implements POI lookup caching with ACTUAL skip optimization.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import com.kneaf.core.util.CachedPoiResult;

/**
 * PoiManagerMixin - POI lookup caching with REAL skip optimization.
 * 
 * ACTUAL OPTIMIZATIONS:
 * 1. Cache POI lookup results - return cached value instead of searching
 * 2. Cache "no POI found" results to skip expensive searches
 * 3. Invalidate cache on POI changes
 */
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/PoiManagerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for getInRange results - key is hash of search params
    @Unique
    private final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<com.kneaf.core.util.CachedPoiResult> kneaf$searchCache = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            256);

    // Track sections that have been modified
    @Unique
    private final com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap kneaf$sectionModTimes = new com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap(
            256);

    @Unique
    private final java.util.concurrent.locks.StampedLock kneaf$cacheLock = new java.util.concurrent.locks.StampedLock();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$searchesSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$currentTick = 0;

    @Unique
    private static final int MAX_CACHE_SIZE = 512;

    @Unique
    private static final int CACHE_TTL_TICKS = 100;

    // Inner class removed to avoid IllegalClassLoadError
    // Using com.kneaf.core.util.CachedPoiResult instead

    @Shadow
    protected abstract java.util.stream.Stream<net.minecraft.world.entity.ai.village.poi.PoiRecord> getInChunk(
            java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> typePredicate,
            net.minecraft.world.level.ChunkPos pos,
            net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy occupancy);

    /**
     * OPTIMIZATION: Direct iterative search with caching.
     * Replaces expensive Stream-based flatMap/filter/min with manual chunk
     * iteration.
     */
    @Overwrite
    public java.util.Optional<BlockPos> findClosest(
            java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> typePredicate,
            BlockPos pos,
            int distance, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy occupancy) {

        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ PoiManagerMixin applied - Extreme iterative search + cache active!");
            kneaf$loggedFirstApply = true;
        }

        // Create cache key
        long cacheKey = kneaf$createCacheKey(pos, distance, typePredicate.hashCode(), occupancy.hashCode());

        // Check cache
        com.kneaf.core.util.CachedPoiResult cached = null;
        long stamp = kneaf$cacheLock.readLock();
        try {
            cached = kneaf$searchCache.get(cacheKey);
        } finally {
            kneaf$cacheLock.unlockRead(stamp);
        }

        if (cached != null && (kneaf$currentTick - cached.timestamp) < CACHE_TTL_TICKS) {
            kneaf$cacheHits.incrementAndGet();
            kneaf$searchesSkipped.incrementAndGet();
            return cached.result;
        }

        kneaf$cacheMisses.incrementAndGet();

        // Iterative Search
        int r = (distance >> 4) + 1;
        BlockPos bestPos = null;
        double bestDistSqr = (double) distance * distance;
        int centerX = pos.getX() >> 4;
        int centerZ = pos.getZ() >> 4;

        // Iterate chunks in a square around the center
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(centerX + dx,
                        centerZ + dz);

                // Manual iteration over chunk records
                java.util.List<net.minecraft.world.entity.ai.village.poi.PoiRecord> chunkRecords = this
                        .getInChunk(typePredicate, cp, occupancy).toList();
                for (net.minecraft.world.entity.ai.village.poi.PoiRecord record : chunkRecords) {
                    BlockPos poiPos = record.getPos();
                    double d = poiPos.distSqr(pos);
                    if (d < bestDistSqr) {
                        bestDistSqr = d;
                        bestPos = poiPos;
                    }
                }
            }
        }

        java.util.Optional<BlockPos> result = java.util.Optional.ofNullable(bestPos);

        // Cache the result
        stamp = kneaf$cacheLock.writeLock();
        try {
            if (kneaf$searchCache.size() < MAX_CACHE_SIZE) {
                kneaf$searchCache.put(cacheKey, new com.kneaf.core.util.CachedPoiResult(result, kneaf$currentTick));
            }
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }

        kneaf$logStats();
        return result;
    }

    /**
     * Invalidate cache when POI is added.
     */
    @Inject(method = "add", at = @At("HEAD"))
    private void kneaf$onAdd(BlockPos pos, Holder<PoiType> type, CallbackInfo ci) {
        kneaf$invalidateNearby(pos);
    }

    /**
     * Invalidate cache when POI is removed.
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void kneaf$onRemove(BlockPos pos, CallbackInfo ci) {
        kneaf$invalidateNearby(pos);
    }

    /**
     * Track tick for TTL.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        kneaf$currentTick++;

        // Periodic cleanup
        if (kneaf$currentTick % 200 == 0) {
            kneaf$cleanupCache();
        }
    }

    @Unique
    private long kneaf$createCacheKey(BlockPos pos, int distance, int typeHash, int occupancyHash) {
        // More robust mixing to prevent collisions
        long x = pos.getX();
        long y = pos.getY();
        long z = pos.getZ();

        long h = x * 3121L + z * 374761393L + y * 132145325L;
        h += distance * 31L;
        h += typeHash * 17L;
        h += occupancyHash;

        // Final mix
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    @Unique
    private void kneaf$invalidateNearby(BlockPos pos) {
        // Clear cache on structural changes to be safe
        long stamp = kneaf$cacheLock.writeLock();
        try {
            kneaf$searchCache.clear();
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }
    }

    @Unique
    private void kneaf$cleanupCache() {
        long stamp = kneaf$cacheLock.writeLock();
        try {
            // Simple clear strategy for primitive map cleanup
            // In a more complex implementation we would iterate and remove expired
            kneaf$searchCache.clear();
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long skipped = kneaf$searchesSkipped.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.poiHits = hits / timeDiff;
                com.kneaf.core.PerformanceStats.poiMisses = misses / timeDiff;
                com.kneaf.core.PerformanceStats.poiSkipped = skipped / timeDiff;

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
                kneaf$searchesSkipped.set(0);
            } else {
                com.kneaf.core.PerformanceStats.poiHits = 0;
            }
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long total = hits + kneaf$cacheMisses.get();
        double rate = total > 0 ? hits * 100.0 / total : 0;
        return String.format("PoiStats{hitRate=%.1f%%, skipped=%d}", rate, kneaf$searchesSkipped.get());
    }
}
