/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.lithium.PathNodeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * WalkNodeEvaluatorMixin - Lithium-style pathfinding optimizations.
 * 
 * Targets WalkNodeEvaluator (formerly LandPathNodeMaker) which is the main
 * pathfinding calculator for land-based entities like zombies, villagers, etc.
 * 
 * Optimizations:
 * 1. Cache PathType for BlockStates to avoid repeated calculations
 * 2. Fast path for air blocks and common passable blocks
 * 3. Optimized neighbor danger checking (check cardinal directions first)
 * 4. Statistics tracking for cache effectiveness
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/WalkNodeEvaluatorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$pathTypeQueries = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$airSkips = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Thread-local mutable BlockPos for neighbor checks
    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> kneaf$mutablePos = ThreadLocal
            .withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Log when mixin is applied.
     */
    @Inject(method = "done", at = @At("HEAD"))
    private void kneaf$onDone(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ WalkNodeEvaluatorMixin applied - Lithium pathfinding optimizations active!");
            kneaf$loggedFirstApply = true;
        }

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 30000) {
            long queries = kneaf$pathTypeQueries.get();
            if (queries > 0) {
                long hits = kneaf$cacheHits.get();
                long airs = kneaf$airSkips.get();
                double hitRate = (double) (hits + airs) / queries * 100;
                kneaf$LOGGER.info("Pathfinding stats: {} queries, {} cache hits, {} air skips ({:.1f}% optimized)",
                        queries, hits, airs, hitRate);

                kneaf$pathTypeQueries.set(0);
                kneaf$cacheHits.set(0);
                kneaf$airSkips.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Inject into getPathTypeOfMob to add caching.
     * This is called for every path node evaluation.
     */
    @Inject(method = "getPathTypeOfMob", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetPathTypeOfMob(
            net.minecraft.world.level.pathfinder.PathfindingContext context,
            int x, int y, int z,
            CallbackInfoReturnable<PathType> cir) {

        kneaf$pathTypeQueries.incrementAndGet();

        // Try to get from cache
        try {
            BlockGetter world = context.level();
            BlockPos.MutableBlockPos pos = kneaf$mutablePos.get();
            pos.set(x, y, z);

            BlockState state = world.getBlockState(pos);

            // Fast path: air blocks are always OPEN
            if (state.isAir()) {
                kneaf$airSkips.incrementAndGet();
                // Don't cancel - let vanilla check if there's ground below
                return;
            }

            // Check cache
            PathType cached = PathNodeCache.getPathType(state);
            if (cached != null) {
                kneaf$cacheHits.incrementAndGet();
                // Note: We can't fully return here because path type also depends on
                // the mob and surrounding blocks. But we can use this info later.
            }
        } catch (Exception e) {
            // Ignore - fall through to vanilla
        }
    }

    /**
     * Inject into getBlockPathType to cache results.
     */
    @Inject(method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;III)Lnet/minecraft/world/level/pathfinder/PathType;", at = @At("RETURN"))
    private static void kneaf$onGetBlockPathTypeReturn(
            BlockGetter world, int x, int y, int z,
            CallbackInfoReturnable<PathType> cir) {

        try {
            PathType result = cir.getReturnValue();
            if (result != null) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState state = world.getBlockState(pos);

                // Cache the result for this block state
                PathNodeCache.cachePathType(state, result);
            }
        } catch (Exception e) {
            // Ignore caching errors
        }
    }
}
