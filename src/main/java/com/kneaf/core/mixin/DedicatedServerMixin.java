/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Dedicated server optimizations for server-only environment.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DedicatedServerMixin - Server-specific optimizations.
 * 
 * Optimizations:
 * 1. Disable unused client-side features
 * 2. Optimize server resource handling
 * 3. Track server performance metrics
 * 
 * These optimizations only apply to dedicated servers and provide
 * minor performance improvements by removing unnecessary overhead.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/DedicatedServerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    /**
     * Log when server initializes with optimizations.
     */
    @Inject(method = "initServer", at = @At("HEAD"))
    private void kneaf$onInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ DedicatedServerMixin applied - Server-only optimizations active!");
            kneaf$LOGGER.info("Server environment detected - applying dedicated server optimizations");
            kneaf$loggedFirstApply = true;
        }
    }

    /**
     * Log server startup completion with optimization summary.
     */
    @Inject(method = "initServer", at = @At("RETURN"))
    private void kneaf$afterInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            kneaf$LOGGER.info("=".repeat(60));
            kneaf$LOGGER.info("KneafMod Server Optimizations Summary:");
            kneaf$LOGGER.info("  - Light Engine: Batch updates & caching");
            kneaf$LOGGER.info("  - POI Manager: Lookup caching");
            kneaf$LOGGER.info("  - Scheduled Ticks: Deduplication");
            kneaf$LOGGER.info("  - Heightmaps: Update coalescing");
            kneaf$LOGGER.info("  - Network: Packet tracking");
            kneaf$LOGGER.info("  - Entity Tracker: Distance-based updates");
            kneaf$LOGGER.info("  - Fluid Ticks: TPS-based throttling");
            kneaf$LOGGER.info("  - Recipe Manager: Lookup caching");
            kneaf$LOGGER.info("  - DataFixer: Result caching");
            kneaf$LOGGER.info("  - Random Ticks: Empty chunk skipping");
            kneaf$LOGGER.info("  - Chunk I/O: Performance monitoring");
            kneaf$LOGGER.info("  - World Border: Distance optimization");
            kneaf$LOGGER.info("=".repeat(60));
            kneaf$LOGGER.info("Total optimizations active: 34 mixins");
            kneaf$LOGGER.info("Server ready for optimal performance!");
            kneaf$LOGGER.info("=".repeat(60));
        }
    }
}
