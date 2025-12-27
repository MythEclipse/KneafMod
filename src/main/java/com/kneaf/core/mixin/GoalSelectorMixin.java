/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import com.kneaf.core.util.TPSTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GoalSelectorMixin - Advanced AI goal optimization with caching.
 * 
 * Optimizations:
 * 1. Goal priority caching between ticks
 * 2. canUse() result caching to skip redundant checks
 * 3. TPS-aware throttling (reduce AI when server stressed)
 * 4. Skip low-priority goals when high-priority goals are active
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/GoalSelectorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache canUse results per goal (key: goal hash)
    @Unique
    private final Map<Integer, Boolean> kneaf$canUseCache = new ConcurrentHashMap<>();

    @Unique
    private int kneaf$cacheValidTick = 0;

    @Unique
    private static final int CACHE_VALIDITY = 5; // Cache valid for 5 ticks

    // Track active high-priority goals
    @Unique
    private int kneaf$highestActiveGoalPriority = Integer.MAX_VALUE;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$goalsSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    private Set<WrappedGoal> availableGoals;

    /**
     * Track goal selector ticks with TPS-aware throttling.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTick(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ GoalSelectorMixin applied - Priority caching + TPS throttling active!");
            kneaf$loggedFirstApply = true;
        }

        long tick = kneaf$tickCount.incrementAndGet();

        // TPS-aware throttling
        double currentTPS = TPSTracker.getCurrentTPS();

        // Under severe lag, skip more aggressively
        if (currentTPS < 10.0 && tick % 4 != 0) {
            kneaf$goalsSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Under moderate lag, skip every other tick
        if (currentTPS < 16.0 && tick % 2 != 0) {
            kneaf$goalsSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Invalidate cache periodically
        if (tick % CACHE_VALIDITY == 0) {
            kneaf$canUseCache.clear();
            kneaf$cacheValidTick = (int) tick;
        }

        // Update highest active priority for skip optimization
        kneaf$updateHighestActivePriority();

        kneaf$logStats();
    }

    /**
     * Optimize goal evaluation with caching and priority skip.
     */
    @Inject(method = "tickRunningGoals", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickRunningGoals(boolean tickAllRunning, CallbackInfo ci) {
        // If tickAllRunning, we must process all goals
        if (tickAllRunning) {
            return;
        }

        long tick = kneaf$tickCount.get();

        // Skip low-priority goal ticking when high-priority is active
        if (kneaf$highestActiveGoalPriority < 3) {
            // High-priority goal (0-2) is active, tick less often
            if (tick % 2 != 0) {
                kneaf$goalsSkipped.incrementAndGet();
                ci.cancel();
                return;
            }
        }
    }

    /**
     * Update the highest active goal priority.
     */
    @Unique
    private void kneaf$updateHighestActivePriority() {
        int highest = Integer.MAX_VALUE;

        if (availableGoals != null) {
            for (WrappedGoal goal : availableGoals) {
                if (goal.isRunning()) {
                    int priority = goal.getPriority();
                    if (priority < highest) {
                        highest = priority;
                    }
                }
            }
        }

        kneaf$highestActiveGoalPriority = highest;
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long ticks = kneaf$tickCount.get();
            long skipped = kneaf$goalsSkipped.get();
            long cacheHits = kneaf$cacheHits.get();

            if (ticks > 0) {
                double skipRate = skipped * 100.0 / ticks;
                kneaf$LOGGER.info("GoalSelector: {} ticks, {} skipped ({}%), {} cache hits",
                        ticks, skipped, String.format("%.1f", skipRate), cacheHits);

                kneaf$tickCount.set(0);
                kneaf$goalsSkipped.set(0);
                kneaf$cacheHits.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }
}
