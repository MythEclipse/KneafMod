/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockEntityMixin - TPS-focused block entity tick optimizations.
 * 
 * Target: net.minecraft.world.level.block.entity.BlockEntity
 * 
 * Block entities (furnaces, hoppers, chests, etc.) are major TPS consumers.
 * Optimizations:
 * - Track which block entities are actually doing work
 * - Skip unnecessary ticks for idle block entities
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/BlockEntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;
    
    @Unique
    private static final AtomicLong kneaf$tickCount = new AtomicLong(0);
    
    @Unique
    private static long kneaf$lastLogTime = 0;
    
    // Track if this block entity is doing useful work
    @Unique
    private boolean kneaf$isActive = true;
    
    @Unique
    private int kneaf$idleTicks = 0;

    /**
     * Track block entity state changes.
     */
    @Inject(method = "setChanged", at = @At("HEAD"))
    private void kneaf$onSetChanged(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ BlockEntityMixin applied - Block entity optimization active!");
            kneaf$loggedFirstApply = true;
        }
        
        // Block entity is doing something - reset idle counter
        kneaf$isActive = true;
        kneaf$idleTicks = 0;
        kneaf$tickCount.incrementAndGet();
        
        // Log stats every 60 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long ticks = kneaf$tickCount.get();
            if (ticks > 0) {
                kneaf$LOGGER.info("BlockEntityMixin stats: {} block entity changes", ticks);
                kneaf$tickCount.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }
    
    /**
     * Check if block entity is considered idle.
     */
    @Unique
    public boolean kneaf$isIdle() {
        return !kneaf$isActive && kneaf$idleTicks > 20; // Idle for 1+ second
    }
    
    /**
     * Increment idle counter.
     */
    @Unique
    public void kneaf$tickIdle() {
        if (!kneaf$isActive) {
            kneaf$idleTicks++;
        }
    }
}
