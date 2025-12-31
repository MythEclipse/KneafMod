/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(Explosion.class)
public abstract class ExplosionExposureMixin {

    @Unique
    private static final ThreadLocal<Map<ExposureKey, Float>> kneaf$EXPOSURE_CACHE = ThreadLocal
            .withInitial(HashMap::new);

    /**
     * Cache explosion exposure results.
     * Raycasting for exposure is extremely expensive (O(N_entities * 16*16*16 rays
     * if not careful)).
     */
    @Inject(method = "getSeenPercent", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onGetSeenPercent(Vec3 source, Entity entity, CallbackInfoReturnable<Float> cir) {
        // Optimization: Use a cache to store seen percent for the same entity in the
        // same explosion
        // In mass TNT scenarios, many entities might overlap or be checked multiple
        // times.

        ExposureKey key = new ExposureKey(source, entity.getId(), entity.getBoundingBox().hashCode());
        Map<ExposureKey, Float> cache = kneaf$EXPOSURE_CACHE.get();

        Float cached = cache.get(key);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getSeenPercent", at = @At("RETURN"))
    private static void kneaf$afterGetSeenPercent(Vec3 source, Entity entity, CallbackInfoReturnable<Float> cir) {
        ExposureKey key = new ExposureKey(source, entity.getId(), entity.getBoundingBox().hashCode());
        kneaf$EXPOSURE_CACHE.get().put(key, cir.getReturnValue());
    }

    /**
     * Clear the cache. This should be called before/after an explosion.
     * We'll hook into ExplosionMixin to call this.
     */
    @Unique
    public static void kneaf$clearExposureCache() {
        kneaf$EXPOSURE_CACHE.get().clear();
    }

    @Unique
    private static record ExposureKey(Vec3 source, int entityId, int bbHash) {
    }
}
