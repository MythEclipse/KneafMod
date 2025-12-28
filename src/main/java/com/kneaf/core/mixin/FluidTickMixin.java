/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Fluid spreading optimization with TPS-based throttling and Rust JNI.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FluidTickMixin - Fluid spreading optimization.
 * Logic delegated to com.kneaf.core.FluidUpdateManager to handle
 * dimension-specific state safely.
 */
@Mixin(FlowingFluid.class)
public abstract class FluidTickMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/FluidTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    /**
     * Adaptive fluid tick skipping with Rust batch processing.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kneaf$onFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ FluidTickMixin applied - Rust fluid simulation active (via Manager)!");
            kneaf$loggedFirstApply = true;
        }

        // Delegate to Manager which handles dimension-specific state
        if (com.kneaf.core.FluidUpdateManager.onFluidTick(level, pos, state)) {
            ci.cancel();
        }
    }

    /**
     * Mark source blocks as static.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$afterFluidTick(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (state.isSource()) {
            com.kneaf.core.FluidUpdateManager.markStatic(level, pos);
        }
    }
}
