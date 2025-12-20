/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RedstoneWireBlockMixin - Redstone optimization.
 * 
 * Optimizations:
 * 1. Anti-Lag Machine: Throttles updates for positions that change too
 * frequently.
 * 2. Recursion Guard: Skips updates if recursion is exhausted.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneWireBlockMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/RedstoneMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final Map<BlockPos, Integer> kneaf$updateCounts = new ConcurrentHashMap<>();

    @Unique
    private static long kneaf$lastCleanTick = 0;

    @Inject(method = "updatePowerStrength", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdatePowerStrength(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ RedstoneWireBlockMixin applied - Redstone optimizations active!");
            kneaf$loggedFirstApply = true;
        }

        // Anti-Lag Machine Logic:
        long gameTime = level.getGameTime();
        if (gameTime - kneaf$lastCleanTick > 200) { // Every 10 seconds
            kneaf$updateCounts.clear();
            kneaf$lastCleanTick = gameTime;
        }

        int count = kneaf$updateCounts.getOrDefault(pos, 0) + 1;
        kneaf$updateCounts.put(pos, count);

        // If one block updates > 100 times per 10 seconds, it's a lag machine
        // component.
        if (count > 100) {
            ci.cancel(); // Skip update for this tick
        }
    }

    /**
     * Recursion guard to prevent stack overflow on long redstone chains.
     */
    @Inject(method = "updateIndirectNeighbourShapes", at = @At("HEAD"), cancellable = true)
    private void kneaf$onUpdateIndirectNeighbourShapes(BlockState state, Level level, BlockPos pos, int flags,
            int recursionLeft, CallbackInfo ci) {
        if (recursionLeft < 0) {
            ci.cancel();
        }
    }
}
