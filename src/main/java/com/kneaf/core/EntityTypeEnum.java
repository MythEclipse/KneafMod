package com.kneaf.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Optimized entity type enumeration for fast damping factor lookup.
 * Replaces expensive string-based entity type checking with hash-based lookup.
 */
public enum EntityTypeEnum {
    PLAYER(0.985, "Player"),
    ZOMBIE(0.990, "Zombie"), // Increased from 0.985 for better knockback preservation
    SKELETON(0.990, "Skeleton"), // Increased from 0.985 for better knockback preservation
    SLIME(0.990, "Slime"), // Increased from 0.985 for better knockback preservation
    COW(0.995, "Cow"), // Increased from 0.992 for better knockback
    SHEEP(0.995, "Sheep"), // Increased from 0.992 for better knockback
    PIG(0.995, "Pig"), // Increased from 0.992 for better knockback
    VILLAGER(0.992, "Villager"), // Increased from 0.988 for better knockback
    SHADOW_ZOMBIE_NINJA(0.990, "ShadowZombieNinja"), // Increased from 0.985 for better knockback preservation
    DEFAULT(0.985, "Default"); // Increased from 0.980 for better knockback
    
    private final double dampingFactor;
    private final String entityName;
    private static final Map<Class<? extends Entity>, EntityTypeEnum> entityClassMap = new HashMap<>();
    private static final Map<String, EntityTypeEnum> entityNameMap = new HashMap<>();
    
    static {
        // Initialize class-based lookup map
        entityClassMap.put(Player.class, PLAYER);
        entityClassMap.put(Zombie.class, ZOMBIE);
        entityClassMap.put(Skeleton.class, SKELETON);
        entityClassMap.put(Slime.class, SLIME);
        entityClassMap.put(Cow.class, COW);
        entityClassMap.put(Sheep.class, SHEEP);
        entityClassMap.put(Pig.class, PIG);
        entityClassMap.put(Villager.class, VILLAGER);
        entityClassMap.put(com.kneaf.entities.ShadowZombieNinja.class, SHADOW_ZOMBIE_NINJA);
        
        // Initialize name-based lookup map
        entityNameMap.put("player", PLAYER);
        entityNameMap.put("zombie", ZOMBIE);
        entityNameMap.put("skeleton", SKELETON);
        entityNameMap.put("slime", SLIME);
        entityNameMap.put("cow", COW);
        entityNameMap.put("sheep", SHEEP);
        entityNameMap.put("pig", PIG);
        entityNameMap.put("villager", VILLAGER);
        entityNameMap.put("shadow_zombie_ninja", SHADOW_ZOMBIE_NINJA);
    }
    
    EntityTypeEnum(double dampingFactor, String entityName) {
        this.dampingFactor = dampingFactor;
        this.entityName = entityName;
    }
    
    public double getDampingFactor() {
        return dampingFactor;
    }
    
    public String getEntityName() {
        return entityName;
    }
    
    /**
     * Fast entity type lookup using class-based hash map
     */
    public static EntityTypeEnum fromEntity(Entity entity) {
        if (entity == null) return DEFAULT;
        
        // Fast class-based lookup
        EntityTypeEnum result = entityClassMap.get(entity.getClass());
        if (result != null) {
            return result;
        }
        
        // Fallback to name-based lookup for subclasses
        String entityType = entity.getType().toString().toLowerCase();
        for (Map.Entry<String, EntityTypeEnum> entry : entityNameMap.entrySet()) {
            if (entityType.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return DEFAULT;
    }
    
    /**
     * Fast entity type lookup using pre-computed hash map
     */
    public static EntityTypeEnum fromString(String entityType) {
        if (entityType == null) return DEFAULT;
        
        String lowerType = entityType.toLowerCase();
        EntityTypeEnum result = entityNameMap.get(lowerType);
        return result != null ? result : DEFAULT;
    }
    
    /**
     * Optimized damping factor calculation using hash-based lookup
     */
    public static double calculateDampingFactor(Entity entity) {
        return fromEntity(entity).getDampingFactor();
    }
    
    /**
     * Batch damping factor calculation for multiple entities
     */
    public static double[] calculateDampingFactors(Entity[] entities) {
        double[] factors = new double[entities.length];
        for (int i = 0; i < entities.length; i++) {
            factors[i] = fromEntity(entities[i]).getDampingFactor();
        }
        return factors;
    }
    
    /**
     * Parallel damping factor calculation using Fork/Join
     */
    public static double[] calculateDampingFactorsParallel(Entity[] entities) {
        return java.util.Arrays.stream(entities)
            .parallel()
            .mapToDouble(EntityTypeEnum::calculateDampingFactor)
            .toArray();
    }
}