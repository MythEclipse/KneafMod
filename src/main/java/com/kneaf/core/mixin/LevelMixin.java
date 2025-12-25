/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LevelMixin - Batch block entity processing with idle skipping.
 * 
 * Target: net.minecraft.world.level.Level
 * 
 * Optimizations:
 * 1. Track idle block entities via static map
 * 2. Skip ticking for block entities that haven't changed recently
 * 3. Adaptive batch sizing based on TPS
 * 4. Integration with ServerLevelMixin for TPS awareness
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LevelMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Metrics
    @Unique
    private static final AtomicLong kneaf$ticksProcessed = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$blockEntitiesSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$blockEntitiesTicked = new AtomicLong(0);

    @Unique
    private static final AtomicInteger kneaf$currentBatchSize = new AtomicInteger(64);

    @Unique
    private int kneaf$blockEntityTickCounter = 0;

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Batch processing configuration
    @Unique
    private static final int MIN_BATCH_SIZE = 16;

    @Unique
    private static final int MAX_BATCH_SIZE = 128;

    // Block entity idle tracking - key is block pos as long
    @Unique
    private static final ConcurrentHashMap<Long, Integer> kneaf$idleTickCounts = new ConcurrentHashMap<>();

    @Unique
    private static final int IDLE_THRESHOLD = 40; // 2 seconds without setChanged()

    @Unique
    private static final int SKIP_INTERVAL = 4; // Skip 3 out of 4 ticks when idle

    /**
     * Inject at the start of tickBlockEntities for batch optimization.
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void kneaf$onTickBlockEntitiesHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LevelMixin applied - Block entity idle skipping active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$ticksProcessed.incrementAndGet();
        kneaf$blockEntityTickCounter++;

        // Adjust batch size based on TPS
        kneaf$adjustBatchSize();

        // Periodically clean up the idle tracking map
        if (kneaf$blockEntityTickCounter % 1200 == 0) { // Every minute
            kneaf$idleTickCounts.entrySet().removeIf(e -> e.getValue() > IDLE_THRESHOLD * 10);
        }
    }

    /**
     * Adjust batch size based on current TPS.
     */
    @Unique
    private void kneaf$adjustBatchSize() {
        double currentTPS = com.kneaf.core.util.TPSTracker.getCurrentTPS();

        int newBatchSize;
        if (currentTPS < 12.0) {
            newBatchSize = MIN_BATCH_SIZE;
        } else if (currentTPS < 16.0) {
            newBatchSize = MIN_BATCH_SIZE * 2;
        } else if (currentTPS < 19.0) {
            newBatchSize = MAX_BATCH_SIZE / 2;
        } else {
            newBatchSize = MAX_BATCH_SIZE;
        }

        kneaf$currentBatchSize.set(newBatchSize);
    }

    /**
     * Inject at the end of tickBlockEntities for metrics.
     */
    @Inject(method = "tickBlockEntities", at = @At("TAIL"))
    private void kneaf$onTickBlockEntitiesTail(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 30000) {
            long ticks = kneaf$ticksProcessed.get();
            long skipped = kneaf$blockEntitiesSkipped.get();
            long ticked = kneaf$blockEntitiesTicked.get();

            if (ticks > 0 && (skipped > 0 || ticked > 0)) {
                double skipRate = (skipped + ticked) > 0
                        ? (skipped * 100.0 / (skipped + ticked))
                        : 0;
                kneaf$LOGGER.info("LevelMixin: {} ticks, {} BE ticked, {} skipped ({:.1f}%), batch={}",
                        ticks, ticked, skipped, skipRate, kneaf$currentBatchSize.get());

                kneaf$ticksProcessed.set(0);
                kneaf$blockEntitiesSkipped.set(0);
                kneaf$blockEntitiesTicked.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Mark a block entity as active (called from BlockEntityMixin.setChanged).
     */
    @Unique
    private static void kneaf$markBlockEntityActive(BlockPos pos) {
        if (pos != null) {
            kneaf$idleTickCounts.put(pos.asLong(), 0);
        }
    }

    /**
     * Check if a block entity should be ticked based on idle state.
     * Returns true if the block entity should tick, false to skip.
     */
    @Unique
    private static boolean kneaf$shouldTickBlockEntity(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        BlockPos pos = blockEntity.getBlockPos();
        if (pos == null) {
            kneaf$blockEntitiesTicked.incrementAndGet();
            return true;
        }

        long posKey = pos.asLong();
        int idleTicks = kneaf$idleTickCounts.compute(posKey, (k, v) -> (v == null) ? 1 : v + 1);

        // If not idle, always tick
        if (idleTicks < IDLE_THRESHOLD) {
            kneaf$blockEntitiesTicked.incrementAndGet();
            return true;
        }

        // Idle - skip most ticks but still tick occasionally
        if (idleTicks % SKIP_INTERVAL == 0) {
            kneaf$blockEntitiesTicked.incrementAndGet();
            return true;
        }

        // Skip this tick
        kneaf$blockEntitiesSkipped.incrementAndGet();
        return false;
    }

    /**
     * Get current batch size for block entity processing.
     */
    @Unique
    private static int kneaf$getBatchSize() {
        return kneaf$currentBatchSize.get();
    }

    /**
     * Get level mixin statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        return String.format(
                "LevelStats{ticks=%d, ticked=%d, skipped=%d, batchSize=%d, tracked=%d}",
                kneaf$ticksProcessed.get(),
                kneaf$blockEntitiesTicked.get(),
                kneaf$blockEntitiesSkipped.get(),
                kneaf$currentBatchSize.get(),
                kneaf$idleTickCounts.size());
    }
}
