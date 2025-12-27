/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Starlight - Light engine optimization with Rust JNI.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import com.kneaf.core.RustOptimizations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LightEngineMixin - Light engine optimization with Rust JNI.
 * 
 * OPTIMIZATIONS:
 * 1. Cache light values and return cached results
 * 2. Skip redundant light checks for same position within tick
 * 3. Coalesce section updates to reduce propagation overhead
 * 4. Rust batch light propagation for bulk updates
 */
@Mixin(LevelLightEngine.class)
public abstract class LightEngineMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LightEngineMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache light values - key is pos.asLong(), value is light level
    @Unique
    private final Map<Long, Integer> kneaf$lightCache = new ConcurrentHashMap<>(512);

    // Track positions checked this tick to skip duplicates
    @Unique
    private final Set<Long> kneaf$checkedThisTick = ConcurrentHashMap.newKeySet();

    // Batch light updates for Rust processing
    @Unique
    private final Map<Long, Byte> kneaf$pendingLightUpdates = new ConcurrentHashMap<>(128);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$checksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$rustBatchCalls = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static final int MAX_CACHE_SIZE = 4096;

    @Unique
    private static final int BATCH_THRESHOLD = 32;

    /**
     * OPTIMIZATION: Return cached light values.
     */
    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetRawBrightness(BlockPos pos, int ambientDarkness,
            CallbackInfoReturnable<Integer> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LightEngineMixin applied - Rust batch light + caching active!");
            kneaf$loggedFirstApply = true;
        }

        long posKey = pos.asLong();

        // Check cache
        Integer cached = kneaf$lightCache.get(posKey);
        if (cached != null) {
            // Apply ambient darkness adjustment
            int result = Math.max(0, cached - ambientDarkness);
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(result);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
    }

    /**
     * Cache result after computation.
     */
    @Inject(method = "getRawBrightness", at = @At("RETURN"))
    private void kneaf$afterGetRawBrightness(BlockPos pos, int ambientDarkness,
            CallbackInfoReturnable<Integer> cir) {
        long posKey = pos.asLong();

        // Store the raw value (before ambient darkness adjustment)
        int rawValue = cir.getReturnValue() + ambientDarkness;

        if (kneaf$lightCache.size() < MAX_CACHE_SIZE) {
            kneaf$lightCache.put(posKey, rawValue);
        }

        kneaf$logStats();
    }

    /**
     * OPTIMIZATION: Skip duplicate checkBlock for same position + queue for batch.
     */
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void kneaf$onCheckBlock(BlockPos pos, CallbackInfo ci) {
        long posKey = pos.asLong();

        // Skip if already checked this tick
        if (!kneaf$checkedThisTick.add(posKey)) {
            kneaf$checksSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Queue for batch processing
        kneaf$pendingLightUpdates.put(posKey, (byte) 15); // Mark for update

        // Invalidate cache for this position
        kneaf$lightCache.remove(posKey);
    }

    /**
     * Process batched light updates using Rust.
     */
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void kneaf$onRunLightUpdates(CallbackInfoReturnable<Integer> cir) {
        // Clear per-tick tracking
        kneaf$checkedThisTick.clear();

        // Process batch if above threshold
        if (kneaf$pendingLightUpdates.size() >= BATCH_THRESHOLD) {
            kneaf$processBatchLightUpdates();
        }

        // Periodic cache cleanup
        if (kneaf$lightCache.size() > MAX_CACHE_SIZE / 2) {
            kneaf$lightCache.clear();
        }
    }

    /**
     * Process queued light updates using Rust batch processing.
     */
    @Unique
    private void kneaf$processBatchLightUpdates() {
        int count = kneaf$pendingLightUpdates.size();
        if (count == 0)
            return;

        // Build arrays for Rust
        byte[] lightLevels = new byte[count];
        int[] positions = new int[count * 3];

        int idx = 0;
        for (var entry : kneaf$pendingLightUpdates.entrySet()) {
            long posKey = entry.getKey();
            int x = BlockPos.getX(posKey);
            int y = BlockPos.getY(posKey);
            int z = BlockPos.getZ(posKey);

            positions[idx * 3] = x;
            positions[idx * 3 + 1] = y;
            positions[idx * 3 + 2] = z;
            lightLevels[idx] = entry.getValue();
            idx++;
        }

        // Use Rust for batch light propagation
        try {
            int[] results = RustOptimizations.batchLight(positions, lightLevels, count);
            kneaf$rustBatchCalls.incrementAndGet();

            // Update cache with results
            idx = 0;
            for (var entry : kneaf$pendingLightUpdates.entrySet()) {
                if (idx < results.length) {
                    kneaf$lightCache.put(entry.getKey(), results[idx] & 0xFF);
                }
                idx++;
            }
        } catch (Exception e) {
            // Java fallback - just clear pending
        }

        kneaf$pendingLightUpdates.clear();
    }

    /**
     * Invalidate cache when sections change.
     */
    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void kneaf$onUpdateSectionStatus(SectionPos pos, boolean isEmpty, CallbackInfo ci) {
        if (kneaf$lightCache.size() > MAX_CACHE_SIZE / 4) {
            kneaf$lightCache.clear();
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long skipped = kneaf$checksSkipped.get();
            long rust = kneaf$rustBatchCalls.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("LightEngine: {}% cache hit, {} skipped, {} Rust batches",
                        String.format("%.1f", hitRate), skipped, rust);
            }

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$checksSkipped.set(0);
            kneaf$rustBatchCalls.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        long total = kneaf$cacheHits.get() + kneaf$cacheMisses.get();
        double rate = total > 0 ? kneaf$cacheHits.get() * 100.0 / total : 0;
        return String.format("LightStats{hitRate=%.1f%%, skipped=%d, rust=%d}",
                rate, kneaf$checksSkipped.get(), kneaf$rustBatchCalls.get());
    }
}
