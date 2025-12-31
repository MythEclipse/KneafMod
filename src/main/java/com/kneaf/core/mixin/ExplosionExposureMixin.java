/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.util.ExplosionControl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Explosion.class)
public abstract class ExplosionExposureMixin {

    @Inject(method = "getSeenPercent", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onGetSeenPercent(Vec3 source, Entity entity, CallbackInfoReturnable<Float> cir) {
        Float cached = ExplosionControl.getCachedExposure(source, entity.getId(), entity.getBoundingBox().hashCode());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getSeenPercent", at = @At("RETURN"))
    private static void kneaf$afterGetSeenPercent(Vec3 source, Entity entity, CallbackInfoReturnable<Float> cir) {
        ExplosionControl.cacheExposure(source, entity.getId(), entity.getBoundingBox().hashCode(),
                cir.getReturnValue());
    }
}
