/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * DataFixer caching for improved world loading performance.
 */
package com.kneaf.core.mixin;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
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
 * DataFixerMixin - DataFixer result caching.
 * 
 * Optimizations:
 * 1. Cache DataFixer results for common data structures
 * 2. Skip redundant fixer passes
 * 3. Track cache statistics
 * 
 * DataFixer is used for backward compatibility when loading worlds,
 * and can be slow. Caching results improves chunk loading performance.
 */
@Mixin(value = DataFixer.class, remap = false)
public abstract class DataFixerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/DataFixerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for fixer results
    // Key: hash of (typeRef + inputVersion + data hashcode)
    // Value: fixed data
    @Unique
    private static final Map<Long, Dynamic<?>> kneaf$fixerCache = new ConcurrentHashMap<>(1024);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$fixerCalls = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration
    @Unique
    private static final int MAX_CACHE_SIZE = 4096;

    /**
     * Cache DataFixer update results.
     */
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdate(DSL.TypeReference type, Dynamic<?> input, int version, int newVersion,
                                CallbackInfoReturnable<Dynamic<?>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ DataFixerMixin applied - DataFixer caching optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$fixerCalls.incrementAndGet();

        // Skip caching if already at latest version
        if (version >= newVersion) {
            return;
        }

        // Create cache key
        long cacheKey = kneaf$createCacheKey(type, version, newVersion, input);
        
        // Check cache
        Dynamic<?> cached = kneaf$fixerCache.get(cacheKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();

        // Periodic stats logging
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long calls = kneaf$fixerCalls.get();
            long total = hits + misses;

            if (total > 0 && calls > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("DataFixer: {} calls, {}% cache hit rate ({} hits, {} misses)",
                        calls, String.format("%.1f", hitRate), hits, misses);
            }

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$fixerCalls.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Store result in cache after fixing.
     */
    @Inject(method = "update", at = @At("RETURN"))
    private void kneaf$afterUpdate(DSL.TypeReference type, Dynamic<?> input, int version, int newVersion,
                                   CallbackInfoReturnable<Dynamic<?>> cir) {
        if (version >= newVersion) {
            return;
        }

        // Cache the result
        long cacheKey = kneaf$createCacheKey(type, version, newVersion, input);
        Dynamic<?> result = cir.getReturnValue();
        
        if (result != null) {
            kneaf$fixerCache.put(cacheKey, result);
        }

        // Limit cache size
        if (kneaf$fixerCache.size() > MAX_CACHE_SIZE) {
            kneaf$cleanupCache();
        }
    }

    /**
     * Create cache key from fixer parameters.
     */
    @Unique
    private long kneaf$createCacheKey(DSL.TypeReference type, int version, int newVersion, Dynamic<?> input) {
        long hash = type.typeName().hashCode();
        hash = hash * 31 + version;
        hash = hash * 31 + newVersion;
        hash = hash * 31 + input.getValue().hashCode();
        return hash;
    }

    /**
     * Cleanup cache when it gets too large.
     */
    @Unique
    private static void kneaf$cleanupCache() {
        // Simple strategy: remove half the entries
        int toRemove = kneaf$fixerCache.size() / 2;
        var iterator = kneaf$fixerCache.entrySet().iterator();
        while (iterator.hasNext() && toRemove > 0) {
            iterator.next();
            iterator.remove();
            toRemove--;
        }
        kneaf$LOGGER.debug("DataFixer cache pruned to {} entries", kneaf$fixerCache.size());
    }

    /**
     * Get statistics.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (hits * 100.0 / total) : 0;

        return String.format(
                "DataFixerStats{calls=%d, cacheHitRate=%.1f%%, cacheSize=%d}",
                kneaf$fixerCalls.get(), hitRate, kneaf$fixerCache.size());
    }
}
