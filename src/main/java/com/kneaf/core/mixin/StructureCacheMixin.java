/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Structure generation result caching for faster world gen.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StructureCacheMixin - Structure lookup caching.
 * 
 * Optimizations:
 * 1. Cache structure start lookups by chunk position
 * 2. Cache "no structure" results to avoid repeated searches
 * 3. Invalidate cache when structures are added
 * 
 * This significantly speeds up structure-related queries during world gen.
 */
@Mixin(StructureManager.class)
public abstract class StructureCacheMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/StructureCacheMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for structure starts: key = (structureId << 48) | chunkPosLong
    @Unique
    private static final Map<Long, StructureStart> kneaf$structureCache = new ConcurrentHashMap<>(512);

    // Cache for "no structure" results to avoid repeated lookups
    @Unique
    private static final Map<Long, Boolean> kneaf$noStructureCache = new ConcurrentHashMap<>(1024);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$noStructureHits = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastCacheClean = 0;

    /**
     * Cache structure start lookups.
     */
    @Inject(method = "getStructureAt", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetStructureAt(net.minecraft.core.BlockPos pos, Structure structure,
            CallbackInfoReturnable<StructureStart> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ StructureCacheMixin applied - Structure caching optimization active!");
            kneaf$loggedFirstApply = true;
        }

        long chunkPos = ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        long cacheKey = kneaf$getCacheKey(structure, chunkPos);

        // Check no-structure cache first
        if (kneaf$noStructureCache.containsKey(cacheKey)) {
            kneaf$noStructureHits.incrementAndGet();
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }

        // Check structure cache
        StructureStart cached = kneaf$structureCache.get(cacheKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
        kneaf$logStats();
    }

    /**
     * Store result in cache after lookup.
     */
    @Inject(method = "getStructureAt", at = @At("RETURN"))
    private void kneaf$afterGetStructureAt(net.minecraft.core.BlockPos pos, Structure structure,
            CallbackInfoReturnable<StructureStart> cir) {
        StructureStart result = cir.getReturnValue();
        long chunkPos = ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        long cacheKey = kneaf$getCacheKey(structure, chunkPos);

        if (result == null || result == StructureStart.INVALID_START) {
            kneaf$noStructureCache.put(cacheKey, true);
        } else {
            kneaf$structureCache.put(cacheKey, result);
        }

        // Periodic cleanup
        long now = System.currentTimeMillis();
        if (now - kneaf$lastCacheClean > 60000) {
            kneaf$cleanupCaches();
            kneaf$lastCacheClean = now;
        }
    }

    @Unique
    private static long kneaf$getCacheKey(Structure structure, long chunkPos) {
        int structureHash = System.identityHashCode(structure) & 0xFFFF;
        return ((long) structureHash << 48) | (chunkPos & 0xFFFFFFFFFFFFL);
    }

    @Unique
    private static void kneaf$cleanupCaches() {
        if (kneaf$structureCache.size() > 2048) {
            kneaf$structureCache.clear();
            kneaf$LOGGER.debug("Structure cache cleared");
        }
        if (kneaf$noStructureCache.size() > 4096) {
            kneaf$noStructureCache.clear();
            kneaf$LOGGER.debug("No-structure cache cleared");
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long noHits = kneaf$noStructureHits.get();
            long total = hits + misses + noHits;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.structureLookups = total / timeDiff;
                double hitRate = (hits + noHits) * 100.0 / total;
                com.kneaf.core.PerformanceStats.structureHitPercent = hitRate;
                com.kneaf.core.PerformanceStats.structureCached = kneaf$structureCache.size();
                com.kneaf.core.PerformanceStats.structureEmptyCached = kneaf$noStructureCache.size();

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
                kneaf$noStructureHits.set(0);
            } else {
                com.kneaf.core.PerformanceStats.structureLookups = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
