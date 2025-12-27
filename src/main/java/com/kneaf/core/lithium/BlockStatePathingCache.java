/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Original code licensed under LGPL-3.0
 */
package com.kneaf.core.lithium;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Caches PathType for BlockStates to avoid repeated calculations.
 * 
 * This is one of the biggest performance wins from Lithium - pathfinding
 * constantly queries the same block states, and calculating PathType is
 * expensive.
 */
public class BlockStatePathingCache {

    // Use IdentityHashMap for fastest lookup - BlockState instances are interned
    private static final Map<BlockState, PathType> BLOCK_PATH_TYPE_CACHE = new IdentityHashMap<>(4096);
    private static final Map<BlockState, PathType> IN_WATER_PATH_TYPE_CACHE = new IdentityHashMap<>(1024);

    /**
     * Get cached PathType for a BlockState, or null if not cached.
     */
    @Nullable
    public static PathType getCachedPathType(BlockState state) {
        return BLOCK_PATH_TYPE_CACHE.get(state);
    }

    /**
     * Cache a PathType for a BlockState.
     */
    public static void cachePathType(BlockState state, PathType type) {
        // Limit cache size to prevent memory issues
        if (BLOCK_PATH_TYPE_CACHE.size() < 8192) {
            BLOCK_PATH_TYPE_CACHE.put(state, type);
        }
    }

    /**
     * Get cached in-water PathType for a BlockState.
     */
    @Nullable
    public static PathType getCachedInWaterPathType(BlockState state) {
        return IN_WATER_PATH_TYPE_CACHE.get(state);
    }

    /**
     * Cache an in-water PathType for a BlockState.
     */
    public static void cacheInWaterPathType(BlockState state, PathType type) {
        if (IN_WATER_PATH_TYPE_CACHE.size() < 2048) {
            IN_WATER_PATH_TYPE_CACHE.put(state, type);
        }
    }

    /**
     * Check if a BlockState is known to be a simple passable block.
     * Simple blocks don't need neighbor checking for pathfinding.
     */
    public static boolean isSimplePassableBlock(BlockState state) {
        // Air and similar blocks are always passable
        if (state.isAir()) {
            return true;
        }

        // Check cached type
        PathType cached = getCachedPathType(state);
        if (cached != null) {
            return cached == PathType.OPEN || cached == PathType.WALKABLE;
        }

        return false;
    }

    /**
     * Clear all caches. Called on world unload.
     */
    public static void clearCaches() {
        BLOCK_PATH_TYPE_CACHE.clear();
        IN_WATER_PATH_TYPE_CACHE.clear();
    }

    /**
     * Get cache statistics for debugging.
     */
    public static String getStats() {
        return String.format("PathCache: %d block types, %d water types",
                BLOCK_PATH_TYPE_CACHE.size(), IN_WATER_PATH_TYPE_CACHE.size());
    }
}
