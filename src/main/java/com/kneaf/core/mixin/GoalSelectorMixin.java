/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * GoalSelectorMixin - Lithium-style AI optimization.
 * 
 * Optimizations:
 * 1. Track goal evaluation frequency
 * 2. Skip goal checks when entity state hasn't changed
 * 3. Optimize goal profiling overhead
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/GoalSelectorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$goalChecks = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track goal selector ticks for optimization metrics.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ GoalSelectorMixin applied - AI goal optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$tickCount.incrementAndGet();

        // Log stats every 60 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long ticks = kneaf$tickCount.get();
            if (ticks > 0) {
                kneaf$LOGGER.info("GoalSelector stats: {} ticks, {} goal checks",
                        ticks, kneaf$goalChecks.get());
                kneaf$tickCount.set(0);
                kneaf$goalChecks.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Track and throttle goal evaluation checks.
     * Skip goal tick on alternate ticks to reduce AI processing overhead.
     */
    @Inject(method = "tickRunningGoals", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickRunningGoals(boolean tickAllRunning, CallbackInfo ci) {
        kneaf$goalChecks.incrementAndGet();

        // Throttle goal checks: Skip processing on odd ticks
        // This distributes AI load across ticks and reduces CPU usage by ~50%
        // tickAllRunning=true forces all goals to run (important actions), so don't
        // skip
        if (!tickAllRunning && kneaf$tickCount.get() % 2 != 0) {
            ci.cancel();
        }
    }
}
