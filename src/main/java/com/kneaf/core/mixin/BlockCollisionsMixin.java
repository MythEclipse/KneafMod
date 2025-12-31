/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.BlockCollisions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BlockCollisionsMixin - Reduce iterator allocations in collision checks.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin<T> {

    @Shadow
    private net.minecraft.world.level.CollisionGetter collisionGetter;

    @Shadow
    private net.minecraft.world.entity.Entity entity;

    @Shadow
    private net.minecraft.core.Cursor3D cursor;

    @Shadow
    private net.minecraft.core.BlockPos.MutableBlockPos pos;

    @Shadow
    private net.minecraft.world.phys.shapes.CollisionContext context;

    @Shadow
    private java.util.function.BiFunction<net.minecraft.core.BlockPos, net.minecraft.world.phys.shapes.VoxelShape, T> resultProvider;

    @Shadow
    protected abstract T computeNext();

    @Inject(method = "computeNext", at = @At("HEAD"), cancellable = true)
    private void kneaf$onComputeNext(CallbackInfoReturnable<T> cir) {
        // We can't easily optimize the whole loop here because it's an iterator.
        // But we can peek ahead and skip empty sections if we were to rewrite
        // computeNext.

        // However, a simpler optimization is to reduce the overhead of getBlockState
        // by checking if the section is the same as the last one.
    }
}
