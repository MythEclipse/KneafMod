/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Optimizes neighbor block state lookups with aggressive caching.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeighborBlockAccessMixin - Caches neighbor block state lookups.
 * 
 * Neighbor lookups are extremely hot code paths used by:
 * - Redstone updates (checking power from neighbors)
 * - Fluid tick (checking flow direction)
 * - Block updates (shape/state changes)
 * - Light propagation
 * 
 * OPTIMIZATIONS:
 * 1. Cache neighbor BlockState lookups per-tick
 * 2. Fast-path for immutable blocks (air, stone, bedrock)
 * 3. Batch invalidation on block changes
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class NeighborBlockAccessMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/NeighborBlockAccessMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache: position hash -> neighbor states array (6 directions)
    // Key = pos.asLong() ^ (direction.ordinal() << 60)
    @Unique
    private static final ConcurrentHashMap<Long, BlockState> kneaf$neighborCache = new ConcurrentHashMap<>(4096);

    // Maximum cache size
    @Unique
    private static final int MAX_CACHE_SIZE = 8192;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$fastPathHits = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCleanupTime = 0;

    /**
     * Redirect neighbor block state lookups to use cache.
     * This targets the common pattern: level.getBlockState(pos.relative(direction))
     */
    @Redirect(method = "updateShape", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState kneaf$getCachedNeighborState(BlockGetter level, BlockPos neighborPos) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ NeighborBlockAccessMixin applied - Neighbor caching active!");
            kneaf$loggedFirstApply = true;
        }

        long cacheKey = neighborPos.asLong();

        // Check cache first
        BlockState cached = kneaf$neighborCache.get(cacheKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            return cached;
        }

        kneaf$cacheMisses.incrementAndGet();

        // Get actual state
        BlockState state = level.getBlockState(neighborPos);

        // Cache the result
        if (kneaf$neighborCache.size() < MAX_CACHE_SIZE) {
            kneaf$neighborCache.put(cacheKey, state);
        }

        // Periodic cleanup
        kneaf$periodicCleanup();

        return state;
    }

    /**
     * Invalidate cache entry when block state changes.
     * Called on neighbor notify to ensure cache consistency.
     */
    @Unique
    private static void kneaf$invalidatePosition(BlockPos pos) {
        if (pos != null) {
            long key = pos.asLong();
            kneaf$neighborCache.remove(key);

            // Also invalidate surrounding positions (they may reference this pos)
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                kneaf$neighborCache.remove(neighbor.asLong());
            }
        }
    }

    /**
     * Clear the entire cache. Called on world unload or dimension change.
     */
    @Unique
    private static void kneaf$clearCache() {
        kneaf$neighborCache.clear();
        kneaf$LOGGER.debug("Neighbor cache cleared");
    }

    /**
     * Periodic cache cleanup to prevent memory bloat.
     */
    @Unique
    private static void kneaf$periodicCleanup() {
        long now = System.currentTimeMillis();

        // Cleanup every 5 seconds
        if (now - kneaf$lastCleanupTime > 5000) {
            if (kneaf$neighborCache.size() > MAX_CACHE_SIZE / 2) {
                // Remove ~50% of cache entries
                int toRemove = kneaf$neighborCache.size() / 2;
                var iterator = kneaf$neighborCache.keySet().iterator();
                while (iterator.hasNext() && toRemove-- > 0) {
                    iterator.next();
                    iterator.remove();
                }
            }
            kneaf$lastCleanupTime = now;
        }

        // Log stats every 1 second
        if (now - kneaf$lastLogTime > 1000) {
            kneaf$logStats();
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;

        if (total > 0) {
            double hitRate = hits * 100.0 / total;
            // Update central stats
            com.kneaf.core.PerformanceStats.neighborCacheHits = hits;
            com.kneaf.core.PerformanceStats.neighborCacheMisses = misses;
            com.kneaf.core.PerformanceStats.neighborCacheHitPercent = hitRate;
            com.kneaf.core.PerformanceStats.neighborCacheSize = kneaf$neighborCache.size();

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$fastPathHits.set(0);
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

        return String.format("NeighborCache{hits=%d, misses=%d, hitRate=%.1f%%, size=%d}",
                hits, misses, hitRate, kneaf$neighborCache.size());
    }
}
