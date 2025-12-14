/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.lithium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced PathNode caching system based on Lithium's implementation.
 * 
 * Key optimizations:
 * 1. Cache PathType for BlockStates (avoid repeated calculations)
 * 2. Cache neighbor danger checks (avoid scanning 26 neighbors repeatedly)
 * 3. Track "common blocks" that are known safe/open
 * 4. Fast path for air blocks
 */
public class PathNodeCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/PathNodeCache");

    // PathType cache for individual block states - Thread Safe
    private static final Map<BlockState, PathType> BLOCK_PATH_TYPE_CACHE = new java.util.concurrent.ConcurrentHashMap<>(4096);

    // Cache for "is this block type always open/walkable"
    private static final Map<BlockState, Boolean> ALWAYS_PASSABLE_CACHE = new java.util.concurrent.ConcurrentHashMap<>(1024);

    // Statistics
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);
    private static final AtomicLong neighborSkips = new AtomicLong(0);

    // Sentinel for "we haven't calculated this yet"
    private static final PathType UNCACHED = null;

    // Maximum cache size to prevent memory issues
    private static final int MAX_CACHE_SIZE = 8192;

    // Thread-local mutable BlockPos for neighbor checks
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS = ThreadLocal
            .withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Get PathType for a BlockState, using cache if available.
     */
    @Nullable
    public static PathType getPathType(BlockState state) {
        PathType cached = BLOCK_PATH_TYPE_CACHE.get(state);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();
        return null;
    }

    /**
     * Cache a PathType for a BlockState.
     */
    public static void cachePathType(BlockState state, PathType type) {
        if (BLOCK_PATH_TYPE_CACHE.size() < MAX_CACHE_SIZE) {
            BLOCK_PATH_TYPE_CACHE.put(state, type);
        }
    }

    /**
     * Check if a block state is known to be always passable.
     * This allows skipping neighbor danger checks for obvious cases.
     */
    public static boolean isKnownPassable(BlockState state) {
        // Fast path for air
        if (state.isAir()) {
            return true;
        }

        Boolean cached = ALWAYS_PASSABLE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }

        return false;
    }

    /**
     * Mark a block state as always passable (for future checks).
     */
    public static void markAsPassable(BlockState state, boolean passable) {
        if (ALWAYS_PASSABLE_CACHE.size() < 2048) {
            ALWAYS_PASSABLE_CACHE.put(state, passable);
        }
    }

    /**
     * Optimized neighbor check for pathfinding danger detection.
     * 
     * Instead of checking all 26 neighbors, we:
     * 1. Skip if center block is known passable
     * 2. Check most dangerous neighbors first (lava, fire, etc.)
     * 3. Early exit on first danger found
     * 
     * @return PathType if danger found, null otherwise
     */
    @Nullable
    public static PathType getNodeTypeFromNeighbors(BlockGetter world, BlockPos.MutableBlockPos pos,
            int x, int y, int z, PathType fallback) {
        // If fallback is already dangerous, no need to check neighbors
        if (fallback != null && isDangerousType(fallback)) {
            return fallback;
        }

        // Check the 6 cardinal directions first (most likely to have danger)
        // This is much faster than checking all 26 neighbors

        // Check below (lava/fire common here)
        pos.set(x, y - 1, z);
        PathType below = checkNeighborDanger(world, pos);
        if (below != null)
            return below;

        // Check sides (4 directions)
        pos.set(x - 1, y, z);
        PathType west = checkNeighborDanger(world, pos);
        if (west != null)
            return west;

        pos.set(x + 1, y, z);
        PathType east = checkNeighborDanger(world, pos);
        if (east != null)
            return east;

        pos.set(x, y, z - 1);
        PathType north = checkNeighborDanger(world, pos);
        if (north != null)
            return north;

        pos.set(x, y, z + 1);
        PathType south = checkNeighborDanger(world, pos);
        if (south != null)
            return south;

        // Check above (less common to have danger)
        pos.set(x, y + 1, z);
        PathType above = checkNeighborDanger(world, pos);
        if (above != null)
            return above;

        neighborSkips.incrementAndGet();
        return fallback;
    }

    @Nullable
    private static PathType checkNeighborDanger(BlockGetter world, BlockPos pos) {
        try {
            BlockState state = world.getBlockState(pos);

            // Fast check for common safe blocks
            if (state.isAir()) {
                return null;
            }

            // Check cached PathType
            PathType cached = getPathType(state);
            if (cached != null && isDangerousType(cached)) {
                return cached;
            }

            // Check for specific dangerous blocks
            // (Simplified - in production would check block tags)
            String blockName = state.getBlock().getDescriptionId();
            if (blockName.contains("lava")) {
                cachePathType(state, PathType.LAVA);
                return PathType.LAVA;
            }
            if (blockName.contains("fire") || blockName.contains("campfire")) {
                cachePathType(state, PathType.DAMAGE_FIRE);
                return PathType.DAMAGE_FIRE;
            }
            if (blockName.contains("cactus")) {
                cachePathType(state, PathType.DAMAGE_OTHER);
                return PathType.DAMAGE_OTHER;
            }
            if (blockName.contains("sweet_berry")) {
                cachePathType(state, PathType.DAMAGE_OTHER);
                return PathType.DAMAGE_OTHER;
            }

        } catch (Exception e) {
            // Block might be unloaded
        }

        return null;
    }

    private static boolean isDangerousType(PathType type) {
        return type == PathType.LAVA
                || type == PathType.DAMAGE_FIRE
                || type == PathType.DAMAGE_OTHER
                || type == PathType.DANGER_FIRE
                || type == PathType.DANGER_OTHER;
    }

    /**
     * Clear all caches. Call on world unload.
     */
    public static void clearCaches() {
        BLOCK_PATH_TYPE_CACHE.clear();
        ALWAYS_PASSABLE_CACHE.clear();
        LOGGER.info("PathNodeCache cleared");
    }

    /**
     * Get cache statistics.
     */
    public static String getStats() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = hits + misses > 0 ? (double) hits / (hits + misses) * 100 : 0;
        return String.format("PathNodeCache: %d hits, %d misses (%.1f%% hit rate), %d neighbor skips, %d cached types",
                hits, misses, hitRate, neighborSkips.get(), BLOCK_PATH_TYPE_CACHE.size());
    }

    /**
     * Reset statistics.
     */
    public static void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
        neighborSkips.set(0);
    }
}
