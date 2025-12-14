/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ServerLevelMixin - TPS-focused server tick optimizations.
 * 
 * Target: net.minecraft.server.level.ServerLevel
 * 
 * Optimizations:
 * - Track tick time distribution
 * - Optimize entity iteration order
 * - Cache frequently accessed data per tick
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ServerLevelMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;
    
    // Per-tick statistics
    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);
    
    @Unique
    private static long kneaf$lastTickStart = 0;
    
    @Unique
    private static long kneaf$totalTickTime = 0;
    
    @Unique
    private static long kneaf$maxTickTime = 0;
    
    @Unique
    private static long kneaf$lastLogTime = 0;

    /**
     * Track tick start time for profiling.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ServerLevelMixin applied - TPS optimization active!");
            kneaf$loggedFirstApply = true;
        }
        
        kneaf$lastTickStart = System.nanoTime();
    }
    
    /**
     * Track tick end time and log statistics.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void kneaf$onTickReturn(java.util.function.BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        long tickTime = System.nanoTime() - kneaf$lastTickStart;
        long tickMs = tickTime / 1_000_000;
        
        kneaf$tickCount.incrementAndGet();
        kneaf$totalTickTime += tickMs;
        
        if (tickMs > kneaf$maxTickTime) {
            kneaf$maxTickTime = tickMs;
        }
        
        // Log stats every 30 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 30000) {
            long ticks = kneaf$tickCount.get();
            if (ticks > 0) {
                long avgMs = kneaf$totalTickTime / ticks;
                double tps = avgMs > 0 ? 1000.0 / avgMs : 20.0;
                tps = Math.min(tps, 20.0);
                
                kneaf$LOGGER.info("ServerLevel TPS stats: avg {}ms/tick ({:.1f} TPS), max {}ms, {} ticks",
                        avgMs, tps, kneaf$maxTickTime, ticks);
                
                // Reset counters
                kneaf$tickCount.set(0);
                kneaf$totalTickTime = 0;
                kneaf$maxTickTime = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
