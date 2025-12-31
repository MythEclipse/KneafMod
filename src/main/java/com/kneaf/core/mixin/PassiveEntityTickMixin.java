/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class PassiveEntityTickMixin extends LivingEntity {

    protected PassiveEntityTickMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Unique
    private int kneaf$idleTicks = 0;

    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void kneaf$optimizePassiveTick(CallbackInfo ci) {
        // Only optimize on server
        if (this.level().isClientSide)
            return;

        // Condition 1: Must be old enough (not a baby growing up)
        if (((Animal) (Object) this).isBaby())
            return;

        // Condition 2: Not in love/breeding mode
        if (((Animal) (Object) this).isInLove())
            return;

        // Condition 3: Not being ridden or riding
        if (this.isVehicle() || this.isPassenger())
            return;

        // Condition 4: Healthy
        if (this.getHealth() < this.getMaxHealth())
            return;

        // Condition 5: Check player distance (expensive check, so do it less often or
        // use rough checks)
        // Optimization: Use modulo to check player distance only occasionally?
        // Actually, just checking closest player is O(Players), which is fine.
        Player closestPlayer = this.level().getNearestPlayer(this, 32.0);

        if (closestPlayer == null) {
            // No player nearby.
            // Aggressive skip: Only run AI every 20 ticks (1 second)
            if (this.tickCount % 20 != 0) {
                // Ensure we still update position/movement roughly if strictly necessary,
                // but for "Idle" animals, we can basically freeze them.
                // We must update "age" or similar counters if needed, but skipping AI is the
                // goal.
                this.xxa = 0;
                this.yya = 0;
                this.zza = 0;
                ci.cancel();
            }
        }
    }
}
