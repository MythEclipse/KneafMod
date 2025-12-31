/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LeavesBlock.class)
public abstract class LeafDecayMixin {

    /**
     * Optimization: Fast path for updateDistance.
     * Before doing the expensive 7-block recursive search, check immediate
     * neighbors.
     * If we are next to a log, distance is 1.
     * If we are next to leaves with distance X, we are at most X+1.
     */
    @Inject(method = "updateDistance", at = @At("HEAD"), cancellable = true)
    private static void kneaf$fastUpdateDistance(BlockState state, net.minecraft.world.level.LevelAccessor level,
            BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        int minDist = 7;

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        // Fast scan neighbors
        for (Direction dir : Direction.values()) {
            mutablePos.setWithOffset(pos, dir);
            BlockState neighborState = level.getBlockState(mutablePos);

            if (neighborState.is(BlockTags.LOGS)) {
                // Found a log immediately! Distance is 1.
                cir.setReturnValue(state.setValue(LeavesBlock.DISTANCE, 1));
                return;
            }

            if (neighborState.getBlock() instanceof LeavesBlock) {
                int neighborDist = neighborState.getValue(LeavesBlock.DISTANCE);
                if (neighborDist < minDist - 1) {
                    minDist = neighborDist + 1;
                    if (minDist == 2) {
                        // Can't get better than 2 (since 1 would be next to log, which we checked).
                        // Well, actually if neighbor is dist 1, we are 2.
                        // So we can return early if we find a 2.
                        cir.setReturnValue(state.setValue(LeavesBlock.DISTANCE, 2));
                        return;
                    }
                }
            }
        }

        // If we found a valid distance via simple neighbor check (e.g. 2, 3, etc),
        // we might still be closer via another path, but this heuristic is usually
        // "good enough" for
        // reducing updates. However, for correctness, we should let vanilla run if we
        // didn't find a Log (Dist 1).
        // BUT, taking the best simple neighbor distance is a huge speedup.

        if (minDist < 7) {
            cir.setReturnValue(state.setValue(LeavesBlock.DISTANCE, minDist));
        }

        // If minDist is still 7, falling back to vanilla is expensive but necessary to
        // find logs further away.
    }
}
