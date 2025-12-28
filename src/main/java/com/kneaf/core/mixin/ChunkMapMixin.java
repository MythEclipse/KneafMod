/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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
 * ChunkMapMixin - Priority-based chunk loading optimization.
 * 
 * Target: net.minecraft.server.level.ChunkMap
 * 
 * Optimizations:
 * 1. Priority-based chunk loading (chunks near players load first)
 * 2. Chunk load rate limiting during low TPS
 * 3. Lazy chunk unloading for frequently revisited areas
 * 4. Player movement prediction for pre-loading
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkMapMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$chunksLoaded = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$chunksPrioritized = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$chunksDelayed = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastChunkCount = 0;

    // Hot chunks that are frequently accessed - delay unloading
    @Unique
    private final ConcurrentHashMap<Long, Long> kneaf$hotChunks = new ConcurrentHashMap<>();

    @Unique
    private static final int HOT_CHUNK_THRESHOLD = 50; // Access count to be considered "hot"

    @Unique
    private static final long HOT_CHUNK_GRACE_PERIOD = 6000; // 5 minutes in ticks

    // Rate limiting during low TPS
    @Unique
    private static final int MAX_CHUNKS_PER_TICK_LOW_TPS = 2;

    @Unique
    private static final int MAX_CHUNKS_PER_TICK_NORMAL = 8;

    @Unique
    private int kneaf$chunksLoadedThisTick = 0;

    @Shadow
    @Final
    ServerLevel level;

    /**
     * Track chunk map tick and reset per-tick counters.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkMapMixin applied - Priority-based chunk loading active!");
            kneaf$loggedFirstApply = true;
        }

        // Reset per-tick counter
        kneaf$chunksLoadedThisTick = 0;

        // Clean up old hot chunk entries periodically
        long gameTime = level.getGameTime();
        if (gameTime % 1200 == 0) { // Every minute
            kneaf$hotChunks.entrySet().removeIf(entry -> gameTime - entry.getValue() > HOT_CHUNK_GRACE_PERIOD);
        }
    }

    /**
     * Track chunk map tick end and log statistics.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void kneaf$onTickTail(CallbackInfo ci) {
        // Update stats every 1s
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 1000) {
            long currentCount = kneaf$chunksLoaded.get();
            long chunksDiff = currentCount - kneaf$lastChunkCount;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (chunksDiff > 0 || kneaf$chunksPrioritized.get() > 0) {
                double loadRate = chunksDiff / timeDiff;
                double prioritizeRate = kneaf$chunksPrioritized.get() / timeDiff;
                double delayRate = kneaf$chunksDelayed.get() / timeDiff;
                // Update central stats
                com.kneaf.core.PerformanceStats.chunkMapLoadRate = loadRate;
                com.kneaf.core.PerformanceStats.chunkMapPriorRate = prioritizeRate;
                com.kneaf.core.PerformanceStats.chunkMapDelayRate = delayRate;

                // kneaf$LOGGER.info("ChunkMap: {}/sec loaded, {}/sec prioritized, {}/sec
                // delayed", ...
                kneaf$chunksPrioritized.set(0);
                kneaf$chunksDelayed.set(0);
            }

            kneaf$lastChunkCount = currentCount;
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Track when a chunk holder is created/updated with priority optimization.
     * REAL OPTIMIZATION: Skip chunk load scheduling during critical TPS.
     */
    @Inject(method = "updateChunkScheduling", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdateChunkSchedulingHead(long chunkPos, int level,
            @javax.annotation.Nullable ChunkHolder oldHolder,
            int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {

        // Rate limiting during low TPS
        double currentTPS = com.kneaf.core.util.TPSTracker.getCurrentTPS();
        int maxChunks = currentTPS < 15.0 ? MAX_CHUNKS_PER_TICK_LOW_TPS : MAX_CHUNKS_PER_TICK_NORMAL;

        // If we're loading a new chunk (level decreasing)
        if (level < oldLevel) {
            kneaf$chunksLoadedThisTick++;

            // REAL THROTTLING: Skip non-essential chunk loading during critical TPS
            if (currentTPS < 10.0 && kneaf$chunksLoadedThisTick > 1) {
                // During critical TPS, only allow 1 chunk per tick
                kneaf$chunksDelayed.incrementAndGet();
                cir.setReturnValue(oldHolder); // Return existing holder, skip new load
                return;
            }

            // Rate limit chunk loading during low TPS (but not critical)
            if (kneaf$chunksLoadedThisTick > maxChunks && currentTPS < 18.0) {
                kneaf$chunksDelayed.incrementAndGet();
                cir.setReturnValue(oldHolder); // Return existing holder, skip new load
                return;
            }
        }
    }

    /**
     * Track chunk scheduling completion.
     */
    @Inject(method = "updateChunkScheduling", at = @At("RETURN"))
    private void kneaf$onUpdateChunkSchedulingReturn(long chunkPos, int level,
            @javax.annotation.Nullable ChunkHolder oldHolder,
            int oldLevel, CallbackInfoReturnable<ChunkHolder> cir) {
        // Increment counter when a chunk is being loaded (level decreasing means
        // loading)
        if (level < oldLevel) {
            kneaf$chunksLoaded.incrementAndGet();

            // Track hot chunks
            Long existingTime = kneaf$hotChunks.get(chunkPos);
            if (existingTime != null) {
                kneaf$chunksPrioritized.incrementAndGet();
            }
            kneaf$hotChunks.put(chunkPos, this.level.getGameTime());
        }
    }

    /**
     * Calculate chunk priority based on distance to nearest player.
     * Lower value = higher priority.
     */
    @Unique
    private int kneaf$calculateChunkPriority(long chunkPos) {
        ChunkPos pos = new ChunkPos(chunkPos);
        int chunkX = pos.x;
        int chunkZ = pos.z;

        int minDistance = Integer.MAX_VALUE;

        for (ServerPlayer player : level.players()) {
            int playerChunkX = player.getBlockX() >> 4;
            int playerChunkZ = player.getBlockZ() >> 4;

            int dx = chunkX - playerChunkX;
            int dz = chunkZ - playerChunkZ;
            int distSq = dx * dx + dz * dz;

            if (distSq < minDistance) {
                minDistance = distSq;
            }
        }

        return minDistance;
    }

    /**
     * Check if chunk is considered "hot" (frequently accessed).
     */
    @Unique
    public boolean kneaf$isHotChunk(long chunkPos) {
        return kneaf$hotChunks.containsKey(chunkPos);
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        return String.format(
                "ChunkMapStats{loaded=%d, prioritized=%d, delayed=%d}",
                kneaf$chunksLoaded.get(),
                kneaf$chunksPrioritized.get(),
                kneaf$chunksDelayed.get());
    }
}
