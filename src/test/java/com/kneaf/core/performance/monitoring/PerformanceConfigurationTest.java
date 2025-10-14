package com.kneaf.core.performance.monitoring;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for PerformanceConfiguration
 */
public class PerformanceConfigurationTest {

    @Test
    public void testGetCurrentThreadPoolSize() {
        // Test that method returns a valid size
        int size = PerformanceConfiguration.getCurrentThreadPoolSize();

        // Should be non-negative
        assertTrue(size >= 0, "Thread pool size should be non-negative");

        // Should be within reasonable bounds
        assertTrue(size <= 1000, "Thread pool size should be reasonable");

        System.out.println("Current thread pool size: " + size);
    }

    @Test
    public void testGetCurrentThreadPoolSizeMultipleCalls() {
        // Test caching behavior - multiple calls should be fast
        long startTime = System.nanoTime();
        int size1 = PerformanceConfiguration.getCurrentThreadPoolSize();
        int size2 = PerformanceConfiguration.getCurrentThreadPoolSize();
        long endTime = System.nanoTime();

        // Should return same or similar values
        assertEquals(size1, size2, "Multiple calls should return consistent values");

        // Should be fast (cached)
        long durationMs = (endTime - startTime) / 1_000_000;
        assertTrue(durationMs < 10, "Multiple calls should be fast due to caching");

        System.out.println("Multiple calls duration: " + durationMs + "ms");
    }

    @Test
    public void testConfigurationBounds() {
        // Test that size is within configured bounds
        int size = PerformanceConfiguration.getCurrentThreadPoolSize();
        int minSize = PerformanceConfiguration.getMinThreadPoolSize();
        int maxSize = PerformanceConfiguration.getMaxThreadPoolSize();

        assertTrue(size >= minSize, "Size should be >= min configured size");
        assertTrue(size <= maxSize, "Size should be <= max configured size");

        System.out.println("Size bounds check: " + size + " in [" + minSize + ", " + maxSize + "]");
    }
}