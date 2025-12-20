/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LivingEntityMixin - Radium/Lithium-style optimizations for LivingEntity.
 * 
 * Optimizations:
 * - Track entity tick stats
 * - Early exit for common cases
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LivingEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private int kneaf$tickCounter = 0;

    /**
     * Log when mixin is applied and track ticks.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LivingEntityMixin applied - entity optimizations active!");
            kneaf$loggedFirstApply = true;
        }
        kneaf$tickCounter++;
    }

    /**
     * Optimization: Throttle active effect ticking for performance.
     * Effects like regeneration/poison tick frequently - skip processing on
     * alternate ticks for non-player entities to reduce server load.
     */
    @Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickEffects(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // Never throttle players - effects must be precise for gameplay
        if (self instanceof net.minecraft.world.entity.player.Player) {
            return;
        }

        // Throttle effect processing for non-player entities every other tick
        // This reduces effect CPU overhead by ~50% for mobs with effects
        if (kneaf$tickCounter % 2 != 0) {
            ci.cancel();
        }
    }
}
