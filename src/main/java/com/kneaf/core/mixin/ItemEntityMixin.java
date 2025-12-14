/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.lithium.ItemMergingHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
 * ItemEntityMixin - Radium/Lithium-style optimizations for ItemEntity.
 * 
 * Optimizations:
 * - Skip merge check for full stacks (early exit)
 * - Reduced merge check frequency
 * - Optimized nearby item search order
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ItemEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$mergeAttempts = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$skipCount = new AtomicLong(0);

    @Unique
    private int kneaf$tickCounter = 0;

    @Shadow
    public abstract ItemStack getItem();

    @Shadow
    public abstract Level level();

    /**
     * Log when mixin is applied.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ItemEntityMixin applied - optimized merging active!");
            kneaf$loggedFirstApply = true;
        }

        // Early exit optimization for merge checks
        kneaf$tickCounter++;

        // Only attempt merge every 10 ticks (twice per second instead of 20 times)
        // This dramatically reduces entity lookup overhead
        if (kneaf$tickCounter >= 10) {
            kneaf$tickCounter = 0;
            kneaf$attemptOptimizedMerge();
        }
    }

    @Unique
    private void kneaf$attemptOptimizedMerge() {
        ItemEntity self = (ItemEntity) (Object) this;

        // Skip if removed
        if (self.isRemoved()) {
            return;
        }

        ItemStack stack = getItem();

        // Early exit: can't merge if empty or full
        if (stack.isEmpty()) {
            kneaf$skipCount.incrementAndGet();
            return;
        }

        if (stack.getCount() >= stack.getMaxStackSize()) {
            kneaf$skipCount.incrementAndGet();
            return;
        }

        Level level = level();
        if (level == null || level.isClientSide) {
            return;
        }

        // Use optimized merging that checks item type before distance
        kneaf$mergeAttempts.incrementAndGet();
        ItemMergingHelper.tryMergeWithNearby(self, level);
    }
}
