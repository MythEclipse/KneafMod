/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockEntityMixin - Advanced idle block entity optimization.
 * 
 * Target: net.minecraft.world.level.block.entity.BlockEntity
 * 
 * Optimizations:
 * 1. Track idle state and skip unnecessary ticks
 * 2. Adaptive sleep duration based on activity patterns
 * 3. Wake-on-change mechanism for instant response
 * 4. Statistics tracking for idle optimization effectiveness
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BlockEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$totalChanges = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$ticksSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Per-instance idle tracking
    @Unique
    private boolean kneaf$isActive = true;

    @Unique
    private int kneaf$idleTicks = 0;

    @Unique
    private int kneaf$sleepDuration = 20; // Start with 1 second sleep

    // Idle thresholds
    @Unique
    private static final int IDLE_THRESHOLD = 40; // 2 seconds of no activity

    @Unique
    private static final int MAX_SLEEP_DURATION = 100; // Max 5 seconds sleep

    @Unique
    private static final int MIN_SLEEP_DURATION = 10; // Min 0.5 seconds sleep

    /**
     * Track block entity state changes - this wakes up the block entity.
     */
    @Inject(method = "setChanged", at = @At("HEAD"))
    private void kneaf$onSetChanged(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BlockEntityMixin applied - Idle block entity optimization active!");
            kneaf$loggedFirstApply = true;
        }

        // Block entity is doing something - wake it up immediately
        kneaf$wakeUp();
        kneaf$totalChanges.incrementAndGet();

        // Notify BlockEntityTracker that this block entity is active
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getBlockPos() != null) {
            com.kneaf.core.util.BlockEntityTracker.markBlockEntityActive(self.getBlockPos());
        }

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Wake up the block entity - resets idle state.
     */
    @Unique
    private void kneaf$wakeUp() {
        kneaf$isActive = true;
        kneaf$idleTicks = 0;
        // Reduce sleep duration on activity (adaptive)
        kneaf$sleepDuration = Math.max(MIN_SLEEP_DURATION, kneaf$sleepDuration - 5);
    }

    @Unique
    private void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long changes = kneaf$totalChanges.get();
            long skipped = kneaf$ticksSkipped.get();
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (changes > 0 || skipped > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.beChanges = changes / timeDiff;
                com.kneaf.core.PerformanceStats.beSkipped = skipped / timeDiff;

                kneaf$totalChanges.set(0);
                kneaf$ticksSkipped.set(0);
            } else {
                com.kneaf.core.PerformanceStats.beChanges = 0;
            }
            kneaf$lastLogTime = now;
        }

    }

    /**
     * Check if block entity should skip this tick (idle optimization).
     * Returns true if the block entity is idle and should skip processing.
     */
    @Unique
    public boolean kneaf$shouldSkipTick() {
        if (kneaf$isActive) {
            kneaf$idleTicks++;

            // After IDLE_THRESHOLD ticks of no setChanged(), consider idle
            if (kneaf$idleTicks > IDLE_THRESHOLD) {
                kneaf$isActive = false;
                // Increase sleep duration adaptively
                kneaf$sleepDuration = Math.min(MAX_SLEEP_DURATION, kneaf$sleepDuration + 10);
            }
            return false;
        }

        // Block entity is idle - check if it's time to wake up and check state
        kneaf$idleTicks++;
        if (kneaf$idleTicks % kneaf$sleepDuration == 0) {
            // Periodic wake-up to check if anything changed
            return false;
        }

        // Skip this tick
        kneaf$ticksSkipped.incrementAndGet();
        return true;
    }

    /**
     * Check if block entity is considered idle.
     */
    @Unique
    public boolean kneaf$isIdle() {
        return !kneaf$isActive && kneaf$idleTicks > IDLE_THRESHOLD;
    }

    /**
     * Get idle ticks count.
     */
    @Unique
    public int kneaf$getIdleTicks() {
        return kneaf$idleTicks;
    }

    /**
     * Increment idle counter - called externally if needed.
     */
    @Unique
    public void kneaf$tickIdle() {
        if (!kneaf$isActive) {
            kneaf$idleTicks++;
        }
    }

    /**
     * Reset state when block entity is reactivated.
     */
    @Inject(method = "clearRemoved", at = @At("RETURN"))
    private void kneaf$onClearRemoved(CallbackInfo ci) {
        kneaf$wakeUp();
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        return String.format(
                "BlockEntityStats{changes=%d, ticksSkipped=%d}",
                kneaf$totalChanges.get(),
                kneaf$ticksSkipped.get());
    }
}
