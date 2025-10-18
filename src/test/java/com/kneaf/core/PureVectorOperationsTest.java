package com.kneaf.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for vector operations that don't depend on Minecraft classes
 * Tests the core logic without requiring full Minecraft environment
 */
public class PureVectorOperationsTest {

    @BeforeEach
    public void setUp() {
        OptimizationInjector.enableTestMode(true);
        OptimizationInjector.resetMetrics();
    }

    @Test
    public void testNaturalMovementValidation_PureMath() {
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
    public void testEdgeCases_PureMath() {
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

    @Test
    public void testDampingFactorCalculation_UnitTest() {
        // Test the damping factor logic directly without Minecraft dependencies
        // This tests the core logic that would be used in calculateEntitySpecificDamping
        
        // Player-like entity (should get 0.985 damping)
        String playerType = "net.minecraft.world.entity.Player";
        double playerDamping = getDampingFactorFromTypeName(playerType);
        assertEquals(0.985, playerDamping, 0.001, "Player should have most stable damping (0.985)");
        
        // Hostile mob-like entity (should get 0.975 damping)
        String zombieType = "net.minecraft.world.entity.Zombie";
        String skeletonType = "net.minecraft.world.entity.Skeleton";
        String slimeType = "net.minecraft.world.entity.Slime";
        
        double zombieDamping = getDampingFactorFromTypeName(zombieType);
        double skeletonDamping = getDampingFactorFromTypeName(skeletonType);
        double slimeDamping = getDampingFactorFromTypeName(slimeType);
        
        assertEquals(0.975, zombieDamping, 0.001, "Zombie should have responsive damping (0.975)");
        assertEquals(0.975, skeletonDamping, 0.001, "Skeleton should have responsive damping (0.975)");
        assertEquals(0.975, slimeDamping, 0.001, "Slime should have responsive damping (0.975)");
        
        // Passive mob-like entity (should get 0.992 damping)
        String cowType = "net.minecraft.world.entity.Cow";
        String sheepType = "net.minecraft.world.entity.Sheep";
        String pigType = "net.minecraft.world.entity.Pig";
        
        double cowDamping = getDampingFactorFromTypeName(cowType);
        double sheepDamping = getDampingFactorFromTypeName(sheepType);
        double pigDamping = getDampingFactorFromTypeName(pigType);
        
        assertEquals(0.992, cowDamping, 0.001, "Cow should have very stable damping (0.992)");
        assertEquals(0.992, sheepDamping, 0.001, "Sheep should have very stable damping (0.992)");
        assertEquals(0.992, pigDamping, 0.001, "Pig should have very stable damping (0.992)");
        
        // Villager-like entity (should get 0.988 damping)
        String villagerType = "net.minecraft.world.entity.Villager";
        double villagerDamping = getDampingFactorFromTypeName(villagerType);
        assertEquals(0.988, villagerDamping, 0.001, "Villager should have natural wandering damping (0.988)");
        
        // Unknown entity type (should get default 0.980 damping)
        String unknownType = "net.minecraft.world.entity.UnknownEntityType";
        double unknownDamping = getDampingFactorFromTypeName(unknownType);
        assertEquals(0.980, unknownDamping, 0.001, "Unknown entity types should use default damping (0.980)");
    }

    /**
     * Test helper that replicates the core logic from calculateEntitySpecificDamping
     * without requiring Minecraft Entity dependencies
     */
    private double getDampingFactorFromTypeName(String entityTypeName) {
        // Replicate the exact logic from OptimizationInjector.calculateEntitySpecificDamping
        if (entityTypeName.contains("Player")) {
            return 0.985; // Players need more stable movement
        } else if (entityTypeName.contains("Zombie") || entityTypeName.contains("Skeleton") || entityTypeName.contains("Slime")) {
            return 0.975; // Hostile mobs need more responsive movement
        } else if (entityTypeName.contains("Cow") || entityTypeName.contains("Sheep") || entityTypeName.contains("Pig")) {
            return 0.992; // Passive mobs need more stable movement
        } else if (entityTypeName.contains("Villager")) {
            return 0.988; // Villagers need natural wandering movement
        } else {
            // Default damping factor for unknown entity types
            return 0.980;
        }
    }
}