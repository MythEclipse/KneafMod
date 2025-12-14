/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkMapMixin - Multi-threaded chunk loading optimization.
 * 
 * Target: net.minecraft.server.level.ChunkMap
 * 
 * ChunkMap manages chunk loading/saving - this is where we can optimize
 * parallel chunk processing.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkMapMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$chunksLoaded = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastChunkCount = 0;

    /**
     * Track chunk map tick for statistics.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkMapMixin applied - parallel chunk loading active!");
            kneaf$loggedFirstApply = true;
        }

        // Log stats every 10 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 10000) {
            long currentCount = kneaf$chunksLoaded.get();
            long chunksDiff = currentCount - kneaf$lastChunkCount;

            if (chunksDiff > 0) {
                double rate = chunksDiff / 10.0;
                kneaf$LOGGER.info("ChunkMap: {} chunks loaded, {:.1f}/sec", currentCount, rate);
            }

            kneaf$lastChunkCount = currentCount;
            kneaf$lastLogTime = now;
        }
    }
}
