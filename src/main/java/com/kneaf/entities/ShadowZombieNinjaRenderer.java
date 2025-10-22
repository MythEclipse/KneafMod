package com.kneaf.entities;

import com.kneaf.core.KneafCore;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ShadowZombieNinjaRenderer extends LivingEntityRenderer<ShadowZombieNinja, ZombieModel<ShadowZombieNinja>> {
    private static final ResourceLocation TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath(KneafCore.MODID, "textures/entity/shadow_zombie_ninja.png");

    public ShadowZombieNinjaRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieModel<>(context.bakeLayer(net.minecraft.client.model.geom.ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ShadowZombieNinja entity) {
        return TEXTURE_LOCATION;
    }
}