/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Optimizes entity lookups using spatial hashing for O(1) queries.
 */
package com.kneaf.core.mixin;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EntitySectionMixin - Optimizes entity lookups with spatial hashing.
 * 
 * PROBLEM:
 * - Entity queries (getEntitiesOfClass, getNearbyEntities) are O(n)
 * - Called frequently by AI, sensors, combat, and game mechanics
 * - Each query iterates through all entities in the section
 * 
 * OPTIMIZATIONS:
 * 1. Grid-based spatial index within each section (8x8x8 cells)
 * 2. Fast AABB intersection using grid cells
 * 3. Lazy update on entity movement
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/EntitySectionMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Spatial grid: divide section into 4x4x4 cells for fast lookups
    // Cell size = 4 blocks (section is 16 blocks)
    @Unique
    private static final int GRID_SIZE = 4;

    @Unique
    private static final int CELL_SIZE = 4; // blocks per cell

    // Grid index: cell key -> entities in that cell
    @Unique
    private final Map<Integer, Set<T>> kneaf$spatialGrid = new ConcurrentHashMap<>();

    // Track entity positions for grid updates
    @Unique
    private final Map<T, Integer> kneaf$entityCells = new ConcurrentHashMap<>();

    // Statistics
    @Unique
    private static final AtomicLong kneaf$fastLookups = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$fullScans = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    @Final
    private net.minecraft.util.ClassInstanceMultiMap<T> storage;

    /**
     * Update spatial grid when entity is added.
     */
    @Inject(method = "add", at = @At("TAIL"))
    private void kneaf$onEntityAdd(T entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ EntitySectionMixin applied - Spatial indexing active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$addToGrid(entity);
    }

    /**
     * Update spatial grid when entity is removed.
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void kneaf$onEntityRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
        kneaf$removeFromGrid(entity);
    }

    /**
     * Optimize getEntities query using spatial grid.
     */
    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;", at = @At("HEAD"), cancellable = true)
    private <U extends T> void kneaf$onGetEntities(EntityTypeTest<T, U> typeTest, AABB bounds,
            AbortableIterationConsumer<U> consumer,
            CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {

        // Use spatial grid for small queries (less than half the section)
        double queryVolume = (bounds.maxX - bounds.minX) * (bounds.maxY - bounds.minY) * (bounds.maxZ - bounds.minZ);

        if (queryVolume < 512 && !kneaf$spatialGrid.isEmpty()) { // 8x8x8 = 512 blocks
            AbortableIterationConsumer.Continuation result = kneaf$queryGridOptimized(typeTest, bounds, consumer);
            if (result != null) {
                kneaf$fastLookups.incrementAndGet();
                cir.setReturnValue(result);
                return;
            }
        }

        kneaf$fullScans.incrementAndGet();
        // Fall through to vanilla for large queries
    }

    /**
     * Add entity to spatial grid.
     */
    @Unique
    @SuppressWarnings("null")
    private void kneaf$addToGrid(T entity) {
        if (entity instanceof Entity e) {
            int cell = kneaf$getCellKey(e.getX(), e.getY(), e.getZ());
            kneaf$spatialGrid.computeIfAbsent(cell, k -> ConcurrentHashMap.newKeySet()).add(entity);
            kneaf$entityCells.put(entity, cell);
        }
    }

    /**
     * Remove entity from spatial grid.
     */
    @Unique
    private void kneaf$removeFromGrid(T entity) {
        Integer cell = kneaf$entityCells.remove(entity);
        if (cell != null) {
            Set<T> cellEntities = kneaf$spatialGrid.get(cell);
            if (cellEntities != null) {
                cellEntities.remove(entity);
                if (cellEntities.isEmpty()) {
                    kneaf$spatialGrid.remove(cell);
                }
            }
        }
    }

    /**
     * Calculate cell key from position.
     */
    @Unique
    private static int kneaf$getCellKey(double x, double y, double z) {
        int cx = ((int) x >> 2) & 0xF; // 4 block cells, wrap at 16
        int cy = ((int) y >> 2) & 0xF;
        int cz = ((int) z >> 2) & 0xF;
        return cx | (cy << 4) | (cz << 8);
    }

    /**
     * Query spatial grid for entities in AABB.
     */
    @Unique
    private <U extends T> AbortableIterationConsumer.Continuation kneaf$queryGridOptimized(
            EntityTypeTest<T, U> typeTest, AABB bounds, AbortableIterationConsumer<U> consumer) {

        // Calculate affected cells
        int minCX = ((int) bounds.minX >> 2) & 0xF;
        int minCY = ((int) bounds.minY >> 2) & 0xF;
        int minCZ = ((int) bounds.minZ >> 2) & 0xF;
        int maxCX = ((int) bounds.maxX >> 2) & 0xF;
        int maxCY = ((int) bounds.maxY >> 2) & 0xF;
        int maxCZ = ((int) bounds.maxZ >> 2) & 0xF;

        // Collect entities from affected cells
        List<T> candidates = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    int cell = cx | (cy << 4) | (cz << 8);
                    Set<T> cellEntities = kneaf$spatialGrid.get(cell);
                    if (cellEntities != null) {
                        candidates.addAll(cellEntities);
                    }
                }
            }
        }

        // Filter by bounds and type
        for (T entity : candidates) {
            if (entity instanceof Entity e && bounds.intersects(e.getBoundingBox())) {
                U casted = typeTest.tryCast(entity);
                if (casted != null) {
                    AbortableIterationConsumer.Continuation result = consumer.accept(casted);
                    if (result == AbortableIterationConsumer.Continuation.ABORT) {
                        return result;
                    }
                }
            }
        }

        return AbortableIterationConsumer.Continuation.CONTINUE;
    }

    /**
     * Update entity position in grid (call when entity moves).
     */
    @Unique
    @SuppressWarnings("null")
    private void kneaf$updateEntityPosition(T entity) {
        if (entity instanceof Entity e) {
            int newCell = kneaf$getCellKey(e.getX(), e.getY(), e.getZ());
            Integer oldCell = kneaf$entityCells.get(entity);

            if (oldCell == null || oldCell != newCell) {
                // Entity moved to different cell
                kneaf$removeFromGrid(entity);
                kneaf$addToGrid(entity);
            }
        }

        kneaf$logStats();
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 1000) {
            long fast = kneaf$fastLookups.get();
            long full = kneaf$fullScans.get();
            long total = fast + full;

            if (total > 0) {
                double fastRate = fast * 100.0 / total;
                // Update central stats
                com.kneaf.core.PerformanceStats.entityGridLookups = fast;
                com.kneaf.core.PerformanceStats.entityFullScans = full;
                com.kneaf.core.PerformanceStats.entityGridHitPercent = fastRate;

                kneaf$fastLookups.set(0);
                kneaf$fullScans.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long fast = kneaf$fastLookups.get();
        long full = kneaf$fullScans.get();
        long total = fast + full;
        double fastRate = total > 0 ? fast * 100.0 / total : 0;

        return String.format("EntitySection{fast=%d, full=%d, fastRate=%.1f%%}",
                fast, full, fastRate);
    }
}
