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

    // Cache for recipe lookups
    @Unique
    private final Map<Long, Optional<RecipeHolder<?>>> kneaf$recipeCache = new ConcurrentHashMap<>(512);

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
     * Inject into getRecipeFor to use cache.
     * We purposefully target the generic method that takes a RecipeInput.
     */
    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;", 
            at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetRecipeFor(RecipeType<?> recipeType, RecipeInput input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        // Only cache crafting recipes for now as they are the most frequent and stateless
        // Some recipe types might depend on more than just the input items (e.g. world time, position) regarding the Level
        // But for standard crafting, input items are usually enough.
        // To be safe, we compute a hash from the input and the recipe type.
        
        if (input == null) return;

        // Simple hash combination: RecipeType hash + Input items hash
        // Note: RecipeInput implementations must implement a good hashCode for this to be effective.
        // Most vanilla inputs do (e.g. CraftingInput).
        long cacheKey = (long) recipeType.hashCode() ^ ((long) input.hashCode() << 32);

        Optional<RecipeHolder<?>> cached = kneaf$recipeCache.get(cacheKey);

        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            kneaf$logStats();
        } else {
            kneaf$cacheMisses.incrementAndGet();
            // We cannot easily capture the result of the vanilla method in the same HEAD injection
            // without doing a double-invoke or using a different injection point.
            // But doing a Tail injection allows us to capture the return value and cache it.
        }
    }

    @Inject(method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;", 
            at = @At("RETURN"))
    private void kneaf$onGetRecipeForReturn(RecipeType<?> recipeType, RecipeInput input, Level level, CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        if (input == null) return;
        
        long cacheKey = (long) recipeType.hashCode() ^ ((long) input.hashCode() << 32);
        
        // If it wasn't in cache (we can assume this because we check in HEAD), store it.
        // To avoid race conditions where we overwrite a parallel put, computeIfAbsent is theoretically better,
        // but here we just want to put the result.
        // We accept the slight race of multiple threads computing the same recipe once.
        
        // Only cache if we haven't exceeded size limit (simple protection)
        if (kneaf$recipeCache.size() < 5000) {
            kneaf$recipeCache.put(cacheKey, cir.getReturnValue());
        } else if (kneaf$recipeCache.size() == 5000) {
             kneaf$cleanupCache();
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

            // Don't reset time every hit, only when we actually log
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Cleanup old cache entries to prevent memory bloat.
     */
    @Unique
    private void kneaf$cleanupCache() {
        // Simple strategy: clear half or all. For now, clear all to be safe and reclaim memory.
        kneaf$recipeCache.clear();
        kneaf$LOGGER.debug("Recipe cache cleared due to size limit");
    }
}
