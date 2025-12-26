/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Chunk storage I/O performance monitoring.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;

/**
 * ChunkStorageMixin - Chunk I/O performance monitoring.
 * 
 * Features:
 * 1. Track chunk save/load performance
 * 2. Log slow operations
 * 3. Collect I/O statistics
 */
@Mixin(ChunkStorage.class)
public abstract class ChunkStorageMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkStorageMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$chunksLoaded = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$chunksSaved = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track chunk load operations.
     */
    @Inject(method = "read", at = @At("HEAD"))
    private void kneaf$onReadStart(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkStorageMixin applied - Chunk I/O monitoring active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$chunksLoaded.incrementAndGet();
    }

    /**
     * Track chunk save operations.
     */
    @Inject(method = "write", at = @At("HEAD"))
    private void kneaf$onWriteStart(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        kneaf$chunksSaved.incrementAndGet();
        kneaf$logStats();
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long loaded = kneaf$chunksLoaded.get();
            long saved = kneaf$chunksSaved.get();

            if (loaded > 0 || saved > 0) {
                kneaf$LOGGER.info("ChunkIO: {} loaded, {} saved", loaded, saved);
            }

            kneaf$chunksLoaded.set(0);
            kneaf$chunksSaved.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
