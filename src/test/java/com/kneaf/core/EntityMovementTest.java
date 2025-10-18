package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Slime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for entity-specific movement optimization to ensure natural movement patterns
 * Validates that Rust vector calculations preserve game physics and entity behavior
 */
public class EntityMovementTest {
    private static final Logger LOGGER = LogUtils.getLogger();

    @BeforeEach
    public void setUp() {
        OptimizationInjector.enableTestMode(true);
        OptimizationInjector.resetMetrics();
    }

    @Test
    public void testPlayerMovementPreservation() {
        Player player = createMockEntity(EntityType.PLAYER);
        double[] originalMovement = {0.5, 0.1, 0.2};
        
        double dampingFactor = OptimizationInjector.calculateEntitySpecificDamping(player);
        assertEquals(0.985, dampingFactor, 0.001, "Player should have most stable damping (0.985)");
        
        // In test mode, we can't call Rust, but we validate the logic flow
        assertTrue(OptimizationInjector.isNaturalMovement(originalMovement, originalMovement[0], originalMovement[1], originalMovement[2]), 
                  "Original player movement should always be considered natural");
    }

    @Test
    public void testHostileMobMovement() {
        Zombie zombie = createMockEntity(EntityType.ZOMBIE);
        Slime slime = createMockEntity(EntityType.SLIME);
        Skeleton skeleton = createMockEntity(EntityType.SKELETON);
        
        double[] originalMovement = {0.8, 0.3, 0.4};
        
        double zombieDamping = OptimizationInjector.calculateEntitySpecificDamping(zombie);
        double slimeDamping = OptimizationInjector.calculateEntitySpecificDamping(slime);
        double skeletonDamping = OptimizationInjector.calculateEntitySpecificDamping(skeleton);
        
        assertEquals(0.975, zombieDamping, 0.001, "Zombie should have responsive damping (0.975)");
        assertEquals(0.975, slimeDamping, 0.001, "Slime should have responsive damping (0.975)");
        assertEquals(0.975, skeletonDamping, 0.001, "Skeleton should have responsive damping (0.975)");
        
        // Test natural movement validation
        double[] modifiedMovement = {0.78, 0.29, 0.39}; // Small changes should be natural
        assertTrue(OptimizationInjector.isNaturalMovement(modifiedMovement, originalMovement[0], originalMovement[1], originalMovement[2]),
                  "Small movement changes should be considered natural");
        
        double[] extremeMovement = {100.0, 50.0, 75.0}; // Extreme changes should be rejected
        assertFalse(OptimizationInjector.isNaturalMovement(extremeMovement, originalMovement[0], originalMovement[1], originalMovement[2]),
                  "Extreme movement changes should be rejected");
    }

    @Test
    public void testPassiveMobMovement() {
        Cow cow = createMockEntity(EntityType.COW);
        Sheep sheep = createMockEntity(EntityType.SHEEP);
        Pig pig = createMockEntity(EntityType.PIG);
        
        double[] originalMovement = {0.2, 0.05, 0.15};
        
        double cowDamping = OptimizationInjector.calculateEntitySpecificDamping(cow);
        double sheepDamping = OptimizationInjector.calculateEntitySpecificDamping(sheep);
        double pigDamping = OptimizationInjector.calculateEntitySpecificDamping(pig);
        
        assertEquals(0.992, cowDamping, 0.001, "Cow should have very stable damping (0.992)");
        assertEquals(0.992, sheepDamping, 0.001, "Sheep should have very stable damping (0.992)");
        assertEquals(0.992, pigDamping, 0.001, "Pig should have very stable damping (0.992)");
    }

    @Test
    public void testVillagerMovement() {
        Villager villager = createMockEntity(EntityType.VILLAGER);
        double[] originalMovement = {0.3, 0.1, 0.2};
        
        double dampingFactor = OptimizationInjector.calculateEntitySpecificDamping(villager);
        assertEquals(0.988, dampingFactor, 0.001, "Villager should have natural wandering damping (0.988)");
    }

    @Test
    public void testUnknownEntityMovement() {
        Entity unknown = createMockEntity(EntityType.PIG);
        // Manually change type to something unknown
        double[] originalMovement = {0.4, 0.2, 0.3};
        
        // Mock unknown entity type
        Entity mockUnknown = new Entity(EntityType.PIG) {
            @Override
            public String toString() {
                return "com.mojang.test.UnknownEntityType";
            }
        };
        
        double dampingFactor = OptimizationInjector.calculateEntitySpecificDamping(mockUnknown);
        assertEquals(0.980, dampingFactor, 0.001, "Unknown entity types should use default damping (0.980)");
    }

    @Test
    public void testNaturalMovementValidation() {
        double[] original = {1.0, 0.5, 0.8};
        
        // Test normal variations (should pass)
        double[] normal1 = {0.9, 0.45, 0.72}; // 10% reduction
        double[] normal2 = {1.1, 0.55, 0.88}; // 10% increase
        assertTrue(OptimizationInjector.isNaturalMovement(normal1, original[0], original[1], original[2]));
        assertTrue(OptimizationInjector.isNaturalMovement(normal2, original[0], original[1], original[2]));
        
        // Test extreme variations (should fail)
        double[] extreme1 = {15.0, 7.5, 12.0}; // 1500% increase
        double[] extreme2 = {-20.0, -10.0, -16.0}; // 2000% decrease
        double[] invalid1 = {Double.NaN, 0.5, 0.8}; // NaN value
        double[] invalid2 = {1.0, Double.POSITIVE_INFINITY, 0.8}; // Infinite value
        
        assertFalse(OptimizationInjector.isNaturalMovement(extreme1, original[0], original[1], original[2]));
        assertFalse(OptimizationInjector.isNaturalMovement(extreme2, original[0], original[1], original[2]));
        assertFalse(OptimizationInjector.isNaturalMovement(invalid1, original[0], original[1], original[2]));
        assertFalse(OptimizationInjector.isNaturalMovement(invalid2, original[0], original[1], original[2]));
    }

    @Test
    public void testEdgeCases() {
        // Test zero movement
        double[] zeroMovement = {0.0, 0.0, 0.0};
        assertTrue(OptimizationInjector.isNaturalMovement(zeroMovement, 0.0, 0.0, 0.0));
        
        // Test negative values (should be valid as long as they're reasonable)
        double[] negativeMovement = {-0.3, -0.1, -0.2};
        assertTrue(OptimizationInjector.isNaturalMovement(negativeMovement, -0.3, -0.1, -0.2));
        
        // Test null/empty arrays
        assertFalse(OptimizationInjector.isNaturalMovement(null, 1.0, 2.0, 3.0));
        assertFalse(OptimizationInjector.isNaturalMovement(new double[0], 1.0, 2.0, 3.0));
        assertFalse(OptimizationInjector.isNaturalMovement(new double[2], 1.0, 2.0, 3.0));
    }

    private <T extends Entity> T createMockEntity(EntityType<T> type) {
        // Create a minimal mock entity for testing
        return type.create(null);
    }
}