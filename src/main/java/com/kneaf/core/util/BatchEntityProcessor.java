/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Batch entity processing to reduce JNI call overhead.
 */
package com.kneaf.core.util;

import com.kneaf.core.RustOptimizations;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch entity processor for reducing JNI overhead.
 * 
 * Instead of making individual JNI calls per entity, this class
 * collects entity data and processes them in batches.
 * 
 * Key optimizations:
 * 1. Single JNI call for N entities (reduces JNI overhead by N-1)
 * 2. Better cache locality for data
 * 3. Enables SIMD parallel processing in Rust
 */
public final class BatchEntityProcessor {

    // Configuration
    private static final int MAX_BATCH_SIZE = 256;

    // Reusable buffers to reduce allocation
    private static final ThreadLocal<double[]> positionBuffer = ThreadLocal
            .withInitial(() -> new double[MAX_BATCH_SIZE * 3]);

    private static final ThreadLocal<double[]> distanceBuffer = ThreadLocal
            .withInitial(() -> new double[MAX_BATCH_SIZE]);

    // Statistics
    private static final AtomicLong batchCalls = new AtomicLong(0);
    private static final AtomicLong entitiesProcessed = new AtomicLong(0);
    private static final AtomicLong jniCallsSaved = new AtomicLong(0);

    private BatchEntityProcessor() {
        // Utility class
    }

    /**
     * Calculate squared distances from multiple entities to a reference point.
     * Uses a single JNI call for all entities.
     *
     * @param entityPositions Array of [x, y, z] positions, size = count * 3
     * @param refX            Reference X coordinate (e.g., player position)
     * @param refY            Reference Y coordinate
     * @param refZ            Reference Z coordinate
     * @param count           Number of entities
     * @return Array of squared distances
     */
    public static double[] batchDistanceSqToPoint(
            double[] entityPositions,
            double refX, double refY, double refZ,
            int count) {

        if (count <= 0) {
            return new double[0];
        }

        // Use single JNI call
        double[] results = RustOptimizations.batchEntityDistanceSq(
                entityPositions, refX, refY, refZ, count);

        if (results != null) {
            batchCalls.incrementAndGet();
            entitiesProcessed.addAndGet(count);
            jniCallsSaved.addAndGet(count - 1); // Saved N-1 JNI calls
            return results;
        }

        // Java fallback
        return batchDistanceSqJava(entityPositions, refX, refY, refZ, count);
    }

    /**
     * Process entities for tick rate determination in batch.
     * Returns tick divisors (1 = every tick, 2 = every 2 ticks, etc.)
     *
     * @param entityPositions Entity positions [x0, y0, z0, x1, y1, z1, ...]
     * @param playerX         Player X position
     * @param playerY         Player Y position
     * @param playerZ         Player Z position
     * @param nearDistance    Distance for full tick rate
     * @param farDistance     Distance for reduced tick rate
     * @param count           Number of entities
     * @return Tick divisors per entity
     */
    public static int[] batchComputeTickRates(
            double[] entityPositions,
            double playerX, double playerY, double playerZ,
            double nearDistance, double farDistance,
            int count) {

        if (count <= 0) {
            return new int[0];
        }

        // Step 1: Calculate distances (single JNI call)
        double[] distancesSq = batchDistanceSqToPoint(
                entityPositions, playerX, playerY, playerZ, count);

        // Step 2: Compute tick rates (single JNI call)
        double nearSq = nearDistance * nearDistance;
        double farSq = farDistance * farDistance;

        int[] tickRates = RustOptimizations.computeEntityTickRates(
                distancesSq, nearSq, farSq, count);

        if (tickRates != null) {
            return tickRates;
        }

        // Java fallback
        return computeTickRatesJava(distancesSq, nearSq, farSq, count);
    }

    /**
     * Java fallback for batch distance calculation.
     */
    private static double[] batchDistanceSqJava(
            double[] positions, double refX, double refY, double refZ, int count) {

        double[] results = new double[count];

        for (int i = 0; i < count; i++) {
            int idx = i * 3;
            double dx = positions[idx] - refX;
            double dy = positions[idx + 1] - refY;
            double dz = positions[idx + 2] - refZ;
            results[i] = dx * dx + dy * dy + dz * dz;
        }

        return results;
    }

    /**
     * Java fallback for tick rate computation.
     */
    private static int[] computeTickRatesJava(
            double[] distancesSq, double nearSq, double farSq, int count) {

        int[] rates = new int[count];
        double veryFarSq = farSq * 4.0;

        for (int i = 0; i < count; i++) {
            double d = distancesSq[i];
            if (d <= nearSq) {
                rates[i] = 1; // Full tick rate
            } else if (d <= farSq) {
                rates[i] = 2; // Half tick rate
            } else if (d <= veryFarSq) {
                rates[i] = 4; // Quarter tick rate
            } else {
                rates[i] = 8; // Eighth tick rate
            }
        }

        return rates;
    }

    /**
     * Get reusable position buffer for thread.
     * Reduces allocation during entity processing.
     */
    public static double[] getPositionBuffer() {
        return positionBuffer.get();
    }

    /**
     * Get reusable distance buffer for thread.
     */
    public static double[] getDistanceBuffer() {
        return distanceBuffer.get();
    }

    /**
     * Get statistics string.
     */
    public static String getStatistics() {
        long calls = batchCalls.get();
        long entities = entitiesProcessed.get();
        long saved = jniCallsSaved.get();
        double avgBatchSize = calls > 0 ? (double) entities / calls : 0;

        return String.format(
                "BatchEntity{batches=%d, entities=%d, jniSaved=%d, avgBatchSize=%.1f}",
                calls, entities, saved, avgBatchSize);
    }

    /**
     * Reset all statistics.
     */
    public static void resetStatistics() {
        batchCalls.set(0);
        entitiesProcessed.set(0);
        jniCallsSaved.set(0);
    }
}
