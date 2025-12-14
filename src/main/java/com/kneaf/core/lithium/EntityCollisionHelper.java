/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Original code licensed under LGPL-3.0
 */
package com.kneaf.core.lithium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimized entity collision detection with early exits and lazy loading.
 * 
 * Key optimizations from Lithium:
 * 1. Check supporting block FIRST for downward movement (gravity is most
 * common)
 * 2. Early exit when single-axis movement is completely blocked
 * 3. Lazy entity collision loading - only fetch when actually needed
 * 4. Smaller bounding box for single-axis movement
 */
public class EntityCollisionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/EntityCollisionHelper");

    // Reusable BlockPos to avoid allocations
    private static final ThreadLocal<BlockPos.MutableBlockPos> MUTABLE_POS = ThreadLocal
            .withInitial(BlockPos.MutableBlockPos::new);

    /**
     * Check if entity movement is single-axis (only X, Y, or Z is non-zero).
     * Single-axis movements can use optimized collision detection.
     */
    public static boolean isSingleAxisMovement(Vec3 movement) {
        int nonZeroAxes = 0;
        if (movement.x != 0.0)
            nonZeroAxes++;
        if (movement.y != 0.0)
            nonZeroAxes++;
        if (movement.z != 0.0)
            nonZeroAxes++;
        return nonZeroAxes == 1;
    }

    /**
     * Check if entity movement is single-axis.
     */
    public static boolean isSingleAxisMovement(double x, double y, double z) {
        int nonZeroAxes = 0;
        if (x != 0.0)
            nonZeroAxes++;
        if (y != 0.0)
            nonZeroAxes++;
        if (z != 0.0)
            nonZeroAxes++;
        return nonZeroAxes == 1;
    }

    /**
     * Get a smaller bounding box for single-axis movement.
     * Instead of stretching the full box, only extend in the movement direction.
     */
    public static AABB getSmallerBoxForSingleAxisMovement(Vec3 movement, AABB entityBox) {
        double x = movement.x;
        double y = movement.y;
        double z = movement.z;

        if (y != 0.0) {
            // Vertical movement - use entity footprint
            return y < 0.0
                    ? new AABB(entityBox.minX, entityBox.minY + y, entityBox.minZ,
                            entityBox.maxX, entityBox.minY, entityBox.maxZ)
                    : new AABB(entityBox.minX, entityBox.maxY, entityBox.minZ,
                            entityBox.maxX, entityBox.maxY + y, entityBox.maxZ);
        } else if (x != 0.0) {
            // X movement
            return x < 0.0
                    ? new AABB(entityBox.minX + x, entityBox.minY, entityBox.minZ,
                            entityBox.minX, entityBox.maxY, entityBox.maxZ)
                    : new AABB(entityBox.maxX, entityBox.minY, entityBox.minZ,
                            entityBox.maxX + x, entityBox.maxY, entityBox.maxZ);
        } else if (z != 0.0) {
            // Z movement
            return z < 0.0
                    ? new AABB(entityBox.minX, entityBox.minY, entityBox.minZ + z,
                            entityBox.maxX, entityBox.maxY, entityBox.minZ)
                    : new AABB(entityBox.minX, entityBox.minY, entityBox.maxZ,
                            entityBox.maxX, entityBox.maxY, entityBox.maxZ + z);
        }

        return entityBox;
    }

    /**
     * Get the supporting block collision for an entity (block directly below).
     * This is the most common collision check - gravity pulling entities down.
     * 
     * @return The collision shape of the supporting block, or null if none
     */
    @Nullable
    public static VoxelShape getSupportingBlockCollision(BlockGetter world, @Nullable Entity entity, AABB entityBox) {
        if (world == null)
            return null;

        BlockPos.MutableBlockPos pos = MUTABLE_POS.get();

        // Check block at entity's feet
        double centerX = (entityBox.minX + entityBox.maxX) / 2.0;
        double centerZ = (entityBox.minZ + entityBox.maxZ) / 2.0;
        pos.set(centerX, entityBox.minY - 0.01, centerZ);

        try {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir()) {
                VoxelShape shape = state.getCollisionShape(world, pos);
                if (!shape.isEmpty()) {
                    // Offset shape to world coordinates
                    return shape.move(pos.getX(), pos.getY(), pos.getZ());
                }
            }
        } catch (Exception e) {
            // Ignore - block might be unloaded
        }

        return null;
    }

    /**
     * Quick check if entity is standing on a solid block.
     * Much faster than full collision detection for stationary entities.
     */
    public static boolean isOnSolidGround(BlockGetter world, AABB entityBox) {
        if (world == null)
            return false;

        BlockPos.MutableBlockPos pos = MUTABLE_POS.get();

        // Check corners and center of entity footprint
        double minX = entityBox.minX + 0.001;
        double maxX = entityBox.maxX - 0.001;
        double minZ = entityBox.minZ + 0.001;
        double maxZ = entityBox.maxZ - 0.001;
        double y = entityBox.minY - 0.01;

        // Check center first (most likely to be solid)
        if (isBlockSolid(world, pos, (minX + maxX) / 2, y, (minZ + maxZ) / 2))
            return true;

        // Check corners
        if (isBlockSolid(world, pos, minX, y, minZ))
            return true;
        if (isBlockSolid(world, pos, maxX, y, minZ))
            return true;
        if (isBlockSolid(world, pos, minX, y, maxZ))
            return true;
        if (isBlockSolid(world, pos, maxX, y, maxZ))
            return true;

        return false;
    }

    private static boolean isBlockSolid(BlockGetter world, BlockPos.MutableBlockPos pos, double x, double y, double z) {
        pos.set(x, y, z);
        try {
            BlockState state = world.getBlockState(pos);
            return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calculate distance squared between two points (avoids sqrt for comparisons).
     */
    public static double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculate horizontal distance squared (ignoring Y).
     */
    public static double horizontalDistanceSquared(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return dx * dx + dz * dz;
    }
}
