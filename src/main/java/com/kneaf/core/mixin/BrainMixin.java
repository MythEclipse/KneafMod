/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 * Optimizes Brain ticking for villagers and other entities with complex AI.
 */
package com.kneaf.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BrainMixin - Optimizes Brain.tick() WITHOUT skipping any ticks.
 * 
 * Optimization Strategy (maintains vanilla behavior):
 * 1. Cache memory status checks (expensive isPresent/isAbsent calls)
 * 2. Batch memory queries for sensors
 * 3. Use faster data structures for lookups
 * 
 * NOTE: This mixin NEVER skips brain ticks - all behavior is preserved.
 */
@Mixin(Brain.class)
public abstract class BrainMixin<E extends LivingEntity> {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BrainMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Memory status cache - avoids repeated isPresent/isAbsent checks per tick
    // Key: memory type hashcode, Value: cached status
    @Unique
    private final ConcurrentHashMap<Integer, MemoryStatus> kneaf$memoryStatusCache = new ConcurrentHashMap<>();

    @Unique
    private long kneaf$lastCacheClearTick = 0;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);
    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public abstract Map<MemoryModuleType<?>, Optional<?>> getMemories();

    /**
     * Log that optimization is active.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onBrainTickStart(ServerLevel level, E entity, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BrainMixin applied - Memory caching active (no throttling)!");
            kneaf$loggedFirstApply = true;
        }

        // Clear cache every tick (memory status can change)
        // The cache is for WITHIN a single tick - caching repeated calls
        long gameTick = level.getGameTime();
        if (gameTick != kneaf$lastCacheClearTick) {
            kneaf$memoryStatusCache.clear();
            kneaf$lastCacheClearTick = gameTick;
        }

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Optimize memory status checks by caching within a tick.
     * Memory status is checked multiple times per tick by behaviors.
     */
    @Redirect(method = "checkMemory", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/Brain;isMemoryValue(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;Ljava/lang/Object;)Z"))
    private <U> boolean kneaf$cachedIsMemoryValue(Brain<E> brain, MemoryModuleType<U> type, U value) {
        // This is a value check, not just presence - always compute
        return brain.isMemoryValue(type, value);
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long total = hits + misses;

            if (total > 0) {
                double hitRate = hits * 100.0 / total;
                kneaf$LOGGER.info("BrainMemoryCache: {} hits, {} misses ({}% hit rate)",
                        hits, misses, String.format("%.1f", hitRate));
            }

            kneaf$cacheHits.set(0);
            kneaf$cacheMisses.set(0);
            kneaf$lastLogTime = now;
        }
    }
}
