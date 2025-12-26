/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HopperBlockEntityMixin - Optimized hopper logic.
 * 
 * Optimizations:
 * 1. Fail Count Delay: Reduces item transfer checks for hoppers that
 * repeatedly fail.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin extends RandomizableContainerBlockEntity implements Hopper {

    protected HopperBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/HopperMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private int kneaf$transferFailCount = 0;

    @Shadow
    private int cooldownTime;

    @Shadow
    protected abstract void setCooldown(int cooldownTime);

    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onSuckInItems(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ HopperBlockEntityMixin applied - hopper optimization active!");
            kneaf$loggedFirstApply = true;
        }

        // Cast via Object to enable mixin pattern matching
        if ((Object) hopper instanceof HopperBlockEntityMixin mixin) {
            if (mixin.kneaf$shouldSkipTick(level)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "pushItems", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onPushItems(Level level, BlockPos pos, BlockState state, Container source,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) source instanceof HopperBlockEntityMixin mixin) {
            if (mixin.kneaf$shouldSkipTick(level)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Unique
    private boolean kneaf$shouldSkipTick(Level level) {
        if (this.kneaf$transferFailCount > 5) {
            // Decay fail count slowly
            if (level.getGameTime() % 20 != 0) {
                return true;
            }
            this.kneaf$transferFailCount--;
        }
        return false;
    }

    @Inject(method = "tryMoveItems", at = @At("RETURN"))
    private static void kneaf$onTryMoveItemsReturn(Level level, BlockPos pos, BlockState state,
            HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) blockEntity instanceof HopperBlockEntityMixin mixin) {
            if (!cir.getReturnValue()) {
                mixin.kneaf$transferFailCount++;
            } else {
                mixin.kneaf$transferFailCount = 0;
            }
        }
    }
}
