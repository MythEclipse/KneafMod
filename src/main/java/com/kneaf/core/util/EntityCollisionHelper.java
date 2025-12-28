/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * SIMD-accelerated entity collision processing via Rust.
 */
package com.kneaf.core.util;

import com.kneaf.core.RustOptimizations;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance entity collision helper using SIMD-accelerated Rust.
 * 
 * Batches collision checks for better cache efficiency and reduced JNI
 * overhead.
 * Falls back to simple Java when native library is unavailable.
 */
public final class EntityCollisionHelper {

    // Statistics
    private static final AtomicLong nativeCollisionChecks = new AtomicLong(0);
    private static final AtomicLong javaCollisionChecks = new AtomicLong(0);
    private static final AtomicLong totalBatches = new AtomicLong(0);

    // Configuration
    private static final int MIN_BATCH_SIZE = 8; // Minimum entities for batching

    private EntityCollisionHelper() {
        // Utility class
    }

    /**
     * Check if an entity's AABB intersects with any block in a collection.
     * Uses Rust SIMD for batch processing when count >= MIN_BATCH_SIZE.
     *
     * @param entityBox  Entity's bounding box [minX, minY, minZ, maxX, maxY, maxZ]
     * @param blockBoxes Array of block boxes, flattened [minX, minY, minZ, maxX,
     *                   maxY, maxZ, ...]
     * @param count      Number of boxes to check
     * @return true if any intersection found
     */
    public static boolean hasAnyCollision(double[] entityBox, double[] blockBoxes, int count) {
        if (count < MIN_BATCH_SIZE) {
            // Use simple Java for small counts (avoid JNI overhead)
            return hasAnyCollisionJava(entityBox, blockBoxes, count);
        }

        // Use Rust SIMD batch processing
        int[] results = RustOptimizations.batchAABBIntersection(blockBoxes, entityBox, count);

        if (results == null) {
            // Native unavailable, use Java fallback
            javaCollisionChecks.addAndGet(count);
            return hasAnyCollisionJava(entityBox, blockBoxes, count);
        }

        nativeCollisionChecks.addAndGet(count);
        totalBatches.incrementAndGet();

        // Check if any result is 1 (collision)
        for (int result : results) {
            if (result == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get collision indices - which boxes collide with the entity.
     * Returns indices of colliding boxes.
     *
     * @param entityBox  Entity's bounding box
     * @param blockBoxes Array of block boxes
     * @param count      Number of boxes
     * @return Array of indices that collide
     */
    public static int[] getCollisionIndices(double[] entityBox, double[] blockBoxes, int count) {
        int[] results;

        if (count >= MIN_BATCH_SIZE) {
            results = RustOptimizations.batchAABBIntersection(blockBoxes, entityBox, count);
        } else {
            results = null;
        }

        if (results == null) {
            // Fallback
            results = getCollisionIndicesJava(entityBox, blockBoxes, count);
        }

        // Count collisions
        int collisionCount = 0;
        for (int r : results) {
            if (r == 1)
                collisionCount++;
        }

        // Build indices array
        int[] indices = new int[collisionCount];
        int idx = 0;
        for (int i = 0; i < results.length; i++) {
            if (results[i] == 1) {
                indices[idx++] = i;
            }
        }

        return indices;
    }

    /**
     * Java fallback for small batch sizes.
     */
    private static boolean hasAnyCollisionJava(double[] entityBox, double[] blockBoxes, int count) {
        javaCollisionChecks.addAndGet(count);

        double eMinX = entityBox[0], eMinY = entityBox[1], eMinZ = entityBox[2];
        double eMaxX = entityBox[3], eMaxY = entityBox[4], eMaxZ = entityBox[5];

        for (int i = 0; i < count; i++) {
            int idx = i * 6;
            double bMinX = blockBoxes[idx], bMinY = blockBoxes[idx + 1], bMinZ = blockBoxes[idx + 2];
            double bMaxX = blockBoxes[idx + 3], bMaxY = blockBoxes[idx + 4], bMaxZ = blockBoxes[idx + 5];

            // AABB intersection test
            if (eMinX <= bMaxX && eMaxX >= bMinX &&
                    eMinY <= bMaxY && eMaxY >= bMinY &&
                    eMinZ <= bMaxZ && eMaxZ >= bMinZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Java fallback for getting collision results.
     */
    private static int[] getCollisionIndicesJava(double[] entityBox, double[] blockBoxes, int count) {
        int[] results = new int[count];

        double eMinX = entityBox[0], eMinY = entityBox[1], eMinZ = entityBox[2];
        double eMaxX = entityBox[3], eMaxY = entityBox[4], eMaxZ = entityBox[5];

        for (int i = 0; i < count; i++) {
            int idx = i * 6;
            double bMinX = blockBoxes[idx], bMinY = blockBoxes[idx + 1], bMinZ = blockBoxes[idx + 2];
            double bMaxX = blockBoxes[idx + 3], bMaxY = blockBoxes[idx + 4], bMaxZ = blockBoxes[idx + 5];

            if (eMinX <= bMaxX && eMaxX >= bMinX &&
                    eMinY <= bMaxY && eMaxY >= bMinY &&
                    eMinZ <= bMaxZ && eMaxZ >= bMinZ) {
                results[i] = 1;
            }
        }
        return results;
    }

    /**
     * Get statistics string.
     */
    public static String getStatistics() {
        long native_ = nativeCollisionChecks.get();
        long java = javaCollisionChecks.get();
        long batches = totalBatches.get();
        long total = native_ + java;

        return String.format(
                "EntityCollision{native=%d, java=%d, batches=%d, nativeRatio=%.1f%%}",
                native_, java, batches,
                total > 0 ? native_ * 100.0 / total : 0);
    }

    /**
     * Reset statistics.
     */
    public static void resetStatistics() {
        nativeCollisionChecks.set(0);
        javaCollisionChecks.set(0);
        totalBatches.set(0);
    }
}
