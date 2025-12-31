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

    /**
     * OPTIMIZATION: Cache and return POI search results.
     */
    @Inject(method = "findClosest", at = @At("HEAD"), cancellable = true)
    private void kneaf$onFindClosest(Predicate<Holder<PoiType>> typePredicate, BlockPos pos,
            int distance, PoiManager.Occupancy occupancy,
            CallbackInfoReturnable<Optional<BlockPos>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ PoiManagerMixin applied - POI search caching with skip active!");
            kneaf$loggedFirstApply = true;
        }

        // Create cache key
        long cacheKey = kneaf$createCacheKey(pos, distance, typePredicate.hashCode(), occupancy.hashCode());

        // Check cache with optimistic read
        com.kneaf.core.util.CachedPoiResult cached = null;
        long stamp = kneaf$cacheLock.tryOptimisticRead();
        cached = kneaf$searchCache.get(cacheKey);

        if (!kneaf$cacheLock.validate(stamp)) {
            stamp = kneaf$cacheLock.readLock();
            try {
                cached = kneaf$searchCache.get(cacheKey);
            } finally {
                kneaf$cacheLock.unlockRead(stamp);
            }
        }

        if (cached != null && (kneaf$currentTick - cached.timestamp) < CACHE_TTL_TICKS) {
            // Cache hit - return cached result
            kneaf$cacheHits.incrementAndGet();
            kneaf$searchesSkipped.incrementAndGet();
            cir.setReturnValue(cached.result);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
    }

    /**
     * Cache the result after search completes.
     */
    @Inject(method = "findClosest", at = @At("RETURN"))
    private void kneaf$afterFindClosest(Predicate<Holder<PoiType>> typePredicate, BlockPos pos,
            int distance, PoiManager.Occupancy occupancy,
            CallbackInfoReturnable<Optional<BlockPos>> cir) {
        // Cache the result
        long cacheKey = kneaf$createCacheKey(pos, distance, typePredicate.hashCode(), occupancy.hashCode());

        // Only cache if not too large
        long stamp = kneaf$cacheLock.writeLock();
        try {
            if (kneaf$searchCache.size() < MAX_CACHE_SIZE) {
                kneaf$searchCache.put(cacheKey,
                        new com.kneaf.core.util.CachedPoiResult(cir.getReturnValue(), kneaf$currentTick));
            }
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }

        kneaf$logStats();
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
        long key = pos.asLong();
        key ^= ((long) distance << 48);
        key ^= ((long) typeHash << 32);
        key ^= occupancyHash;
        return key;
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
