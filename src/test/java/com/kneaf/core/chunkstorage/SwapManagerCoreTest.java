package com.kneaf.core.chunkstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Core test for SwapManager functionality without Minecraft dependencies.
 * This test validates the basic swap system operations and configuration.
 */
public class SwapManagerCoreTest {
    
    private SwapManager.SwapConfig swapConfig;
    private SwapManager swapManager;
    
    @BeforeEach
    void setUp() {
        System.out.println("=== Setting up SwapManager Core Test ===");
        
        // Create a basic swap configuration
        swapConfig = new SwapManager.SwapConfig();
        swapConfig.setEnabled(true);
        swapConfig.setMemoryCheckIntervalMs(1000);
        swapConfig.setMaxConcurrentSwaps(3);
        swapConfig.setSwapBatchSize(5);
        swapConfig.setEnableAutomaticSwapping(true);
        swapConfig.setCriticalMemoryThreshold(0.95);
        swapConfig.setHighMemoryThreshold(0.85);
        swapConfig.setElevatedMemoryThreshold(0.75);
    }
    
    @Test
    @DisplayName("Test SwapManager Creation")
    void testSwapManagerCreation() {
        System.out.println("Testing SwapManager Creation...");
        
        // Test creating SwapManager with valid config
        swapManager = new SwapManager(swapConfig);
        assertNotNull(swapManager, "SwapManager should be created");
        
        // Test basic operations
        SwapManager.MemoryUsageInfo memoryUsage = swapManager.getMemoryUsage();
        assertNotNull(memoryUsage, "Memory usage should not be null");
        assertTrue(memoryUsage.getHeapUsed() >= 0, "Heap used should be non-negative");
        assertTrue(memoryUsage.getHeapMax() > 0, "Heap max should be positive");
        
        SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();
        assertNotNull(pressureLevel, "Memory pressure level should not be null");
        
        SwapManager.SwapStatistics swapStats = swapManager.getSwapStatistics();
        assertNotNull(swapStats, "Swap statistics should not be null");
        
        SwapManager.SwapManagerStats managerStats = swapManager.getStats();
        assertNotNull(managerStats, "Swap manager statistics should not be null");
        assertTrue(managerStats.isEnabled(), "Swap manager should be enabled");
        
        // Cleanup
        swapManager.shutdown();
        System.out.println("✓ SwapManager creation test passed!");
    }
    
    @Test
    @DisplayName("Test Memory Pressure Detection")
    void testMemoryPressureDetection() {
        System.out.println("Testing Memory Pressure Detection...");
        
        swapManager = new SwapManager(swapConfig);
        
        // Test different memory pressure scenarios
        SwapManager.MemoryUsageInfo memoryUsage = swapManager.getMemoryUsage();
        SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();
        
        System.out.println("✓ Memory usage: " + memoryUsage.getHeapUsed() + " / " + memoryUsage.getHeapMax());
        System.out.println("✓ Memory pressure level: " + pressureLevel);
        System.out.println("✓ Usage percentage: " + memoryUsage.getUsagePercentage());
        
        // Verify pressure level is valid
        assertTrue(pressureLevel == SwapManager.MemoryPressureLevel.NORMAL ||
                  pressureLevel == SwapManager.MemoryPressureLevel.ELEVATED ||
                  pressureLevel == SwapManager.MemoryPressureLevel.HIGH ||
                  pressureLevel == SwapManager.MemoryPressureLevel.CRITICAL,
                  "Pressure level should be one of the defined levels");
        
        // Verify usage percentage is reasonable
        double usagePercentage = memoryUsage.getUsagePercentage();
        assertTrue(usagePercentage >= 0.0 && usagePercentage <= 1.0,
                  "Usage percentage should be between 0.0 and 1.0");
        
        // Cleanup
        swapManager.shutdown();
        System.out.println("✓ Memory pressure detection test passed!");
    }
    
    @Test
    @DisplayName("Test Swap Configuration")
    void testSwapConfiguration() {
        System.out.println("Testing Swap Configuration...");
        
        // Test configuration getters and setters
        assertTrue(swapConfig.isEnabled(), "Swap should be enabled");
        assertEquals(1000, swapConfig.getMemoryCheckIntervalMs(), "Memory check interval should be 1000ms");
        assertEquals(3, swapConfig.getMaxConcurrentSwaps(), "Max concurrent swaps should be 3");
        assertEquals(5, swapConfig.getSwapBatchSize(), "Swap batch size should be 5");
        assertTrue(swapConfig.isEnableAutomaticSwapping(), "Automatic swapping should be enabled");
        assertEquals(0.95, swapConfig.getCriticalMemoryThreshold(), 0.01, "Critical threshold should be 0.95");
        assertEquals(0.85, swapConfig.getHighMemoryThreshold(), 0.01, "High threshold should be 0.85");
        assertEquals(0.75, swapConfig.getElevatedMemoryThreshold(), 0.01, "Elevated threshold should be 0.75");
        
        // Test configuration modification
        swapConfig.setEnabled(false);
        swapConfig.setMaxConcurrentSwaps(10);
        swapConfig.setSwapBatchSize(20);
        
        assertFalse(swapConfig.isEnabled(), "Swap should be disabled");
        assertEquals(10, swapConfig.getMaxConcurrentSwaps(), "Max concurrent swaps should be 10");
        assertEquals(20, swapConfig.getSwapBatchSize(), "Swap batch size should be 20");
        
        System.out.println("✓ Swap configuration test passed!");
    }
    
    @Test
    @DisplayName("Test Swap Statistics")
    void testSwapStatistics() {
        System.out.println("Testing Swap Statistics...");
        
        swapManager = new SwapManager(swapConfig);
        
        SwapManager.SwapStatistics stats = swapManager.getSwapStatistics();
        
        // Test initial state
        assertEquals(0, stats.getTotalSwapOuts(), "Initial swap outs should be 0");
        assertEquals(0, stats.getTotalSwapIns(), "Initial swap ins should be 0");
        assertEquals(0, stats.getTotalFailures(), "Initial failures should be 0");
        assertEquals(0.0, stats.getAverageSwapOutTime(), 0.01, "Initial avg swap out time should be 0");
        assertEquals(0.0, stats.getAverageSwapInTime(), 0.01, "Initial avg swap in time should be 0");
        assertEquals(0.0, stats.getFailureRate(), 0.01, "Initial failure rate should be 0");
        
        // Test recording operations
        stats.recordSwapOut(100, 16384);
        assertEquals(1, stats.getTotalSwapOuts(), "Swap outs should be 1");
        assertEquals(100, stats.getTotalSwapOutTime(), "Total swap out time should be 100");
        assertEquals(16384, stats.getTotalBytesSwappedOut(), "Total bytes swapped out should be 16384");
        
        stats.recordSwapIn(200, 32768);
        assertEquals(1, stats.getTotalSwapIns(), "Swap ins should be 1");
        assertEquals(200, stats.getTotalSwapInTime(), "Total swap in time should be 200");
        assertEquals(32768, stats.getTotalBytesSwappedIn(), "Total bytes swapped in should be 32768");
        
        stats.recordFailure();
        assertEquals(1, stats.getTotalFailures(), "Failures should be 1");
        
        // Test calculated values
        assertEquals(100.0, stats.getAverageSwapOutTime(), 0.01, "Average swap out time should be 100");
        assertEquals(200.0, stats.getAverageSwapInTime(), 0.01, "Average swap in time should be 200");
        assertEquals(0.5, stats.getFailureRate(), 0.01, "Failure rate should be 0.5");
        
        // Cleanup
        swapManager.shutdown();
        System.out.println("✓ Swap statistics test passed!");
    }
    
    @Test
    @DisplayName("Test SwapManager Statistics")
    void testSwapManagerStatistics() {
        System.out.println("Testing SwapManager Statistics...");
        
        // Create fresh config to ensure it's enabled
        SwapManager.SwapConfig freshConfig = new SwapManager.SwapConfig();
        freshConfig.setEnabled(true);
        freshConfig.setMemoryCheckIntervalMs(1000);
        freshConfig.setMaxConcurrentSwaps(3);
        freshConfig.setSwapBatchSize(5);
        freshConfig.setEnableAutomaticSwapping(true);
        freshConfig.setCriticalMemoryThreshold(0.95);
        freshConfig.setHighMemoryThreshold(0.85);
        freshConfig.setElevatedMemoryThreshold(0.75);
        
        swapManager = new SwapManager(freshConfig);
        
        SwapManager.SwapManagerStats stats = swapManager.getStats();
        
        // Debug information
        System.out.println("✓ Fresh config enabled: " + freshConfig.isEnabled());
        System.out.println("✓ Stats enabled: " + stats.isEnabled());
        System.out.println("✓ SwapManager instance: " + swapManager);
        System.out.println("✓ Stats instance: " + stats);
        
        // Test basic statistics
        assertTrue(stats.isEnabled(), "Swap manager should be enabled (config: " + freshConfig.isEnabled() + ", stats: " + stats.isEnabled() + ")");
        assertNotNull(stats.getPressureLevel(), "Pressure level should not be null");
        assertEquals(0, stats.getTotalOperations(), "Total operations should be 0");
        assertEquals(0, stats.getFailedOperations(), "Failed operations should be 0");
        assertEquals(0, stats.getActiveSwaps(), "Active swaps should be 0");
        assertEquals(0, stats.getPressureTriggers(), "Pressure triggers should be 0");
        assertNotNull(stats.getMemoryUsage(), "Memory usage should not be null");
        assertNotNull(stats.getSwapStats(), "Swap stats should not be null");
        assertEquals(0.0, stats.getFailureRate(), 0.01, "Failure rate should be 0");
        
        // Cleanup
        swapManager.shutdown();
        System.out.println("✓ SwapManager statistics test passed!");
    }
    
    @Test
    @DisplayName("Test Disabled SwapManager")
    void testDisabledSwapManager() {
        System.out.println("Testing Disabled SwapManager...");
        
        // Create disabled configuration
        SwapManager.SwapConfig disabledConfig = new SwapManager.SwapConfig();
        disabledConfig.setEnabled(false);
        
        SwapManager disabledManager = new SwapManager(disabledConfig);
        
        // Test that operations return false when disabled
        SwapManager.SwapManagerStats stats = disabledManager.getStats();
        assertFalse(stats.isEnabled(), "Swap manager should be disabled");
        
        // Cleanup
        disabledManager.shutdown();
        System.out.println("✓ Disabled SwapManager test passed!");
    }
    
    @Test
    @DisplayName("Test Complete Integration")
    void testCompleteIntegration() {
        System.out.println("=== Complete Swap System Integration Test ===\n");
        
        testSwapManagerCreation();
        testMemoryPressureDetection();
        testSwapConfiguration();
        testSwapStatistics();
        testSwapManagerStatistics();
        testDisabledSwapManager();
        
        System.out.println("\n🎉 All core SwapManager integration tests completed successfully!");
        System.out.println("\nValidated Features:");
        System.out.println("✓ SwapManager creation and configuration");
        System.out.println("✓ Memory pressure detection and monitoring");
        System.out.println("✓ Swap statistics collection and reporting");
        System.out.println("✓ Configuration management and validation");
        System.out.println("✓ Statistics aggregation and failure rate calculation");
        System.out.println("✓ Proper resource management and shutdown");
    }
}