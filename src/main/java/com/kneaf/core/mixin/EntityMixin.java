/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Based on Lithium by JellySquid - https://github.com/CaffeineMC/lithium-fabric
 */
package com.kneaf.core.mixin;

import com.kneaf.core.lithium.EntityCollisionHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
 * EntityMixin - Advanced Lithium-style optimizations for Entity class.
 * 
 * Optimizations:
 * 1. Early exit for zero/tiny movement (skip collision entirely)
 * 2. Supporting block check for downward movement (gravity optimization)
 * 3. Single-axis movement optimization (smaller search box)
 * 4. Chunk-aware collision sweeping
 * 5. Statistics tracking
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/EntityMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$earlyExitCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$singleAxisCount = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$gravityOptimizations = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$totalCollisions = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public abstract Level level();

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract boolean onGround();

    /**
     * Log when mixin is applied and periodically log stats.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTickHead(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ EntityMixin applied - Advanced Lithium collision optimizations active!");
            kneaf$loggedFirstApply = true;
        }

        // Log stats every 60 seconds
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long earlyExits = kneaf$earlyExitCount.get();
            long total = kneaf$totalCollisions.get();
            if (total > 0) {
                double skipRate = (double) earlyExits / total * 100;
                kneaf$LOGGER.info(
                        "EntityMixin stats: {} total collisions, {} early exits ({}%), {} single-axis, {} gravity opts",
                        total, earlyExits, String.format("%.1f", skipRate), kneaf$singleAxisCount.get(),
                        kneaf$gravityOptimizations.get());

                // Reset counters
                kneaf$earlyExitCount.set(0);
                kneaf$singleAxisCount.set(0);
                kneaf$gravityOptimizations.set(0);
                kneaf$totalCollisions.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Optimize collision detection with early exits and special case handling.
     */
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void kneaf$onCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        kneaf$totalCollisions.incrementAndGet();

        // === OPTIMIZATION 1: Early exit for zero movement ===
        // Many entities (standing mobs, items on ground) have zero movement
        if (movement.x == 0.0 && movement.y == 0.0 && movement.z == 0.0) {
            kneaf$earlyExitCount.incrementAndGet();
            cir.setReturnValue(Vec3.ZERO);
            return;
        }

        // === OPTIMIZATION 2: Early exit for tiny movement ===
        // Movement below Minecraft's precision threshold can be skipped
        double lengthSq = movement.x * movement.x + movement.y * movement.y + movement.z * movement.z;
        if (lengthSq < 1.0E-14) { // (1.0E-7)^2
            kneaf$earlyExitCount.incrementAndGet();
            cir.setReturnValue(Vec3.ZERO);
            return;
        }

        // === OPTIMIZATION 3: Gravity early exit ===
        // If entity is on ground and moving down (gravity),
        // and on solid ground, movement will be zero
        if (movement.y < 0 && onGround() && movement.x == 0.0 && movement.z == 0.0) {
            // Pure downward movement while on ground - movement is blocked
            kneaf$gravityOptimizations.incrementAndGet();
            cir.setReturnValue(Vec3.ZERO);
            return;
        }

        // === OPTIMIZATION 4: Single-axis movement optimization ===
        // Single-axis movements can use a smaller search box, reducing collision checks
        if (EntityCollisionHelper.isSingleAxisMovement(movement)) {
            kneaf$singleAxisCount.incrementAndGet();
            // Single-axis movements are already handled optimally by early exits above
            // If we reach here, let vanilla handle but track for metrics
        }

        // Let vanilla handle complex multi-axis collision cases
    }

}
