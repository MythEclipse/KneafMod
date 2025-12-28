/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * F3 Debug Overlay - Shows KneafMod performance stats in debug screen.
 */
package com.kneaf.core.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kneaf.core.util.TPSTracker;
import com.kneaf.core.ParallelEntityTicker;
import com.kneaf.core.PerformanceStats;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DebugOverlayHandler - Adds KneafMod statistics to F3 debug screen.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = "kneafcore", value = Dist.CLIENT)
public class DebugOverlayHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/DebugOverlay");
    private static boolean loggedFirstApply = false;

    // Statistics counters (updated by mixins)
    private static final AtomicLong chunksLoaded = new AtomicLong(0);
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);
    private static final AtomicLong entitiesOptimized = new AtomicLong(0);

    // Rates (updated periodically)
    private static double chunksPerSecond = 0.0;
    private static double cacheHitRate = 0.0;
    private static double parallelEntitiesPerSecond = 0.0;
    private static long lastUpdateTime = System.currentTimeMillis();
    private static long lastParallelCount = 0;

    /**
     * Add KneafMod debug info to F3 screen.
     */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!loggedFirstApply) {
            LOGGER.info("✅ DebugOverlayHandler registered - F3 stats active!");
            loggedFirstApply = true;
        }

        // Update rates periodically
        updateRates();

        // Add to right side of F3 screen
        List<String> rightText = event.getRight();

        rightText.add("");
        rightText.add("§6[KneafMod Performance]§r");
        rightText.add(String.format("TPS: §a%.1f§r", TPSTracker.getCurrentTPS()));
        rightText.add(String.format("Chunks/s: §a%.1f§r", chunksPerSecond));
        rightText.add(String.format("Cache Hit: §a%.1f%%§r", cacheHitRate));
        rightText.add(String.format("Entities Opt: §a%d§r", entitiesOptimized.get()));

        // Parallel entity ticker stats
        if (parallelEntitiesPerSecond > 0) {
            rightText.add(String.format("Parallel: §a%.1f/s§r", parallelEntitiesPerSecond));
        } else {
            rightText.add("Parallel: §7Idle§r");
        }

        // Detailed Stats from Central Registry
        if (PerformanceStats.chunkMapLoadRate > 0 || PerformanceStats.chunkMapPriorRate > 0) {
            rightText.add(String.format("ChunkMap: §a%.1f/s§r L, §a%.1f/s§r P",
                    PerformanceStats.chunkMapLoadRate, PerformanceStats.chunkMapPriorRate));
        }

        if (PerformanceStats.chunkGenRate > 0) {
            rightText.add(String.format("ChunkGen: §a%.1f/s§r (Avg: %.2fms)",
                    PerformanceStats.chunkGenRate, PerformanceStats.chunkGenAvgMs));
            if (PerformanceStats.chunkGenParallelRate > 0) {
                rightText.add(String.format("  > Parallel: §a%.1f/s§r", PerformanceStats.chunkGenParallelRate));
            }
        }

        if (PerformanceStats.chunkThroughput > 0) {
            rightText.add(String.format("ChunkProc: §a%d/s§r (Active: %d)",
                    PerformanceStats.chunkThroughput, PerformanceStats.chunkActiveThreads));
        }

        if (PerformanceStats.voxelHitRate > 0) {
            rightText.add(String.format("Voxel: §a%.0f/s§r Hit (%.1f%%)",
                    PerformanceStats.voxelHitRate, PerformanceStats.voxelHitPercent));
        }

        if (PerformanceStats.nbtTagRate > 0) {
            rightText.add(String.format("NBT: §a%.0f/s§r Tags, §a%.0f/s§r Acc",
                    PerformanceStats.nbtTagRate, PerformanceStats.nbtAccessRate));
        }

        if (PerformanceStats.noiseGenRate > 0) {
            rightText.add(String.format("NoiseGen: §a%.1f/s§r (Avg: %.2fms)",
                    PerformanceStats.noiseGenRate, PerformanceStats.noiseGenAvgMs));
        }

        if (PerformanceStats.playerChunkMoveRate > 0) {
            rightText.add(String.format("PlayerChunk: §a%.1f/s§r Move (%.1f%% Saved)",
                    PerformanceStats.playerChunkMoveRate, PerformanceStats.playerChunkSavedPercent));
        }

        if (PerformanceStats.entityCacheSize > 0) {
            rightText.add(String.format("EntityCache: §a%d§r", PerformanceStats.entityCacheSize));
        }

        if (PerformanceStats.walkPathQueries > 0) {
            double total = PerformanceStats.walkPathQueries;
            double hitRate = (PerformanceStats.walkCacheHits + PerformanceStats.walkAirSkips) * 100.0
                    / Math.max(1, total);
            rightText.add(String.format("WalkPath: §a%.0f/s§r (%.1f%% Hit)", total, hitRate));
        }

        if (PerformanceStats.chunkPacketCreated > 0) {
            double dupRate = PerformanceStats.chunkPacketDuplicates * 100.0
                    / Math.max(1, PerformanceStats.chunkPacketCreated);
            rightText.add(
                    String.format("ChunkPkt: §a%.0f/s§r (%.1f%% Dup)", PerformanceStats.chunkPacketCreated, dupRate));
        }

        if (PerformanceStats.poiHits + PerformanceStats.poiMisses > 0) {
            double total = PerformanceStats.poiHits + PerformanceStats.poiMisses;
            double hitRate = PerformanceStats.poiHits * 100.0 / Math.max(1, total);
            rightText.add(String.format("POI: §a%.0f/s§r Hit (%.1f%%), §a%.0f/s§r Skip",
                    PerformanceStats.poiHits, hitRate, PerformanceStats.poiSkipped));
        }

        if (PerformanceStats.trackerUpdates > 0) {
            double skipRate = PerformanceStats.trackerSkipped * 100.0 / Math.max(1, PerformanceStats.trackerUpdates);
            rightText.add(
                    String.format("Tracker: §a%.0f/s§r Upd (%.1f%% Skip)", PerformanceStats.trackerUpdates, skipRate));
        }

        if (PerformanceStats.biomeCacheHits + PerformanceStats.biomeCacheMisses > 0) {
            double total = PerformanceStats.biomeCacheHits + PerformanceStats.biomeCacheMisses;
            double hitRate = PerformanceStats.biomeCacheHits * 100.0 / Math.max(1, total);
            rightText.add(String.format("Biome: §a%.0f/s§r Hit (%.1f%%)", PerformanceStats.biomeCacheHits, hitRate));
        }

        if (PerformanceStats.tickScheduled > 0) {
            double skipRate = PerformanceStats.tickSkipped * 100.0 / Math.max(1, PerformanceStats.tickScheduled);
            rightText.add(
                    String.format("Ticks: §a%.0f/s§r Sched (%.1f%% Skip)", PerformanceStats.tickScheduled, skipRate));
        }

        if (PerformanceStats.borderChecks > 0) {
            double skipRate = PerformanceStats.borderSkipped * 100.0 / Math.max(1, PerformanceStats.borderChecks);
            rightText.add(
                    String.format("Border: §a%.0f/s§r Chk (%.1f%% Skip)", PerformanceStats.borderChecks, skipRate));
        }

        if (PerformanceStats.netPackets > 0) {
            rightText.add(String.format("Net: §a%.0f/s§r Pkt, §a%.0f/s§r Batch, §a%.0f/s§r Coal",
                    PerformanceStats.netPackets, PerformanceStats.netBatches, PerformanceStats.netCoalesced));
        }

        if (PerformanceStats.itemMerges > 0) {
            double skipRate = PerformanceStats.itemSkipped * 100.0 / Math.max(1, PerformanceStats.itemMerges);
            rightText.add(String.format("Item: §a%.0f/s§r Merge (%.1f%% Skip)", PerformanceStats.itemMerges, skipRate));
        }

        if (PerformanceStats.beChanges + PerformanceStats.beSkipped > 0) {
            double total = PerformanceStats.beChanges + PerformanceStats.beSkipped;
            double skipRate = PerformanceStats.beSkipped * 100.0 / Math.max(1, total);
            rightText
                    .add(String.format("BlockEnt: §a%.0f/s§r Chg (%.1f%% Idle)", PerformanceStats.beChanges, skipRate));
        }

        if (PerformanceStats.lightRustBatches > 0 || PerformanceStats.lightSkipped > 0) {
            rightText.add(String.format("Light: §a%.0f/s§r Batch, §a%.0f/s§r Skip (%.1f%% Hit)",
                    PerformanceStats.lightRustBatches, PerformanceStats.lightSkipped,
                    PerformanceStats.lightCacheHitPercent));
        }

        if (PerformanceStats.blockStateCount > 0) {
            rightText.add(String.format("BlkState: §a%.0f/s§r New, §a%.0f/s§r Dedup, %d Cache",
                    PerformanceStats.blockStateCount, PerformanceStats.blockStateDedup,
                    (int) PerformanceStats.blockStateCached));
        }

        if (PerformanceStats.heightmapUpdates > 0) {
            double coalesceRate = PerformanceStats.heightmapCoalesced * 100.0
                    / Math.max(1, PerformanceStats.heightmapUpdates);
            rightText.add(String.format("Heightmap: §a%.0f/s§r Upd (%.1f%% Coal, %.1f%% Hit)",
                    PerformanceStats.heightmapUpdates, coalesceRate, PerformanceStats.heightmapCacheHitPercent));
        }

        if (PerformanceStats.recipeLookups > 0) {
            rightText.add(String.format("Recipe: §a%.0f/s§r Lookup (%.1f%% Hit, %d Cache)",
                    PerformanceStats.recipeLookups, PerformanceStats.recipeHitPercent,
                    PerformanceStats.recipeCacheSize));
        }

        if (PerformanceStats.storageReads > 0 || PerformanceStats.storageWritesSkipped > 0) {
            rightText.add(String.format("ChunkStor: §a%.0f/s§r Read (%.1f%% Hit), §a%.0f/s§r W-Skip",
                    PerformanceStats.storageReads, PerformanceStats.storageHitPercent,
                    PerformanceStats.storageWritesSkipped));
        }

        if (PerformanceStats.structureLookups > 0) {
            rightText.add(String.format("Struct: §a%.0f/s§r Lookup (%.1f%% Hit, %d/%d Cache)",
                    PerformanceStats.structureLookups, PerformanceStats.structureHitPercent,
                    PerformanceStats.structureCached, PerformanceStats.structureEmptyCached));
        }

        if (PerformanceStats.fluidTicks > 0) {
            rightText.add(String.format("Fluid: §a%.0f/s§r Tick (%.1f%% Skip, %d RustBatch)",
                    PerformanceStats.fluidTicks, PerformanceStats.fluidSkippedPercent,
                    PerformanceStats.fluidRustBatches));
        }

        if (PerformanceStats.chunkCacheAccesses > 0) {
            rightText.add(String.format("SrvChunkCache: §a%.0f/s§r Acc (%.1f%% Hit)",
                    PerformanceStats.chunkCacheAccesses, PerformanceStats.chunkCacheHitPercent));
        }

        if (PerformanceStats.beBatched > 0 || PerformanceStats.beBatchSkipped > 0) {
            rightText.add(String.format("BE-Batch: §a%.0f/s§r Batch, §a%.0f/s§r Idle-Skip",
                    PerformanceStats.beBatched, PerformanceStats.beBatchSkipped));
        }

        if (PerformanceStats.anvilSaved > 0 || PerformanceStats.anvilSkipped > 0) {
            rightText.add(String.format("Anvil: §a%.0f/s§r Save, §a%.0f/s§r Skip, §a%.0f/s§r Throttle",
                    PerformanceStats.anvilSaved, PerformanceStats.anvilSkipped, PerformanceStats.anvilThrottled));
        }

        if (PerformanceStats.regionReads > 0 || PerformanceStats.regionWrites > 0) {
            rightText.add(String.format("Region: §a%.0f/s§r R, §a%.0f/s§r W, §a%.0f/s§r Skip",
                    PerformanceStats.regionReads, PerformanceStats.regionWrites, PerformanceStats.regionSkipped));
        }

        if (PerformanceStats.randomTickChunks > 0) {
            rightText.add(String.format("RandTick: §a%.0f/s§r Chunks (%.1f%% Skip), §a%.0f/s§r Ticks",
                    PerformanceStats.randomTickChunks, PerformanceStats.randomTickSkippedPercent,
                    PerformanceStats.randomTicks));
        }

        if (PerformanceStats.entityPushOptimized > 0 || PerformanceStats.entityPushSkipped > 0) {
            rightText.add(String.format("EntityPush: §a%.0f/s§r Opt, §a%.0f/s§r Skip",
                    PerformanceStats.entityPushOptimized, PerformanceStats.entityPushSkipped));
        }

    }

    /**
     * Update rate calculations.
     */
    private static void updateRates() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdateTime;

        if (elapsed >= 1000) { // Update every second

            // Top summary stats from PerformanceStats (updated by Mixins)
            chunksPerSecond = com.kneaf.core.PerformanceStats.chunkGenRate;
            cacheHitRate = com.kneaf.core.PerformanceStats.voxelHitPercent;

            // Parallel entities rate (calculated locally here because ParallelEntityTicker
            // updates every 60s)
            // Wait, ParallelEntityTicker updates internal stats every 60s LOG.
            // But getTotalEntitiesProcessed is live atomic.
            long currentParallel = ParallelEntityTicker.getTotalEntitiesProcessed();
            long parallelDiff = currentParallel - lastParallelCount;
            parallelEntitiesPerSecond = parallelDiff * 1000.0 / elapsed;

            // "Entities Opt" can be just total processed
            entitiesOptimized.set(currentParallel);

            lastParallelCount = currentParallel;
            lastUpdateTime = now;
        }
    }

    // Public methods for mixins to update stats
    public static void incrementChunksLoaded() {
        chunksLoaded.incrementAndGet();
    }

    public static void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public static void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public static void incrementEntitiesOptimized() {
        entitiesOptimized.incrementAndGet();
    }

    public static void resetStats() {
        chunksLoaded.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        entitiesOptimized.set(0);
    }
}
