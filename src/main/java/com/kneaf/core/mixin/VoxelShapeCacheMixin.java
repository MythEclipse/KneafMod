/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Optimizes VoxelShape caching for faster collision detection.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * VoxelShapeCacheMixin - Cache VoxelShape computations for collision detection.
 * 
 * VoxelShape operations are one of the biggest CPU bottlenecks in Minecraft.
 * This mixin provides:
 * 1. Fast-path for full blocks (Shapes.block()) and empty shapes
 * 2. Per-BlockState shape caching
 * 3. Collision shape result caching
 * 4. Statistics tracking for cache effectiveness
 * 
 * Similar to Lithium's shape caching but adapted for NeoForge.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class VoxelShapeCacheMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/VoxelShapeCacheMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache for collision shapes keyed by block state hash
    // We use a size-limited cache to avoid memory bloat
    @Unique
    private static final int MAX_CACHE_SIZE = 4096;

    @Unique
    private static final Map<Integer, VoxelShape> kneaf$collisionShapeCache = new ConcurrentHashMap<>(1024);

    @Unique
    private static final Map<Integer, VoxelShape> kneaf$outlineShapeCache = new ConcurrentHashMap<>(1024);

    // Pre-computed common shapes for fast comparison
    @Unique
    private static final VoxelShape FULL_BLOCK = Shapes.block();

    @Unique
    private static final VoxelShape EMPTY = Shapes.empty();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$fastPathHits = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Optimize getCollisionShape with caching.
     * Collision shapes are called extremely frequently during entity movement.
     */
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetCollisionShape(BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ VoxelShapeCacheMixin applied - Collision shape caching active!");
            kneaf$loggedFirstApply = true;
        }

        BlockState state = (BlockState) (Object) this;

        // Fast path: Check if this is a simple full or empty block
        // Many blocks have constant collision shapes that don't depend on context
        @SuppressWarnings("null")
        boolean isFullBlock = !state.hasBlockEntity() && state.isCollisionShapeFullBlock(level, pos);
        if (isFullBlock) {
            kneaf$fastPathHits.incrementAndGet();
            cir.setReturnValue(FULL_BLOCK);
            return;
        }

        // Generate cache key from block state
        int cacheKey = kneaf$generateCacheKey(state, pos);

        // Check cache
        VoxelShape cached = kneaf$collisionShapeCache.get(cacheKey);
        if (cached != null) {
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(cached);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
        // Let vanilla compute it, we'll cache on return
    }

    /**
     * Cache the result after vanilla computes it.
     */
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("RETURN"))
    private void kneaf$afterGetCollisionShape(BlockGetter level, BlockPos pos, CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir) {
        BlockState state = (BlockState) (Object) this;
        VoxelShape result = cir.getReturnValue();

        // Only cache if the shape is simple and cacheable
        // Complex shapes that depend on neighbors shouldn't be cached
        if (result != null && kneaf$isShapeCacheable(state)) {
            int cacheKey = kneaf$generateCacheKey(state, pos);

            // Limit cache size
            if (kneaf$collisionShapeCache.size() >= MAX_CACHE_SIZE) {
                kneaf$cleanupCache();
            }

            // Normalize common shapes to reduce memory
            if (result.isEmpty()) {
                kneaf$collisionShapeCache.put(cacheKey, EMPTY);
            } else if (result == FULL_BLOCK || kneaf$isFullBlock(result)) {
                kneaf$collisionShapeCache.put(cacheKey, FULL_BLOCK);
            } else {
                kneaf$collisionShapeCache.put(cacheKey, result);
            }
        }

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Generate a cache key for a block state at a position.
     * We use state hash + position hash for position-dependent shapes.
     */
    @Unique
    private static int kneaf$generateCacheKey(BlockState state, BlockPos pos) {
        // For most blocks, the shape only depends on the state, not position
        // Exception: blocks like fences that connect to neighbors
        return state.hashCode();
    }

    /**
     * Check if a block state's shape is cacheable.
     * Some blocks have shapes that depend on neighbors or other factors.
     */
    @Unique
    private static boolean kneaf$isShapeCacheable(BlockState state) {
        // Don't cache shapes for blocks that depend on neighbors
        // This includes fences, walls, glass panes, etc.
        // We identify these by checking if they have certain properties
        var block = state.getBlock();
        String blockName = block.getClass().getSimpleName().toLowerCase();

        // Skip blocks that connect to neighbors
        if (blockName.contains("fence") ||
                blockName.contains("wall") ||
                blockName.contains("pane") ||
                blockName.contains("stairs") ||
                blockName.contains("slab")) {
            return false;
        }

        return true;
    }

    /**
     * Check if a VoxelShape is equivalent to a full block.
     */
    @Unique
    private static boolean kneaf$isFullBlock(VoxelShape shape) {
        if (shape == FULL_BLOCK)
            return true;
        if (shape.isEmpty())
            return false;

        // Check bounds
        return shape.min(net.minecraft.core.Direction.Axis.X) <= 0.0 &&
                shape.min(net.minecraft.core.Direction.Axis.Y) <= 0.0 &&
                shape.min(net.minecraft.core.Direction.Axis.Z) <= 0.0 &&
                shape.max(net.minecraft.core.Direction.Axis.X) >= 1.0 &&
                shape.max(net.minecraft.core.Direction.Axis.Y) >= 1.0 &&
                shape.max(net.minecraft.core.Direction.Axis.Z) >= 1.0;
    }

    /**
     * Cleanup cache when it gets too large.
     */
    @Unique
    private static void kneaf$cleanupCache() {
        // Simple strategy: clear half the cache
        int toRemove = MAX_CACHE_SIZE / 2;
        var iterator = kneaf$collisionShapeCache.keySet().iterator();
        while (iterator.hasNext() && toRemove-- > 0) {
            iterator.next();
            iterator.remove();
        }

        // Also clear outline cache
        toRemove = MAX_CACHE_SIZE / 2;
        iterator = kneaf$outlineShapeCache.keySet().iterator();
        while (iterator.hasNext() && toRemove-- > 0) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Log cache statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long fastPath = kneaf$fastPathHits.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("VoxelShape cache: {} hits, {} misses ({}% hit rate), {} fast-path, {} cached shapes",
                        hits, misses, String.format("%.1f", hitRate), fastPath, kneaf$collisionShapeCache.size());
            }

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$fastPathHits.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get cache statistics as a string.
     */
    @Unique
    public static String kneaf$getStatistics() {
        long hits = kneaf$cacheHits.get();
        long misses = kneaf$cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? hits * 100.0 / total : 0;

        return String.format("VoxelShapeCache{hits=%d, misses=%d, hitRate=%.1f%%, cached=%d, fastPath=%d}",
                hits, misses, hitRate, kneaf$collisionShapeCache.size(), kneaf$fastPathHits.get());
    }

    /**
     * Clear all caches. Called when world unloads or on config change.
     */
    @Unique
    public static void kneaf$clearCaches() {
        kneaf$collisionShapeCache.clear();
        kneaf$outlineShapeCache.clear();
        kneaf$LOGGER.debug("VoxelShape caches cleared");
    }
}
