/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerChunkCacheMixin - Aggressive mixin for ServerChunkCache.
 * 
 * Optimizations:
 * - Priority-based chunk loading (near players first)
 * - Chunk access caching
 * - Rust noise generation for terrain
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ServerChunkCacheMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Metrics
    @Unique
    private static final AtomicLong kneaf$chunkAccesses = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    // Hot chunk cache for frequently accessed chunks
    @Unique
    private static final int HOT_CACHE_SIZE = 256;
    @Unique
    private final ConcurrentHashMap<Long, ChunkAccess> kneaf$hotChunkCache = new ConcurrentHashMap<>();

    // ThreadLocal L1 cache: Lock-free, instant access for the same thread accessing
    // the same chunk repeatedly
    @Unique
    private static final ThreadLocal<ChunkAccess> kneaf$threadLocalChunk = new ThreadLocal<>();

    @Shadow
    @Final
    ServerLevel level;

    /**
     * Inject at HEAD of getChunk for caching optimization.
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetChunk(int x, int z, ChunkStatus status, boolean create,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerChunkCacheMixin applied successfully - chunk optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$chunkAccesses.incrementAndGet();

        // Only cache full chunks
        if (status != ChunkStatus.FULL) {
            return;
        }

        // 1. ThreadLocal L1 Cache (Fastest, No Locks)
        ChunkAccess tlChunk = kneaf$threadLocalChunk.get();
        if (tlChunk != null) {
            net.minecraft.world.level.ChunkPos pos = tlChunk.getPos();
            if (pos.x == x && pos.z == z) {
                // Validate it's still part of this level (fast check)
                if (tlChunk instanceof LevelChunk lc && lc.getLevel() == level) {
                    kneaf$cacheHits.incrementAndGet();
                    cir.setReturnValue(tlChunk);
                    return;
                }
            }
        }

        // 2. Hot Cache (Shared, ConcurrentHashMap)
        long key = kneaf$chunkKey(x, z);
        ChunkAccess cached = kneaf$hotChunkCache.get(key);

        if (cached != null && cached instanceof LevelChunk levelChunk) {
            // ✅ CRITICAL: Validate chunk is still loaded and valid
            // A stale chunk reference can cause "No chunk holder after ticket" crash
            try {
                // Check if chunk is still part of the level
                if (level != null && level.getChunkSource().hasChunk(x, z)) {
                    // Verify chunk hasn't been unloaded
                    if (levelChunk.getLevel() == level) {
                        kneaf$cacheHits.incrementAndGet();
                        // Update L1 cache
                        kneaf$threadLocalChunk.set(cached);
                        cir.setReturnValue(cached);
                        return;
                    }
                }
            } catch (Exception e) {
                // Chunk is invalid, remove from cache
                kneaf$hotChunkCache.remove(key);
            }

            // Cache entry is stale, remove it
            kneaf$hotChunkCache.remove(key);
            kneaf$cacheMisses.incrementAndGet();
        } else {
            kneaf$cacheMisses.incrementAndGet();
        }
    }

    /**
     * Inject at RETURN of getChunk to cache results.
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("RETURN"))
    private void kneaf$onGetChunkReturn(int x, int z, ChunkStatus status, boolean create,
            CallbackInfoReturnable<ChunkAccess> cir) {
        if (status != ChunkStatus.FULL) {
            return;
        }

        ChunkAccess result = cir.getReturnValue();
        if (result instanceof LevelChunk levelChunk) {
            // ✅ VALIDATION: Only cache valid, fully-loaded chunks
            try {
                // Ensure chunk is properly loaded and part of this level
                if (levelChunk.getLevel() != level) {
                    return; // Don't cache chunks from different levels
                }

                // Don't cache empty or unloaded chunks
                if (levelChunk.isEmpty()) {
                    return;
                }
            } catch (Exception e) {
                return; // Skip invalid chunks
            }

            long key = kneaf$chunkKey(x, z);

            // Limit cache size
            if (kneaf$hotChunkCache.size() >= HOT_CACHE_SIZE) {
                // Remove ~25% of cache (simple cleanup strategy)
                int toRemove = HOT_CACHE_SIZE / 4;
                var iterator = kneaf$hotChunkCache.keySet().iterator();
                while (iterator.hasNext() && toRemove-- > 0) {
                    iterator.next();
                    iterator.remove();
                }
            }

            kneaf$hotChunkCache.put(key, result);
            // Update L1 cache
            kneaf$threadLocalChunk.set(result);
        }
    }

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCacheCleanup = 0;

    /**
     * Inject into tick to clean stale cache entries.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void kneaf$onTick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        long now = System.currentTimeMillis();

        // ✅ CRITICAL: Periodic stale cache cleanup (every 5 seconds)
        if (now - kneaf$lastCacheCleanup > 5000) {
            kneaf$cleanupStaleCache();
            kneaf$lastCacheCleanup = now;
        }

        // Periodically update stats
        if (now - kneaf$lastLogTime > 1000) {
            long accesses = kneaf$chunkAccesses.get();
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (accesses > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.chunkCacheAccesses = accesses / timeDiff;
                double hitRate = total > 0 ? (hits * 100.0 / total) : 0;
                com.kneaf.core.PerformanceStats.chunkCacheHitPercent = hitRate;

                kneaf$chunkAccesses.set(0);
                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
            } else {
                com.kneaf.core.PerformanceStats.chunkCacheAccesses = 0;
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Remove stale chunks from cache (chunks that are no longer loaded).
     */
    @Unique
    private void kneaf$cleanupStaleCache() {
        if (level == null) {
            kneaf$hotChunkCache.clear();
            return;
        }

        int removed = 0;
        var iterator = kneaf$hotChunkCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ChunkAccess chunk = entry.getValue();

            if (chunk instanceof LevelChunk levelChunk) {
                try {
                    // Remove if chunk's level doesn't match or chunk is unloaded
                    if (levelChunk.getLevel() != level) {
                        iterator.remove();
                        removed++;
                        continue;
                    }

                    // Check if chunk is still loaded in chunk source
                    int x = levelChunk.getPos().x;
                    int z = levelChunk.getPos().z;
                    if (!level.getChunkSource().hasChunk(x, z)) {
                        iterator.remove();
                        removed++;
                    }
                } catch (Exception e) {
                    iterator.remove();
                    removed++;
                }
            }
        }

        if (removed > 0) {
            kneaf$LOGGER.debug("Cleaned {} stale chunks from hot cache (remaining: {})",
                    removed, kneaf$hotChunkCache.size());
        }
    }

    /**
     * Generate chunk key for cache.
     */
    @Unique
    private static long kneaf$chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    /**
     * Clear hot chunk cache.
     */
    @Unique
    private void kneaf$clearHotCache() {
        kneaf$hotChunkCache.clear();
    }

    /**
     * Get chunk cache statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;

        return String.format(
                "ChunkCacheStats{accesses=%d, hits=%d, misses=%d, ratio=%.1f%%}",
                kneaf$chunkAccesses.get(),
                hits,
                misses,
                total > 0 ? (hits * 100.0 / total) : 0);
    }
}
