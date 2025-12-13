package com.kneaf.entities;

import com.kneaf.core.KneafCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, KneafCore.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KneafCore.MODID);

    // Entity registrations removed - focusing on performance mod only
}