/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityPushMixin {

    @Unique
    private static final double kneaf$MIN_PUSH_DIST_SQR = 0.01;

    /**
     * Optimization: Fail-fast entity pushing.
     * Before doing AABB intersection (which involves min/max checks),
     * do a simple center-point distance check.
     */
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void kneaf$optimizePush(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        // Condition 1: If either entity is removed, stop.
        if (self.isRemoved() || other.isRemoved()) {
            ci.cancel();
            return;
        }

        // Condition 2: If connected (vehicle/passenger), stop (vanilla usually checks
        // this, but let's be sure)
        if (self.isPassengerOfSameVehicle(other)) {
            ci.cancel();
            return;
        }

        // Condition 3: Simple distance check
        // If center points are too far apart, AABB definitely won't intersect
        // (approximation)
        // Or if they are virtually at the same spot (delta ~ 0) but not intersecting
        // due to offsets?
        // Actually, vanilla calculates delta X and Z.

        // Let's do a Manhattan distance check on horizontal plane first.
        // Bounding boxes are usually < 1-2 blocks wide.
        // If dx > width1 + width2, no collision.
        double dx = other.getX() - self.getX();
        double dz = other.getZ() - self.getZ();

        double combinedWidth = self.getBbWidth() + other.getBbWidth();

        if (Math.abs(dx) > combinedWidth || Math.abs(dz) > combinedWidth) {
            ci.cancel();
            return;
        }

        // If we passed the fast check, let vanilla do the precise AABB intersection and
        // pushing.
    }
}
