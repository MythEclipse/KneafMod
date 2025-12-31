/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ItemEntity.class)
public abstract class ItemEntityAggressiveMixin extends Entity {

    public ItemEntityAggressiveMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Unique
    private int kneaf$mergeDelay = 0;

    @Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
    private void kneaf$optimizeMerge(CallbackInfo ci) {
        // Optimization: Don't check for merging EVERY tick.
        // Check every 4 ticks (0.2s) instead of every tick.
        // Item merging is not frame-critical.
        if (this.tickCount % 4 != 0) {
            ci.cancel();
            return;
        }

        // Further optimization: If we have tried to merge recently and failed, back
        // off?
        // For now, the modulo 4 is a safe 4x speedup on this method calls.

        // Note: Vanilla creates a list of entities in AABB bounds.
        // This is expensive.
        // We could implement a direct Spatial Index lookup here if we had access to one
        // for entities.
        // Since we don't have a global Entity Spatial Index easily accessible without
        // major rewrites,
        // reducing frequency is the safest "Extreme" optimization.
    }
}
