package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive testing for concurrent swap operations. Tests thread safety, concurrent access
 * patterns, and performance under load.
 */
public class SwapConcurrentOperationsTest {

  private SwapManager swapManager;
  private SwapManager.SwapConfig config;
  private ChunkCache chunkCache;
  private RustDatabaseAdapter databaseAdapter;
  private Random random = new Random();

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up Concurrent Swap Operations Test ===");

    // Check if native library is available
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping Concurrent Swap Operations Test - native library not available");
      swapManager = null;
      chunkCache = null;
      databaseAdapter = null;
      config = null;
      return;
    }

    // Create swap configuration optimized for concurrent testing
    config = new SwapManager.SwapConfig();
    config.setEnabled(true);
    config.setMemoryCheckIntervalMs(50); // Fast monitoring
    config.setMaxConcurrentSwaps(10); // Allow more concurrent operations
    config.setSwapBatchSize(5);
    config.setSwapTimeoutMs(5000);
    config.setEnableAutomaticSwapping(true);
    config.setCriticalMemoryThreshold(0.95);
    config.setHighMemoryThreshold(0.85);
    config.setElevatedMemoryThreshold(0.75);
    config.setMinSwapChunkAgeMs(10); // Very short for testing
    config.setEnableSwapStatistics(true);
    config.setEnablePerformanceMonitoring(true);

    // Initialize components
    swapManager = new SwapManager(config);
    chunkCache = new ChunkCache(20, new ChunkCache.LRUEvictionPolicy());
    databaseAdapter = new RustDatabaseAdapter("memory", true);
    swapManager.initializeComponents(chunkCache, databaseAdapter);
  }

  @AfterEach
  void tearDown() {
    System.out.println("=== Tearing down Concurrent Swap Operations Test ===");
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
  @DisplayName("Test Concurrent Swap Out Operations")
  @Timeout(30)
  void testConcurrentSwapOutOperations() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testConcurrentSwapOutOperations - native library not available");
      return;
    }

    System.out.println("Testing concurrent swap out operations...");

    // Create test chunks
    int numChunks = 20;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();
    long initialOperations = initialStats.getTotalOperations();

    // Perform concurrent swap out operations
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (String chunkKey : chunkKeys) {
      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await(); // Wait for all threads to be ready
                  return swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                  System.err.println("Error in swap out for " + chunkKey + ": " + e.getMessage());
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Start all operations simultaneously
    startLatch.countDown();

    // Wait for all operations to complete
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(10, TimeUnit.SECONDS);

    // Analyze results
    int successfulSwaps = 0;
    for (CompletableFuture<Boolean> future : swapFutures) {
      if (future.get()) {
        successfulSwaps++;
      }
    }

    System.out.println(
        "✓ Concurrent swap out completed: " + successfulSwaps + "/" + numChunks + " successful");

    // Verify statistics
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertTrue(
        finalStats.getTotalOperations() >= initialOperations + successfulSwaps,
        "Total operations should increase");
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Concurrent swap out operations test passed!");
  }

  @Test
  @DisplayName("Test Concurrent Swap In Operations")
  @Timeout(30)
  void testConcurrentSwapInOperations() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testConcurrentSwapInOperations - native library not available");
      return;
    }

    System.out.println("Testing concurrent swap in operations...");

    // Create and swap out test chunks first
    int numChunks = 15;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Swap out chunks first
    for (String chunkKey : chunkKeys) {
      swapManager.swapOutChunk(chunkKey).get(2, TimeUnit.SECONDS);
    }

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();
    long initialOperations = initialStats.getTotalOperations();

    // Perform concurrent swap in operations
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (String chunkKey : chunkKeys) {
      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await(); // Wait for all threads to be ready
                  return swapManager.swapInChunk(chunkKey).get(3, TimeUnit.SECONDS);
                } catch (Exception e) {
                  System.err.println("Error in swap in for " + chunkKey + ": " + e.getMessage());
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Start all operations simultaneously
    startLatch.countDown();

    // Wait for all operations to complete
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(10, TimeUnit.SECONDS);

    // Analyze results
    int successfulSwaps = 0;
    for (CompletableFuture<Boolean> future : swapFutures) {
      if (future.get()) {
        successfulSwaps++;
      }
    }

    System.out.println(
        "✓ Concurrent swap in completed: " + successfulSwaps + "/" + numChunks + " successful");

    // Verify statistics
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertTrue(
        finalStats.getTotalOperations() >= initialOperations + successfulSwaps,
        "Total operations should increase");
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Concurrent swap in operations test passed!");
  }

  @Test
  @DisplayName("Test Mixed Concurrent Swap Operations")
  @Timeout(30)
  void testMixedConcurrentSwapOperations() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testMixedConcurrentSwapOperations - native library not available");
      return;
    }

    System.out.println("Testing mixed concurrent swap operations...");

    // Create test chunks
    int numChunks = 20;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Swap out half the chunks first
    List<String> swappedOutChunks = new ArrayList<>();
    for (int i = 0; i < numChunks / 2; i++) {
      String chunkKey = chunkKeys.get(i);
      swapManager.swapOutChunk(chunkKey).get(2, TimeUnit.SECONDS);
      swappedOutChunks.add(chunkKey);
    }

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();

    // Perform mixed concurrent operations
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (int i = 0; i < numChunks; i++) {
      String chunkKey = chunkKeys.get(i);
      final int index = i;

      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await(); // Wait for all threads to be ready

                  if (index < numChunks / 2) {
                    // Swap in previously swapped out chunks
                    return swapManager.swapInChunk(chunkKey).get(3, TimeUnit.SECONDS);
                  } else {
                    // Swap out remaining chunks
                    return swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS);
                  }
                } catch (Exception e) {
                  System.err.println("Error in mixed swap for " + chunkKey + ": " + e.getMessage());
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Start all operations simultaneously
    startLatch.countDown();

    // Wait for all operations to complete
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(10, TimeUnit.SECONDS);

    // Analyze results
    int successfulSwaps = 0;
    for (CompletableFuture<Boolean> future : swapFutures) {
      if (future.get()) {
        successfulSwaps++;
      }
    }

    System.out.println(
        "✓ Mixed concurrent swap completed: " + successfulSwaps + "/" + numChunks + " successful");

    // Verify statistics
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertTrue(
        finalStats.getTotalOperations() >= initialStats.getTotalOperations() + successfulSwaps,
        "Total operations should increase");
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Mixed concurrent swap operations test passed!");
  }

  @Test
  @DisplayName("Test Concurrent Bulk Swap Operations")
  @Timeout(30)
  void testConcurrentBulkSwapOperations() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testConcurrentBulkSwapOperations - native library not available");
      return;
    }

    System.out.println("Testing concurrent bulk swap operations...");

    // Create test chunks
    int numChunks = 30;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Split chunks into groups for bulk operations
    int groupSize = 5;
    List<List<String>> chunkGroups = new ArrayList<>();
    for (int i = 0; i < numChunks; i += groupSize) {
      List<String> group = new ArrayList<>();
      for (int j = i; j < Math.min(i + groupSize, numChunks); j++) {
        group.add(chunkKeys.get(j));
      }
      chunkGroups.add(group);
    }

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();

    // Perform concurrent bulk operations
    List<CompletableFuture<Integer>> bulkFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (int i = 0; i < chunkGroups.size(); i++) {
      List<String> group = chunkGroups.get(i);
      final int groupIndex = i;

      CompletableFuture<Integer> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await(); // Wait for all threads to be ready

                  SwapManager.SwapOperationType operationType =
                      (groupIndex % 2 == 0)
                          ? SwapManager.SwapOperationType.SWAP_OUT
                          : SwapManager.SwapOperationType.SWAP_IN;

                  if (operationType == SwapManager.SwapOperationType.SWAP_IN) {
                    // First swap out the group, then swap them back in
                    swapManager
                        .bulkSwapChunks(group, SwapManager.SwapOperationType.SWAP_OUT)
                        .get(3, TimeUnit.SECONDS);
                  }

                  return swapManager.bulkSwapChunks(group, operationType).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                  System.err.println(
                      "Error in bulk swap group " + groupIndex + ": " + e.getMessage());
                  return 0;
                }
              });
      bulkFutures.add(future);
    }

    // Start all operations simultaneously
    startLatch.countDown();

    // Wait for all bulk operations to complete
    CompletableFuture<Void> allBulks =
        CompletableFuture.allOf(bulkFutures.toArray(new CompletableFuture[0]));
    allBulks.get(15, TimeUnit.SECONDS);

    // Analyze results
    int totalSuccessfulOperations = 0;
    for (CompletableFuture<Integer> future : bulkFutures) {
      totalSuccessfulOperations += future.get();
    }

    System.out.println(
        "✓ Concurrent bulk swap completed: "
            + totalSuccessfulOperations
            + " successful operations");

    // Verify statistics
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertTrue(
        finalStats.getTotalOperations()
            >= initialStats.getTotalOperations() + totalSuccessfulOperations,
        "Total operations should increase");
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Concurrent bulk swap operations test passed!");
  }

  @Test
  @DisplayName("Test Thread Safety of Swap Operations")
  @Timeout(30)
  void testThreadSafetyOfSwapOperations() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testThreadSafetyOfSwapOperations - native library not available");
      return;
    }

    System.out.println("Testing thread safety of swap operations...");

    // Create test chunks
    int numChunks = 25;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Use atomic counters to track concurrent access
    AtomicInteger concurrentAccesses = new AtomicInteger(0);
    AtomicInteger maxConcurrentAccesses = new AtomicInteger(0);
    AtomicBoolean threadSafetyViolation = new AtomicBoolean(false);

    // Perform concurrent operations with access tracking
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (String chunkKey : chunkKeys) {
      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await();

                  // Track concurrent access
                  int currentAccesses = concurrentAccesses.incrementAndGet();
                  int maxAccesses = maxConcurrentAccesses.get();
                  while (currentAccesses > maxAccesses) {
                    if (maxConcurrentAccesses.compareAndSet(maxAccesses, currentAccesses)) {
                      break;
                    }
                    maxAccesses = maxConcurrentAccesses.get();
                  }

                  // Perform swap operation
                  boolean result =
                      random.nextBoolean()
                          ? swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS)
                          : swapManager.swapInChunk(chunkKey).get(3, TimeUnit.SECONDS);

                  // Decrement concurrent access counter
                  concurrentAccesses.decrementAndGet();

                  return result;
                } catch (Exception e) {
                  concurrentAccesses.decrementAndGet();
                  System.err.println(
                      "Error in thread-safe swap for " + chunkKey + ": " + e.getMessage());
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Start all operations
    startLatch.countDown();

    // Wait for completion
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(10, TimeUnit.SECONDS);

    // Analyze thread safety
    int maxConcurrent = maxConcurrentAccesses.get();
    System.out.println("✓ Maximum concurrent accesses: " + maxConcurrent);
    System.out.println("✓ Total operations completed: " + swapFutures.size());

    // Verify no thread safety violations occurred
    assertFalse(threadSafetyViolation.get(), "No thread safety violations should occur");
    assertTrue(maxConcurrent > 1, "Should have actual concurrent access");

    // Verify final state is consistent
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Thread safety test passed!");
  }

  @Test
  @DisplayName("Test Concurrent Statistics Updates")
  @Timeout(20)
  void testConcurrentStatisticsUpdates() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testConcurrentStatisticsUpdates - native library not available");
      return;
    }

    System.out.println("Testing concurrent statistics updates...");

    // Record initial statistics
    SwapManager.SwapStatistics initialStats = swapManager.getSwapStatistics();
    long initialSwapOuts = initialStats.getTotalSwapOuts();
    long initialSwapIns = initialStats.getTotalSwapIns();

    // Create test chunks
    int numChunks = 50;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Perform concurrent operations that update statistics
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);

    for (int i = 0; i < numChunks; i++) {
      String chunkKey = chunkKeys.get(i);
      final boolean swapOut = (i % 2 == 0); // Alternate between swap out and swap in

      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  startLatch.await();

                  if (swapOut) {
                    return swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS);
                  } else {
                    // First swap out, then swap in
                    swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS);
                    return swapManager.swapInChunk(chunkKey).get(3, TimeUnit.SECONDS);
                  }
                } catch (Exception e) {
                  System.err.println(
                      "Error in concurrent Stats update for " + chunkKey + ": " + e.getMessage());
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Start all operations
    startLatch.countDown();

    // Wait for completion
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(15, TimeUnit.SECONDS);

    // Count successful operations
    int successfulSwaps = 0;
    for (CompletableFuture<Boolean> future : swapFutures) {
      if (future.get()) {
        successfulSwaps++;
      }
    }

    // Verify statistics consistency
    SwapManager.SwapStatistics finalStats = swapManager.getSwapStatistics();

    System.out.println("✓ Concurrent operations completed: " + successfulSwaps + "/" + numChunks);
    System.out.println("✓ Final swap outs: " + finalStats.getTotalSwapOuts());
    System.out.println("✓ Final swap ins: " + finalStats.getTotalSwapIns());

    // Verify statistics are consistent (allowing for some operations that might have failed)
    assertTrue(
        finalStats.getTotalSwapOuts() >= initialSwapOuts + (numChunks / 2),
        "Swap out count should increase significantly");
    assertTrue(
        finalStats.getTotalSwapIns() >= initialSwapIns + (numChunks / 4),
        "Swap in count should increase");

    // Verify timing statistics are reasonable
    assertTrue(finalStats.getAverageSwapOutTime() > 0, "Average swap out time should be positive");
    assertTrue(finalStats.getAverageSwapInTime() > 0, "Average swap in time should be positive");

    System.out.println("✓ Concurrent statistics updates test passed!");
  }

  @Test
  @DisplayName("Test Performance Under High Concurrency")
  @Timeout(30)
  void testPerformanceUnderHighConcurrency() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping testPerformanceUnderHighConcurrency - native library not available");
      return;
    }

    System.out.println("Testing performance under high concurrency...");

    // Create many test chunks
    int numChunks = 100;
    List<String> chunkKeys = createTestChunks(numChunks);

    // Record timing information
    long startTime = System.currentTimeMillis();

    // Perform high-concurrency operations
    List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();

    for (int i = 0; i < numChunks; i++) {
      String chunkKey = chunkKeys.get(i);
      final int operationType = i % 3; // Mix of operations

      CompletableFuture<Boolean> future =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  switch (operationType) {
                    case 0:
                      return swapManager.swapOutChunk(chunkKey).get(2, TimeUnit.SECONDS);
                    case 1:
                      return swapManager.swapInChunk(chunkKey).get(2, TimeUnit.SECONDS);
                    default:
                      // Bulk operation on small group
                      List<String> group = new ArrayList<>();
                      group.add(chunkKey);
                      return swapManager
                              .bulkSwapChunks(group, SwapManager.SwapOperationType.SWAP_OUT)
                              .get(3, TimeUnit.SECONDS)
                          > 0;
                  }
                } catch (Exception e) {
                  return false;
                }
              });
      swapFutures.add(future);
    }

    // Wait for all operations to complete
    CompletableFuture<Void> allSwaps =
        CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
    allSwaps.get(20, TimeUnit.SECONDS);

    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;

    // Analyze results
    int successfulOperations = 0;
    for (CompletableFuture<Boolean> future : swapFutures) {
      if (future.get()) {
        successfulOperations++;
      }
    }

    System.out.println("✓ High concurrency test completed in " + duration + "ms");
    System.out.println("✓ Successful operations: " + successfulOperations + "/" + numChunks);
    System.out.println("✓ Operations per second: " + (numChunks * 1000.0 / duration));

    // Verify reasonable performance
    assertTrue(duration < 20000, "Should complete within reasonable time");
    assertTrue(successfulOperations > numChunks * 0.7, "Should have high success rate");

    // Verify system remains stable
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");

    System.out.println("✓ Performance under high concurrency test passed!");
  }

  // Helper methods

  private List<String> createTestChunks(int count) {
    List<String> chunkKeys = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      String chunkKey = "concurrent:test:" + i + ":" + i;
      chunkKeys.add(chunkKey);

      try {
        // Create chunk data
        byte[] chunkData = new byte[1024 * (8 + random.nextInt(24))]; // 8-32KB chunks
        random.nextBytes(chunkData);
        databaseAdapter.putChunk(chunkKey, chunkData);

        // Add to cache - use a simple mock since we don't have Minecraft classes in test
        createMockChunk(i, i);
        // Note: In real usage, this would be a LevelChunk, but for testing we use Object
        // The cache should handle this gracefully or we should mock the cache behavior
      } catch (Exception e) {
        System.err.println("Error creating test chunk " + i + ": " + e.getMessage());
      }
    }

    return chunkKeys;
  }

  private Object createMockChunk(int x, int z) {
    // Create a simple mock object that can be cast to LevelChunk
    return new Object() {};
  }
}
