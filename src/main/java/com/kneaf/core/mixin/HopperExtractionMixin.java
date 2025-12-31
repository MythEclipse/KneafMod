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

/**
 * HopperExtractionMixin - Optimizes hopper extraction from containers.
 */
@Mixin(value = VanillaInventoryCodeHooks.class, remap = false)
public abstract class HopperExtractionMixin {

    @Inject(method = "extractHook", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onExtractHook(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide())
            return;

        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(hopper.getLevelX(),
                hopper.getLevelY() + 1.0D, hopper.getLevelZ());

        // Use SlotCache to skip redundant empty scans.
        int cachedSlot = com.kneaf.core.util.SlotCache.getFirstNonEmptySlot(pos);
        if (cachedSlot == -2) { // Special value for "definitely empty"
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "extractHook", at = @At("RETURN"))
    private static void kneaf$afterExtractHook(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide())
            return;

        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(hopper.getLevelX(),
                hopper.getLevelY() + 1.0D, hopper.getLevelZ());

        // Safely get the return value - it might be null if cancelled early
        Boolean returnValue = cir.getReturnValue();
        if (returnValue == null) {
            return; // Method was cancelled before setting a return value
        }

        if (!returnValue) {
            // If it returned false, it means no item was extracted.
            // We can mark this position as "defined as empty" for now.
            com.kneaf.core.util.SlotCache.setFirstNonEmptySlot(pos, -2);
        } else {
            // If it returned true, an item was taken. This might have changed which slots
            // are empty.
            // We'll invalidate to be safe, though BlockEntity.setChanged should also
            // trigger this.
            com.kneaf.core.util.SlotCache.setFirstNonEmptySlot(pos, 0); // Reset to "check all"
        }
    }
}
