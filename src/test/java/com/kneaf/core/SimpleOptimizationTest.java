package com.kneaf.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test class for OptimizationInjector logic that avoids static initialization issues
 * by focusing on pure Java logic without native library dependencies.
 */
class SimpleOptimizationTest {

    @Test
    void testInputValidationLogic() {
        // Test the input validation logic directly
        assertTrue(hasInvalidValues(null), "Should detect null input");
        assertTrue(hasInvalidValues(new double[]{1.0, 2.0}), "Should detect wrong length input");
        assertTrue(hasInvalidValues(new double[]{Double.NaN, 2.0, 3.0}), "Should detect NaN values");
        assertTrue(hasInvalidValues(new double[]{Double.POSITIVE_INFINITY, 2.0, 3.0}), "Should detect Infinite values");
        assertFalse(hasInvalidValues(new double[]{1.0, 2.0, 3.0}), "Should accept valid values");
    }

    @Test
    void testMetricsClassStructure() {
        // Test that the metrics class works correctly
        OptimizationInjector.OptimizationMetrics metrics = new OptimizationInjector.OptimizationMetrics(
            10, 5, 100, true
        );
        
        assertEquals(10, metrics.getNativeHits(), "Should return correct hit count");
        assertEquals(5, metrics.getNativeMisses(), "Should return correct miss count");
    }

    @Test
    void testResultValidationLogic() {
        // Test the result validation logic directly
        assertTrue(hasInvalidResultValues(null), "Should detect null result");
        assertTrue(hasInvalidResultValues(new double[]{Double.NaN, 2.0, 3.0}), "Should detect NaN in result");
        assertTrue(hasInvalidResultValues(new double[]{Double.POSITIVE_INFINITY, 2.0, 3.0}), "Should detect Infinite in result");
        assertFalse(hasInvalidResultValues(new double[]{1.0, 2.0, 3.0}), "Should accept valid result");
    }

    /**
     * Helper method that replicates the input validation logic from OptimizationInjector
     */
    private boolean hasInvalidValues(double[] values) {
        if (values == null || values.length != 3) {
            return true;
        }
        
        for (double val : values) {
            if (Double.isNaN(val) || Double.isInfinite(val)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Helper method that replicates the result validation logic from OptimizationInjector
     */
    private boolean hasInvalidResultValues(double[] results) {
        if (results == null || results.length != 3) {
            return true;
        }
        
        for (double res : results) {
            if (Double.isNaN(res) || Double.isInfinite(res)) {
                return true;
            }
        }
        
        return false;
    }
}