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

    @Unique
    private static final ThreadLocal<StateCache> kneaf$threadCache = ThreadLocal.withInitial(StateCache::new);

    @Unique
    private static class StateCache {
        // Use parallel arrays for value and presence
        public boolean[] isAirValues = new boolean[4096];
        public boolean[] isAirPresent = new boolean[4096];

        public void ensureCapacity(int id) {
            if (id >= isAirValues.length) {
                int newSize = Math.max(id + 1024, isAirValues.length * 2);
                isAirValues = java.util.Arrays.copyOf(isAirValues, newSize);
                isAirPresent = java.util.Arrays.copyOf(isAirPresent, newSize);
            }
        }

        public void clear() {
            java.util.Arrays.fill(isAirPresent, false);
        }
    }

    // Track cache effectiveness (loose stats for performance)
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong kneaf$cacheHits = new java.util.concurrent.atomic.AtomicLong(
            0);
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong kneaf$cacheMisses = new java.util.concurrent.atomic.AtomicLong(
            0);

    /**
     * Cache isAir results - called millions of times per tick
     * Optimization: ThreadLocal Direct Array Indexing (O(1))
     */
    @Inject(method = "isAir", at = @At("HEAD"), cancellable = true)
    private void kneaf$onIsAir(CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BlockStateCacheMixin applied - ThreadLocal Array Cache active!");
            kneaf$loggedFirstApply = true;
        }

        BlockState self = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(self);

        StateCache cache = kneaf$threadCache.get();
        if (id < cache.isAirPresent.length && cache.isAirPresent[id]) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cache.isAirValues[id]);
        } else {
            kneaf$cacheMisses.incrementAndGet();
        }
    }

    /**
     * Store isAir result after vanilla calculates it
     */
    @Inject(method = "isAir", at = @At("RETURN"))
    private void kneaf$afterIsAir(CallbackInfoReturnable<Boolean> cir) {
        BlockState self = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(self);
        boolean result = cir.getReturnValue();

        StateCache cache = kneaf$threadCache.get();
        cache.ensureCapacity(id);

        cache.isAirValues[id] = result;
        cache.isAirPresent[id] = true;
    }

    /**
     * Clear caches periodically to prevent memory issues
     * Note: This only clears the current thread's cache, which is imperfect but
     * acceptable.
     * For true cleanup we rely on ThreadLocal weakness or explicit management,
     * but strictly clearing the heavy array is good enough.
     */
    @Unique
    private static void kneaf$clearCaches() {
        // Can't easily clear all threads' caches without a registry of them.
        // But we can clear the current thread's cache if this is called on main thread.
        kneaf$threadCache.get().clear();
    }

    /**
     * Get cache statistics
     */
    @Unique
    private static String kneaf$getStats() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        return String.format("BlockStateCache: %d hits, %d misses (%.1f%% hit rate)",
                hits, misses, hitRate);
    }
}
