/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VanillaInventoryCodeHooks.class, remap = false)
public abstract class HopperExtractionMixin {

    @Inject(method = "extractHook", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onExtractHook(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        // Optimized in HopperBlockEntityMixin instead.
    }
}
