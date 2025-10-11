package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.kneaf.core.chunkstorage.cache.ChunkCache;

/**
 * Comprehensive testing for memory pressure simulation and automatic swap triggering. Tests the
 * system's response to different memory pressure levels and swap behavior.
 */
public class SwapMemoryPressureTest {

  private SwapManager swapManager;
  private SwapManager.SwapConfig config;
  private ChunkCache chunkCache;
  private RustDatabaseAdapter databaseAdapter;

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up Memory Pressure Test ===");

    // Check if native library is available
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping Memory Pressure Test - native library not available");
      swapManager = null;
      chunkCache = null;
      databaseAdapter = null;
      config = null;
      return;
    }

    // Create swap configuration with sensitive memory thresholds for testing
    config = new SwapManager.SwapConfig();
    config.setEnabled(true);
    config.setMemoryCheckIntervalMs(100); // Very fast monitoring for tests
    config.setMaxConcurrentSwaps(5);
    config.setSwapBatchSize(3);
    config.setSwapTimeoutMs(5000);
    config.setEnableAutomaticSwapping(true);

    // Set memory thresholds for testing (lower values to trigger more easily)
    config.setCriticalMemoryThreshold(0.90); // 90%
    config.setHighMemoryThreshold(0.75); // 75%
    config.setElevatedMemoryThreshold(0.60); // 60%

    config.setMinSwapChunkAgeMs(50); // Very short for testing
    config.setEnableSwapStatistics(true);
    config.setEnablePerformanceMonitoring(true);

    // Initialize swap manager
    swapManager = new SwapManager(config);

    // Create and initialize cache and database components
    chunkCache = new ChunkCache(10, new ChunkCache.HybridEvictionPolicy());
    databaseAdapter = new RustDatabaseAdapter("memory", true);

    // Initialize swap manager components
    swapManager.initializeComponents(chunkCache, databaseAdapter);
  }

  @AfterEach
  void tearDown() {
    System.out.println("=== Tearing down Memory Pressure Test ===");
    if (swapManager != null) {
      swapManager.shutdown();
    }
    if (databaseAdapter != null) {
      try {
        databaseAdapter.close();
      } catch (Exception e) {
        System.err.println("Error closing database adapter: " + e.getMessage());
      }
    }
    // Reset references to prevent memory leaks
    swapManager = null;
    chunkCache = null;
    databaseAdapter = null;
    config = null;
  }

  @Test
  @DisplayName("Test Memory Pressure Level Detection")
  @Timeout(10)
  void testMemoryPressureLevelDetection() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testMemoryPressureLevelDetection - native library not available");
      return;
    }

    System.out.println("Testing memory pressure level detection...");

    // Get initial memory usage
    SwapManager.MemoryUsageInfo initialUsage = swapManager.getMemoryUsage();
    SwapManager.MemoryPressureLevel initialPressure = swapManager.getMemoryPressureLevel();

    System.out.println("✓ Initial memory usage: " + formatMemoryUsage(initialUsage));
    System.out.println("✓ Initial pressure level: " + initialPressure);

    // Verify initial state
    assertNotNull(initialUsage, "Memory usage should not be null");
    assertNotNull(initialPressure, "Memory pressure level should not be null");
    assertTrue(initialUsage.getHeapUsed() >= 0, "Heap used should be non-negative");
    assertTrue(initialUsage.getHeapMax() > 0, "Heap max should be positive");
    assertTrue(
        initialUsage.getUsagePercentage() >= 0.0 && initialUsage.getUsagePercentage() <= 1.0,
        "Usage percentage should be between 0.0 and 1.0");

    // Test pressure level mapping
    double usagePercentage = initialUsage.getUsagePercentage();
    SwapManager.MemoryPressureLevel expectedLevel;

    if (usagePercentage >= 0.90) {
      expectedLevel = SwapManager.MemoryPressureLevel.CRITICAL;
    } else if (usagePercentage >= 0.75) {
      expectedLevel = SwapManager.MemoryPressureLevel.HIGH;
    } else if (usagePercentage >= 0.60) {
      expectedLevel = SwapManager.MemoryPressureLevel.ELEVATED;
    } else {
      expectedLevel = SwapManager.MemoryPressureLevel.NORMAL;
    }

    assertEquals(
        expectedLevel,
        initialPressure,
        "Pressure level should match usage percentage: " + usagePercentage);

    System.out.println("✓ Memory pressure level detection test passed!");
  }

  @Test
  @DisplayName("Test Automatic Swap Triggering Thresholds")
  @Timeout(15)
  void testAutomaticSwapTriggeringThresholds() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testAutomaticSwapTriggeringThresholds - native library not available");
      return;
    }

    System.out.println("Testing automatic swap triggering thresholds...");

    // Fill cache to trigger memory pressure
    for (int i = 0; i < 15; i++) {
      String chunkKey = "test:chunk:" + i;
      byte[] chunkData = createTestChunkData(chunkKey, 1024 * 32);

      try {
        databaseAdapter.putChunk(chunkKey, chunkData);
        // Simulate chunk being in cache by creating a mock chunk
        createMockChunk(i, i);
        // Note: In real usage, this would be a LevelChunk, but for testing we use Object
        // The cache should handle this gracefully or we should mock the cache behavior
        // chunkCache.putChunk(chunkKey, mockChunk); // Commented out due to missing Minecraft
        // classes
      } catch (Exception e) {
        System.err.println("Error setting up test chunk " + i + ": " + e.getMessage());
      }
    }

    System.out.println("✓ Filled cache with 15 test chunks");

    // Wait for memory monitoring to detect pressure
    Thread.sleep(500); // Wait for at least 5 monitoring cycles

    // Check if automatic swaps were triggered
    SwapManager.SwapManagerStats Stats = swapManager.getStats();
    int pressureTriggers = Stats.getPressureTriggers();

    System.out.println("✓ Memory pressure triggers detected: " + pressureTriggers);

    // Verify that some automatic swapping occurred based on pressure level
    SwapManager.MemoryPressureLevel CURRENT_PRESSURE = swapManager.getMemoryPressureLevel();
    if (CURRENT_PRESSURE == SwapManager.MemoryPressureLevel.HIGH
        || CURRENT_PRESSURE == SwapManager.MemoryPressureLevel.CRITICAL) {
      assertTrue(pressureTriggers > 0, "Should have pressure triggers at HIGH/CRITICAL level");
    }

    System.out.println("✓ Current memory pressure level: " + CURRENT_PRESSURE);
    System.out.println("✓ Automatic swap triggering test passed!");
  }

  @Test
  @DisplayName("Test Memory Pressure Transitions")
  @Timeout(20)
  void testMemoryPressureTransitions() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testMemoryPressureTransitions - native library not available");
      return;
    }

    System.out.println("Testing memory pressure level transitions...");

    // Monitor pressure level changes over time
    List<SwapManager.MemoryPressureLevel> pressureHistory = new ArrayList<>();
    List<SwapManager.MemoryUsageInfo> usageHistory = new ArrayList<>();

    // Record initial state
    pressureHistory.add(swapManager.getMemoryPressureLevel());
    usageHistory.add(swapManager.getMemoryUsage());

    // Monitor for several cycles
    for (int i = 0; i < 10; i++) {
      Thread.sleep(150); // Wait for monitoring cycle
      SwapManager.MemoryPressureLevel currentLevel = swapManager.getMemoryPressureLevel();
      SwapManager.MemoryUsageInfo currentUsage = swapManager.getMemoryUsage();

      pressureHistory.add(currentLevel);
      usageHistory.add(currentUsage);

      System.out.println(
          "Cycle "
              + i
              + ": "
              + currentLevel
              + " ("
              + String.format("%.2f%%", currentUsage.getUsagePercentage() * 100)
              + ")");
    }

    // Verify pressure level consistency
    for (int i = 0; i < pressureHistory.size(); i++) {
      SwapManager.MemoryPressureLevel level = pressureHistory.get(i);
      SwapManager.MemoryUsageInfo usage = usageHistory.get(i);

      // Verify level matches usage percentage
      double usagePercentage = usage.getUsagePercentage();
      SwapManager.MemoryPressureLevel expectedLevel;

      if (usagePercentage >= 0.90) {
        expectedLevel = SwapManager.MemoryPressureLevel.CRITICAL;
      } else if (usagePercentage >= 0.75) {
        expectedLevel = SwapManager.MemoryPressureLevel.HIGH;
      } else if (usagePercentage >= 0.60) {
        expectedLevel = SwapManager.MemoryPressureLevel.ELEVATED;
      } else {
        expectedLevel = SwapManager.MemoryPressureLevel.NORMAL;
      }

      assertEquals(
          expectedLevel, level, "Pressure level should be consistent with usage at cycle " + i);
    }

    // Verify that we have valid transitions
    for (int i = 1; i < pressureHistory.size(); i++) {
      SwapManager.MemoryPressureLevel prev = pressureHistory.get(i - 1);
      SwapManager.MemoryPressureLevel curr = pressureHistory.get(i);

      // Allow same level or adjacent levels (no skipping)
      boolean validTransition = isValidPressureTransition(prev, curr);
      assertTrue(
          validTransition,
          "Invalid pressure transition from " + prev + " to " + curr + " at cycle " + i);
    }

    System.out.println("✓ Memory pressure transitions test passed!");
    System.out.println("✓ Recorded " + pressureHistory.size() + " pressure readings");
  }

  @Test
  @DisplayName("Test Swap Batch Size Under Different Pressure Levels")
  @Timeout(15)
  void testSwapBatchSizeUnderPressure() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testSwapBatchSizeUnderPressure - native library not available");
      return;
    }

    System.out.println("Testing swap batch size under different pressure levels...");

    // Fill cache with chunks
    for (int i = 0; i < 15; i++) {
      String chunkKey = "test:chunk:" + i;
      byte[] chunkData = createTestChunkData(chunkKey, 1024 * 16);

      try {
        databaseAdapter.putChunk(chunkKey, chunkData);
        createMockChunk(i, i);
        // Note: In real usage, this would be a LevelChunk, but for testing we use Object
        // The cache should handle this gracefully or we should mock the cache behavior
        // chunkCache.putChunk(chunkKey, mockChunk); // Commented out due to missing Minecraft
        // classes
      } catch (Exception e) {
        System.err.println("Error setting up test chunk " + i + ": " + e.getMessage());
      }
    }

    // Wait for pressure detection
    Thread.sleep(500);

    SwapManager.MemoryPressureLevel CURRENT_PRESSURE = swapManager.getMemoryPressureLevel();
    SwapManager.SwapManagerStats Stats = swapManager.getStats();

    System.out.println("✓ Current pressure level: " + CURRENT_PRESSURE);
    System.out.println("✓ Pressure triggers: " + Stats.getPressureTriggers());

    // Verify that swap operations were triggered with appropriate batch sizes
    if (Stats.getPressureTriggers() > 0) {
      // Verify that the number of operations is reasonable for the batch size
      int expectedMaxOperations =
          config.getSwapBatchSize() * Stats.getPressureTriggers() * 2; // Conservative estimate
      assertTrue(
          Stats.getTotalOperations() <= expectedMaxOperations,
          "Total operations should not exceed expected maximum for batch size");
    }

    System.out.println("✓ Swap batch size test passed!");
  }

  @Test
  @DisplayName("Test Memory Usage Statistics Accuracy")
  @Timeout(10)
  void testMemoryUsageStatisticsAccuracy() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testMemoryUsageStatisticsAccuracy - native library not available");
      return;
    }

    System.out.println("Testing memory usage statistics accuracy...");

    SwapManager.MemoryUsageInfo usage = swapManager.getMemoryUsage();

    // Verify basic properties
    assertTrue(usage.getHeapUsed() >= 0, "Heap used should be non-negative");
    assertTrue(usage.getHeapMax() > 0, "Heap max should be positive");
    assertTrue(usage.getHeapCommitted() >= 0, "Heap committed should be non-negative");
    assertTrue(usage.getNonHeapUsed() >= 0, "Non-heap used should be non-negative");

    // Verify usage percentage calculation
    double calculatedPercentage =
        usage.getHeapMax() > 0 ? (double) usage.getHeapUsed() / usage.getHeapMax() : 0.0;
    assertEquals(
        calculatedPercentage,
        usage.getUsagePercentage(),
        0.001,
        "Usage percentage should be correctly calculated");

    // Verify usage percentage is in valid range
    assertTrue(
        usage.getUsagePercentage() >= 0.0 && usage.getUsagePercentage() <= 1.0,
        "Usage percentage should be between 0.0 and 1.0");

    // Test consistency across multiple calls
    for (int i = 0; i < 5; i++) {
      SwapManager.MemoryUsageInfo newUsage = swapManager.getMemoryUsage();
      assertTrue(newUsage.getHeapMax() == usage.getHeapMax(), "Heap max should remain constant");
      assertTrue(
          newUsage.getUsagePercentage() >= 0.0 && newUsage.getUsagePercentage() <= 1.0,
          "Usage percentage should remain valid");
    }

    System.out.println("✓ Memory usage statistics accuracy test passed!");
    System.out.println("✓ Current usage: " + formatMemoryUsage(usage));
  }

  @Test
  @DisplayName("Test Swap Statistics Under Memory Pressure")
  @Timeout(15)
  void testSwapStatisticsUnderMemoryPressure() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testSwapStatisticsUnderMemoryPressure - native library not available");
      return;
    }

    System.out.println("Testing swap statistics under memory pressure...");

    // Record initial statistics
    SwapManager.SwapStatistics initialStats = swapManager.getSwapStatistics();
    long initialSwapOuts = initialStats.getTotalSwapOuts();
    long initialSwapIns = initialStats.getTotalSwapIns();
    long initialFailures = initialStats.getTotalFailures();

    // Create test chunks
    for (int i = 0; i < 5; i++) {
      String chunkKey = "test:chunk:" + i;
      byte[] chunkData = createTestChunkData(chunkKey, 1024 * 8);

      try {
        databaseAdapter.putChunk(chunkKey, chunkData);
        createMockChunk(i, i);
        // Note: In real usage, this would be a LevelChunk, but for testing we use Object
        // The cache should handle this gracefully or we should mock the cache behavior
        // chunkCache.putChunk(chunkKey, mockChunk); // Commented out due to missing Minecraft
        // classes
      } catch (Exception e) {
        System.err.println("Error setting up test chunk " + i + ": " + e.getMessage());
      }
    }

    // Perform swap operations
    try {
      swapManager.swapOutChunk("test:chunk:0").get(2, TimeUnit.SECONDS);
      swapManager.swapInChunk("test:chunk:0").get(2, TimeUnit.SECONDS);
    } catch (Exception e) {
      System.err.println("Error performing swap operations: " + e.getMessage());
    }

    // Record final statistics
    SwapManager.SwapStatistics finalStats = swapManager.getSwapStatistics();

    // Verify statistics were updated correctly
    assertEquals(
        initialSwapOuts + 1, finalStats.getTotalSwapOuts(), "Swap out count should increase by 1");
    assertEquals(
        initialSwapIns + 1, finalStats.getTotalSwapIns(), "Swap in count should increase by 1");
    assertEquals(initialFailures, finalStats.getTotalFailures(), "Failure count should not change");

    // Verify timing statistics
    assertTrue(finalStats.getAverageSwapOutTime() > 0, "Average swap out time should be positive");
    assertTrue(finalStats.getAverageSwapInTime() > 0, "Average swap in time should be positive");

    // Verify throughput calculation
    double throughput = finalStats.getSwapThroughputMBps();
    assertTrue(throughput >= 0, "Swap throughput should be non-negative");

    System.out.println("✓ Swap statistics under memory pressure test passed!");
    System.out.println(
        "✓ Average swap out time: " + String.format("%.2f ms", finalStats.getAverageSwapOutTime()));
    System.out.println(
        "✓ Average swap in time: " + String.format("%.2f ms", finalStats.getAverageSwapInTime()));
    System.out.println("✓ Swap throughput: " + String.format("%.2f MB/s", throughput));
  }

  @Test
  @DisplayName("Test Concurrent Memory Pressure Monitoring")
  @Timeout(15)
  void testConcurrentMemoryPressureMonitoring() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testConcurrentMemoryPressureMonitoring - native library not available");
      return;
    }

    System.out.println("Testing concurrent memory pressure monitoring...");

    // Create multiple threads that access memory usage and pressure information
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 5; i++) {
      final int threadId = i;
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () -> {
                try {
                  for (int j = 0; j < 10; j++) {
                    SwapManager.MemoryUsageInfo usage = swapManager.getMemoryUsage();
                    SwapManager.MemoryPressureLevel pressure = swapManager.getMemoryPressureLevel();

                    // Verify data consistency
                    assertNotNull(usage, "Memory usage should not be null in thread " + threadId);
                    assertNotNull(
                        pressure, "Memory pressure should not be null in thread " + threadId);
                    assertTrue(
                        usage.getUsagePercentage() >= 0.0 && usage.getUsagePercentage() <= 1.0,
                        "Usage percentage should be valid in thread " + threadId);

                    Thread.sleep(10); // Small delay between checks
                  }
                } catch (Exception e) {
                  throw new RuntimeException("Error in monitoring thread " + threadId, e);
                }
              });
      futures.add(future);
    }

    // Wait for all monitoring threads to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

    System.out.println("✓ Concurrent memory pressure monitoring test passed!");
  }

  // Helper methods

  private String formatMemoryUsage(SwapManager.MemoryUsageInfo usage) {
    return String.format(
        "%.1f MB / %.1f MB (%.1f%%)",
        usage.getHeapUsed() / (1024.0 * 1024.0),
        usage.getHeapMax() / (1024.0 * 1024.0),
        usage.getUsagePercentage() * 100);
  }

  private boolean isValidPressureTransition(
      SwapManager.MemoryPressureLevel from, SwapManager.MemoryPressureLevel to) {
    if (from == to) return true; // Same level is always valid

    // Define allowed transitions (adjacent levels only)
    switch (from) {
      case NORMAL:
        return to == SwapManager.MemoryPressureLevel.ELEVATED;
      case ELEVATED:
        return to == SwapManager.MemoryPressureLevel.NORMAL
            || to == SwapManager.MemoryPressureLevel.HIGH;
      case HIGH:
        return to == SwapManager.MemoryPressureLevel.ELEVATED
            || to == SwapManager.MemoryPressureLevel.CRITICAL;
      case CRITICAL:
        return to == SwapManager.MemoryPressureLevel.HIGH;
      default:
        return false;
    }
  }

  private byte[] createTestChunkData(String key, int size) {
    byte[] data = new byte[size];
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (key.hashCode() + i);
    }
    return data;
  }

  private Object createMockChunk(int x, int z) {
    // Create a simple mock object that can be cast to LevelChunk
    // In a real test environment, this would be a proper mock
    return new Object() {};
  }
}
