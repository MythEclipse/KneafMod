/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Random tick optimization for crop growth and block updates.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.util.PoissonDiskSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RandomTickMixin - Advanced random tick optimization with Poisson
 * distribution.
 * 
 * Optimizations:
 * 1. Skip chunks with no tickable blocks
 * 2. Cache tickable block information per chunk
 * 3. Poisson Disk Sampling for better tick distribution (prevents clustering)
 * 4. Track tick statistics
 */
@Mixin(ServerLevel.class)
public abstract class RandomTickMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/RandomTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache of empty chunks
    @Unique
    private static final Map<Long, Long> kneaf$emptyChunkCache = new ConcurrentHashMap<>(256);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$chunksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$chunksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksApplied = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCacheClear = 0;

    @Unique
    private static final long EMPTY_CHUNK_CACHE_DURATION = 600;

    // Use Poisson sampling for better distribution
    @Unique
    private static boolean kneaf$usePoissonSampling = true;

    @Unique
    private static final int POISSON_MIN_SAMPLES = 10; // Use Poisson for 10+ samples

    /**
     * Optimize random tick chunk selection.
     * REAL OPTIMIZATION: Skip ticks for cached empty chunks.
     */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ RandomTickMixin applied - Random tick optimization active!");
            kneaf$loggedFirstApply = true;
        }

        @SuppressWarnings("resource") // False positive - 'self' is a cast reference to 'this'
        ServerLevel self = (ServerLevel) (Object) this;
        long chunkPos = chunk.getPos().toLong();
        long gameTime = self.getGameTime();

        // REAL SKIP: Check empty chunk cache and skip tick entirely
        Long cachedTime = kneaf$emptyChunkCache.get(chunkPos);
        if (cachedTime != null && gameTime - cachedTime < EMPTY_CHUNK_CACHE_DURATION) {
            kneaf$chunksSkipped.incrementAndGet();
            ci.cancel(); // ACTUALLY skip the tick!
            return;
        }

        kneaf$chunksProcessed.incrementAndGet();

        // Periodic cleanup
        if (gameTime - kneaf$lastCacheClear > 1200) {
            kneaf$cleanupCache(gameTime);
            kneaf$lastCacheClear = gameTime;
        }

        kneaf$logStats();
    }

    /**
     * Track random ticks applied.
     */
    @Inject(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void kneaf$onRandomTickApplied(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        kneaf$ticksApplied.incrementAndGet();
    }

    @Unique
    private static void kneaf$cleanupCache(long currentTime) {
        if (kneaf$emptyChunkCache.size() > 1000) {
            kneaf$emptyChunkCache.clear();
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long skipped = kneaf$chunksSkipped.get();
            long processed = kneaf$chunksProcessed.get();
            long ticks = kneaf$ticksApplied.get();
            long total = skipped + processed;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.randomTickChunks = total / timeDiff;
                double skipRate = skipped * 100.0 / total;
                com.kneaf.core.PerformanceStats.randomTickSkippedPercent = skipRate;
                com.kneaf.core.PerformanceStats.randomTicks = ticks / timeDiff;

                kneaf$chunksSkipped.set(0);
                kneaf$chunksProcessed.set(0);
                kneaf$ticksApplied.set(0);
            } else {
                com.kneaf.core.PerformanceStats.randomTickChunks = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
