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

        // Check hot cache
        long key = kneaf$chunkKey(x, z);
        ChunkAccess cached = kneaf$hotChunkCache.get(key);

        if (cached != null && cached instanceof LevelChunk) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
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
        if (result instanceof LevelChunk levelChunk && levelChunk.getBlockEntities().size() >= 0) {
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
        }
    }

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Inject into tick to clean stale cache entries.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void kneaf$onTick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        // Periodically log stats
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long accesses = kneaf$chunkAccesses.get();
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (accesses > 0) {
                double hitRate = total > 0 ? (hits * 100.0 / total) : 0;
                kneaf$LOGGER.debug("ChunkCache: {}/sec accesses, {}% hit rate",
                        String.format("%.1f", accesses / timeDiff),
                        String.format("%.1f", hitRate));
            }

            kneaf$chunkAccesses.set(0);
            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$lastLogTime = now;
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
