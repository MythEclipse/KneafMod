/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * F3 Debug Overlay - Shows KneafMod performance stats in debug screen.
 */
package com.kneaf.core.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kneaf.core.util.TPSTracker;
import com.kneaf.core.ParallelEntityTicker;

import java.util.ArrayList;
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
    private static long lastUpdateTime = System.currentTimeMillis();
    private static long lastChunkCount = 0;

    /**
     * Add KneafMod debug info to F3 screen.
     */
    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!loggedFirstApply) {
            LOGGER.info("✅ DebugOverlayHandler registered - F3 stats active!");
            loggedFirstApply = true;
        }

        Minecraft mc = Minecraft.getInstance();

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
        String parallelStats = ParallelEntityTicker.getStatistics();
        if (parallelStats != null && !parallelStats.isEmpty()) {
            rightText.add(String.format("Parallel: §a%s§r", extractParallelCount(parallelStats)));
        }
    }

    /**
     * Update rate calculations.
     */
    private static void updateRates() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUpdateTime;

        if (elapsed >= 1000) { // Update every second
            long currentChunks = chunksLoaded.get();
            long chunksDiff = currentChunks - lastChunkCount;

            chunksPerSecond = chunksDiff * 1000.0 / elapsed;

            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            long total = hits + misses;
            cacheHitRate = total > 0 ? (hits * 100.0 / total) : 0;

            lastChunkCount = currentChunks;
            lastUpdateTime = now;
        }
    }

    /**
     * Extract parallel entity count from stats string.
     */
    private static String extractParallelCount(String stats) {
        // ParallelEntity{entities=123, batches=4, ...}
        int start = stats.indexOf("entities=");
        if (start >= 0) {
            int end = stats.indexOf(",", start);
            if (end >= 0) {
                return stats.substring(start + 9, end);
            }
        }
        return "N/A";
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
        lastChunkCount = 0;
    }
}
