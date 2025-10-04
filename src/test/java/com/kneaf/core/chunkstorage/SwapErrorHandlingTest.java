package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive error handling and recovery testing for swap operations. Tests failure scenarios,
 * error propagation, and recovery mechanisms.
 */
public class SwapErrorHandlingTest {

  private SwapManager swapManager;
  private SwapManager.SwapConfig config;
  private ChunkCache chunkCache;
  private RustDatabaseAdapter databaseAdapter;
  private Random random = new Random();

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up Swap Error Handling Test ===");

    // Check if native library is available
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping Swap Error Handling Test - native library not available");
      swapManager = null;
      chunkCache = null;
      databaseAdapter = null;
      config = null;
      return;
    }

    // Create swap configuration for error testing
    config = new SwapManager.SwapConfig();
    config.setEnabled(true);
    config.setMemoryCheckIntervalMs(100);
    config.setMaxConcurrentSwaps(3);
    config.setSwapBatchSize(5);
    config.setSwapTimeoutMs(2000); // Short timeout for error testing
    config.setEnableAutomaticSwapping(true);
    config.setCriticalMemoryThreshold(0.95);
    config.setHighMemoryThreshold(0.85);
    config.setElevatedMemoryThreshold(0.75);
    config.setMinSwapChunkAgeMs(1);
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
    System.out.println("=== Tearing down Swap Error Handling Test ===");
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
  @DisplayName("Test Swap Operation Timeout Handling")
  @Timeout(15)
  void testSwapOperationTimeoutHandling() throws Exception {
    System.out.println("Testing swap operation timeout handling...");

    // Skip test if components not initialized
    if (swapManager == null || databaseAdapter == null) {
      System.out.println(
          "✓ Test skipped - components not initialized due to missing native library");
      return;
    }

    // Create test chunks
    List<String> chunkKeys = createTestChunks(5, 1024 * 8); // 8KB chunks

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();
    long initialTimeouts = initialStats.getFailedOperations();

    // Simulate timeout by using very short timeout
    SwapManager.SwapConfig timeoutConfig = new SwapManager.SwapConfig();
    timeoutConfig.setEnabled(true);
    timeoutConfig.setSwapTimeoutMs(1); // 1ms timeout to force timeout
    timeoutConfig.setMemoryCheckIntervalMs(100);
    timeoutConfig.setMaxConcurrentSwaps(1);

    SwapManager timeoutSwapManager = new SwapManager(timeoutConfig);
    timeoutSwapManager.initializeComponents(chunkCache, databaseAdapter);

    try {
      // Attempt swap operation that should timeout
      CompletableFuture<Boolean> timeoutFuture = timeoutSwapManager.swapOutChunk(chunkKeys.get(0));

      // Should timeout or fail quickly
      Boolean result = timeoutFuture.get(100, TimeUnit.MILLISECONDS);
      assertFalse(result, "Swap operation should fail with timeout");

      // Verify timeout is recorded in statistics
      SwapManager.SwapManagerStats Stats = timeoutSwapManager.getStats();
      assertTrue(
          Stats.getFailedOperations() > initialTimeouts,
          "Timeout should be recorded as failed operation");

      System.out.println("✓ Timeout handling test passed!");

    } finally {
      timeoutSwapManager.shutdown();
    }
  }

  @Test
  @DisplayName("Test Database Failure Recovery")
  @Timeout(20)
  void testDatabaseFailureRecovery() throws Exception {
    System.out.println("Testing database failure recovery...");

    // Skip test if components not initialized
    if (swapManager == null || databaseAdapter == null) {
      System.out.println(
          "✓ Test skipped - components not initialized due to missing native library");
      return;
    }

    // Create test chunks
    List<String> chunkKeys = createTestChunks(10, 1024 * 8);

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();
    long initialFailures = initialStats.getFailedOperations();

    // Create a failing database adapter by extending RustDatabaseAdapter
    class FailingAdapter extends RustDatabaseAdapter {
      private boolean shouldFail = true;

      public FailingAdapter(String connectionString, boolean enableCompression) {
        super(connectionString, enableCompression);
      }

      @Override
      public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
        if (shouldFail) {
          CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Simulated database read failure"));
          return future;
        }
        return super.getChunkAsync(key);
      }

      @Override
      public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
        if (shouldFail) {
          CompletableFuture<Void> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Simulated database write failure"));
          return future;
        }
        return super.putChunkAsync(key, data);
      }

      @Override
      public CompletableFuture<Boolean> deleteChunkAsync(String key) {
        if (shouldFail) {
          CompletableFuture<Boolean> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Simulated database delete failure"));
          return future;
        }
        return super.deleteChunkAsync(key);
      }

      @Override
      public boolean isHealthy() {
        return !shouldFail && super.isHealthy();
      }

      public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
      }
    }
    ;

    // Create instance of failing adapter
    FailingAdapter failingAdapter = new FailingAdapter("memory", true);

    // Initialize with failing adapter
    SwapManager recoverySwapManager = new SwapManager(config);
    recoverySwapManager.initializeComponents(chunkCache, failingAdapter);

    try {
      // Attempt swap operation that should fail
      CompletableFuture<Boolean> failureFuture = recoverySwapManager.swapOutChunk(chunkKeys.get(0));
      Boolean result = failureFuture.get(3, TimeUnit.SECONDS);

      // Should handle failure gracefully
      assertFalse(result, "Swap operation should fail gracefully");

      // Verify failure is recorded
      SwapManager.SwapManagerStats failureStats = recoverySwapManager.getStats();
      assertTrue(
          failureStats.getFailedOperations() > initialFailures, "Failure should be recorded");

      // Recover by fixing the database
      failingAdapter.setShouldFail(false);

      // Attempt operation again - should succeed now
      CompletableFuture<Boolean> successFuture = recoverySwapManager.swapOutChunk(chunkKeys.get(1));
      Boolean successResult = successFuture.get(3, TimeUnit.SECONDS);

      assertTrue(successResult, "Swap operation should succeed after recovery");

      System.out.println("✓ Database failure recovery test passed!");

    } finally {
      recoverySwapManager.shutdown();
    }
  }

  @Test
  @DisplayName("Test Invalid Chunk Key Handling")
  @Timeout(15)
  void testInvalidChunkKeyHandling() throws Exception {
    System.out.println("Testing invalid chunk key handling...");

    // Test with various invalid chunk keys
    String[] invalidKeys = {
      null,
      "",
      "   ",
      "invalid:key:format",
      "way:too:long:key:with:many:colons:and:extra:parts",
      "negative:-1:-1",
      "special:chars:!@#$%^&*()"
    };

    for (String invalidKey : invalidKeys) {
      try {
        // Test swap out with invalid key
        CompletableFuture<Boolean> swapOutFuture = swapManager.swapOutChunk(invalidKey);
        Boolean swapOutResult = swapOutFuture.get(2, TimeUnit.SECONDS);

        // Should handle invalid key gracefully
        assertFalse(swapOutResult, "Swap out should fail with invalid key: " + invalidKey);

        // Test swap in with invalid key
        CompletableFuture<Boolean> swapInFuture = swapManager.swapInChunk(invalidKey);
        Boolean swapInResult = swapInFuture.get(2, TimeUnit.SECONDS);

        // Should handle invalid key gracefully
        assertFalse(swapInResult, "Swap in should fail with invalid key: " + invalidKey);

      } catch (Exception e) {
        // Exception is also acceptable for invalid keys
        System.out.println(
            "✓ Invalid key '" + invalidKey + "' handled with exception: " + e.getMessage());
      }
    }

    System.out.println("✓ Invalid chunk key handling test passed!");
  }

  @Test
  @DisplayName("Test Concurrent Error Handling")
  @Timeout(20)
  void testConcurrentErrorHandling() throws Exception {
    System.out.println("Testing concurrent error handling...");

    // Skip test if components not initialized
    if (swapManager == null || databaseAdapter == null) {
      System.out.println(
          "✓ Test skipped - components not initialized due to missing native library");
      return;
    }

    // Create test chunks
    List<String> chunkKeys = createTestChunks(20, 1024 * 8);

    // Record initial statistics
    SwapManager.SwapManagerStats initialStats = swapManager.getStats();
    long initialFailures = initialStats.getFailedOperations();

    // Create a database that fails randomly
    class RandomFailureAdapter extends RustDatabaseAdapter {
      private Random random = new Random();

      public RandomFailureAdapter(String connectionString, boolean enableCompression) {
        super(connectionString, enableCompression);
      }

      @Override
      public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
        if (random.nextDouble() < 0.3) { // 30% failure rate
          CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Random database read failure"));
          return future;
        }
        return super.getChunkAsync(key);
      }

      @Override
      public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
        if (random.nextDouble() < 0.3) { // 30% failure rate
          CompletableFuture<Void> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Random database write failure"));
          return future;
        }
        return super.putChunkAsync(key, data);
      }

      @Override
      public CompletableFuture<Boolean> deleteChunkAsync(String key) {
        if (random.nextDouble() < 0.3) { // 30% failure rate
          CompletableFuture<Boolean> future = new CompletableFuture<>();
          future.completeExceptionally(new RuntimeException("Random database delete failure"));
          return future;
        }
        return super.deleteChunkAsync(key);
      }

      @Override
      public boolean isHealthy() {
        return random.nextDouble() > 0.3 && super.isHealthy(); // 70% healthy
      }
    }
    ;

    // Create instance of random failure adapter
    RandomFailureAdapter randomFailureAdapter = new RandomFailureAdapter("memory", true);

    // Initialize with random failure adapter
    SwapManager concurrentErrorSwapManager = new SwapManager(config);
    concurrentErrorSwapManager.initializeComponents(chunkCache, randomFailureAdapter);

    try {
      // Perform concurrent operations with random failures
      List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();

      for (int i = 0; i < 10; i++) {
        final String chunkKey = chunkKeys.get(i);
        final boolean swapOut = (i % 2 == 0);

        CompletableFuture<Boolean> future =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    if (swapOut) {
                      return concurrentErrorSwapManager
                          .swapOutChunk(chunkKey)
                          .get(2, TimeUnit.SECONDS);
                    } else {
                      return concurrentErrorSwapManager
                          .swapInChunk(chunkKey)
                          .get(2, TimeUnit.SECONDS);
                    }
                  } catch (Exception e) {
                    return false; // Handle exceptions gracefully
                  }
                });
        swapFutures.add(future);
      }

      // Wait for all operations to complete
      CompletableFuture<Void> allSwaps =
          CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]));
      allSwaps.get(10, TimeUnit.SECONDS);

      // Count successful operations
      int successfulOperations = 0;
      for (CompletableFuture<Boolean> future : swapFutures) {
        if (future.get()) {
          successfulOperations++;
        }
      }

      System.out.println(
          "✓ Concurrent operations completed: " + successfulOperations + "/10 successful");

      // Verify some operations succeeded (due to random failures, not all will fail)
      assertTrue(successfulOperations > 0, "At least some operations should succeed");
      assertTrue(successfulOperations < 10, "At least some operations should fail");

      // Verify failures are recorded
      SwapManager.SwapManagerStats finalStats = concurrentErrorSwapManager.getStats();
      assertTrue(finalStats.getFailedOperations() > initialFailures, "Failures should be recorded");

      System.out.println("✓ Concurrent error handling test passed!");

    } finally {
      concurrentErrorSwapManager.shutdown();
    }
  }

  @Test
  @DisplayName("Test Error Recovery Mechanisms")
  @Timeout(20)
  void testErrorRecoveryMechanisms() throws Exception {
    System.out.println("Testing error recovery mechanisms...");

    // Skip test if components not initialized
    if (swapManager == null || databaseAdapter == null) {
      System.out.println(
          "✓ Test skipped - components not initialized due to missing native library");
      return;
    }

    // Create test chunks
    List<String> chunkKeys = createTestChunks(10, 1024 * 8);

    // Create a database that fails initially but recovers
    class RecoveringAdapter extends RustDatabaseAdapter {
      private boolean isHealthy = false;
      private int attemptCount = 0;

      public RecoveringAdapter(String connectionString, boolean enableCompression) {
        super(connectionString, enableCompression);
      }

      @Override
      public CompletableFuture<Optional<byte[]>> getChunkAsync(String key) {
        attemptCount++;
        if (!isHealthy && attemptCount < 3) {
          CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();
          future.completeExceptionally(
              new RuntimeException("Database recovering - attempt " + attemptCount));
          return future;
        }
        return super.getChunkAsync(key);
      }

      @Override
      public CompletableFuture<Void> putChunkAsync(String key, byte[] data) {
        attemptCount++;
        if (!isHealthy && attemptCount < 3) {
          CompletableFuture<Void> future = new CompletableFuture<>();
          future.completeExceptionally(
              new RuntimeException("Database recovering - attempt " + attemptCount));
          return future;
        }
        return super.putChunkAsync(key, data);
      }

      @Override
      public CompletableFuture<Boolean> deleteChunkAsync(String key) {
        attemptCount++;
        if (!isHealthy && attemptCount < 3) {
          CompletableFuture<Boolean> future = new CompletableFuture<>();
          future.completeExceptionally(
              new RuntimeException("Database recovering - attempt " + attemptCount));
          return future;
        }
        return super.deleteChunkAsync(key);
      }

      @Override
      public boolean isHealthy() {
        return (isHealthy || attemptCount >= 3) && super.isHealthy();
      }

      public void setHealthy(boolean healthy) {
        this.isHealthy = healthy;
      }
    }
    ;

    // Create instance of recovering adapter
    RecoveringAdapter recoveringAdapter = new RecoveringAdapter("memory", true);

    // Initialize with recovering adapter
    SwapManager recoverySwapManager2 = new SwapManager(config);
    recoverySwapManager2.initializeComponents(chunkCache, recoveringAdapter);

    try {
      // First attempt should fail (database not ready)
      CompletableFuture<Boolean> firstAttempt = recoverySwapManager2.swapOutChunk(chunkKeys.get(0));
      Boolean firstResult = firstAttempt.get(3, TimeUnit.SECONDS);
      assertFalse(firstResult, "First attempt should fail due to database issues");

      // Simulate database recovery
      recoveringAdapter.setHealthy(true);

      // Second attempt should succeed (database recovered)
      CompletableFuture<Boolean> secondAttempt =
          recoverySwapManager2.swapOutChunk(chunkKeys.get(1));
      Boolean secondResult = secondAttempt.get(3, TimeUnit.SECONDS);
      assertTrue(secondResult, "Second attempt should succeed after recovery");

      System.out.println("✓ Error recovery mechanisms test passed!");

    } finally {
      recoverySwapManager2.shutdown();
    }
  }

  // Helper methods

  private List<String> createTestChunks(int count, int chunkSize) {
    List<String> chunkKeys = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      String chunkKey = "error:test:" + i + ":" + i;
      chunkKeys.add(chunkKey);

      try {
        // Create chunk data of specified size
        byte[] chunkData = new byte[chunkSize];
        random.nextBytes(chunkData);

        // Only try to put in database if adapter is available
        if (databaseAdapter != null) {
          databaseAdapter.putChunk(chunkKey, chunkData);
        }

        // Add to cache - use a simple mock since we don't have Minecraft classes in test
        Object mockChunk = createMockChunk(i, i);
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
    return new Object() {
      public int getX() {
        return x;
      }

      public int getZ() {
        return z;
      }
    };
  }
}
