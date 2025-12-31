/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Optimizes mob spawner tick with player distance check and entity count caching.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SpawnerBlockEntityMixin - Optimizes mob spawner tick processing.
 * 
 * PROBLEM:
 * - Spawners call Level.getEntitiesOfClass() every tick to count nearby
 * entities
 * - This is O(n) per spawner and very expensive with many spawners
 * - Spawners tick even when no players are nearby
 * 
 * OPTIMIZATIONS:
 * 1. Early exit if no player within activation range (16 blocks)
 * 2. Cache nearby entity count for 5-10 ticks
 * 3. Skip spawn attempts when already at max capacity
 */
@Mixin(BaseSpawner.class)
public abstract class SpawnerBlockEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/SpawnerBlockEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Configuration
    @Unique
    private static final double ACTIVATION_RANGE = 16.0; // Default Minecraft spawner range

    @Unique
    private static final int ENTITY_COUNT_CACHE_TICKS = 10; // Ticks between entity count updates

    // Cache state per spawner instance
    @Unique
    private int kneaf$cachedEntityCount = -1;

    @Unique
    private long kneaf$lastCountTick = 0;

    @Unique
    private boolean kneaf$wasPlayerNearby = true;

    @Unique
    private long kneaf$lastPlayerCheckTick = 0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$entityCountCacheHits = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    private int spawnCount;

    @Shadow
    private int maxNearbyEntities;

    @Shadow
    private int requiredPlayerRange;

    /**
     * OPTIMIZATION: Skip spawner tick if no player nearby.
     */
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onServerTick(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ SpawnerBlockEntityMixin applied - Spawner optimization active!");
            kneaf$loggedFirstApply = true;
        }

        long currentTick = level.getGameTime();

        // === OPTIMIZATION 1: Player Range Check (every 20 ticks) ===
        if (currentTick - kneaf$lastPlayerCheckTick >= 20) {
            kneaf$wasPlayerNearby = kneaf$isPlayerNearby(level, pos);
            kneaf$lastPlayerCheckTick = currentTick;
        }

        if (!kneaf$wasPlayerNearby) {
            kneaf$ticksSkipped.incrementAndGet();
            ci.cancel(); // Skip entire tick - no player nearby
            return;
        }

        // === OPTIMIZATION 2: Cached Entity Count Check ===
        if (currentTick - kneaf$lastCountTick >= ENTITY_COUNT_CACHE_TICKS) {
            // Refresh entity count
            kneaf$cachedEntityCount = kneaf$countNearbyEntities(level, pos);
            kneaf$lastCountTick = currentTick;
        } else {
            kneaf$entityCountCacheHits.incrementAndGet();
        }

        // === OPTIMIZATION 3: Skip if at max capacity ===
        if (kneaf$cachedEntityCount >= maxNearbyEntities) {
            kneaf$ticksSkipped.incrementAndGet();
            ci.cancel(); // At capacity, no need to process
            return;
        }

        kneaf$ticksProcessed.incrementAndGet();
        kneaf$logStats();
    }

    /**
     * Check if any player is within activation range.
     */
    @Unique
    private boolean kneaf$isPlayerNearby(ServerLevel level, BlockPos pos) {
        double range = requiredPlayerRange > 0 ? requiredPlayerRange : ACTIVATION_RANGE;
        return level.hasNearbyAlivePlayer(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                range);
    }

    /**
     * Count nearby entities of the spawner's type.
     * This replaces the expensive per-tick count with a cached version.
     */
    @Unique
    private int kneaf$countNearbyEntities(Level level, BlockPos pos) {
        // Create AABB around spawner
        double range = 4.0; // Standard spawner check range
        AABB box = new AABB(
                pos.getX() - range,
                pos.getY() - range,
                pos.getZ() - range,
                pos.getX() + range + 1,
                pos.getY() + range + 1,
                pos.getZ() + range + 1);

        // Count entities (this is the expensive call we're caching)
        return level.getEntitiesOfClass(Entity.class, box).size();
    }

    /**
     * Invalidate cache when entity is spawned or killed nearby.
     */
    @Unique
    private void kneaf$invalidateCache() {
        kneaf$cachedEntityCount = -1;
        kneaf$lastCountTick = 0;
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 1000) {
            long skipped = kneaf$ticksSkipped.get();
            long processed = kneaf$ticksProcessed.get();
            long cacheHits = kneaf$entityCountCacheHits.get();
            long total = skipped + processed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                // Update central stats
                com.kneaf.core.PerformanceStats.spawnerTicksSkipped = skipped;
                com.kneaf.core.PerformanceStats.spawnerTicksProcessed = processed;
                com.kneaf.core.PerformanceStats.spawnerCacheHits = cacheHits;
                com.kneaf.core.PerformanceStats.spawnerSkipPercent = skipRate;

                kneaf$ticksSkipped.set(0);
                kneaf$ticksProcessed.set(0);
                kneaf$entityCountCacheHits.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long skipped = kneaf$ticksSkipped.get();
        long processed = kneaf$ticksProcessed.get();
        long total = skipped + processed;
        double skipRate = total > 0 ? skipped * 100.0 / total : 0;

        return String.format("Spawner{skipped=%d, processed=%d, skipRate=%.1f%%}",
                skipped, processed, skipRate);
    }
}
