/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import com.kneaf.core.RustOptimizations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MobMixin - Advanced mob AI optimization with Rust native support.
 * 
 * Target: net.minecraft.world.entity.Mob
 * 
 * Optimizations:
 * 1. Distance-based AI throttling (far mobs process AI less)
 * 2. Target caching with validation interval
 * 3. AI priority evaluation using Rust native batch processing
 * 4. Skip AI for very distant mobs entirely
 */
@Mixin(Mob.class)
public abstract class MobMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/MobMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$aiTickCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$aiSkipCount = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Per-mob caching
    @Unique
    private int kneaf$lastTargetCheckTick = 0;

    @Unique
    private boolean kneaf$hasTarget = false;

    @Unique
    private int kneaf$aiPriority = 2; // Default: medium priority

    @Unique
    private double kneaf$cachedDistanceSq = 0.0;

    // Configuration
    @Unique
    private static final int TARGET_CHECK_INTERVAL = 10; // Check target every 10 ticks

    @Unique
    private static final int DISTANCE_CHECK_INTERVAL = 20; // Update distance every second

    @Unique
    private int kneaf$lastDistanceCheckTick = 0;

    /**
     * Track mob AI ticking with priority-based throttling.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ MobMixin applied - AI priority throttling active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$aiTickCount.incrementAndGet();

        Mob self = (Mob) (Object) this;
        int tickCount = self.tickCount;

        // Update distance and priority periodically
        if (tickCount - kneaf$lastDistanceCheckTick >= DISTANCE_CHECK_INTERVAL) {
            kneaf$updateAIPriority(self);
            kneaf$lastDistanceCheckTick = tickCount;
        }

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long total = kneaf$aiTickCount.get();
            long skipped = kneaf$aiSkipCount.get();
            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                kneaf$LOGGER.info("MobAI: {} ticks, {}% throttled, avg priority {}",
                        total, String.format("%.1f", skipRate), kneaf$aiPriority);
                kneaf$aiTickCount.set(0);
                kneaf$aiSkipCount.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Update AI priority based on distance and target status.
     */
    @Unique
    private void kneaf$updateAIPriority(Mob mob) {
        if (mob.level() instanceof ServerLevel level) {
            // Find nearest player distance
            double minDistSq = Double.MAX_VALUE;
            for (var player : level.players()) {
                if (player.isSpectator())
                    continue;
                double distSq = mob.distanceToSqr(player);
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                }
            }
            kneaf$cachedDistanceSq = minDistSq;

            // Use Rust for priority calculation if available
            if (RustOptimizations.isAvailable()) {
                try {
                    int[] hasTarget = new int[] { mob.getTarget() != null ? 1 : 0 };
                    double[] distances = new double[] { minDistSq };
                    int[] priorities = RustOptimizations.mobAIPriorities(hasTarget, distances, 1);
                    if (priorities != null && priorities.length > 0) {
                        kneaf$aiPriority = priorities[0];
                        kneaf$hasTarget = hasTarget[0] != 0;
                        return;
                    }
                } catch (Exception e) {
                    // Fall through to Java calculation
                }
            }

            // Java fallback priority calculation
            kneaf$hasTarget = mob.getTarget() != null;
            double nearSq = 32.0 * 32.0;
            double farSq = 64.0 * 64.0;

            if (kneaf$hasTarget) {
                kneaf$aiPriority = 3; // High - has target
            } else if (minDistSq <= nearSq) {
                kneaf$aiPriority = 2; // Medium - near player
            } else if (minDistSq <= farSq) {
                kneaf$aiPriority = 1; // Low - moderate distance
            } else {
                kneaf$aiPriority = 0; // Skip - too far
            }
        }
    }

    /**
     * Optimize serverAiStep - priority-based AI throttling.
     */
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void kneaf$onServerAiStep(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        int tickCount = self.tickCount;

        // Update target cache periodically
        if (tickCount - kneaf$lastTargetCheckTick >= TARGET_CHECK_INTERVAL) {
            kneaf$hasTarget = self.getTarget() != null;
            kneaf$lastTargetCheckTick = tickCount;
        }

        // Apply throttling based on AI priority
        switch (kneaf$aiPriority) {
            case 0: // Skip - very far from players, no target
                if (tickCount % 16 != 0) {
                    kneaf$aiSkipCount.incrementAndGet();
                    ci.cancel();
                }
                break;
            case 1: // Low priority - every 4 ticks
                if (tickCount % 4 != 0) {
                    kneaf$aiSkipCount.incrementAndGet();
                    ci.cancel();
                }
                break;
            case 2: // Medium priority - every 2 ticks
                if (tickCount % 2 != 0) {
                    kneaf$aiSkipCount.incrementAndGet();
                    ci.cancel();
                }
                break;
            case 3: // High priority (has target) - every tick
                break;
        }
    }
}
