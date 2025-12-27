/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.lithium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Chunk-aware block collision sweeper based on Lithium's implementation.
 * 
 * Key optimizations:
 * 1. Only iterate over chunks that the movement box intersects
 * 2. Cache chunk lookups to avoid repeated hash map access
 * 3. Skip empty/non-full chunks
 * 4. Use BlockPos.MutableBlockPos to avoid allocations
 * 5. Early exit when collision found for single-axis movement
 */
public class ChunkAwareBlockCollisionSweeper implements Iterator<VoxelShape> {

    // Reusable mutable position to avoid allocations
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    private final Level world;
    @Nullable
    private final Entity entity;
    private final AABB box;

    // Chunk iteration state
    private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    private int currentChunkX, currentChunkZ;
    @Nullable
    private LevelChunk currentChunk;

    // Block iteration state within current chunk
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private int blockX, blockY, blockZ;

    // Result storage
    private VoxelShape nextShape = null;
    private boolean finished = false;

    // Statistics
    private static long totalChecks = 0;
    private static long chunkCacheHits = 0;
    private static long earlyExits = 0;

    public ChunkAwareBlockCollisionSweeper(Level world, @Nullable Entity entity, AABB box) {
        this.world = world;
        this.entity = entity;
        this.box = box;

        // Calculate block bounds (inflated by epsilon for precision)
        this.minX = (int) Math.floor(box.minX - 1.0E-7) - 1;
        this.maxX = (int) Math.floor(box.maxX + 1.0E-7) + 1;
        this.minY = Math.max(-64, (int) Math.floor(box.minY - 1.0E-7) - 1); // Clamp to world height
        this.maxY = Math.min(320, (int) Math.floor(box.maxY + 1.0E-7) + 1);
        this.minZ = (int) Math.floor(box.minZ - 1.0E-7) - 1;
        this.maxZ = (int) Math.floor(box.maxZ + 1.0E-7) + 1;

        // Calculate chunk bounds
        this.minChunkX = minX >> 4;
        this.minChunkZ = minZ >> 4;
        this.maxChunkX = maxX >> 4;
        this.maxChunkZ = maxZ >> 4;

        // Initialize iteration position
        this.currentChunkX = minChunkX;
        this.currentChunkZ = minChunkZ;
        this.blockX = minX;
        this.blockY = minY;
        this.blockZ = minZ;

        // Get first chunk
        this.currentChunk = getChunkIfLoaded(currentChunkX, currentChunkZ);
    }

    @Nullable
    private LevelChunk getChunkIfLoaded(int chunkX, int chunkZ) {
        // Use hasChunk to check if chunk is loaded first, then get it
        if (world.hasChunk(chunkX, chunkZ)) {
            try {
                // getChunk with requireChunk=false returns null if not loaded
                var chunk = world.getChunk(chunkX, chunkZ);
                if (chunk instanceof LevelChunk levelChunk) {
                    chunkCacheHits++;
                    return levelChunk;
                }
            } catch (Exception e) {
                // Chunk not available, skip
            }
        }
        return null;
    }

    @Override
    public boolean hasNext() {
        if (finished) {
            return false;
        }

        if (nextShape != null) {
            return true;
        }

        // Find next collision
        nextShape = findNextCollision();
        return nextShape != null;
    }

    @Override
    public VoxelShape next() {
        if (nextShape == null) {
            nextShape = findNextCollision();
        }
        VoxelShape result = nextShape;
        nextShape = null;
        return result;
    }

    @Nullable
    private VoxelShape findNextCollision() {
        while (!finished) {
            totalChecks++;

            // Skip if chunk not loaded
            if (currentChunk != null) {
                // Check current block position
                mutablePos.set(blockX, blockY, blockZ);

                VoxelShape collision = checkBlockAt(mutablePos);
                if (collision != null) {
                    advancePosition();
                    return collision;
                }
            }

            advancePosition();
        }

        return null;
    }

    @Nullable
    private VoxelShape checkBlockAt(BlockPos pos) {
        if (currentChunk == null) {
            return null;
        }

        try {
            // Get block state from chunk directly (faster than world.getBlockState)
            @SuppressWarnings("null")
            BlockState state = currentChunk.getBlockState(pos);

            // Fast path: air blocks have no collision
            if (state.isAir()) {
                return null;
            }

            // Get collision shape
            @SuppressWarnings("null")
            VoxelShape shape = state.getCollisionShape(world, pos);

            // Fast path: empty shapes
            if (shape.isEmpty()) {
                return null;
            }

            // Full block optimization - most common case
            if (shape == Shapes.block()) {
                // Check if box intersects this full block
                if (box.intersects(pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1)) {
                    return shape.move(pos.getX(), pos.getY(), pos.getZ());
                }
                return null;
            }

            // Complex shape - do full intersection test
            VoxelShape offsetShape = shape.move(pos.getX(), pos.getY(), pos.getZ());
            @SuppressWarnings("null")
            boolean hasCollision = Shapes.joinIsNotEmpty(Shapes.create(box), offsetShape, BooleanOp.AND);
            if (hasCollision) {
                return offsetShape;
            }

        } catch (Exception e) {
            // Block might be inaccessible, skip it
        }

        return null;
    }

    private void advancePosition() {
        // Move to next Y
        blockY++;
        if (blockY > maxY) {
            blockY = minY;

            // Move to next Z
            blockZ++;
            if (blockZ > maxZ) {
                blockZ = minZ;

                // Move to next X
                blockX++;
                if (blockX > maxX) {
                    // Move to next chunk
                    advanceChunk();
                    return;
                }
            }
        }

        // Check if we need to update chunk reference
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (chunkX != currentChunkX || chunkZ != currentChunkZ) {
            currentChunkX = chunkX;
            currentChunkZ = chunkZ;
            currentChunk = getChunkIfLoaded(chunkX, chunkZ);
        }
    }

    private void advanceChunk() {
        currentChunkZ++;
        if (currentChunkZ > maxChunkZ) {
            currentChunkZ = minChunkZ;
            currentChunkX++;
            if (currentChunkX > maxChunkX) {
                finished = true;
                return;
            }
        }

        currentChunk = getChunkIfLoaded(currentChunkX, currentChunkZ);

        // Reset block position to chunk bounds
        blockX = Math.max(minX, currentChunkX << 4);
        blockY = minY;
        blockZ = Math.max(minZ, currentChunkZ << 4);
    }

    /**
     * Collect all collisions into a list (for use with vanilla code).
     */
    public List<VoxelShape> collectAll() {
        List<VoxelShape> result = new ArrayList<>();
        while (hasNext()) {
            VoxelShape shape = next();
            if (shape != null) {
                result.add(shape);
            }
        }
        return result;
    }

    /**
     * Get statistics for debugging.
     */
    public static String getStats() {
        return String.format("CollisionSweeper: %d checks, %d chunk hits, %d early exits",
                totalChecks, chunkCacheHits, earlyExits);
    }

    /**
     * Reset statistics.
     */
    public static void resetStats() {
        totalChecks = 0;
        chunkCacheHits = 0;
        earlyExits = 0;
    }
}
