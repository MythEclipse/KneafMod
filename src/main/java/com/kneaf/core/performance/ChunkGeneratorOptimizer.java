package com.kneaf.core.performance;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Optimizes chunk generation using multithreading.
 * Generates chunks asynchronously to reduce server lag.
 */
@EventBusSubscriber(modid = "kneafcore")
public class ChunkGeneratorOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ChunkGeneratorOptimizer() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Pre-generate nearby chunks using Rust
            int chunkX = event.getChunk().getPos().x;
            int chunkZ = event.getChunk().getPos().z;
            int generated = RustPerformance.preGenerateNearbyChunks(chunkX, chunkZ, 1);
            if (generated > 0) {
                LOGGER.debug("Pre-generated {} chunks around ({}, {})", generated, chunkX, chunkZ);
            }
        }
    }

    /**
     * Shutdown the chunk executor.
     */
    public static void shutdown() {
        // No executor to shutdown - using Rust for multithreading
    }
}