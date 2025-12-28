/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.ParallelEntityTicker;
import com.kneaf.core.util.TPSTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerLevelMixin - TPS tracking, parallel processing, and performance
 * monitoring.
 * 
 * Target: net.minecraft.server.level.ServerLevel
 * 
 * Optimizations:
 * 1. TPS calculation and tracking
 * 2. Dynamic chunk processor concurrency adjustment
 * 3. Parallel entity distance calculations with caching
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ServerLevelMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Tick timing for TPS calculation
    @Unique
    private static long kneaf$lastTickStart = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Entity batch processing interval
    @Unique
    private static long kneaf$lastBatchProcess = 0;

    @Unique
    private static final long BATCH_INTERVAL_MS = 500; // Process batch every 500ms

    // Cache of entity ID -> distance squared to nearest player
    // Used by BrainMixin, EntityTrackerMixin, etc. to avoid recalculation
    @Unique
    private static final Map<Integer, Double> kneaf$entityDistanceCache = new ConcurrentHashMap<>();

    /**
     * Get cached distance for an entity.
     * 
     * @param entityId Entity's ID
     * @return Squared distance to nearest player, or -1 if not cached
     */
    @Unique
    private static double kneaf$getCachedDistance(int entityId) {
        Double cached = kneaf$entityDistanceCache.get(entityId);
        return cached != null ? cached : -1.0;
    }

    /**
     * Track tick start time.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerLevelMixin applied - TPS tracking + ParallelEntityTicker active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$lastTickStart = System.nanoTime();
    }

    /**
     * Track tick end time, update TPS, and run parallel entity processing.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$onTickReturn(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        long tickTime = System.nanoTime() - kneaf$lastTickStart;
        long tickMs = tickTime / 1_000_000;

        // Update the centralized TPS tracker
        TPSTracker.recordTick(tickMs);

        // Feed real-time tick data to dynamic chunk processor
        try {
            com.kneaf.core.ChunkProcessor.updateConcurrency(tickMs);
        } catch (Throwable t) {
            // Ignore - don't crash server for optimization logic
        }

        // Run parallel entity batch processing periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastBatchProcess > BATCH_INTERVAL_MS) {
            kneaf$runParallelEntityProcessing((ServerLevel) (Object) this);
            kneaf$lastBatchProcess = now;
        }

        // Log stats every 30 seconds
        // Update stats (run more frequently if needed, but 30s is fine for cache size)
        if (now - kneaf$lastLogTime > 2000) { // Update stats every 2s for F3
            com.kneaf.core.PerformanceStats.entityCacheSize = kneaf$entityDistanceCache.size();
            kneaf$lastLogTime = now;
        }

        /*
         * if (now - kneaf$lastLogTime > 30000) {
         * kneaf$LOGGER.info("ServerLevel TPS: {}, EntityCache: {}, Parallel: {}",
         * String.format("%.1f", TPSTracker.getCurrentTPS()),
         * kneaf$entityDistanceCache.size(),
         * ParallelEntityTicker.getStatistics());
         * kneaf$lastLogTime = now;
         * }
         */
    }

    /**
     * Run parallel entity distance calculations and cache results.
     * Other mixins can use getCachedDistance() to get pre-computed values.
     */
    @Unique
    private void kneaf$runParallelEntityProcessing(ServerLevel level) {
        try {
            // Collect entities for batch processing
            List<Entity> entities = new ArrayList<>();
            level.getAllEntities().forEach(entities::add);

            int count = entities.size();
            if (count > 16) {
                // Use ParallelEntityTicker for batch distance calculation via Rust SIMD
                double[] distances = ParallelEntityTicker.batchCalculatePlayerDistances(entities, level);

                // Cache results for use by other mixins
                kneaf$entityDistanceCache.clear();
                for (int i = 0; i < count && i < distances.length; i++) {
                    kneaf$entityDistanceCache.put(entities.get(i).getId(), distances[i]);
                }
            }
        } catch (Throwable t) {
            // Ignore - don't crash server for optimization logic
        }
    }
}
