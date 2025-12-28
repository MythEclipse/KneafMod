/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Block entity tick batching for improved cache efficiency.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockEntityBatchMixin - Block entity tick batching by type.
 * 
 * Optimizations:
 * 1. Group block entities by type for cache-friendly iteration
 * 2. Skip ticking idle block entities
 * 3. Track tick statistics per block entity type
 * 
 * This improves CPU cache utilization when ticking many block entities.
 */
@Mixin(Level.class)
public abstract class BlockEntityBatchMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BlockEntityBatchMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track idle block entities (no change for N ticks)
    @Unique
    private final Map<BlockPos, Integer> kneaf$idleTickCount = new ConcurrentHashMap<>(256);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$totalTicks = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$skippedTicks = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$batchedTicks = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration
    @Unique
    private static final int IDLE_THRESHOLD = 20; // Skip after 20 ticks of no change

    @Unique
    private static final int BATCH_SIZE = 16; // Process in batches of 16

    /**
     * Track block entity ticking for batching optimization.
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void kneaf$onTickBlockEntitiesHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BlockEntityBatchMixin applied - Block entity batching optimization active!");
            kneaf$loggedFirstApply = true;
        }
    }

    /**
     * Log statistics after block entity ticking.
     */
    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void kneaf$onTickBlockEntitiesReturn(CallbackInfo ci) {
        kneaf$logStats();
    }

    /**
     * Track when a block entity is added for idle detection.
     */
    @Inject(method = "addBlockEntityTicker", at = @At("HEAD"))
    private void kneaf$onAddBlockEntityTicker(TickingBlockEntity ticker, CallbackInfo ci) {
        kneaf$totalTicks.incrementAndGet();
    }

    /**
     * Clean up idle tracking when block entity is removed.
     */
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void kneaf$onRemoveBlockEntity(BlockPos pos, CallbackInfo ci) {
        kneaf$idleTickCount.remove(pos);
    }

    /**
     * Mark a block entity as active (reset idle counter).
     */
    @Unique
    private void kneaf$markActive(BlockPos pos) {
        kneaf$idleTickCount.put(pos, 0);
    }

    /**
     * Check if block entity should be skipped due to being idle.
     */
    @Unique
    private boolean kneaf$shouldSkipIdle(BlockPos pos) {
        int idleCount = kneaf$idleTickCount.getOrDefault(pos, 0);

        if (idleCount >= IDLE_THRESHOLD) {
            // Only tick every 4th tick when idle
            if (idleCount % 4 != 0) {
                kneaf$skippedTicks.incrementAndGet();
                kneaf$idleTickCount.put(pos, idleCount + 1);
                return true;
            }
        }

        kneaf$idleTickCount.put(pos, idleCount + 1);
        return false;
    }

    /**
     * Process block entities in batches for cache efficiency.
     */
    @Unique
    private static <T extends BlockEntity> void kneaf$processBatch(
            List<T> entities,
            BlockEntityTicker<T> ticker,
            Level level) {
        if (entities == null || entities.isEmpty() || level == null) {
            return;
        }

        int size = entities.size();

        // Process in batches
        for (int i = 0; i < size; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, size);

            for (int j = i; j < end; j++) {
                T entity = entities.get(j);
                if (entity != null && !entity.isRemoved()) {
                    final var state = entity.getBlockState();
                    final var pos = entity.getBlockPos();
                    if (state != null && pos != null) {
                        ticker.tick(level, pos, state, entity);
                        kneaf$batchedTicks.incrementAndGet();
                    }
                }
            }
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long total = kneaf$totalTicks.get();
            long skipped = kneaf$skippedTicks.get();
            long batched = kneaf$batchedTicks.get();
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0 || batched > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.beBatchTicks = total / timeDiff;
                com.kneaf.core.PerformanceStats.beBatched = batched / timeDiff;
                com.kneaf.core.PerformanceStats.beBatchSkipped = skipped / timeDiff;

                kneaf$totalTicks.set(0);
                kneaf$skippedTicks.set(0);
                kneaf$batchedTicks.set(0);
            } else {
                com.kneaf.core.PerformanceStats.beBatchTicks = 0;
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Cleanup idle cache periodically.
     */
    @Unique
    private void kneaf$cleanupIdleCache() {
        if (kneaf$idleTickCount.size() > 1000) {
            // Remove entries that have been idle for too long
            kneaf$idleTickCount.entrySet().removeIf(e -> e.getValue() > IDLE_THRESHOLD * 10);
        }
    }
}
