package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LightEngineBatchMixin - Reduces redundant lighting updates.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineBatchMixin {

    @Unique
    private final com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap kneaf$checkedBlocks = new com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap(
            1024);

    @Unique
    private final java.util.concurrent.locks.StampedLock kneaf$lock = new java.util.concurrent.locks.StampedLock();

    /**
     * Optimization: checkBlock throttle.
     * Prevents re-checking the same BlockPos multiple times within a single tick.
     */
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void kneaf$onCheckBlock(BlockPos pos, CallbackInfo ci) {
        long posLong = pos.asLong();

        long stamp = kneaf$lock.readLock();
        try {
            if (kneaf$checkedBlocks.containsKey(posLong)) {
                ci.cancel();
                return;
            }
        } finally {
            kneaf$lock.unlockRead(stamp);
        }

        stamp = kneaf$lock.writeLock();
        try {
            if (kneaf$checkedBlocks.size() > 4096) {
                kneaf$checkedBlocks.clear();
            }
            kneaf$checkedBlocks.put(posLong, 1L);
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }

    @Inject(method = "runUpdates", at = @At("HEAD"))
    private void kneaf$onRunUpdates(CallbackInfo ci) {
        // Clear checked blocks when engine runs updates
        long stamp = kneaf$lock.writeLock();
        try {
            kneaf$checkedBlocks.clear();
        } finally {
            kneaf$lock.unlockWrite(stamp);
        }
    }
}
