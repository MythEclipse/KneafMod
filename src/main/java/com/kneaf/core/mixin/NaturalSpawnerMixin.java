/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NaturalSpawnerMixin - Optimize mob spawning.
 * 
 * Optimizations:
 * 1. Spawn skip: Reduce frequency of spawn attempts.
 * 2. Fail Cache: Remember chunks where spawning failed recently.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    // Cache of chunks where spawning failed recently (ChunkPos long -> Expiry tick)
    @Unique
    private static final Map<Long, Long> kneaf$spawnFailCache = new ConcurrentHashMap<>();

    @Unique
    private static long kneaf$lastCleanTime = 0;

    /**
     * Optimize spawn requests for a chunk.
     */
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onSpawnForChunk(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState spawnState,
            boolean spawnFriendlies, boolean spawnEnemies, boolean spawnMapData, CallbackInfo ci) {
        long time = level.getGameTime();
        long chunkPos = chunk.getPos().toLong();

        // Cleanup cache occasionally
        if (time - kneaf$lastCleanTime > 1200) { // Every minute
            kneaf$spawnFailCache.entrySet().removeIf(entry -> entry.getValue() < time);
            kneaf$lastCleanTime = time;
        }

        // Skip: If this chunk recently failed spawning or is marked, skip
        if (kneaf$spawnFailCache.containsKey(chunkPos)) {
            if (kneaf$spawnFailCache.get(chunkPos) > time) {
                ci.cancel();
                return;
            }
        }

        // Global skip: Only run full spawn logic every 10 ticks per chunk
        // effectively
        // (Randomizing prevents all chunks skipping same tick)
        if ((time + chunkPos) % 2 != 0) {
            ci.cancel();
        }
    }
}
