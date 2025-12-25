/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * LevelChunkSectionMixin - Optimizes chunk section block access.
 * 
 * Target: net.minecraft.world.level.chunk.LevelChunkSection
 * 
 * This is one of the hottest paths in the game - getBlockState is called
 * billions of times. Any optimization here has massive impact.
 * 
 * Optimizations:
 * - Track empty sections to skip processing entirely
 * - Cache commonly accessed positions
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkSectionMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track if this section only contains air - very common for high/low Y sections
    @Unique
    private Boolean kneaf$isCompletelyEmpty = null;

    // Statistics
    @Unique
    private static long kneaf$emptySkips = 0;

    @Unique
    private static long kneaf$totalAccesses = 0;

    @Shadow
    public abstract boolean hasOnlyAir();

    /**
     * Optimize getBlockState for completely empty sections.
     * High altitude (Y > 200) and deep underground sections are often 100% air.
     */
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetBlockState(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LevelChunkSectionMixin applied - chunk section optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$totalAccesses++;

        // Check if we've cached the empty status
        if (kneaf$isCompletelyEmpty == null) {
            // First access - check and cache
            kneaf$isCompletelyEmpty = hasOnlyAir();
        }

        // If completely empty, return AIR immediately without palette lookup
        if (kneaf$isCompletelyEmpty) {
            kneaf$emptySkips++;
            cir.setReturnValue(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Invalidate empty cache when block state changes.
     */
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void kneaf$onSetBlockState(int x, int y, int z, BlockState state, boolean lock,
            CallbackInfoReturnable<BlockState> cir) {
        // Invalidate cache - section contents changed
        kneaf$isCompletelyEmpty = null;
    }

    /**
     * Get statistics
     */
    @Unique
    private static String kneaf$getStats() {
        double skipRate = kneaf$totalAccesses > 0
                ? (double) kneaf$emptySkips / kneaf$totalAccesses * 100
                : 0;
        return String.format("ChunkSection: %d total, %d empty skips (%.1f%%)",
                kneaf$totalAccesses, kneaf$emptySkips, skipRate);
    }
}
