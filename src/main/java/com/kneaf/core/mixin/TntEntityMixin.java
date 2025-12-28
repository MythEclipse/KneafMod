/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.extension.ServerLevelExtension;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * TntEntityMixin - Optimizes TNT entity ticking and physics.
 * 
 * Optimizations:
 * 1. Physics Throttling: Skip collision/movement for TNT far from players.
 * 2. Tick Throttling: Reduce fuse update frequency when thousands of TNT exist.
 * 3. Rest Detection: Skip movement if velocity is near zero.
 */
@Mixin(PrimedTnt.class)
public abstract class TntEntityMixin {

    @Unique
    private static final AtomicInteger kneaf$activeTntCount = new AtomicInteger(0);

    @Unique
    private int kneaf$tickCounter = 0;

    @Shadow
    public abstract int getFuse();

    @Shadow
    public abstract void setFuse(int fuse);

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", at = @At("RETURN"))
    private void kneaf$onInit(CallbackInfo ci) {
        kneaf$activeTntCount.incrementAndGet();
    }

    @Inject(method = "discard", at = @At("HEAD"))
    private void kneaf$onDiscard(CallbackInfo ci) {
        kneaf$activeTntCount.decrementAndGet();
    }

    /**
     * Optimized TNT ticking.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTick(CallbackInfo ci) {
        PrimedTnt self = (PrimedTnt) (Object) this;
        Level level = self.level();

        kneaf$tickCounter++;

        // 0. Explosion Budget (Queueing System)
        // If the fuse is about to run out, check if we have budget for more explosions
        // this tick.
        if (getFuse() <= 1 && !level.isClientSide) {
            if (!com.kneaf.core.util.ExplosionControl.tryExplode(level.getGameTime())) {
                // Budget full! Defer this explosion to the next tick.
                // This spreads the load and prevents TPS drop to 0.
                if (getFuse() <= 0) {
                    // Small visual hint that it's primed but waiting
                    @SuppressWarnings("null")
                    net.minecraft.core.particles.ParticleOptions smoke = net.minecraft.core.particles.ParticleTypes.SMOKE;
                    if (smoke != null) {
                        level.addParticle(smoke, self.getX(), self.getY() + 0.5, self.getZ(), 0.0, 0.1, 0.0);
                    }
                }
                ci.cancel();
                return;
            }
        }

        // 1. Get distance to nearest player (cached if on server)
        double dist = -1.0;
        if (level instanceof ServerLevelExtension) {
            dist = ((ServerLevelExtension) level).kneaf$getCachedDistance(self.getId());
        }

        // 2. Adaptive Throttling
        int tntCount = kneaf$activeTntCount.get();

        // If thousands of TNT exist, start skipping minor updates for far ones
        // This condition will not be met if dist remains -1.0
        if (tntCount > 500 && dist > 64.0) {
            // Far TNT: Update fuse every 2nd tick
            if (kneaf$tickCounter % 2 != 0) {
                ci.cancel();
                return;
            }
        }

        // 3. Movement Throttling (Gravity/Collision)
        // If extremely far (> 128) and many TNT, skip movement entirely to save CPU
        // This condition will not be met if dist remains -1.0
        if (tntCount > 200 && dist > 128.0) {
            // Just decrease fuse manually and cancel vanilla tick (which handles movement)
            int fuse = getFuse();
            if (fuse > 0) {
                setFuse(fuse - 1);
            } else {
                // Let vanilla handle the explosion
                return;
            }
            ci.cancel();
        }
    }
}
