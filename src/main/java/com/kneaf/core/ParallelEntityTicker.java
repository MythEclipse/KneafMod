/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Parallel entity processing using Rust SIMD and Java ForkJoinPool.
 */
package com.kneaf.core;

import com.kneaf.core.util.BatchEntityProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ParallelEntityTicker - Batch process entities for optimized
 * distance/visibility calculations.
 * 
 * Uses:
 * 1. ForkJoinPool for parallel Java processing
 * 2. Rust SIMD via BatchEntityProcessor for distance calculations
 * 3. No throttling - every entity is processed
 * 
 * This class helps ServerLevelMixin batch entity operations.
 */
public final class ParallelEntityTicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ParallelEntityTicker");

    // Thread pool for parallel entity processing
    private static final int THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final ForkJoinPool entityPool = new ForkJoinPool(THREAD_COUNT);

    // Batch size for efficient processing
    private static final int BATCH_SIZE = 64;

    // Statistics
    private static final AtomicLong entitiesProcessed = new AtomicLong(0);
    private static final AtomicLong batchesProcessed = new AtomicLong(0);
    private static final AtomicLong parallelMs = new AtomicLong(0);
    private static long lastLogTime = 0;
    private static long lastLogEntities = 0;
    private static long lastLogBatches = 0;
    private static long lastLogMs = 0;

    private ParallelEntityTicker() {
    }

    /**
     * Batch calculate distances from entities to nearest player.
     * Uses Rust SIMD when available, parallel Java otherwise.
     *
     * @param entities List of entities to process
     * @param level    Server level for player lookup
     * @return Array of squared distances to nearest player
     */
    public static double[] batchCalculatePlayerDistances(List<Entity> entities, ServerLevel level) {
        if (entities.isEmpty()) {
            return new double[0];
        }

        long startTime = System.nanoTime();

        // Get nearest player position (or center of world)
        Player nearestPlayer = level.getNearestPlayer(0, 64, 0, 1000, false);
        double playerX = nearestPlayer != null ? nearestPlayer.getX() : 0;
        double playerY = nearestPlayer != null ? nearestPlayer.getY() : 64;
        double playerZ = nearestPlayer != null ? nearestPlayer.getZ() : 0;

        // Build positions array for Rust batch processing
        int count = entities.size();
        double[] positions = new double[count * 3];

        for (int i = 0; i < count; i++) {
            Entity entity = entities.get(i);
            positions[i * 3] = entity.getX();
            positions[i * 3 + 1] = entity.getY();
            positions[i * 3 + 2] = entity.getZ();
        }

        // Use Rust batch distance calculation
        double[] distances = BatchEntityProcessor.batchDistanceSqToPoint(
                positions, playerX, playerY, playerZ, count);

        // Update stats
        entitiesProcessed.addAndGet(count);
        batchesProcessed.incrementAndGet();
        parallelMs.addAndGet((System.nanoTime() - startTime) / 1_000_000);

        logStats();
        return distances;
    }

    /**
     * Process entities in parallel batches using ForkJoinPool.
     * Each batch processes BATCH_SIZE entities concurrently.
     *
     * @param entities  All entities to process
     * @param processor The processing function for each entity
     */
    public static void processInParallel(List<Entity> entities, EntityProcessor processor) {
        if (entities.isEmpty()) {
            return;
        }

        long startTime = System.nanoTime();
        int count = entities.size();

        if (count < BATCH_SIZE) {
            // Small number - process sequentially (avoid thread overhead)
            for (Entity entity : entities) {
                processor.process(entity);
            }
        } else {
            // Large number - process in parallel batches
            List<ForkJoinTask<?>> tasks = new ArrayList<>();

            for (int i = 0; i < count; i += BATCH_SIZE) {
                final int start = i;
                final int end = Math.min(i + BATCH_SIZE, count);

                tasks.add(entityPool.submit(() -> {
                    for (int j = start; j < end; j++) {
                        processor.process(entities.get(j));
                    }
                }));
            }

            // Wait for all tasks to complete
            for (ForkJoinTask<?> task : tasks) {
                task.join();
            }
        }

        // Update stats
        entitiesProcessed.addAndGet(count);
        batchesProcessed.addAndGet((count + BATCH_SIZE - 1) / BATCH_SIZE);
        parallelMs.addAndGet((System.nanoTime() - startTime) / 1_000_000);

        logStats();
    }

    /**
     * Calculate visibility for entities in batch.
     * Returns boolean array indicating if each entity should render.
     */
    public static boolean[] batchCalculateVisibility(
            List<Entity> entities, double playerX, double playerY, double playerZ, double maxDistanceSq) {

        int count = entities.size();
        boolean[] visible = new boolean[count];

        // Build positions array
        double[] positions = new double[count * 3];
        for (int i = 0; i < count; i++) {
            Entity entity = entities.get(i);
            positions[i * 3] = entity.getX();
            positions[i * 3 + 1] = entity.getY();
            positions[i * 3 + 2] = entity.getZ();
        }

        // Get distances via Rust
        double[] distancesSq = BatchEntityProcessor.batchDistanceSqToPoint(
                positions, playerX, playerY, playerZ, count);

        // Check visibility
        for (int i = 0; i < count; i++) {
            visible[i] = distancesSq[i] <= maxDistanceSq;
        }

        return visible;
    }

    /**
     * Get pool parallelism level.
     */
    public static int getParallelism() {
        return entityPool.getParallelism();
    }

    /**
     * Get active thread count.
     */
    public static int getActiveThreads() {
        return entityPool.getActiveThreadCount();
    }

    /**
     * Get statistics.
     */
    public static String getStatistics() {
        long entities = entitiesProcessed.get();
        long batches = batchesProcessed.get();
        long ms = parallelMs.get();
        double avgBatch = batches > 0 ? (double) entities / batches : 0;

        return String.format(
                "ParallelEntity{entities=%d, batches=%d, avgBatch=%.1f, totalMs=%d, threads=%d}",
                entities, batches, avgBatch, ms, THREAD_COUNT);
    }

    private static void logStats() {
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 60000) {
            long currentEntities = entitiesProcessed.get();
            long currentBatches = batchesProcessed.get();
            long currentMs = parallelMs.get();

            long deltaEntities = currentEntities - lastLogEntities;
            long deltaBatches = currentBatches - lastLogBatches;
            long deltaMs = currentMs - lastLogMs;

            double avgBatch = deltaBatches > 0 ? (double) deltaEntities / deltaBatches : 0;

            LOGGER.info("ParallelEntityTicker (Last 60s): entities={}, batches={}, avgBatch={:.1f}, ms={}",
                    deltaEntities, deltaBatches, avgBatch, deltaMs);

            lastLogEntities = currentEntities;
            lastLogBatches = currentBatches;
            lastLogMs = currentMs;
            lastLogTime = now;
        }
    }

    public static long getTotalEntitiesProcessed() {
        return entitiesProcessed.get();
    }

    /**
     * Shutdown the entity pool.
     */
    public static void shutdown() {
        entityPool.shutdown();
        LOGGER.info("ParallelEntityTicker shutdown. Stats: {}", getStatistics());
    }

    /**
     * Functional interface for entity processing.
     */
    @FunctionalInterface
    public interface EntityProcessor {
        void process(Entity entity);
    }
}
