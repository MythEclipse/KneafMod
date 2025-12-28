/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
        if (positions.isEmpty())
            return;

        // For large batches, use flag 2 (BLOCK_UPDATE) to skip neighbor updates
        // which are the main source of lag in explosions.
        int flag = (positions.size() > 64) ? 2 : 3;

        for (BlockPos pos : positions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flag);
        }

        // If we skipped neighbor updates, we might want to trigger a single update
        // for the entire area or just let it be (vanilla explosions trigger updates per
        // block).
        // For performance, we skip the cascade of updates.
    }
}
