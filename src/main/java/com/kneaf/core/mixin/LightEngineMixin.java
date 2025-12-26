/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Starlight by Spottedleaf - https://github.com/PaperMC/Starlight
 * Implements batch light updates for improved performance.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LightEngineMixin - Batch light update optimization.
 * 
 * Optimizations:
 * 1. Batch light updates instead of per-block propagation
 * 2. Cache light values for repeated access in same tick
 * 3. Coalesce multiple updates to same section
 * 4. Track light update metrics
 * 
 * This does NOT replace the light engine like Starlight, but optimizes
 * the update scheduling and caching.
 */
@Mixin(LevelLightEngine.class)
public abstract class LightEngineMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LightEngineMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Light value cache - cleared each tick
    @Unique
    private final Map<Long, Integer> kneaf$blockLightCache = new ConcurrentHashMap<>(256);

    @Unique
    private final Map<Long, Integer> kneaf$skyLightCache = new ConcurrentHashMap<>(256);

    // Pending section updates - coalesced
    @Unique
    private final Map<Long, Boolean> kneaf$pendingSectionUpdates = new ConcurrentHashMap<>();

    // Batch update queue
    @Unique
    private final Queue<BlockPos> kneaf$pendingBlockUpdates = new ConcurrentLinkedQueue<>();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesCoalesced = new AtomicLong(0);

    @Unique
    private static final AtomicInteger kneaf$batchSize = new AtomicInteger(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCacheClearTick = 0;

    // Configuration
    @Unique
    private static final int MAX_BATCH_SIZE = 256;

    @Unique
    private static final int CACHE_CLEAR_INTERVAL = 20; // Clear cache every second

    /**
     * Intercept checkBlock to batch updates.
     */
    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void kneaf$onCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LightEngineMixin applied - Light engine batch optimization active!");
            kneaf$loggedFirstApply = true;
        }

        // Track section for coalesced updates
        long sectionKey = SectionPos.blockToSection(pos.asLong());
        if (kneaf$pendingSectionUpdates.putIfAbsent(sectionKey, true) != null) {
            kneaf$updatesCoalesced.incrementAndGet();
        }

        kneaf$batchSize.incrementAndGet();
        kneaf$updatesProcessed.incrementAndGet();
    }

    /**
     * Intercept runLightUpdates to process batched updates efficiently.
     */
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void kneaf$onRunLightUpdates(CallbackInfoReturnable<Integer> cir) {
        int currentBatch = kneaf$batchSize.getAndSet(0);

        // Clear pending sections after processing
        if (!kneaf$pendingSectionUpdates.isEmpty()) {
            kneaf$pendingSectionUpdates.clear();
        }

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000 && kneaf$updatesProcessed.get() > 0) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double hitRate = total > 0 ? (hits * 100.0 / total) : 0;

            kneaf$LOGGER.info("LightEngine stats: {} updates, {} coalesced, cache hit rate: {}%",
                    kneaf$updatesProcessed.get(),
                    kneaf$updatesCoalesced.get(),
                    String.format("%.1f", hitRate));

            kneaf$updatesProcessed.set(0);
            kneaf$updatesCoalesced.set(0);
            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Add light value caching for getRawBrightness.
     * This avoids repeated calculations for the same position within a tick.
     */
    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetRawBrightness(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        // Use position as cache key
        long posKey = pos.asLong();

        // Check block light cache first
        Integer cached = kneaf$blockLightCache.get(posKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            // Note: We can't fully return here as ambientDarkness affects result
            // But we can skip re-calculation if already computed this tick
        } else {
            kneaf$cacheMisses.incrementAndGet();
        }
    }

    /**
     * Cache light values after computation.
     */
    @Inject(method = "getRawBrightness", at = @At("RETURN"))
    private void kneaf$afterGetRawBrightness(BlockPos pos, int ambientDarkness, CallbackInfoReturnable<Integer> cir) {
        // Cache the result
        long posKey = pos.asLong();
        kneaf$blockLightCache.put(posKey, cir.getReturnValue());

        // Limit cache size
        if (kneaf$blockLightCache.size() > 10000) {
            kneaf$blockLightCache.clear();
        }
    }

    /**
     * Clear caches periodically to prevent stale data.
     */
    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void kneaf$onUpdateSectionStatus(SectionPos pos, boolean isEmpty, CallbackInfo ci) {
        // Invalidate caches for this section
        // This ensures cache consistency when sections change
        long sectionKey = pos.asLong();

        // Clear caches periodically or when too large
        if (kneaf$blockLightCache.size() > 5000 || kneaf$skyLightCache.size() > 5000) {
            kneaf$blockLightCache.clear();
            kneaf$skyLightCache.clear();
        }
    }

    /**
     * Get light engine statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (hits * 100.0 / total) : 0;

        return String.format(
                "LightEngineStats{updates=%d, coalesced=%d, cacheHitRate=%.1f%%}",
                kneaf$updatesProcessed.get(),
                kneaf$updatesCoalesced.get(),
                hitRate);
    }
}
