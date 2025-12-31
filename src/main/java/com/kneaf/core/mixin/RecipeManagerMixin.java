/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Recipe lookup caching for improved crafting performance.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RecipeManagerMixin - Recipe lookup caching.
 * 
 * Optimizations:
 * 1. Cache recipe lookups by input hash
 * 2. Skip redundant recipe searches
 * 3. Clear cache on recipe reload
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/RecipeManagerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for recipe lookups - usage StampedLock for best read performance
    @Unique
    private final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<Optional<RecipeHolder<?>>> kneaf$recipeCache = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            2048);

    @Unique
    private final java.util.concurrent.locks.StampedLock kneaf$cacheLock = new java.util.concurrent.locks.StampedLock();

    // Statistics
    @Unique
    private final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private long kneaf$lastLogTime = 0;

    /**
     * Clear cache when recipes are reloaded.
     */
    @Inject(method = "apply*", at = @At("HEAD"))
    private void kneaf$onRecipeReload(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ RecipeManagerMixin applied - Primitive Map Recipe Cache active!");
            kneaf$loggedFirstApply = true;
        }

        // Clear cache on reload
        long stamp = kneaf$cacheLock.writeLock();
        int oldSize = 0;
        try {
            oldSize = kneaf$recipeCache.size();
            kneaf$recipeCache.clear();
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }

        kneaf$cacheHits.set(0);
        kneaf$cacheMisses.set(0);

        if (oldSize > 0) {
            kneaf$LOGGER.info("Recipe cache cleared on reload ({} entries)", oldSize);
        }
    }

    /**
     * Inject into getRecipeFor to use cache.
     * We purposefully target the generic method that takes a RecipeInput.
     */
    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetRecipeFor(RecipeType<?> recipeType, RecipeInput input, Level level,
            CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {

        if (input == null)
            return;

        long cacheKey = (long) recipeType.hashCode() ^ ((long) input.hashCode() << 32);

        Optional<RecipeHolder<?>> cached = null;

        // Optimistic read
        long stamp = kneaf$cacheLock.tryOptimisticRead();
        cached = kneaf$recipeCache.get(cacheKey);

        if (!kneaf$cacheLock.validate(stamp)) {
            // Fallback to read lock
            stamp = kneaf$cacheLock.readLock();
            try {
                cached = kneaf$recipeCache.get(cacheKey);
            } finally {
                kneaf$cacheLock.unlockRead(stamp);
            }
        }

        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            kneaf$logStats();
        } else {
            kneaf$cacheMisses.incrementAndGet();
        }
    }

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;", at = @At("RETURN"))
    private void kneaf$onGetRecipeForReturn(RecipeType<?> recipeType, RecipeInput input, Level level,
            CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        if (input == null)
            return;

        long cacheKey = (long) recipeType.hashCode() ^ ((long) input.hashCode() << 32);

        // Store result
        long stamp = kneaf$cacheLock.writeLock();
        try {
            // Limit size to prevent unbounded growth
            if (kneaf$recipeCache.size() < 5000) {
                kneaf$recipeCache.put(cacheKey, cir.getReturnValue());
            } else if (kneaf$recipeCache.size() >= 5000 && (kneaf$recipeCache.size() % 100 == 0)) {
                // Simple loose check to clear occasionally if full
                if (kneaf$recipeCache.size() > 6000) {
                    kneaf$recipeCache.clear();
                }
            }
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }
    }

    /**
     * Log cache statistics periodically.
     */
    @Unique
    private void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.recipeLookups = total / timeDiff;
                double hitRate = hits * 100.0 / total;
                com.kneaf.core.PerformanceStats.recipeHitPercent = hitRate;
                com.kneaf.core.PerformanceStats.recipeCacheSize = kneaf$recipeCache.size();

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
            } else {
                com.kneaf.core.PerformanceStats.recipeLookups = 0;
            }

            // Don't reset time every hit, only when we actually log
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Cleanup old cache entries to prevent memory bloat.
     */
    @Unique
    private void kneaf$cleanupCache() {
        // Simple strategy: clear half or all. For now, clear all to be safe and reclaim
        // memory.
        kneaf$recipeCache.clear();
        kneaf$LOGGER.debug("Recipe cache cleared due to size limit");
    }
}
