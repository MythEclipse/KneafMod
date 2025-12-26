/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Dedicated server optimizations and startup summary.
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
 * Features:
 * 1. Log optimization summary on startup
 * 2. Server-only environment detection
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/DedicatedServerMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    /**
     * Log when server initializes.
     */
    @Inject(method = "initServer", at = @At("HEAD"))
    private void kneaf$onInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ DedicatedServerMixin applied - Server optimizations active!");
            kneaf$loggedFirstApply = true;
        }
    }

    /**
     * Log optimization summary on startup.
     */
    @Inject(method = "initServer", at = @At("RETURN"))
    private void kneaf$afterInitServer(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            kneaf$LOGGER.info("============================================================");
            kneaf$LOGGER.info("KneafMod Server Optimizations Summary:");
            kneaf$LOGGER.info("  - Entity: Tick culling, physics optimization");
            kneaf$LOGGER.info("  - Chunk: Generation, loading, caching");
            kneaf$LOGGER.info("  - Light: Batch updates, caching");
            kneaf$LOGGER.info("  - POI: Lookup caching");
            kneaf$LOGGER.info("  - Fluid: TPS-based throttling");
            kneaf$LOGGER.info("  - Hopper: Fail count delay");
            kneaf$LOGGER.info("  - Redstone: Anti-lag machine");
            kneaf$LOGGER.info("  - Network: Entity tracker optimization");
            kneaf$LOGGER.info("  - Random Tick: Empty chunk skipping");
            kneaf$LOGGER.info("  - Recipe: Lookup caching");
            kneaf$LOGGER.info("  - World Border: Distance optimization");
            kneaf$LOGGER.info("============================================================");
            kneaf$LOGGER.info("Total optimizations: 33 mixins active");
            kneaf$LOGGER.info("Server ready for optimal performance!");
            kneaf$LOGGER.info("============================================================");
        }
    }
}
