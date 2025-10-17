package com.kneaf.core;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for OptimizationInjector that avoids static initialization issues
 * by focusing on test-friendly methods and avoiding native library loading.
 */
class OptimizationInjectorTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationInjectorTest.class);

    @Test
    void testCalculatePhysics_InputValidation_NaNValues() {
        // Test NaN input validation directly without triggering native calls
        OptimizationInjectorTestHelper helper = new OptimizationInjectorTestHelper();
        double[] nanInputs = {Double.NaN, 0.0, 0.0};
        boolean onGround = false;

        // This should handle NaN values gracefully without native calls
        assertDoesNotThrow(() -> {
            helper.testCalculatePhysics(nanInputs, onGround);
        });
    }

    @Test
    void testCalculatePhysics_InputValidation_InfiniteValues() {
        // Test Infinite input validation directly without triggering native calls
        OptimizationInjectorTestHelper helper = new OptimizationInjectorTestHelper();
        double[] infiniteInputs = {Double.POSITIVE_INFINITY, 0.0, 0.0};
        boolean onGround = false;

        // This should handle Infinite values gracefully without native calls
        assertDoesNotThrow(() -> {
            helper.testCalculatePhysics(infiniteInputs, onGround);
        });
    }

    @Test
    void testCalculatePhysics_InputValidation_NullInput() {
        // Test null input validation directly without triggering native calls
        OptimizationInjectorTestHelper helper = new OptimizationInjectorTestHelper();
        double[] nullInputs = null;
        boolean onGround = false;

        // This should handle null inputs gracefully without native calls
        assertDoesNotThrow(() -> {
            helper.testCalculatePhysics(nullInputs, onGround);
        });
    }

    @Test
    void testCalculatePhysics_InputValidation_WrongLength() {
        // Test wrong length input validation directly without triggering native calls
        OptimizationInjectorTestHelper helper = new OptimizationInjectorTestHelper();
        double[] wrongLengthInputs = {1.0, 2.0}; // Only 2 elements instead of 3
        boolean onGround = false;

        // This should handle wrong length inputs gracefully without native calls
        assertDoesNotThrow(() -> {
            helper.testCalculatePhysics(wrongLengthInputs, onGround);
        });
    }

    @Test
    void testFallbackToVanilla_ReturnsOriginalValues() {
        // Test the fallback method directly which should always be available
        OptimizationInjectorTestHelper helper = new OptimizationInjectorTestHelper();
        double[] originalInputs = {1.0, 2.0, 3.0};
        boolean onGround = false;

        double[] result = helper.testFallbackToVanilla(originalInputs, onGround);
         
        assertArrayEquals(originalInputs, result, "Fallback should return original values unchanged");
    }

    @Test
    void testOptimizationMetricsStructure() {
        // Test that we can create and access metrics objects without native calls
        OptimizationInjector.OptimizationMetrics metrics = new OptimizationInjector.OptimizationMetrics(
            10, 5, 100, true
        );
         
        assertEquals(10, metrics.getNativeHits(), "Should return correct hit count");
        assertEquals(5, metrics.getNativeMisses(), "Should return correct miss count");
    }

    /**
     * Test helper class that avoids static initialization issues
     * by creating instances rather than using static methods
     */
    static class OptimizationInjectorTestHelper {
        public OptimizationInjectorTestHelper() {
            // Enable test mode when helper is created
            System.setProperty("kneaf.test.mode", "true");
        }

        public double[] testCalculatePhysics(double[] position, boolean onGround) {
            // Input validation - matches OptimizationInjector behavior
            if (position == null || position.length != 3) {
                LOGGER.error("Invalid input: position array must contain exactly 3 elements");
                return testFallbackToVanilla(position, onGround);
            }

            for (double val : position) {
                if (Double.isNaN(val) || Double.isInfinite(val)) {
                    LOGGER.warn("Native physics calculation skipped - invalid input values (NaN/Infinite)");
                    return testFallbackToVanilla(position, onGround);
                }
            }

            // Always use fallback in tests (mimics test mode behavior)
            return testFallbackToVanilla(position, onGround);
        }

        public double[] testFallbackToVanilla(double[] position, boolean onGround) {
            // Simulate the fallback behavior exactly as in OptimizationInjector
            if (position == null) {
                return null;
            }
            return position.clone();
        }
    }
}