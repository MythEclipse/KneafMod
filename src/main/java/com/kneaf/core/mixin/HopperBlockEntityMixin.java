/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.util.HopperSpatialIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HopperBlockEntityMixin - Advanced hopper optimization with spatial indexing.
 * 
 * Optimizations:
 * 1. Spatial Indexing: O(1) inventory lookups instead of O(n) linear search
 * 2. Target Caching: Cache transfer targets between ticks
 * 3. Fail Count Delay: Skip checks for hoppers that repeatedly fail
 */
@Mixin(HopperBlockEntity.class)
@SuppressWarnings("null")
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

    @Unique
    private static final Map<Level, HopperSpatialIndex> kneaf$spatialIndexes = new ConcurrentHashMap<>();

    @Unique
    private BlockPos kneaf$cachedTargetPos = null;

    @Unique
    private long kneaf$lastCacheTime = 0;

    @Shadow
    private int cooldownTime;

    @Shadow
    protected abstract void setCooldown(int cooldownTime);

    /**
     * Get or create spatial index for a level.
     */
    @Unique
    private static HopperSpatialIndex kneaf$getIndex(Level level) {
        return kneaf$spatialIndexes.computeIfAbsent(level, k -> new HopperSpatialIndex());
    }

    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onSuckInItems(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ HopperBlockEntityMixin applied - Advanced hopper optimizations active!");
            kneaf$loggedFirstApply = true;
        }

        if ((Object) hopper instanceof HopperBlockEntityMixin mixin) {
            if (mixin.kneaf$shouldSkipTick(level)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "pushItems", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("null")
    private static void kneaf$onPushItems(Level level, BlockPos pos, BlockState state, Container source,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) source instanceof HopperBlockEntityMixin mixin) {
            if (mixin.kneaf$shouldSkipTick(level)) {
                cir.setReturnValue(false);
                return;
            }

            // Use spatial index to find transfer target
            HopperSpatialIndex index = kneaf$getIndex(level);

            // Check cache first
            long gameTime = level.getGameTime();
            if (mixin.kneaf$cachedTargetPos != null && gameTime - mixin.kneaf$lastCacheTime < 20) {
                // Cache valid for 1 second
                BlockEntity target = level.getBlockEntity(mixin.kneaf$cachedTargetPos);
                if (target instanceof Container) {
                    // Cache hit - target still valid
                    return;
                }
            }

            // Query spatial index for nearby inventories
            List<BlockPos> nearbyInventories = index.queryNearby(pos, 2); // Hoppers check 1 block range

            // Cache the first valid target
            for (BlockPos inventoryPos : nearbyInventories) {
                BlockEntity be = level.getBlockEntity(inventoryPos);
                if (be instanceof Container && be != source) {
                    mixin.kneaf$cachedTargetPos = inventoryPos;
                    mixin.kneaf$lastCacheTime = gameTime;
                    break;
                }
            }

            // If no targets found, skip this tick
            if (nearbyInventories.isEmpty()) {
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
            HopperBlockEntity blockEntity, java.util.function.BooleanSupplier validator,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) blockEntity instanceof HopperBlockEntityMixin mixin) {
            if (!cir.getReturnValue()) {
                mixin.kneaf$transferFailCount++;
            } else {
                mixin.kneaf$transferFailCount = 0;
            }
        }
    }

    /**
     * Track hopper placement for spatial index.
     */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void kneaf$onSetBlockState(BlockState state, CallbackInfo ci) {
        if (this.level != null && !this.level.isClientSide()) {
            kneaf$getIndex(this.level).addInventory(this.worldPosition);
        }
    }

    /**
     * Track hopper removal for spatial index.
     */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void kneaf$onSetRemoved(CallbackInfo ci) {
        if (this.level != null && !this.level.isClientSide()) {
            kneaf$getIndex(this.level).removeInventory(this.worldPosition);
        }
    }
}
