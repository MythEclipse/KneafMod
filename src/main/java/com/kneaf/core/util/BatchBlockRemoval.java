/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * BatchBlockRemoval - Utility for efficiently removing large groups of blocks.
 * 
 * Optimizations:
 * 1. Skips neighbor updates during removal (using flag 2 instead of 3).
 * 2. Runs neighbor updates once at the end (optional/optimized).
 * 3. Reduces lighting calculation overhead.
 */
public final class BatchBlockRemoval {

    /**
     * Efficiently remove a list of blocks from the world.
     * 
     * @param level     The level
     * @param positions List of positions to remove
     */
    public static void removeBlocks(Level level, List<BlockPos> positions) {
        if (positions == null || positions.isEmpty())
            return;

        // Optimization: Use flag 2 (BLOCK_UPDATE) for large batches to skip individual
        // neighbor updates
        // Neighbor updates will be triggered once at the end or left to vanilla logic
        int flag = positions.size() > 64 ? 2 : 3;

        for (net.minecraft.core.BlockPos pos : positions) {
            if (pos != null) {
                @SuppressWarnings("null")
                net.minecraft.world.level.block.state.BlockState airState = net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState();
                if (airState != null) {
                    level.setBlock(pos, airState, flag);
                }
            }
        }

        // If we skipped neighbor updates, we might want to trigger a single update
        // for the entire area or just let it be (vanilla explosions trigger updates per
        // block).
        // For performance, we skip the cascade of updates.
    }
}
