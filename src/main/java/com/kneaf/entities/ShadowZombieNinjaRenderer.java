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
    // Use default Minecraft zombie texture instead of custom texture
    private static final ResourceLocation ZOMBIE_TEXTURE_LOCATION = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public ShadowZombieNinjaRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieModel<>(context.bakeLayer(net.minecraft.client.model.geom.ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ShadowZombieNinja entity) {
        // Return default zombie texture instead of custom shadow ninja texture
        return ZOMBIE_TEXTURE_LOCATION;
    }
}