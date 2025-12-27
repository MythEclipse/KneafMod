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
import com.kneaf.core.RustOptimizations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BiomeSourceMixin - Caches biome lookups for faster world generation.
 * 
 * Biome lookups happen extremely frequently during world generation.
 * Since biomes are determined solely by coordinates and the seed,
 * we can cache results for repeated lookups at the same position.
 * 
 * This is especially effective for:
 * 1. Feature decoration (multiple features query same biome)
 * 2. Structure placement (structures query biomes in patterns)
 * 3. Entity spawning (spawn checks query biomes)
 */
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * BiomeSourceMixin - Caches biome lookups for faster world generation.
 * 
 * Biome lookups happen extremely frequently during world generation.
 * Since biomes are determined solely by coordinates and the seed,
 * we can cache results for repeated lookups at the same position.
 * 
 * This is especially effective for:
 * 1. Feature decoration (multiple features query same biome)
 * 2. Structure placement (structures query biomes in patterns)
 * 3. Entity spawning (spawn checks query biomes)
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class BiomeSourceMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BiomeSourceMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache biome lookups by position
    // Key: packed position (x, y, z as int)
    // Value: biome holder
    @Unique
    private static final int MAX_CACHE_SIZE = 16384;

    @Unique
    private final Map<Long, Holder<Biome>> kneaf$biomeCache = new ConcurrentHashMap<>(4096);

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

        // Check cache
        Holder<Biome> cached = kneaf$biomeCache.get(cacheKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
        // Let vanilla compute it
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

        // Check cache size and cleanup if needed
        if (kneaf$biomeCache.size() >= MAX_CACHE_SIZE) {
            kneaf$cleanupCache();
        }

        kneaf$biomeCache.put(cacheKey, result);

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Generate a cache key from coordinates using Rust spatial hashing.
     */
    @Unique
    private long kneaf$positionKey(int x, int y, int z) {
        // Try Rust spatial hashing
        if (kneaf$rustHashCount.get() > 0 || kneaf$cacheHits.get() % 100 == 0) {
            try {
                double[] positions = new double[] { x, y, z };
                long[] hashes = RustOptimizations.batchSpatialHash(positions, 1.0, 1);
                if (hashes != null && hashes.length > 0) {
                    kneaf$rustHashCount.incrementAndGet();
                    return hashes[0];
                }
            } catch (Exception e) {
                // Fall through to Java implementation
            }
        }

        kneaf$javaHashCount.incrementAndGet();

        // Java fallback: pack coordinates into a long
        return ((long) x & 0x3FFFFF) |
                (((long) y & 0xFFF) << 22) |
                (((long) z & 0x3FFFFF) << 34);
    }

    /**
     * Cleanup cache when it gets too large.
     */
    @Unique
    private void kneaf$cleanupCache() {
        long now = System.currentTimeMillis();

        // Don't cleanup too frequently
        if (now - kneaf$lastCleanupTime < 5000) {
            return;
        }
        kneaf$lastCleanupTime = now;

        // Clear half the cache
        int toRemove = MAX_CACHE_SIZE / 2;
        var iterator = kneaf$biomeCache.keySet().iterator();
        while (iterator.hasNext() && toRemove-- > 0) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Clear the cache entirely. Called when world changes.
     */
    @Unique
    public void kneaf$clearCache() {
        kneaf$biomeCache.clear();
    }

    /**
     * Log statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("Biome cache: {} hits, {} misses ({}% hit rate)",
                        hits, misses, String.format("%.1f", hitRate));
            }

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
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
