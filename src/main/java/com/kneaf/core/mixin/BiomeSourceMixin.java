/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by C2ME - https://github.com/RelativityMC/C2ME-fabric
 * Caches biome lookups for improved world generation performance.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * BiomeSourceMixin - Caches biome lookups for faster world generation.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class BiomeSourceMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BiomeSourceMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache biome lookups by position
    @Unique
    private static final int MAX_CACHE_SIZE = 16384;

    @Unique
    private final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<Holder<Biome>> kneaf$biomeCache = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            4096);

    // Lock for thread safety
    @Unique
    private final java.util.concurrent.locks.StampedLock kneaf$lock = new java.util.concurrent.locks.StampedLock();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCleanupTime = 0;

    // Track Rust native usage
    @Unique
    private static final AtomicLong kneaf$rustHashCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$javaHashCount = new AtomicLong(0);

    /**
     * Cache biome lookups from getNoiseBiome.
     */
    @Inject(method = "getNoiseBiome", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetNoiseBiome(int x, int y, int z, Climate.Sampler sampler,
            CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BiomeSourceMixin applied - Biome lookup caching active!");
            kneaf$loggedFirstApply = true;
        }

        // Generate cache key from coordinates
        long cacheKey = kneaf$positionKey(x, y, z);

        // Check cache with optimistic read
        long stamp = kneaf$lock.tryOptimisticRead();
        Holder<Biome> cached = kneaf$biomeCache.get(cacheKey);

        if (!kneaf$lock.validate(stamp)) {
            stamp = kneaf$lock.readLock();
            try {
                cached = kneaf$biomeCache.get(cacheKey);
            } finally {
                kneaf$lock.unlockRead(stamp);
            }
        }

        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
    }

    /**
     * Cache the result after computation.
     */
    @Inject(method = "getNoiseBiome", at = @At("RETURN"))
    private void kneaf$afterGetNoiseBiome(int x, int y, int z, Climate.Sampler sampler,
            CallbackInfoReturnable<Holder<Biome>> cir) {
        Holder<Biome> result = cir.getReturnValue();
        if (result == null)
            return;

        long cacheKey = kneaf$positionKey(x, y, z);
        long now = System.currentTimeMillis();

        long stamp = kneaf$lock.writeLock();
        try {
            // Check cache size and cleanup if needed
            if (kneaf$biomeCache.size() >= MAX_CACHE_SIZE) {
                if (now - kneaf$lastCleanupTime > 5000) {
                    kneaf$cleanupCache(); // Cleanup takes lock? No, called inside lock
                    kneaf$lastCleanupTime = now;
                }
            }

            // Double check inside lock if we still need to put?
            // Actually primitive maps overwrite is fine.
            kneaf$biomeCache.put(cacheKey, result);
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Generate a cache key from coordinates using Rust spatial hashing.
     */
    @Unique
    private long kneaf$positionKey(int x, int y, int z) {

        kneaf$javaHashCount.incrementAndGet();

        // Java fallback: pack coordinates into a long
        return ((long) x & 0x3FFFFF) |
                (((long) y & 0xFFF) << 22) |
                (((long) z & 0x3FFFFF) << 34);
    }

    /**
     * Cleanup cache when it gets too large.
     * MUST be called while holding write lock.
     */
    @Unique
    private void kneaf$cleanupCache() {
        // Simple clear for primitive map as we don't have iterator yet or complex
        // eviction
        // For now, clearing half is hard without iterator support in PrimitiveMaps
        // public API
        // So we just clear it all to be safe and simple.
        // Or we improved PrimitiveMaps? We didn't add iterator.
        // Clearing is safer than OOM.
        kneaf$biomeCache.clear();
    }

    /**
     * Clear the cache entirely.
     */
    @Unique
    public void kneaf$clearCache() {
        long stamp = kneaf$lock.writeLock();
        try {
            kneaf$biomeCache.clear();
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }

    /**
     * Log statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.biomeCacheHits = hits / timeDiff;
                com.kneaf.core.PerformanceStats.biomeCacheMisses = misses / timeDiff;

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
            } else {
                com.kneaf.core.PerformanceStats.biomeCacheHits = 0;
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get cache statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? hits * 100.0 / total : 0;

        return String.format("BiomeSourceStats{hits=%d, misses=%d, hitRate=%.1f%%}",
                hits, misses, hitRate);
    }
}
