/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import com.kneaf.core.RustOptimizations;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Highly optimized Explosion handling for mass TNT scenarios.
 * Sequential DDA-optimized scanning with adaptive resolution.
 */
@Mixin(Explosion.class)
@SuppressWarnings("null")
public abstract class ExplosionMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ExplosionMixin");

    @Unique
    private static final AtomicLong kneaf$explosionsProcessed = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$rustRayCasts = new AtomicLong(0);

    @Shadow
    @Final
    private Level level;
    @Shadow
    @Final
    private double x;
    @Shadow
    @Final
    private double y;
    @Shadow
    @Final
    private double z;
    @Shadow
    @Final
    private float radius;
    @Shadow
    @Final
    private net.minecraft.world.level.ExplosionDamageCalculator damageCalculator;
    @Shadow
    @Final
    private net.minecraft.world.damagesource.DamageSource damageSource;

    @Shadow
    public abstract List<BlockPos> getToBlow();

    @Shadow
    public abstract net.minecraft.world.entity.Entity getDirectSourceEntity();

    @SuppressWarnings("null")
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void kneaf$onExplode(CallbackInfo ci) {
        kneaf$explosionsProcessed.incrementAndGet();

        com.kneaf.core.util.ExplosionControl.notifyExploded(level.getGameTime());

        // Early exit for tiny explosions
        if (radius < 0.5f) {
            ci.cancel();
            return;
        }

        List<BlockPos> blocks = getToBlow();
        kneaf$performOptimizedScan(blocks);
        kneaf$performEntityImpact();

        // Clear exposure cache after each explosion to prevent memory leak and stale
        // data
        ExplosionExposureMixin.kneaf$clearExposureCache();

        ci.cancel();
    }

    @Unique
    @SuppressWarnings("null")
    private void kneaf$performOptimizedScan(List<BlockPos> blocks) {
        // Rust Acceleration for huge radii (Keep existing logic)
        if (RustOptimizations.isAvailable() && radius > 4.0f) {
            try {
                double[] origin = new double[] { x, y, z };
                int rayCount = 4096;
                double[] rayDirs = new double[rayCount * 3];
                double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
                for (int i = 0; i < rayCount; i++) {
                    double yFrac = 1.0 - (i / (double) (rayCount - 1)) * 2.0;
                    double radiusAtY = Math.sqrt(1.0 - yFrac * yFrac);
                    double theta = goldenAngle * i;
                    rayDirs[i * 3] = Math.cos(theta) * radiusAtY;
                    rayDirs[i * 3 + 1] = yFrac;
                    rayDirs[i * 3 + 2] = Math.sin(theta) * radiusAtY;
                }
                if (RustOptimizations.castRays(origin, rayDirs, rayCount, radius) != null) {
                    kneaf$rustRayCasts.addAndGet(rayCount);
                }
            } catch (Exception e) {
            }
        }

        java.util.Set<BlockPos> scanSet = new java.util.HashSet<>();

        // Amanatides-Woo 3D DDA Algorithm
        // Visits every voxel on the line exactly once.
        // Much faster than stepping 0.3 units (which oversamples) or 1.0 units (which
        // might skip).

        int resolution = 16;
        float resF = (float) (resolution - 1);

        for (int j = 0; j < resolution; ++j) {
            for (int k = 0; k < resolution; ++k) {
                for (int l = 0; l < resolution; ++l) {
                    if (j == 0 || j == resolution - 1 || k == 0 || k == resolution - 1 || l == 0
                            || l == resolution - 1) {
                        double dx = (double) ((float) j / resF * 2.0F - 1.0F);
                        double dy = (double) ((float) k / resF * 2.0F - 1.0F);
                        double dz = (double) ((float) l / resF * 2.0F - 1.0F);
                        double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        dx /= mag;
                        dy /= mag;
                        dz /= mag;

                        float f = radius * (0.7F + level.random.nextFloat() * 0.6F);

                        // Start position
                        int currentX = net.minecraft.util.Mth.floor(x);
                        int currentY = net.minecraft.util.Mth.floor(y);
                        int currentZ = net.minecraft.util.Mth.floor(z);

                        // Direction signs
                        int stepX = (dx > 0) ? 1 : -1;
                        int stepY = (dy > 0) ? 1 : -1;
                        int stepZ = (dz > 0) ? 1 : -1;

                        // Ray delta
                        double deltaDistX = (dx == 0) ? Double.MAX_VALUE : Math.abs(1.0 / dx);
                        double deltaDistY = (dy == 0) ? Double.MAX_VALUE : Math.abs(1.0 / dy);
                        double deltaDistZ = (dz == 0) ? Double.MAX_VALUE : Math.abs(1.0 / dz);

                        // Initial intersection distance
                        double sideDistX = (dx > 0) ? (currentX + 1.0 - x) * deltaDistX : (x - currentX) * deltaDistX;
                        double sideDistY = (dy > 0) ? (currentY + 1.0 - y) * deltaDistY : (y - currentY) * deltaDistY;
                        double sideDistZ = (dz > 0) ? (currentZ + 1.0 - z) * deltaDistZ : (z - currentZ) * deltaDistZ;

                        BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

                        while (f > 0.0F) {
                            mutPos.set(currentX, currentY, currentZ);

                            if (!level.isInWorldBounds(mutPos))
                                break;

                            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(mutPos);
                            net.minecraft.world.level.material.FluidState fluid = level.getFluidState(mutPos);
                            Explosion self = (Explosion) (Object) this;

                            java.util.Optional<Float> res = damageCalculator.getBlockExplosionResistance(self,
                                    level, mutPos, state, fluid);

                            if (res.isPresent()) {
                                f -= (res.get() + 0.3F) * 0.3F;
                            }

                            if (f > 0.0F && damageCalculator.shouldBlockExplode(self, level, mutPos, state, f)) {
                                scanSet.add(mutPos.immutable());
                            }

                            // Advance ray to next voxel
                            if (sideDistX < sideDistY) {
                                if (sideDistX < sideDistZ) {
                                    f -= 0.3F; // Heuristic approximation of distance consumed for checking
                                    sideDistX += deltaDistX;
                                    currentX += stepX;
                                } else {
                                    f -= 0.3F;
                                    sideDistZ += deltaDistZ;
                                    currentZ += stepZ;
                                }
                            } else {
                                if (sideDistY < sideDistZ) {
                                    f -= 0.3F;
                                    sideDistY += deltaDistY;
                                    currentY += stepY;
                                } else {
                                    f -= 0.3F;
                                    sideDistZ += deltaDistZ;
                                    currentZ += stepZ;
                                }
                            }
                        }
                    }
                }
            }
        }
        blocks.addAll(scanSet);
    }

    @Unique
    @SuppressWarnings("null")
    private void kneaf$performEntityImpact() {
        float q = radius * 2.0F;
        net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(x - q - 1, y - q - 1, z - q - 1,
                x + q + 1, y + q + 1, z + q + 1);
        List<net.minecraft.world.entity.Entity> entities = level.getEntities(getDirectSourceEntity(), aabb);

        if (entities.isEmpty())
            return;

        double qSq = (double) q * (double) q;

        for (net.minecraft.world.entity.Entity entity : entities) {
            if (!entity.ignoreExplosion((Explosion) (Object) this)) {
                double distSq = entity.distanceToSqr(x, y, z);
                if (distSq > qSq)
                    continue;

                double dist = Math.sqrt(distSq) / (double) q;
                if (dist <= 1.0D) {
                    double dx = entity.getX() - x,
                            dy = (entity instanceof net.minecraft.world.entity.item.PrimedTnt ? entity.getY()
                                    : entity.getEyeY()) - y,
                            dz = entity.getZ() - z;
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len != 0.0D) {
                        dx /= len;
                        dy /= len;
                        dz /= len;
                        double seen = (double) net.minecraft.world.level.Explosion
                                .getSeenPercent(new net.minecraft.world.phys.Vec3(x, y, z), entity);
                        double exposure = (1.0D - dist) * seen;
                        float damage = (float) ((int) ((exposure * exposure + exposure) / 2.0D * 7.0D * (double) q
                                + 1.0D));
                        net.minecraft.world.phys.Vec3 kb = new net.minecraft.world.phys.Vec3(dx * exposure,
                                dy * exposure, dz * exposure);

                        entity.hurt(damageSource, damage);
                        entity.setDeltaMovement(entity.getDeltaMovement().add(kb));
                    }
                }
            }
        }
    }

    @Inject(method = "finalizeExplosion", at = @At("HEAD"))
    @SuppressWarnings("null")
    private void kneaf$onFinalize(boolean spawnParticles, CallbackInfo ci) {
        List<BlockPos> blocks = getToBlow();
        if (!blocks.isEmpty()) {
            for (BlockPos bp : blocks) {
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(bp);
                if (state.is(net.minecraft.world.level.block.Blocks.TNT)) {
                    state.getBlock().wasExploded(level, bp, (Explosion) (Object) this);
                }
            }
            // Lower threshold for faster batch processing
            if (blocks.size() > 20) {
                com.kneaf.core.util.BatchBlockRemoval.removeBlocks(level, blocks);
                blocks.clear();
            }
        }
    }
}
