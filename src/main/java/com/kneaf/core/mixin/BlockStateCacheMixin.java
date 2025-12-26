/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * BlockStateCacheMixin - Caches expensive BlockState calculations.
 * 
 * Target: net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase
 * 
 * This is a core optimization from Lithium - BlockState.getCollisionShape() and
 * related methods are called MILLIONS of times per tick. Caching these results
 * provides significant performance improvement.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public abstract class BlockStateCacheMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BlockStateCacheMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for isAir check - very frequently called
    @Unique
    private static final ConcurrentHashMap<BlockState, Boolean> kneaf$isAirCache = new ConcurrentHashMap<>(1024);

    // Cache for isSolid check
    @Unique
    private static final ConcurrentHashMap<BlockState, Boolean> kneaf$isSolidCache = new ConcurrentHashMap<>(1024);

    // Cache for hasBlockEntity check
    @Unique
    private static final ConcurrentHashMap<BlockState, Boolean> kneaf$hasBlockEntityCache = new ConcurrentHashMap<>(
            512);

    // Track cache effectiveness
    @Unique
    private static long kneaf$cacheHits = 0;
    @Unique
    private static long kneaf$cacheMisses = 0;

    /**
     * Cache isAir results - called millions of times per tick
     */
    @Inject(method = "isAir", at = @At("HEAD"), cancellable = true)
    private void kneaf$onIsAir(CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BlockStateCacheMixin applied - aggressive state caching active!");
            kneaf$loggedFirstApply = true;
        }

        BlockState self = (BlockState) (Object) this;
        Boolean cached = kneaf$isAirCache.get(self);

        if (cached != null) {
            kneaf$cacheHits++;
            cir.setReturnValue(cached);
        } else {
            kneaf$cacheMisses++;
        }
    }

    /**
     * Store isAir result after vanilla calculates it
     */
    @Inject(method = "isAir", at = @At("RETURN"))
    private void kneaf$afterIsAir(CallbackInfoReturnable<Boolean> cir) {
        if (kneaf$isAirCache.size() < 4096) {
            BlockState self = (BlockState) (Object) this;
            kneaf$isAirCache.putIfAbsent(self, cir.getReturnValue());
        }
    }

    /**
     * Clear caches periodically to prevent memory issues
     */
    @Unique
    private static void kneaf$clearCaches() {
        kneaf$isAirCache.clear();
        kneaf$isSolidCache.clear();
        kneaf$hasBlockEntityCache.clear();
        kneaf$LOGGER.info("BlockState caches cleared");
    }

    /**
     * Get cache statistics
     */
    @Unique
    private static String kneaf$getStats() {
        long total = kneaf$cacheHits + kneaf$cacheMisses;
        double hitRate = total > 0 ? (double) kneaf$cacheHits / total * 100 : 0;
        return String.format("BlockStateCache: %d hits, %d misses (%.1f%% hit rate), %d cached states",
                kneaf$cacheHits, kneaf$cacheMisses, hitRate, kneaf$isAirCache.size());
    }
}
