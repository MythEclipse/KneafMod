package com.kneaf.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic test to verify Rust performance library functionality without Minecraft dependencies
 */
public class RustPerformanceBasicTest {

    private PerformanceManager performanceManager;

    @BeforeEach
    void setUp() {
        performanceManager = PerformanceManager.getInstance();
    }

    @Test
    @DisplayName("Test PerformanceManager basic functionality")
    void testPerformanceManagerBasic() {
        // Test that PerformanceManager can be instantiated
        assertNotNull(performanceManager);
        
        // Test basic configuration methods
        performanceManager.setEntityThrottlingEnabled(true);
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        
        performanceManager.setEntityThrottlingEnabled(false);
        assertFalse(performanceManager.isEntityThrottlingEnabled());
        
        performanceManager.setAiPathfindingOptimized(true);
        assertTrue(performanceManager.isAiPathfindingOptimized());
        
        
        performanceManager.setRustIntegrationEnabled(false);
        assertFalse(performanceManager.isRustIntegrationEnabled());
        
        performanceManager.setHorizontalPhysicsOnly(false);
        assertFalse(performanceManager.isHorizontalPhysicsOnly());
    }

    @Test
    @DisplayName("Test PerformanceManager string representation")
    void testPerformanceManagerToString() {
        String representation = performanceManager.toString();
        assertNotNull(representation);
        assertTrue(representation.contains("PerformanceManager"));
        assertTrue(representation.contains("entityThrottling"));
        assertTrue(representation.contains("aiPathfinding"));
        assertTrue(representation.contains("rustIntegration"));
    }

    @Test
    @DisplayName("Test configuration reset functionality")
    void testConfigurationReset() {
        // Modify some settings
        performanceManager.setEntityThrottlingEnabled(false);
        performanceManager.setAiPathfindingOptimized(false);
        
        // Reset to defaults
        performanceManager.resetToDefaults();
        
        // Verify defaults are restored
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        assertFalse(performanceManager.isRustIntegrationEnabled());
        assertFalse(performanceManager.isHorizontalPhysicsOnly());
    }

    @Test
    @DisplayName("Test async configuration loading")
    void testAsyncConfigurationLoading() throws Exception {
        // Test that async configuration loading can be called
        var future = performanceManager.loadConfigurationAsync();
        assertNotNull(future);
        
        // Wait for completion (should complete quickly)
        future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        
        // Verify configuration is still valid after async load
        assertNotNull(performanceManager.toString());
    }

    @Test
    @DisplayName("Test thread safety of configuration changes")
    void testThreadSafety() throws Exception {
        int threadCount = 10;
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        
        // Create multiple threads that modify configuration
        for (int i = 0; i < threadCount; i++) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 100; j++) {
                    performanceManager.setEntityThrottlingEnabled(j % 2 == 0);
                    performanceManager.setAiPathfindingOptimized(j % 3 == 0);
                }
            }));
        }
        
        // Wait for all threads to complete
        for (var future : futures) {
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        
        // Verify the system is still in a valid state
        String finalState = performanceManager.toString();
        assertNotNull(finalState);
        assertTrue(finalState.contains("PerformanceManager"));
    }
}