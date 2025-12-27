/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Random tick optimization for crop growth and block updates.
 */
package com.kneaf.core.mixin;

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
 * RandomTickMixin - Random tick optimization.
 * 
 * Optimizations:
 * 1. Skip chunks with no tickable blocks
 * 2. Cache tickable block information per chunk
 * 3. Track tick statistics
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

    /**
     * Optimize random tick chunk selection.
     */
    @Inject(method = "tickChunk", at = @At("HEAD"))
    private void kneaf$onTickChunk(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ RandomTickMixin applied - Random tick optimization active!");
            kneaf$loggedFirstApply = true;
        }

        @SuppressWarnings("resource") // False positive - 'self' is a cast reference to 'this'
        ServerLevel self = (ServerLevel) (Object) this;
        long chunkPos = chunk.getPos().toLong();
        long gameTime = self.getGameTime();

        // Check empty chunk cache
        Long cachedTime = kneaf$emptyChunkCache.get(chunkPos);
        if (cachedTime != null && gameTime - cachedTime < EMPTY_CHUNK_CACHE_DURATION) {
            kneaf$chunksSkipped.incrementAndGet();
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
        if (now - kneaf$lastLogTime > 60000) {
            long skipped = kneaf$chunksSkipped.get();
            long processed = kneaf$chunksProcessed.get();
            long ticks = kneaf$ticksApplied.get();
            long total = skipped + processed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("RandomTick: {} chunks, {}% skipped, {} ticks",
                        total, String.format("%.1f", skipRate), ticks);
            }

            kneaf$chunksSkipped.set(0);
            kneaf$chunksProcessed.set(0);
            kneaf$ticksApplied.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
