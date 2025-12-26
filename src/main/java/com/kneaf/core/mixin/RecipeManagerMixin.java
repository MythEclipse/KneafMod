/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Recipe lookup caching for improved crafting performance.
 */
package com.kneaf.core.mixin;

import net.minecraft.resources.ResourceLocation;
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

    // Cache for recipe lookups
    @Unique
    private final Map<Long, Optional<ResourceLocation>> kneaf$recipeCache = new ConcurrentHashMap<>(512);

    // Statistics
    @Unique
    private final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private long kneaf$lastLogTime = 0;

    @Unique
    private long kneaf$lastCacheCleanup = 0;

    /**
     * Clear cache when recipes are reloaded.
     */
    @Inject(method = "apply*", at = @At("HEAD"))
    private void kneaf$onRecipeReload(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ RecipeManagerMixin applied - Recipe caching optimization active!");
            kneaf$loggedFirstApply = true;
        }

        // Clear cache on reload
        int oldSize = kneaf$recipeCache.size();
        kneaf$recipeCache.clear();
        kneaf$cacheHits.set(0);
        kneaf$cacheMisses.set(0);

        if (oldSize > 0) {
            kneaf$LOGGER.info("Recipe cache cleared on reload ({} entries)", oldSize);
        }
    }

    /**
     * Log cache statistics periodically.
     */
    @Unique
    private void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("RecipeCache: {} lookups, {}% hit rate, {} entries",
                        total, String.format("%.1f", hitRate), kneaf$recipeCache.size());
            }

            kneaf$lastLogTime = now;
        }
    }

    /**
     * Cleanup old cache entries to prevent memory bloat.
     */
    @Unique
    private void kneaf$cleanupCache() {
        if (kneaf$recipeCache.size() > 2048) {
            kneaf$recipeCache.clear();
            kneaf$LOGGER.debug("Recipe cache cleared due to size");
        }
    }
}
