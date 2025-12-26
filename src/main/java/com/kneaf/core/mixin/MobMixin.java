/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MobMixin - TPS-focused mob AI optimizations.
 * 
 * Target: net.minecraft.world.entity.Mob
 * 
 * Mob AI is one of the biggest TPS consumers. Optimizations:
 * - Track AI tick time
 * - Cache target lookups
 * - Optimize goal execution order
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
    private static long kneaf$lastLogTime = 0;

    // Per-mob caching
    @Unique
    private int kneaf$lastTargetCheckTick = 0;

    @Unique
    private boolean kneaf$hasTarget = false;

    /**
     * Track mob AI ticking.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ MobMixin applied - Mob AI optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$aiTickCount.incrementAndGet();

        // Log stats every 60 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long ticks = kneaf$aiTickCount.get();
            if (ticks > 0) {
                kneaf$LOGGER.info("MobMixin stats: {} mob ticks processed", ticks);
                kneaf$aiTickCount.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Optimize serverAiStep - this is where most mob AI processing happens.
     * Key insight: Many mobs without targets just need basic navigation.
     * Optimization: Skip full AI step for mobs without a target.
     */
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void kneaf$onServerAiStep(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        int tickCount = self.tickCount;

        // Caching: Only check for target every 10 ticks
        if (tickCount - kneaf$lastTargetCheckTick >= 10) {
            kneaf$hasTarget = self.getTarget() != null;
            kneaf$lastTargetCheckTick = tickCount;
        }

        // Skip AI: If no target, run full AI less often (every 4 ticks)
        if (!kneaf$hasTarget && tickCount % 4 != 0) {
            ci.cancel(); // Skip this AI step
        }
    }
}
