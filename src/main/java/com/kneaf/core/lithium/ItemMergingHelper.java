/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Original code licensed under LGPL-3.0
 */
package com.kneaf.core.lithium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Optimized item entity merging with spatial awareness and early exits.
 * 
 * Key optimizations:
 * 1. Check item type FIRST (cheapest comparison)
 * 2. Check count limits SECOND (avoid merge if result would exceed max)
 * 3. Check distance LAST (most expensive due to sqrt)
 * 4. Use squared distance for comparisons where possible
 */
public class ItemMergingHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ItemMergingHelper");

    // Default merge radius squared (3.5 blocks)
    private static final double MERGE_RADIUS_SQ = 3.5 * 3.5;

    // Minimum ticks before items can merge (prevents spam merging)
    private static final int MIN_AGE_FOR_MERGE = 10;

    /**
     * Try to merge an item entity with nearby items.
     * Uses optimized search order for best performance.
     * 
     * @param item  The item entity to merge
     * @param level The world
     * @return true if merge was successful
     */
    public static boolean tryMergeWithNearby(ItemEntity item, Level level) {
        if (level == null || item.isRemoved()) {
            return false;
        }

        ItemStack stack = item.getItem();
        if (stack.isEmpty()) {
            return false;
        }

        // Can't merge if already at max
        if (stack.getCount() >= stack.getMaxStackSize()) {
            return false;
        }

        // Get item type for fast comparison
        Item itemType = stack.getItem();
        int currentCount = stack.getCount();
        int maxStack = stack.getMaxStackSize();
        int spaceAvailable = maxStack - currentCount;

        // Create search box
        AABB searchBox = item.getBoundingBox().inflate(2.0);

        // Get nearby item entities
        List<ItemEntity> nearbyItems = level.getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                other -> other != item && !other.isRemoved());

        if (nearbyItems.isEmpty()) {
            return false;
        }

        // Position for distance calculations
        double x = item.getX();
        double y = item.getY();
        double z = item.getZ();

        for (ItemEntity other : nearbyItems) {
            ItemStack otherStack = other.getItem();

            // 1. Check item type FIRST (cheapest)
            if (otherStack.getItem() != itemType) {
                continue;
            }

            // 2. Check if other item has anything to give
            int otherCount = otherStack.getCount();
            if (otherCount <= 0) {
                continue;
            }

            // 3. Check NBT/damage compatibility (fairly cheap)
            if (!ItemStack.isSameItemSameComponents(stack, otherStack)) {
                continue;
            }

            // 4. Check distance (more expensive, do last)
            double distSq = EntityCollisionHelper.distanceSquared(
                    x, y, z, other.getX(), other.getY(), other.getZ());

            if (distSq > MERGE_RADIUS_SQ) {
                continue;
            }

            // Merge!
            int toTransfer = Math.min(otherCount, spaceAvailable);

            if (toTransfer > 0) {
                stack.grow(toTransfer);
                otherStack.shrink(toTransfer);

                if (otherStack.isEmpty()) {
                    other.discard();
                }

                // Update our space available
                spaceAvailable -= toTransfer;

                // If we're full, stop searching
                if (spaceAvailable <= 0) {
                    return true;
                }
            }
        }

        return spaceAvailable < (maxStack - currentCount); // true if we merged anything
    }

    /**
     * Check if two items CAN merge (without actually merging).
     * Use this for pre-checks to avoid unnecessary entity lookups.
     */
    public static boolean canMerge(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }

        // Same item type
        if (a.getItem() != b.getItem()) {
            return false;
        }

        // Combined count doesn't exceed max
        if (a.getCount() + b.getCount() > a.getMaxStackSize()) {
            return false; // Can still partially merge, but not fully
        }

        // Same NBT/components
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * Get the effective merge radius for distance checks.
     */
    public static double getMergeRadiusSquared() {
        return MERGE_RADIUS_SQ;
    }
}
