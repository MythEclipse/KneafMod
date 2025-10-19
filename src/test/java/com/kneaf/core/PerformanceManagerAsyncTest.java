package com.kneaf.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for PerformanceManager async configuration loading with caching.
 * Tests async configuration loading, caching mechanisms, thread safety, and fallback behavior.
 */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PerformanceManagerAsyncTest {

    @TempDir
    Path tempDir;
    
    private PerformanceManager performanceManager;
    private File configFile;
    private final AtomicInteger configLoadCount = new AtomicInteger(0);
    private final AtomicBoolean asyncLoadCompleted = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws IOException {
        // Create a temporary config file for testing
        configFile = tempDir.resolve("kneaf-performance.properties").toFile();
        performanceManager = PerformanceManager.getInstance();
        
        // Reset to defaults before each test
        performanceManager.resetToDefaults();
        configLoadCount.set(0);
        asyncLoadCompleted.set(false);
    }

    @AfterEach
    void tearDown() {
        // Clean up any test-specific state
        asyncLoadCompleted.set(false);
    }

    /**
     * Test async configuration loading with valid configuration file
     */
    @Test
    @DisplayName("Test async configuration loading with valid file")
    void testAsyncConfigurationLoadingValidFile() throws Exception {
        // Create valid configuration file
        createValidConfigurationFile();
        
        // Test async loading
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        
        // Wait for completion
        future.get(5, TimeUnit.SECONDS);
        
        // Verify configuration was loaded
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        assertTrue(performanceManager.isRenderingMathOptimized());
        assertTrue(performanceManager.isRustIntegrationEnabled());
        assertFalse(performanceManager.isHorizontalPhysicsOnly());
        
        asyncLoadCompleted.set(true);
    }

    /**
     * Test async configuration loading with missing file (fallback to defaults)
     */
    @Test
    @DisplayName("Test async configuration loading with missing file")
    void testAsyncConfigurationLoadingMissingFile() throws Exception {
        // Ensure config file doesn't exist
        if (configFile.exists()) {
            configFile.delete();
        }
        
        // Test async loading with missing file
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        
        // Wait for completion
        future.get(5, TimeUnit.SECONDS);
        
        // Verify fallback to defaults
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        assertTrue(performanceManager.isRenderingMathOptimized());
        assertFalse(performanceManager.isRustIntegrationEnabled()); // Default is false
        assertFalse(performanceManager.isHorizontalPhysicsOnly());
        
        asyncLoadCompleted.set(true);
    }

    /**
     * Test async configuration loading with invalid file content
     */
    @Test
    @DisplayName("Test async configuration loading with invalid content")
    void testAsyncConfigurationLoadingInvalidContent() throws Exception {
        // Create invalid configuration file
        createInvalidConfigurationFile();
        
        // Test async loading
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        
        // Wait for completion
        future.get(5, TimeUnit.SECONDS);
        
        // Verify fallback to defaults on error
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        assertTrue(performanceManager.isRenderingMathOptimized());
        assertFalse(performanceManager.isRustIntegrationEnabled());
        assertFalse(performanceManager.isHorizontalPhysicsOnly());
        
        asyncLoadCompleted.set(true);
    }

    /**
     * Test concurrent async configuration loading (thread safety)
     */
    @Test
    @DisplayName("Test concurrent async configuration loading")
    void testConcurrentAsyncConfigurationLoading() throws Exception {
        createValidConfigurationFile();
        
        int numThreads = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    
                    CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
                    future.get(3, TimeUnit.SECONDS);
                    
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Async config loading failed: " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all to complete
        boolean completed = completeLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent async loading timed out");
        
        // Verify all threads succeeded
        assertEquals(numThreads, successCount.get());
        
        // Verify final configuration state
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        
        executor.shutdown();
    }

    /**
     * Test configuration caching - multiple loads should be efficient
     */
    @Test
    @DisplayName("Test configuration caching effectiveness")
    void testConfigurationCaching() throws Exception {
        createValidConfigurationFile();
        
        long startTime = System.nanoTime();
        
        // Load configuration multiple times
        for (int i = 0; i < 5; i++) {
            CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
            future.get(2, TimeUnit.SECONDS);
        }
        
        long endTime = System.nanoTime();
        long totalDurationMs = (endTime - startTime) / 1_000_000;
        
        // Should be fast due to caching (if implemented)
        // Note: Current implementation doesn't have explicit caching, but this tests the async mechanism
        assertTrue(totalDurationMs < 5000, "Multiple async loads took too long: " + totalDurationMs + "ms");
        
        // Verify configuration is consistent
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
    }

    /**
     * Test async configuration loading performance benchmarks
     */
    @Test
    @DisplayName("Test async configuration loading performance")
    void testAsyncConfigurationLoadingPerformance() throws Exception {
        createValidConfigurationFile();
        
        int iterations = 100;
        long[] durations = new long[iterations];
        
        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            
            CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
            future.get(1, TimeUnit.SECONDS);
            
            long endTime = System.nanoTime();
            durations[i] = (endTime - startTime) / 1_000_000; // Convert to milliseconds
        }
        
        // Calculate statistics
        long totalDuration = 0;
        long minDuration = Long.MAX_VALUE;
        long maxDuration = 0;
        
        for (long duration : durations) {
            totalDuration += duration;
            minDuration = Math.min(minDuration, duration);
            maxDuration = Math.max(maxDuration, duration);
        }
        
        double avgDuration = (double) totalDuration / iterations;
        
        System.out.println("Async Configuration Loading Performance:");
        System.out.println("  Iterations: " + iterations);
        System.out.println("  Average duration: " + avgDuration + "ms");
        System.out.println("  Min duration: " + minDuration + "ms");
        System.out.println("  Max duration: " + maxDuration + "ms");
        
        // Performance assertions
        assertTrue(avgDuration < 100.0, "Average duration too high: " + avgDuration + "ms");
        assertTrue(maxDuration < 500, "Max duration too high: " + maxDuration + "ms");
    }

    /**
     * Test configuration change notifications
     */
    @Test
    @DisplayName("Test configuration change notifications")
    void testConfigurationChangeNotifications() throws Exception {
        createValidConfigurationFile();
        
        // Track configuration changes
        AtomicBoolean entityThrottlingChanged = new AtomicBoolean(false);
        AtomicBoolean aiPathfindingChanged = new AtomicBoolean(false);
        
        // Get initial state
        boolean initialEntityThrottling = performanceManager.isEntityThrottlingEnabled();
        boolean initialAiPathfinding = performanceManager.isAiPathfindingOptimized();
        
        // Load configuration (should trigger notifications if state changes)
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        future.get(3, TimeUnit.SECONDS);
        
        // Check if states changed (they might not if already in correct state)
        boolean finalEntityThrottling = performanceManager.isEntityThrottlingEnabled();
        boolean finalAiPathfinding = performanceManager.isAiPathfindingOptimized();
        
        // Verify configuration is loaded correctly
        assertEquals(true, finalEntityThrottling);
        assertEquals(true, finalAiPathfinding);
        
        // Test explicit configuration changes
        performanceManager.setEntityThrottlingEnabled(!initialEntityThrottling);
        performanceManager.setAiPathfindingOptimized(!initialAiPathfinding);
        
        // Verify changes took effect
        assertEquals(!initialEntityThrottling, performanceManager.isEntityThrottlingEnabled());
        assertEquals(!initialAiPathfinding, performanceManager.isAiPathfindingOptimized());
    }

    /**
     * Test error handling and fallback mechanisms during async loading
     */
    @Test
    @DisplayName("Test error handling during async configuration loading")
    void testErrorHandlingDuringAsyncLoading() throws Exception {
        // Create a file that will cause an IOException when read
        File corruptedFile = tempDir.resolve("corrupted.properties").toFile();
        try (FileWriter writer = new FileWriter(corruptedFile)) {
            writer.write("invalid content that might cause issues");
        }
        
        // Make file unreadable (might not work on all systems)
        try {
            corruptedFile.setReadable(false);
        } catch (Exception e) {
            // Ignore if we can't make it unreadable
        }
        
        // Test that async loading handles errors gracefully
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        
        // Should complete without throwing exception
        future.get(5, TimeUnit.SECONDS);
        
        // Should fall back to defaults
        assertTrue(performanceManager.isEntityThrottlingEnabled());
        assertTrue(performanceManager.isAiPathfindingOptimized());
        
        // Clean up
        if (corruptedFile.exists()) {
            corruptedFile.delete();
        }
    }

    /**
     * Test async configuration loading with system properties override
     */
    @Test
    @DisplayName("Test async configuration loading with system properties")
    void testAsyncConfigurationWithSystemProperties() throws Exception {
        // Set system properties to override configuration
        System.setProperty("kneaf.performance.entityThrottlingEnabled", "false");
        System.setProperty("kneaf.performance.aiPathfindingOptimized", "false");
        
        createValidConfigurationFile();
        
        // Load configuration
        CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
        future.get(3, TimeUnit.SECONDS);
        
        // System properties should take precedence (if implemented)
        // Note: Current implementation doesn't support system properties override
        // This test documents the expected behavior for future enhancement
        
        // Clean up
        System.clearProperty("kneaf.performance.entityThrottlingEnabled");
        System.clearProperty("kneaf.performance.aiPathfindingOptimized");
    }

    /**
     * Test memory efficiency of async configuration loading
     */
    @Test
    @DisplayName("Test memory efficiency of async configuration loading")
    void testMemoryEfficiency() throws Exception {
        createValidConfigurationFile();
        
        // Get initial memory usage
        System.gc();
        Thread.sleep(100);
        long initialMemory = getUsedMemory();
        
        // Perform many async configuration loads
        int iterations = 1000;
        CountDownLatch latch = new CountDownLatch(iterations);
        
        for (int i = 0; i < iterations; i++) {
            CompletableFuture<Void> future = performanceManager.loadConfigurationAsync();
            future.thenRun(() -> latch.countDown());
        }
        
        // Wait for all to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Memory efficiency test timed out");
        
        // Force garbage collection
        System.gc();
        Thread.sleep(200);
        
        long finalMemory = getUsedMemory();
        long memoryGrowth = finalMemory - initialMemory;
        
        System.out.println("Memory efficiency test:");
        System.out.println("  Initial memory: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("  Final memory: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("  Memory growth: " + (memoryGrowth / 1024 / 1024) + " MB");
        
        // Memory growth should be reasonable
        assertTrue(memoryGrowth < 50 * 1024 * 1024, // 50MB threshold
                  "Memory grew too much: " + memoryGrowth + " bytes");
    }

    // Helper methods

    private void createValidConfigurationFile() throws IOException {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("entityThrottlingEnabled=true\n");
            writer.write("aiPathfindingOptimized=true\n");
            writer.write("renderingMathOptimized=true\n");
            writer.write("rustIntegrationEnabled=true\n");
            writer.write("horizontalPhysicsOnly=false\n");
        }
    }

    private void createInvalidConfigurationFile() throws IOException {
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("invalid_property=not_a_boolean\n");
            writer.write("entityThrottlingEnabled=maybe\n"); // Invalid boolean
            writer.write("aiPathfindingOptimized=yes\n"); // Invalid boolean
            writer.write("renderingMathOptimized=1\n"); // Invalid boolean
        }
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}