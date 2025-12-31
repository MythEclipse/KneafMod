/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class LightEngineStateCacheMixin {

    @Unique
    private static int[] kneaf$lightBlockCache = new int[1024];

    @Unique
    private static boolean[] kneaf$hasLightBlockCache = new boolean[1024];

    @Unique
    private static final java.util.concurrent.locks.StampedLock kneaf$lock = new java.util.concurrent.locks.StampedLock();

    @Inject(method = "getLightBlock", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetLightBlock(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.tryOptimisticRead();
        boolean has = id < kneaf$hasLightBlockCache.length && kneaf$hasLightBlockCache[id];
        int val = has ? kneaf$lightBlockCache[id] : 0;

        if (!kneaf$lock.validate(stamp)) {
            stamp = kneaf$lock.readLock();
            try {
                has = id < kneaf$hasLightBlockCache.length && kneaf$hasLightBlockCache[id];
                val = has ? kneaf$lightBlockCache[id] : 0;
            } finally {
                kneaf$lock.unlockRead(stamp);
            }
        }

        if (has) {
            cir.setReturnValue(val);
        }
    }

    @Inject(method = "getLightBlock", at = @At("RETURN"))
    private void kneaf$afterGetLightBlock(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.writeLock();
        try {
            if (id >= kneaf$hasLightBlockCache.length) {
                kneaf$expandCache(id + 1);
            }
            kneaf$lightBlockCache[id] = cir.getReturnValue();
            kneaf$hasLightBlockCache[id] = true;
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }

    @Unique
    private static int[] kneaf$lightEmissionCache = new int[1024];

    @Unique
    private static boolean[] kneaf$hasLightEmissionCache = new boolean[1024];

    @Inject(method = "getLightEmission", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetLightEmission(CallbackInfoReturnable<Integer> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.tryOptimisticRead();
        boolean has = id < kneaf$hasLightEmissionCache.length && kneaf$hasLightEmissionCache[id];
        int val = has ? kneaf$lightEmissionCache[id] : 0;

        if (!kneaf$lock.validate(stamp)) {
            stamp = kneaf$lock.readLock();
            try {
                has = id < kneaf$hasLightEmissionCache.length && kneaf$hasLightEmissionCache[id];
                val = has ? kneaf$lightEmissionCache[id] : 0;
            } finally {
                kneaf$lock.unlockRead(stamp);
            }
        }

        if (has) {
            cir.setReturnValue(val);
        }
    }

    @Inject(method = "getLightEmission", at = @At("RETURN"))
    private void kneaf$afterGetLightEmission(CallbackInfoReturnable<Integer> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.writeLock();
        try {
            if (id >= kneaf$hasLightEmissionCache.length) {
                kneaf$expandCache(id + 1);
            }
            kneaf$lightEmissionCache[id] = cir.getReturnValue();
            kneaf$hasLightEmissionCache[id] = true;
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }

    @Unique
    private static synchronized void kneaf$expandCache(int size) {
        if (size <= kneaf$lightBlockCache.length)
            return;

        int newSize = Math.max(size, kneaf$lightBlockCache.length * 2);
        int[] newIntCache = new int[newSize];
        boolean[] newHasCache = new boolean[newSize];
        int[] newEmissionCache = new int[newSize];
        boolean[] newHasEmissionCache = new boolean[newSize];

        System.arraycopy(kneaf$lightBlockCache, 0, newIntCache, 0, kneaf$lightBlockCache.length);
        System.arraycopy(kneaf$hasLightBlockCache, 0, newHasCache, 0, kneaf$hasLightBlockCache.length);
        System.arraycopy(kneaf$lightEmissionCache, 0, newEmissionCache, 0, kneaf$lightEmissionCache.length);
        System.arraycopy(kneaf$hasLightEmissionCache, 0, newHasEmissionCache, 0, kneaf$hasLightEmissionCache.length);

        kneaf$lightBlockCache = newIntCache;
        kneaf$hasLightBlockCache = newHasCache;
        kneaf$lightEmissionCache = newEmissionCache;
        kneaf$hasLightEmissionCache = newHasEmissionCache;
    }
}
