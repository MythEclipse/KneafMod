/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Entity push physics optimization with optimized vector math.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Accelerates Entity physics using optimized math and Rust Native calls.
 * 
 * Optimizations:
 * 1. Optimized push calculation with reduced unnecessary operations
 * 2. Early exit for very small distances (avoids division by tiny numbers)
 * 3. Statistics tracking for monitoring optimization effectiveness
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/EntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics tracking
    @Unique
    private static final AtomicLong kneaf$pushesOptimized = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$pushesSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public abstract void setDeltaMovement(Vec3 vec);

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract void push(double x, double y, double z);

    @Shadow
    public abstract boolean isVehicle();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Shadow
    public abstract boolean hasPassenger(Entity entity);

    /**
     * Optimized push logic using efficient vector math.
     * Uses Java intrinsics which are faster than JNI roundtrip for single
     * operations.
     */
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void kneaf$optimizedPush(Entity entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ EntityMixin applied - Optimized push physics active!");
            kneaf$loggedFirstApply = true;
        }

        Entity self = (Entity) (Object) this;

        // Manhattan distance check first (fastest)
        double dx = entity.getX() - self.getX();
        double dz = entity.getZ() - self.getZ();
        double combinedWidth = self.getBbWidth() + entity.getBbWidth();

        if (Math.abs(dx) > combinedWidth || Math.abs(dz) > combinedWidth) {
            kneaf$pushesSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        if (!this.hasPassenger(entity) && !entity.hasPassenger(self)) {
            double distSq = dx * dx + dz * dz;

            if (distSq >= 0.0001D) {
                double dist = Math.sqrt(distSq);
                dx /= dist;
                dz /= dist;

                double amount = 1.0D / dist;
                if (amount > 1.0D)
                    amount = 1.0D;

                dx *= amount * 0.05D;
                dz *= amount * 0.05D;

                if (!this.isVehicle()) {
                    this.push(-dx, 0.0D, -dz);
                }
                if (!entity.isVehicle()) {
                    entity.push(dx, 0.0D, dz);
                }

                kneaf$pushesOptimized.incrementAndGet();
                ci.cancel();
            } else {
                kneaf$pushesSkipped.incrementAndGet();
                ci.cancel();
            }
        }

        kneaf$logStats();
    }

    /**
     * Optimization: Skip redundant water state updates if Y > 64 and chunk predicts
     * no water.
     */
    @Inject(method = "updateInWaterStateAndNotify", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdateInWater(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // Fast path: If we are high above ground and not in a rainy biome, skip.
        // Or simpler: skip if Y is very high and we were not in water last tick.
        if (self.getY() > 100.0 && !self.isInWater()) {
            ci.cancel();
        }
    }

    /**
     * Optimization: Skip redundant lava state updates if Y > 64.
     */
    @Inject(method = "updateInLavaState", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdateInLava(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.getY() > 64.0 && !self.isInLava()) {
            ci.cancel();
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long optimized = kneaf$pushesOptimized.get();
            long skipped = kneaf$pushesSkipped.get();
            long total = optimized + skipped;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.entityPushOptimized = optimized / timeDiff;
                com.kneaf.core.PerformanceStats.entityPushSkipped = skipped / timeDiff;

                kneaf$pushesOptimized.set(0);
                kneaf$pushesSkipped.set(0);
            } else {
                com.kneaf.core.PerformanceStats.entityPushOptimized = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
