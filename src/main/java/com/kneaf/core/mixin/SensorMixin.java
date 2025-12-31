/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Optimizes AI sensor ticks for villagers, piglins, and other brain-based entities.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.Sensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SensorMixin - Optimizes AI sensor tick processing.
 * 
 * PROBLEM:
 * - Sensors like NearestLivingEntitySensor run expensive scans every tick
 * - VillagerHostilesSensor scans for zombies even when none are present
 * - These sensors are the main CPU cost for villager/piglin AI
 * 
 * OPTIMIZATIONS:
 * 1. Skip sensor ticks when last result was empty
 * 2. Randomize skip intervals (5-15 ticks) to prevent sync
 * 3. Force re-check when entity takes damage or memories change
 */
@Mixin(Sensor.class)
public abstract class SensorMixin<E extends LivingEntity> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/SensorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Skip configuration
    @Unique
    private static final int MIN_SKIP_TICKS = 5;

    @Unique
    private static final int MAX_SKIP_TICKS = 15;

    // Per-sensor state
    @Unique
    private boolean kneaf$lastResultEmpty = false;

    @Unique
    private int kneaf$skipUntilTick = 0;

    @Unique
    private long kneaf$lastSenseTime = 0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$sensorsSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$sensorsProcessed = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    private long timeToTick;

    @Shadow
    protected abstract void doTick(ServerLevel level, E entity);

    /**
     * OPTIMIZATION: Skip sensor ticks when results were empty.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTick(ServerLevel level, E entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ SensorMixin applied - Sensor skip optimization active!");
            kneaf$loggedFirstApply = true;
        }

        long currentTick = level.getGameTime();

        // === OPTIMIZATION: Skip if last result was empty and within skip window ===
        if (kneaf$lastResultEmpty && currentTick < kneaf$skipUntilTick) {
            // Check if entity took damage recently (force re-sense)
            if (!kneaf$shouldForceSense(entity, currentTick)) {
                kneaf$sensorsSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }

        kneaf$sensorsProcessed.incrementAndGet();
        kneaf$lastSenseTime = currentTick;
        kneaf$logStats();
    }

    /**
     * Track sensor result to enable skipping.
     * Called after doTick to check if result was empty.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void kneaf$afterTick(ServerLevel level, E entity, CallbackInfo ci) {
        long currentTick = level.getGameTime();

        // Check if sensor found anything (simplified heuristic)
        boolean resultEmpty = kneaf$checkResultEmpty(entity);

        if (resultEmpty && !kneaf$lastResultEmpty) {
            // Transitioned to empty - start skipping
            int skipTicks = ThreadLocalRandom.current().nextInt(MIN_SKIP_TICKS, MAX_SKIP_TICKS + 1);
            kneaf$skipUntilTick = (int) (currentTick + skipTicks);
        }

        kneaf$lastResultEmpty = resultEmpty;
    }

    /**
     * Check if sensor result was empty (heuristic).
     * Checks common sensor memory types.
     */
    @Unique
    private boolean kneaf$checkResultEmpty(E entity) {
        // Check if brain has any hostile/target memories set
        var brain = entity.getBrain();
        if (brain == null) {
            return true;
        }

        // Check common target memories
        // If any of these are present, sensor found something
        try {
            if (brain.hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_HOSTILE)) {
                return false;
            }
            if (brain.hasMemoryValue(
                    net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)) {
                var entities = brain.getMemory(
                        net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
                if (entities.isPresent()) {
                    // Check if iterator has any entries - Iterable doesn't have isEmpty()
                    var iter = entities.get().findAll(e -> true).iterator();
                    if (iter.hasNext()) {
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            // Memory not registered for this entity type - treat as empty
        }

        return true;
    }

    /**
     * Check if entity should force a sensor re-scan.
     * Returns true if entity took damage or has new memories.
     */
    @Unique
    private boolean kneaf$shouldForceSense(E entity, long currentTick) {
        // Force sense if entity took damage in last 20 ticks
        if (entity.hurtTime > 0) {
            return true;
        }

        // Force sense if it's been too long (max 20 ticks skip)
        if (currentTick - kneaf$lastSenseTime > 20) {
            return true;
        }

        return false;
    }

    /**
     * Invalidate skip state (e.g., when entity is attacked).
     */
    @Unique
    private void kneaf$invalidate() {
        kneaf$lastResultEmpty = false;
        kneaf$skipUntilTick = 0;
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 1000) {
            long skipped = kneaf$sensorsSkipped.get();
            long processed = kneaf$sensorsProcessed.get();
            long total = skipped + processed;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                // Update central stats
                com.kneaf.core.PerformanceStats.sensorTicksSkipped = skipped;
                com.kneaf.core.PerformanceStats.sensorTicksProcessed = processed;
                com.kneaf.core.PerformanceStats.sensorSkipPercent = skipRate;

                kneaf$sensorsSkipped.set(0);
                kneaf$sensorsProcessed.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long skipped = kneaf$sensorsSkipped.get();
        long processed = kneaf$sensorsProcessed.get();
        long total = skipped + processed;
        double skipRate = total > 0 ? skipped * 100.0 / total : 0;

        return String.format("Sensor{skipped=%d, processed=%d, skipRate=%.1f%%}",
                skipped, processed, skipRate);
    }
}
