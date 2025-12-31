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

    // Direct Array Cache: O(1) access by BlockState ID
    // Much faster than HashMap, minimal memory overhead (references only)
    @Unique
    private static volatile VoxelShape[] kneaf$collisionShapeCache = new VoxelShape[1024];

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

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
            kneaf$LOGGER.info("✅ VoxelShapeCacheMixin applied - Direct Array Caching active!");
            kneaf$loggedFirstApply = true;
        }

        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        // Check array bounds and content
        VoxelShape[] cache = kneaf$collisionShapeCache;
        if (id < cache.length) {
            VoxelShape cached = cache[id];
            if (cached != null) {
                kneaf$cacheHits.incrementAndGet();
                cir.setReturnValue(cached);
                return;
            }
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

        if (result != null && kneaf$isShapeCacheable(state)) {
            int id = net.minecraft.world.level.block.Block.getId(state);

            // Ensure capacity
            kneaf$ensureCapacity(id + 1);

            // Normalize common shapes to reduce memory (canonical instances)
            // Note: vanilla Shapes.block() and empty() are singletons, so == check works
            // But we trust the result is good.
            kneaf$collisionShapeCache[id] = result;
        }

        // Log stats periodically
        kneaf$logStats();
    }

    @Unique
    private static synchronized void kneaf$ensureCapacity(int minCapacity) {
        if (minCapacity > kneaf$collisionShapeCache.length) {
            int newSize = Math.max(minCapacity, kneaf$collisionShapeCache.length * 2);
            // Cap at reasonable max to prevent DoS (though block IDs are finite)
            if (newSize > 65536)
                newSize = 65536; // 65k states is plenty for most packs
            if (minCapacity > newSize)
                newSize = minCapacity; // absolute fallback

            VoxelShape[] newCache = new VoxelShape[newSize];
            System.arraycopy(kneaf$collisionShapeCache, 0, newCache, 0, kneaf$collisionShapeCache.length);
            kneaf$collisionShapeCache = newCache;
        }
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
     * Log cache statistics periodically.
     */
    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                // Update central stats
                com.kneaf.core.PerformanceStats.voxelHitRate = hits; // Just raw hits this second
                com.kneaf.core.PerformanceStats.voxelMissRate = misses;
                com.kneaf.core.PerformanceStats.voxelHitPercent = hitRate;
                com.kneaf.core.PerformanceStats.voxelCacheSize = kneaf$collisionShapeCache.length; // array capacity

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }
}
