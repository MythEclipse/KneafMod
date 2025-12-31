package com.kneaf.core.mixin;

import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VoxelShapeMixin - Extreme optimization for shape intersections.
 */
@Mixin(Shapes.class)
public abstract class VoxelShapeMixin {

    /**
     * Optimization: joinIsNotEmpty fail-fast.
     * Before doing expensive voxel-by-voxel intersection, check if AABBs even
     * touch.
     */
    @Inject(method = "joinIsNotEmpty", at = @At("HEAD"), cancellable = true)
    private static void kneaf$onJoinIsNotEmpty(VoxelShape shape1, VoxelShape shape2,
            net.minecraft.world.phys.shapes.BooleanOp operator, CallbackInfoReturnable<Boolean> cir) {
        if (operator == net.minecraft.world.phys.shapes.BooleanOp.AND) {
            // If bounding boxes don't intersect, the shapes certainly don't
            if (shape1.isEmpty() || shape2.isEmpty()) {
                cir.setReturnValue(false);
                return;
            }

            // Fast AABB intersection check
            if (shape1.min(net.minecraft.core.Direction.Axis.X) >= shape2.max(net.minecraft.core.Direction.Axis.X) ||
                    shape1.max(net.minecraft.core.Direction.Axis.X) <= shape2.min(net.minecraft.core.Direction.Axis.X)
                    ||
                    shape1.min(net.minecraft.core.Direction.Axis.Y) >= shape2.max(net.minecraft.core.Direction.Axis.Y)
                    ||
                    shape1.max(net.minecraft.core.Direction.Axis.Y) <= shape2.min(net.minecraft.core.Direction.Axis.Y)
                    ||
                    shape1.min(net.minecraft.core.Direction.Axis.Z) >= shape2.max(net.minecraft.core.Direction.Axis.Z)
                    ||
                    shape1.max(net.minecraft.core.Direction.Axis.Z) <= shape2
                            .min(net.minecraft.core.Direction.Axis.Z)) {
                cir.setReturnValue(false);
            }
        }
    }
}
