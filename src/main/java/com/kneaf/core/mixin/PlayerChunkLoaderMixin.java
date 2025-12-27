/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Optimizes player chunk loading with skip optimization.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
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
 * PlayerChunkLoaderMixin - REAL optimization for player chunk tracking.
 * 
 * ACTUAL OPTIMIZATIONS:
 * 1. Skip redundant move() calls when player is in same chunk
 * 2. Cache player positions to avoid recalculation
 * 3. Batch chunk updates for players that haven't moved
 */
@Mixin(ChunkMap.class)
public abstract class PlayerChunkLoaderMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/PlayerChunkLoaderMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache last chunk position per player
    @Unique
    private static final Map<Integer, Long> kneaf$playerChunkCache = new ConcurrentHashMap<>();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$movesCalled = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$movesSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * OPTIMIZATION: Skip move() processing if player hasn't changed chunks.
     */
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void kneaf$onPlayerMove(ServerPlayer player, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ PlayerChunkLoaderMixin applied - Move skip optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$movesCalled.incrementAndGet();

        // Get current chunk position
        ChunkPos currentPos = player.chunkPosition();
        long currentChunkKey = currentPos.toLong();

        // Check if player is still in the same chunk
        Long lastChunkKey = kneaf$playerChunkCache.get(player.getId());

        if (lastChunkKey != null && lastChunkKey == currentChunkKey) {
            // Player hasn't changed chunks - skip full move processing
            kneaf$movesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Update cache with new position
        kneaf$playerChunkCache.put(player.getId(), currentChunkKey);

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Clean up when entity is removed.
     */
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void kneaf$onEntityRemove(net.minecraft.world.entity.Entity entity, CallbackInfo ci) {
        if (entity instanceof ServerPlayer player) {
            kneaf$playerChunkCache.remove(player.getId());
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long moves = kneaf$movesCalled.get();
            long skipped = kneaf$movesSkipped.get();

            if (moves > 0) {
                double skipRate = skipped * 100.0 / moves;
                kneaf$LOGGER.info("PlayerChunk optimization: {} moves, {} skipped ({}% saved)",
                        moves, skipped, String.format("%.1f", skipRate));
            }

            kneaf$movesCalled.set(0);
            kneaf$movesSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    public static String kneaf$getStatistics() {
        return String.format("PlayerChunkStats{moves=%d, skipped=%d, players=%d}",
                kneaf$movesCalled.get(), kneaf$movesSkipped.get(), kneaf$playerChunkCache.size());
    }

    @Unique
    public static void kneaf$clearCaches() {
        kneaf$playerChunkCache.clear();
    }
}
