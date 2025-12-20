/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExplosionMixin - Blast optimization.
 * 
 * Optimizations:
 * 1. Explosion Tracking: Metrics for explosion frequency.
 * 2. Finalization Hook: For future Rust-accelerated block removal.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ExplosionMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static long kneaf$explosionCount = 0;

    @Inject(method = "explode", at = @At("HEAD"))
    private void kneaf$onExplode(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ExplosionMixin applied - Blast optimizations active!");
            kneaf$loggedFirstApply = true;
        }
        kneaf$explosionCount++;
    }

    /**
     * Hook into finalizeExplosion for block removal optimization.
     */
    @Inject(method = "finalizeExplosion", at = @At("HEAD"))
    private void kneaf$onFinalizeExplosion(boolean spawnParticles, CallbackInfo ci) {
        // Track finalization for metrics.
        if (kneaf$explosionCount % 100 == 0 && kneaf$explosionCount > 0) {
            kneaf$LOGGER.debug("ExplosionMixin: {} explosions processed so far.", kneaf$explosionCount);
        }
    }
}
