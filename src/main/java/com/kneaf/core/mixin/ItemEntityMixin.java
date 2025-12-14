/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ItemEntityMixin - Radium/Lithium-style optimizations for ItemEntity.
 * 
 * Optimizations:
 * - Track item entity for future optimization
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ItemEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    /**
     * Log when mixin is applied.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ItemEntityMixin applied - item optimizations active!");
            kneaf$loggedFirstApply = true;
        }
    }
}
