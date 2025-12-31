package com.kneaf.core.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FluidStateCacheMixin - Caches FluidState for BlockStates.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class FluidStateCacheMixin {

    @Unique
    private static FluidState[] kneaf$fluidStateCache = new FluidState[1024];

    @Unique
    private static final java.util.concurrent.locks.StampedLock kneaf$lock = new java.util.concurrent.locks.StampedLock();

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetFluidState(CallbackInfoReturnable<FluidState> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.tryOptimisticRead();
        FluidState cached = id < kneaf$fluidStateCache.length ? kneaf$fluidStateCache[id] : null;

        if (!kneaf$lock.validate(stamp)) {
            stamp = kneaf$lock.readLock();
            try {
                cached = id < kneaf$fluidStateCache.length ? kneaf$fluidStateCache[id] : null;
            } finally {
                kneaf$lock.unlockRead(stamp);
            }
        }

        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getFluidState", at = @At("RETURN"))
    private void kneaf$afterGetFluidState(CallbackInfoReturnable<FluidState> cir) {
        BlockState state = (BlockState) (Object) this;
        int id = net.minecraft.world.level.block.Block.getId(state);

        long stamp = kneaf$lock.writeLock();
        try {
            if (id >= kneaf$fluidStateCache.length) {
                kneaf$expandCache(id + 1);
            }
            kneaf$fluidStateCache[id] = cir.getReturnValue();
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }

    @Unique
    private static void kneaf$expandCache(int size) {
        int newSize = Math.max(size, kneaf$fluidStateCache.length * 2);
        FluidState[] newCache = new FluidState[newSize];
        System.arraycopy(kneaf$fluidStateCache, 0, newCache, 0, kneaf$fluidStateCache.length);
        kneaf$fluidStateCache = newCache;
    }
}
