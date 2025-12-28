/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Chunk Pre-generation System
 */
package com.kneaf.core.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChunkPreGenerator - Background chunk generation.
 * 
 * Generates chunks when server TPS is healthy.
 */
@EventBusSubscriber(modid = "kneafcore")
@SuppressWarnings("null")
public class ChunkPreGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ChunkPreGen");

    // State
    private static boolean active = false;
    private static ServerLevel targetLevel = null;
    private static final ConcurrentLinkedQueue<ChunkPos> queue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger generatedCount = new AtomicInteger(0);
    private static int totalToGenerate = 0;

    // Config
    private static final double MIN_TPS_FOR_GEN = 18.0;
    private static final int MAX_CHUNKS_PER_TICK = 2;

    /**
     * Start pre-generation task.
     */
    public static void start(ServerLevel level, int radius, int centerX, int centerZ) {
        if (active) {
            LOGGER.warn("Pre-generation already active!");
            return;
        }

        targetLevel = level;
        queue.clear();
        generatedCount.set(0);

        int count = 0;
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;

        LOGGER.info("Starting pre-generation: radius={}, center=({}, {}), bounds=[{}, {}, {}, {}]",
                radius, centerX, centerZ, minX, minZ, maxX, maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x, z)) { // Only queue unloaded/ungenerated chunks (approx check)
                    queue.add(new ChunkPos(x, z));
                    count++;
                }
            }
        }

        totalToGenerate = count;
        active = true;
        LOGGER.info("Queued {} chunks for pre-generation.", count);
    }

    /**
     * Stop pre-generation.
     */
    public static void stop() {
        active = false;
        queue.clear();
        LOGGER.info("Stopped pre-generation.");
    }

    /**
     * Get status string.
     */
    public static String getStatus() {
        if (!active)
            return "Idle";
        return String.format("Generating: %d/%d (%.1f%%)",
                generatedCount.get(), totalToGenerate,
                (generatedCount.get() * 100.0 / Math.max(1, totalToGenerate)));
    }

    public static boolean isActive() {
        return active;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active || targetLevel == null || queue.isEmpty()) {
            if (active && queue.isEmpty()) {
                active = false;
                LOGGER.info("Pre-generation complete! Generated {} chunks.", generatedCount.get());
                // Reset target level to prevent leak
                targetLevel = null;
            }
            return;
        }

        // Check TPS
        if (TPSTracker.getCurrentTPS() < MIN_TPS_FOR_GEN) {
            return; // Skip tick if lagging
        }

        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        // Process a few chunks
        for (int i = 0; i < MAX_CHUNKS_PER_TICK; i++) {
            ChunkPos pos = queue.poll();
            if (pos == null)
                break;

            try {
                // getChunk with true forces generation
                @SuppressWarnings("null")
                net.minecraft.world.level.chunk.status.ChunkStatus status = net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
                targetLevel.getChunkSource().getChunk(pos.x, pos.z, status, true);
                generatedCount.incrementAndGet();
            } catch (Exception e) {
                LOGGER.error("Failed to generate chunk at {}", pos, e);
            }
        }
    }
}
