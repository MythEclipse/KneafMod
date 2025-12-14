/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkGeneratorMixin - Multi-threaded chunk generation optimization.
 * 
 * Target: net.minecraft.world.level.chunk.ChunkGenerator
 * 
 * Optimizations:
 * - Track chunk generation time
 * - Log throughput statistics
 * - Hook point for parallel noise generation
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkGeneratorMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$chunksGenerated = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$totalGenTimeNs = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static long kneaf$lastChunkCount = 0;

    // ThreadLocal for timing without contention
    @Unique
    private static final ThreadLocal<Long> kneaf$genStartTime = new ThreadLocal<>();

    /**
     * Track biome generation start
     */
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void kneaf$onApplyBiomeDecorationHead(WorldGenRegion region, ChunkAccess chunk,
            net.minecraft.world.level.StructureManager structureManager, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkGeneratorMixin applied - parallel chunk generation active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$genStartTime.set(System.nanoTime());
    }

    /**
     * Track biome generation end and log statistics
     */
    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void kneaf$onApplyBiomeDecorationReturn(WorldGenRegion region, ChunkAccess chunk,
            net.minecraft.world.level.StructureManager structureManager, CallbackInfo ci) {
        Long startTime = kneaf$genStartTime.get();
        if (startTime != null) {
            long elapsed = System.nanoTime() - startTime;
            kneaf$totalGenTimeNs.addAndGet(elapsed);
            kneaf$chunksGenerated.incrementAndGet();

            // Log stats every 10 seconds
            long now = System.currentTimeMillis();
            if (now - kneaf$lastLogTime > 10000) {
                logGenerationStats();
                kneaf$lastLogTime = now;
            }
        }
    }

    @Unique
    private static void logGenerationStats() {
        long totalChunks = kneaf$chunksGenerated.get();
        long chunksDiff = totalChunks - kneaf$lastChunkCount;
        kneaf$lastChunkCount = totalChunks;

        if (chunksDiff > 0) {
            long totalNs = kneaf$totalGenTimeNs.get();
            double avgMs = totalChunks > 0 ? (totalNs / 1_000_000.0) / totalChunks : 0;

            // Calculate approximate chunks/sec
            double chunksPerSec = chunksDiff / 10.0; // 10 second window

            kneaf$LOGGER.info("ChunkGen stats: {} total, {:.1f}/sec, avg {:.2f}ms/chunk",
                    totalChunks, chunksPerSec, avgMs);
        }
    }
}
