/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin extends Entity {

    public ExperienceOrbMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Unique
    private int kneaf$tickCounter = 0;

    /**
     * Redirect the expensive entity scan to run less frequently.
     * We target the method call where ExperienceOrb scans for other orbs.
     * In Vanilla 1.21, this is likely 'level.getEntities(...)'.
     */
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> kneaf$rateLimitMergeScan(Level level, Entity entity, net.minecraft.world.phys.AABB aabb,
            java.util.function.Predicate<? super Entity> predicate) {
        // Optimization: Only scan for merge candidates every 10 ticks (0.5s)
        // Orbs don't move fast enough to necessitate per-tick merging.
        if (this.kneaf$tickCounter++ % 10 != 0) {
            return java.util.Collections.emptyList();
        }
        return level.getEntities(entity, aabb, predicate);
    }
}
