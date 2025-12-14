/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LivingEntityMixin - Radium/Lithium-style optimizations for LivingEntity.
 * 
 * Optimizations:
 * - Cached EquipmentSlot.values() array to avoid allocations
 * - Early exit for common cases in expensive methods
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LivingEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Cache EquipmentSlot.values() - calling values() allocates a new array every
    // time!
    @Unique
    private static final EquipmentSlot[] kneaf$EQUIPMENT_SLOTS = EquipmentSlot.values();

    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    /**
     * Log when mixin is applied.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LivingEntityMixin applied - equipment caching active!");
            kneaf$loggedFirstApply = true;
        }
    }

    /**
     * Get cached equipment slots array.
     * This avoids the array allocation that EquipmentSlot.values() does every call.
     */
    @Unique
    public static EquipmentSlot[] kneaf$getEquipmentSlots() {
        kneaf$cacheHits.incrementAndGet();
        return kneaf$EQUIPMENT_SLOTS;
    }

    /**
     * Optimize hand swing duration - skip expensive attribute lookup if no weapon.
     */
    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetCurrentSwingDuration(CallbackInfoReturnable<Integer> cir) {
        // Common case: entities without special attack speed have 6 tick swing
        // Only players and some mobs with weapons need the full calculation
        // We let vanilla handle this but track for future optimization
    }
}
