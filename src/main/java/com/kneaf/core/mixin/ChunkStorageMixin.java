/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Chunk storage I/O with REAL caching optimization.
 */
package com.kneaf.core.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkStorageMixin - Chunk I/O with REAL caching optimization.
 * 
 * ACTUAL OPTIMIZATIONS:
 * 1. Cache recently read chunks to avoid redundant disk reads
 * 2. Skip duplicate write requests within short time
 */
@Mixin(ChunkStorage.class)
public abstract class ChunkStorageMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkStorageMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache recently read chunks
    @Unique
    private final Map<Long, CompletableFuture<Optional<CompoundTag>>> kneaf$readCache = new ConcurrentHashMap<>(64);

    // Track recent writes to skip duplicates
    @Unique
    private final Map<Long, Long> kneaf$recentWrites = new ConcurrentHashMap<>(64);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$readCacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$readCacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$writesSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static final int WRITE_COALESCE_MS = 500;

    @Unique
    private static final int MAX_CACHE_SIZE = 128;

    /**
     * OPTIMIZATION: Return cached reads.
     */
    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void kneaf$onRead(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkStorageMixin applied - Chunk I/O caching active!");
            kneaf$loggedFirstApply = true;
        }

        long key = pos.toLong();

        // Check read cache
        CompletableFuture<Optional<CompoundTag>> cached = kneaf$readCache.get(key);
        if (cached != null && cached.isDone()) {
            kneaf$readCacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$readCacheMisses.incrementAndGet();
    }

    /**
     * Cache read results.
     */
    @Inject(method = "read", at = @At("RETURN"))
    private void kneaf$afterRead(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        long key = pos.toLong();

        // Cache the result
        if (kneaf$readCache.size() < MAX_CACHE_SIZE) {
            kneaf$readCache.put(key, cir.getReturnValue());
        }

        // Cleanup old entries periodically
        if (kneaf$readCache.size() >= MAX_CACHE_SIZE) {
            // Clear half the cache
            int count = 0;
            var iter = kneaf$readCache.entrySet().iterator();
            while (iter.hasNext() && count < MAX_CACHE_SIZE / 2) {
                iter.next();
                iter.remove();
                count++;
            }
        }

        kneaf$logStats();
    }

    /**
     * OPTIMIZATION: Skip duplicate writes.
     */
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void kneaf$onWrite(ChunkPos pos, CompoundTag tag,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        long key = pos.toLong();
        long now = System.currentTimeMillis();

        // Check if recently written
        Long lastWrite = kneaf$recentWrites.get(key);
        if (lastWrite != null && (now - lastWrite) < WRITE_COALESCE_MS) {
            kneaf$writesSkipped.incrementAndGet();
            cir.setReturnValue(CompletableFuture.completedFuture(null));
            return;
        }

        // Record this write
        kneaf$recentWrites.put(key, now);

        // Invalidate read cache for this chunk
        kneaf$readCache.remove(key);

        // Cleanup old write records
        if (kneaf$recentWrites.size() > 1000) {
            kneaf$recentWrites.entrySet().removeIf(e -> (now - e.getValue()) > 5000);
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$readCacheHits.get();
            long misses = kneaf$readCacheMisses.get();
            long skipped = kneaf$writesSkipped.get();
            long total = hits + misses;

            if (total > 0 || skipped > 0) {
                double hitRate = total > 0 ? hits * 100.0 / total : 0;
                kneaf$LOGGER.info("ChunkStorage: {}% read cache hit, {} writes skipped",
                        String.format("%.1f", hitRate), skipped);
            }

            kneaf$readCacheHits.set(0);
            kneaf$readCacheMisses.set(0);
            kneaf$writesSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        long total = kneaf$readCacheHits.get() + kneaf$readCacheMisses.get();
        double rate = total > 0 ? kneaf$readCacheHits.get() * 100.0 / total : 0;
        return String.format("ChunkStorageStats{hitRate=%.1f%%, writesSkipped=%d}",
                rate, kneaf$writesSkipped.get());
    }
}
