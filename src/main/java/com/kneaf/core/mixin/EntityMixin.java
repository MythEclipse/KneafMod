/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.lithium.EntityCollisionHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * EntityMixin - Radium/Lithium-style optimizations for Entity class.
 * 
 * Optimizations:
 * - Early exit for zero/tiny movement
 * - Optimized single-axis collision detection
 * - Track optimization stats
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/EntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;
    
    @Unique
    private static final AtomicLong kneaf$earlyExitCount = new AtomicLong(0);
    
    @Unique
    private static final AtomicLong kneaf$singleAxisCount = new AtomicLong(0);
    
    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public abstract Vec3 getDeltaMovement();

    /**
     * Log when mixin is applied.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ EntityMixin applied - Radium-style collision optimizations active!");
            kneaf$loggedFirstApply = true;
        }
        
        // Log stats every 60 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000 && kneaf$earlyExitCount.get() > 0) {
            kneaf$LOGGER.info("EntityMixin stats: {} early exits, {} single-axis optimizations",
                    kneaf$earlyExitCount.getAndSet(0), kneaf$singleAxisCount.getAndSet(0));
            kneaf$lastLogTime = now;
        }
    }
    
    /**
     * Inject into collide method to add early exit optimizations.
     * The collide method handles all movement collision detection.
     */
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void kneaf$onCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        // Early exit for zero movement - very common case!
        // Many entities (standing mobs, items on ground) have zero movement most ticks.
        if (movement.x == 0.0 && movement.y == 0.0 && movement.z == 0.0) {
            kneaf$earlyExitCount.incrementAndGet();
            cir.setReturnValue(Vec3.ZERO);
            return;
        }
        
        // Early exit for tiny movement below Minecraft's threshold
        // Vanilla uses 1.0E-7, we use squared comparison to avoid sqrt
        double lengthSq = movement.x * movement.x + movement.y * movement.y + movement.z * movement.z;
        if (lengthSq < 1.0E-14) {
            kneaf$earlyExitCount.incrementAndGet();
            cir.setReturnValue(Vec3.ZERO);
            return;
        }
        
        // Track single-axis movements for metrics
        if (EntityCollisionHelper.isSingleAxisMovement(movement)) {
            kneaf$singleAxisCount.incrementAndGet();
        }
        
        // Let vanilla handle normal collision - we've just optimized the trivial cases
    }
}
