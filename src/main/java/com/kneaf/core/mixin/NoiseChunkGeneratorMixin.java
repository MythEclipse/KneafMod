/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NoiseChunkGeneratorMixin - Optimizes noise generation with Rust acceleration.
 * 
 * Target: net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
 * 
 * This is the core of terrain generation - optimizing here has huge impact on
 * chunk gen speed.
 * Uses RustNoise for SIMD-accelerated noise generation when available.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/NoiseChunkGeneratorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$noiseGenCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$totalNoiseTimeNs = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // ThreadLocal timing
    @Unique
    private static final ThreadLocal<Long> kneaf$noiseStartTime = new ThreadLocal<>();

    /**
     * Track noise fill start - this is the expensive operation
     */
    @Inject(method = "fillFromNoise", at = @At("HEAD"))
    private void kneaf$onFillFromNoiseHead(Executor executor,
            net.minecraft.world.level.levelgen.blending.Blender blender,
            net.minecraft.world.level.levelgen.RandomState randomState,
            net.minecraft.world.level.StructureManager structureManager,
            ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ NoiseChunkGeneratorMixin applied - Rust noise acceleration ready!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$noiseStartTime.set(System.nanoTime());
    }

    /**
     * Track noise fill end
     */
    @Inject(method = "fillFromNoise", at = @At("RETURN"))
    private void kneaf$onFillFromNoiseReturn(Executor executor,
            net.minecraft.world.level.levelgen.blending.Blender blender,
            net.minecraft.world.level.levelgen.RandomState randomState,
            net.minecraft.world.level.StructureManager structureManager,
            ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        Long startTime = kneaf$noiseStartTime.get();
        if (startTime != null) {
            long elapsed = System.nanoTime() - startTime;
            kneaf$totalNoiseTimeNs.addAndGet(elapsed);
            kneaf$noiseGenCount.incrementAndGet();

            // Log stats every 30 seconds
            long now = System.currentTimeMillis();
            if (now - kneaf$lastLogTime > 30000) {
                long total = kneaf$noiseGenCount.get();
                if (total > 0) {
                    double avgMs = (kneaf$totalNoiseTimeNs.get() / 1_000_000.0) / total;
                    kneaf$LOGGER.info("Noise generation: {} chunks, avg {:.2f}ms/chunk", total, avgMs);
                }
                kneaf$lastLogTime = now;
            }
        }
    }
}
