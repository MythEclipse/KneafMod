/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Chunk storage I/O optimization for reduced lag spikes.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkStorageMixin - Chunk I/O optimization.
 * 
 * Optimizations:
 * 1. Track chunk save/load performance
 * 2. Monitor I/O batch sizes
 * 3. Collect I/O statistics for optimization tuning
 * 
 * This provides metrics for chunk I/O operations which can help
 * identify performance issues and lag spikes from disk operations.
 */
@Mixin(ChunkStorage.class)
public abstract class ChunkStorageMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkStorageMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$chunksLoaded = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$chunksSaved = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$totalLoadTimeMs = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$totalSaveTimeMs = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Per-operation timing
    @Unique
    private final ThreadLocal<Long> kneaf$operationStartTime = new ThreadLocal<>();

    /**
     * Track chunk load start.
     */
    @Inject(method = "read", at = @At("HEAD"))
    private void kneaf$onReadStart(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkStorageMixin applied - Chunk I/O monitoring active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$operationStartTime.set(System.currentTimeMillis());
    }

    /**
     * Track chunk load completion.
     */
    @Inject(method = "read", at = @At("RETURN"))
    private void kneaf$onReadEnd(CallbackInfo ci) {
        Long startTime = kneaf$operationStartTime.get();
        if (startTime != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            kneaf$chunksLoaded.incrementAndGet();
            kneaf$totalLoadTimeMs.addAndGet(elapsed);
            kneaf$operationStartTime.remove();

            // Log slow loads
            if (elapsed > 100) {
                kneaf$LOGGER.warn("Slow chunk load: {}ms", elapsed);
            }
        }

        kneaf$logStats();
    }

    /**
     * Track chunk save start.
     */
    @Inject(method = "write", at = @At("HEAD"))
    private void kneaf$onWriteStart(CallbackInfo ci) {
        kneaf$operationStartTime.set(System.currentTimeMillis());
    }

    /**
     * Track chunk save completion.
     */
    @Inject(method = "write", at = @At("RETURN"))
    private void kneaf$onWriteEnd(CallbackInfo ci) {
        Long startTime = kneaf$operationStartTime.get();
        if (startTime != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            kneaf$chunksSaved.incrementAndGet();
            kneaf$totalSaveTimeMs.addAndGet(elapsed);
            kneaf$operationStartTime.remove();

            // Log slow saves
            if (elapsed > 100) {
                kneaf$LOGGER.warn("Slow chunk save: {}ms", elapsed);
            }
        }

        kneaf$logStats();
    }

    /**
     * Log statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long loaded = kneaf$chunksLoaded.get();
            long saved = kneaf$chunksSaved.get();
            long loadTime = kneaf$totalLoadTimeMs.get();
            long saveTime = kneaf$totalSaveTimeMs.get();

            if (loaded > 0 || saved > 0) {
                double avgLoadMs = loaded > 0 ? (double) loadTime / loaded : 0;
                double avgSaveMs = saved > 0 ? (double) saveTime / saved : 0;

                kneaf$LOGGER.info("Chunk I/O: {} loaded (avg {}ms), {} saved (avg {}ms)",
                        loaded, String.format("%.1f", avgLoadMs),
                        saved, String.format("%.1f", avgSaveMs));
            }

            kneaf$chunksLoaded.set(0);
            kneaf$chunksSaved.set(0);
            kneaf$totalLoadTimeMs.set(0);
            kneaf$totalSaveTimeMs.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long loaded = kneaf$chunksLoaded.get();
        long saved = kneaf$chunksSaved.get();
        long loadTime = kneaf$totalLoadTimeMs.get();
        long saveTime = kneaf$totalSaveTimeMs.get();

        double avgLoadMs = loaded > 0 ? (double) loadTime / loaded : 0;
        double avgSaveMs = saved > 0 ? (double) saveTime / saved : 0;

        return String.format(
                "ChunkIOStats{loaded=%d (avg %.1fms), saved=%d (avg %.1fms)}",
                loaded, avgLoadMs, saved, avgSaveMs);
    }
}
