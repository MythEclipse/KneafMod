/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TntRendererMixin - Client-side optimization for TNT rendering.
 * 
 * Optimizations:
 * 1. Distance Culling: Skip rendering TNT entities beyond 64 blocks.
 */
@Mixin(TntRenderer.class)
public abstract class TntRendererMixin extends EntityRenderer<PrimedTnt> {

    protected TntRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * Cull TNT rendering based on distance.
     */
    @Inject(method = "render(Lnet/minecraft/world/entity/item/PrimedTnt;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("HEAD"), cancellable = true)
    private void kneaf$onRender(PrimedTnt entity, float yaw, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        // Skip rendering if too far to save draw calls when thousands of TNT are
        // exploding
        // 64 blocks squared is 4096
        if (entity.distanceToSqr(this.entityRenderDispatcher.camera.getPosition()) > 4096.0) {
            ci.cancel();
        }
    }
}
