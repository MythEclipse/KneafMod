package com.kneaf.entities;

import com.kneaf.core.KneafCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, KneafCore.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KneafCore.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<ShadowZombieNinja>> SHADOW_ZOMBIE_NINJA = ENTITIES.register("shadow_zombie_ninja",
        () -> EntityType.Builder.of(ShadowZombieNinja::new, MobCategory.MONSTER)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(8)
            .build("shadow_zombie_ninja")
    );

    // Spawn egg for Shadow Zombie Ninja
    public static final DeferredHolder<Item, DeferredSpawnEggItem> SHADOW_ZOMBIE_NINJA_SPAWN_EGG = ITEMS.register("shadow_zombie_ninja_spawn_egg",
        () -> new DeferredSpawnEggItem(ModEntities.SHADOW_ZOMBIE_NINJA, 0x2B2B2B, 0x4A4A4A, new Item.Properties())
    );
}