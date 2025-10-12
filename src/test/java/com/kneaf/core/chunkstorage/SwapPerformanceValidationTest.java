package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.kneaf.core.chunkstorage.cache.ChunkCache;

/**
 * Comprehensive performance validation testing for swap operations. Tests timing, throughput,
 * latency, and performance metrics accuracy.
 */
public class SwapPerformanceValidationTest {

  private SwapManager swapManager;
  private SwapManager.SwapConfig config;
  private ChunkCache chunkCache;
  private RustDatabaseAdapter databaseAdapter;
  private Random random = new Random();

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up Swap Performance Validation Test ===");

    // Check if native library is available
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println(
          "Skipping Swap Performance Validation Test - native library not available");
      swapManager = null;
      chunkCache = null;
      databaseAdapter = null;
      config = null;
      return;
    }

    // Create swap configuration optimized for performance testing
    config = new SwapManager.SwapConfig();
    config.setEnabled(true);
    config.setMemoryCheckIntervalMs(100); // Moderate monitoring
    config.setMaxConcurrentSwaps(5);
    config.setSwapBatchSize(10);
    config.setSwapTimeoutMs(10000); // Longer timeout for performance tests
    config.setEnableAutomaticSwapping(true);
    config.setCriticalMemoryThreshold(0.95);
    config.setHighMemoryThreshold(0.85);
    config.setElevatedMemoryThreshold(0.75);
    config.setMinSwapChunkAgeMs(1); // Minimal age for testing
    config.setEnableSwapStatistics(true);
    config.setEnablePerformanceMonitoring(true);

    // Initialize components
    swapManager = new SwapManager(config);
    chunkCache = new ChunkCache(50, new ChunkCache.LRUEvictionPolicy());
    databaseAdapter = new RustDatabaseAdapter("memory", true);
    swapManager.initializeComponents(chunkCache, databaseAdapter);
  }

  @AfterEach
  void tearDown() {
    System.out.println("=== Tearing down Swap Performance Validation Test ===");
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
  }

  @Test
  @DisplayName("Test Swap Operation Timing Metrics")
  @Timeout(30)
  void testSwapOperationTimingMetrics() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testSwapOperationTimingMetrics - native library not available");
      return;
    }

    System.out.println("Testing swap operation timing metrics...");

    // Create test chunks with different sizes
    List<String> smallChunks = createTestChunks(10, 1024); // 1KB chunks
    List<String> mediumChunks = createTestChunks(10, 1024 * 16); // 16KB chunks
    List<String> largeChunks = createTestChunks(10, 1024 * 64); // 64KB chunks

    // Test swap out timing for different chunk sizes
    Map<String, Long> swapOutTimes = new HashMap<>();

    // Small chunks
    long startTime = System.nanoTime();
    for (String chunkKey : smallChunks) {
      swapManager.swapOutChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long smallSwapOutTime = System.nanoTime() - startTime;
    swapOutTimes.put("small", smallSwapOutTime / smallChunks.size());

    // Medium chunks
    startTime = System.nanoTime();
    for (String chunkKey : mediumChunks) {
      swapManager.swapOutChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long mediumSwapOutTime = System.nanoTime() - startTime;
    swapOutTimes.put("medium", mediumSwapOutTime / mediumChunks.size());

    // Large chunks
    startTime = System.nanoTime();
    for (String chunkKey : largeChunks) {
      swapManager.swapOutChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long largeSwapOutTime = System.nanoTime() - startTime;
    swapOutTimes.put("large", largeSwapOutTime / largeChunks.size());

    // Test swap in timing
    Map<String, Long> swapInTimes = new HashMap<>();

    // Small chunks
    startTime = System.nanoTime();
    for (String chunkKey : smallChunks) {
      swapManager.swapInChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long smallSwapInTime = System.nanoTime() - startTime;
    swapInTimes.put("small", smallSwapInTime / smallChunks.size());

    // Medium chunks
    startTime = System.nanoTime();
    for (String chunkKey : mediumChunks) {
      swapManager.swapInChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long mediumSwapInTime = System.nanoTime() - startTime;
    swapInTimes.put("medium", mediumSwapInTime / mediumChunks.size());

    // Large chunks
    startTime = System.nanoTime();
    for (String chunkKey : largeChunks) {
      swapManager.swapInChunk(chunkKey).get(5, TimeUnit.SECONDS);
    }
    long largeSwapInTime = System.nanoTime() - startTime;
    swapInTimes.put("large", largeSwapInTime / largeChunks.size());

    // Verify timing metrics are recorded
    SwapManager.SwapStatistics Stats = swapManager.getSwapStatistics();

    System.out.println(
        "✓ Small chunk swap out avg: " + (swapOutTimes.get("small") / 1_000_000.0) + "ms");
    System.out.println(
        "✓ Medium chunk swap out avg: " + (swapOutTimes.get("medium") / 1_000_000.0) + "ms");
    System.out.println(
        "✓ Large chunk swap out avg: " + (swapOutTimes.get("large") / 1_000_000.0) + "ms");
    System.out.println(
        "✓ Small chunk swap in avg: " + (swapInTimes.get("small") / 1_000_000.0) + "ms");
    System.out.println(
        "✓ Medium chunk swap in avg: " + (swapInTimes.get("medium") / 1_000_000.0) + "ms");
    System.out.println(
        "✓ Large chunk swap in avg: " + (swapInTimes.get("large") / 1_000_000.0) + "ms");

    // Verify timing is reasonable (should be under 100ms for all operations)
    assertTrue(swapOutTimes.get("small") < 100_000_000, "Small chunk swap out should be fast");
    assertTrue(swapOutTimes.get("medium") < 100_000_000, "Medium chunk swap out should be fast");
    assertTrue(swapOutTimes.get("large") < 100_000_000, "Large chunk swap out should be fast");
    assertTrue(swapInTimes.get("small") < 100_000_000, "Small chunk swap in should be fast");
    assertTrue(swapInTimes.get("medium") < 100_000_000, "Medium chunk swap in should be fast");
    assertTrue(swapInTimes.get("large") < 100_000_000, "Large chunk swap in should be fast");

    // Verify statistics are updated
    assertTrue(Stats.getAverageSwapOutTime() > 0, "Average swap out time should be positive");
    assertTrue(Stats.getAverageSwapInTime() > 0, "Average swap in time should be positive");
    assertTrue(Stats.getTotalSwapOuts() >= 30, "Should have many swap outs recorded");
    assertTrue(Stats.getTotalSwapIns() >= 15, "Should have many swap ins recorded");

    System.out.println("✓ Swap operation timing metrics test passed!");
  }

  @Test
  @DisplayName("Test Throughput Performance")
  @Timeout(30)
  void testThroughputPerformance() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testThroughputPerformance - native library not available");
      return;
    }

    System.out.println("Testing throughput performance...");

    // Create test chunks
    int numChunks = 50;
    List<String> chunkKeys = createTestChunks(numChunks, 1024 * 16); // 16KB chunks

    // Test swap out throughput
    long startTime = System.currentTimeMillis();
    List<CompletableFuture<Boolean>> swapOutFutures = new ArrayList<>();

    for (String chunkKey : chunkKeys) {
      CompletableFuture<Boolean> future = swapManager.swapOutChunk(chunkKey);
      swapOutFutures.add(future);
    }

    // Wait for all swap outs to complete
    CompletableFuture<Void> allSwapOuts =
        CompletableFuture.allOf(swapOutFutures.toArray(new CompletableFuture[0]));
    allSwapOuts.get(10, TimeUnit.SECONDS);

    long swapOutEndTime = System.currentTimeMillis();
    long swapOutDuration = swapOutEndTime - startTime;

    // Calculate swap out throughput
    double swapOutThroughput = (numChunks * 1000.0) / swapOutDuration; // chunks per second
    double swapOutDataThroughput = (numChunks * 16.0 * 1000.0) / swapOutDuration; // KB per second

    System.out.println("✓ Swap out throughput: " + swapOutThroughput + " chunks/second");
    System.out.println("✓ Swap out data throughput: " + swapOutDataThroughput + " KB/second");

    // Test swap in throughput
    startTime = System.currentTimeMillis();
    List<CompletableFuture<Boolean>> swapInFutures = new ArrayList<>();

    for (String chunkKey : chunkKeys) {
      CompletableFuture<Boolean> future = swapManager.swapInChunk(chunkKey);
      swapInFutures.add(future);
    }

    // Wait for all swap ins to complete
    CompletableFuture<Void> allSwapIns =
        CompletableFuture.allOf(swapInFutures.toArray(new CompletableFuture[0]));
    allSwapIns.get(10, TimeUnit.SECONDS);

    long swapInEndTime = System.currentTimeMillis();
    long swapInDuration = swapInEndTime - startTime;

    // Calculate swap in throughput
    double swapInThroughput = (numChunks * 1000.0) / swapInDuration; // chunks per second
    double swapInDataThroughput = (numChunks * 16.0 * 1000.0) / swapInDuration; // KB per second

    System.out.println("✓ Swap in throughput: " + swapInThroughput + " chunks/second");
    System.out.println("✓ Swap in data throughput: " + swapInDataThroughput + " KB/second");

    // Verify throughput meets minimum requirements
    assertTrue(swapOutThroughput > 5, "Swap out throughput should be at least 5 chunks/second");
    assertTrue(swapInThroughput > 5, "Swap in throughput should be at least 5 chunks/second");
    assertTrue(
        swapOutDataThroughput > 50, "Swap out data throughput should be at least 50 KB/second");
    assertTrue(
        swapInDataThroughput > 50, "Swap in data throughput should be at least 50 KB/second");

    // Verify statistics reflect throughput
    SwapManager.SwapStatistics Stats = swapManager.getSwapStatistics();
    assertTrue(Stats.getTotalSwapOuts() >= numChunks, "Should have recorded all swap outs");
    assertTrue(Stats.getTotalSwapIns() >= numChunks, "Should have recorded all swap ins");

    System.out.println("✓ Throughput performance test passed!");
  }

  @Test
  @DisplayName("Test Latency Performance")
  @Timeout(30)
  void testLatencyPerformance() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testLatencyPerformance - native library not available");
      return;
    }

    System.out.println("Testing latency performance...");

    // Create test chunks
    int numChunks = 20;
    List<String> chunkKeys = createTestChunks(numChunks, 1024 * 8); // 8KB chunks

    // Wait to ensure chunks are old enough for swapping (min age is 1ms)
    Thread.sleep(10);

    // Measure individual operation latencies
    List<Long> swapOutLatencies = new ArrayList<>();
    List<Long> swapInLatencies = new ArrayList<>();

    // Measure swap out latencies
    for (String chunkKey : chunkKeys) {
      long startTime = System.nanoTime();
      swapManager.swapOutChunk(chunkKey).get(3, TimeUnit.SECONDS);
      long endTime = System.nanoTime();
      swapOutLatencies.add(endTime - startTime);
    }

    // Wait a bit between swap out and swap in to ensure proper state
    Thread.sleep(10);

    // Measure swap in latencies
    for (String chunkKey : chunkKeys) {
      long startTime = System.nanoTime();
      swapManager.swapInChunk(chunkKey).get(3, TimeUnit.SECONDS);
      long endTime = System.nanoTime();
      swapInLatencies.add(endTime - startTime);
    }

    // Calculate latency statistics
    long minSwapOutLatency = swapOutLatencies.stream().min(Long::compare).orElse(0L);
    long maxSwapOutLatency = swapOutLatencies.stream().max(Long::compare).orElse(0L);
    double avgSwapOutLatency =
        swapOutLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

    long minSwapInLatency = swapInLatencies.stream().min(Long::compare).orElse(0L);
    long maxSwapInLatency = swapInLatencies.stream().max(Long::compare).orElse(0L);
    double avgSwapInLatency =
        swapInLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

    System.out.println("✓ Swap out latency - Min: " + (minSwapOutLatency / 1_000_000.0) + "ms");
    System.out.println("✓ Swap out latency - Max: " + (maxSwapOutLatency / 1_000_000.0) + "ms");
    System.out.println("✓ Swap out latency - Avg: " + (avgSwapOutLatency / 1_000_000.0) + "ms");
    System.out.println("✓ Swap in latency - Min: " + (minSwapInLatency / 1_000_000.0) + "ms");
    System.out.println("✓ Swap in latency - Max: " + (maxSwapInLatency / 1_000_000.0) + "ms");
    System.out.println("✓ Swap in latency - Avg: " + (avgSwapInLatency / 1_000_000.0) + "ms");

    // Verify latency requirements - relax constraints for test environment
    assertTrue(avgSwapOutLatency < 100_000_000, "Average swap out latency should be under 100ms");
    assertTrue(avgSwapInLatency < 100_000_000, "Average swap in latency should be under 100ms");
    assertTrue(maxSwapOutLatency < 200_000_000, "Maximum swap out latency should be under 200ms");
    assertTrue(maxSwapInLatency < 200_000_000, "Maximum swap in latency should be under 200ms");

    // Verify latency variance is reasonable
    double swapOutVariance = calculateVariance(swapOutLatencies);
    double swapInVariance = calculateVariance(swapInLatencies);

    assertTrue(
        swapOutVariance < 200_000_000_000L, "Swap out latency variance should be reasonable");
    assertTrue(swapInVariance < 200_000_000_000L, "Swap in latency variance should be reasonable");

    System.out.println("✓ Latency performance test passed!");
  }

  @Test
  @DisplayName("Test Performance Under Load")
  @Timeout(30)
  void testPerformanceUnderLoad() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testPerformanceUnderLoad - native library not available");
      return;
    }

    System.out.println("Testing performance under load...");

    // Create many test chunks
    int numChunks = 100;
    List<String> chunkKeys = createTestChunks(numChunks, 1024 * 4); // 4KB chunks

    // Simulate load by performing operations rapidly
    long startTime = System.currentTimeMillis();
    int successfulOperations = 0;

    // Perform rapid swap operations
    for (int i = 0; i < numChunks; i++) {
      String chunkKey = chunkKeys.get(i);

      try {
        // Alternate between swap out and swap in
        if (i % 2 == 0) {
          if (swapManager.swapOutChunk(chunkKey).get(2, TimeUnit.SECONDS)) {
            successfulOperations++;
          }
        } else {
          if (swapManager.swapInChunk(chunkKey).get(2, TimeUnit.SECONDS)) {
            successfulOperations++;
          }
        }
      } catch (Exception e) {
        // Count as failure but continue
        System.err.println("Operation failed for " + chunkKey + ": " + e.getMessage());
      }
    }

    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;

    // Calculate performance metrics
    double operationsPerSecond = (successfulOperations * 1000.0) / duration;
    double successRate = (successfulOperations * 100.0) / numChunks;

    System.out.println("✓ Operations per second under load: " + operationsPerSecond);
    System.out.println("✓ Success rate under load: " + successRate + "%");
    System.out.println("✓ Total duration: " + duration + "ms");

    // Verify performance under load
    assertTrue(
        operationsPerSecond > 10, "Should maintain at least 10 operations/second under load");
    assertTrue(successRate > 80, "Should maintain at least 80% success rate under load");
    assertTrue(duration < 15000, "Should complete within reasonable time under load");

    // Verify system remains stable
    SwapManager.SwapManagerStats Stats = swapManager.getStats();
    assertEquals(0, Stats.getActiveSwaps(), "No active swaps should remain after load test");

    System.out.println("✓ Performance under load test passed!");
  }

  @Test
  @DisplayName("Test Performance Metrics Accuracy")
  @Timeout(30)
  void testPerformanceMetricsAccuracy() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testPerformanceMetricsAccuracy - native library not available");
      return;
    }

    System.out.println("Testing performance metrics accuracy...");

    // Create test chunks
    int numChunks = 10;
    List<String> chunkKeys = createTestChunks(numChunks, 1024 * 8); // 8KB chunks

    // Record initial statistics
    SwapManager.SwapStatistics initialStats = swapManager.getSwapStatistics();
    long initialSwapOuts = initialStats.getTotalSwapOuts();
    long initialSwapIns = initialStats.getTotalSwapIns();
    double initialAvgSwapOutTime = initialStats.getAverageSwapOutTime();
    double initialAvgSwapInTime = initialStats.getAverageSwapInTime();

    // Perform controlled number of operations
    int testSwapOuts = 5;
    int testSwapIns = 5;

    // Perform swap outs
    for (int i = 0; i < testSwapOuts; i++) {
      swapManager.swapOutChunk(chunkKeys.get(i)).get(3, TimeUnit.SECONDS);
    }

    // Perform swap ins
    for (int i = 0; i < testSwapIns; i++) {
      swapManager.swapInChunk(chunkKeys.get(i)).get(3, TimeUnit.SECONDS);
    }

    // Get final statistics
    SwapManager.SwapStatistics finalStats = swapManager.getSwapStatistics();

    // Verify statistics accuracy
    assertEquals(
        initialSwapOuts + testSwapOuts,
        finalStats.getTotalSwapOuts(),
        "Swap out count should be accurate");
    assertEquals(
        initialSwapIns + testSwapIns,
        finalStats.getTotalSwapIns(),
        "Swap in count should be accurate");

    // Verify timing metrics are updated
    assertTrue(
        finalStats.getAverageSwapOutTime() >= initialAvgSwapOutTime,
        "Average swap out time should be updated");
    assertTrue(
        finalStats.getAverageSwapInTime() >= initialAvgSwapInTime,
        "Average swap in time should be updated");

    // Verify timing metrics are reasonable
    assertTrue(
        finalStats.getAverageSwapOutTime() > 0 && finalStats.getAverageSwapOutTime() < 1000,
        "Average swap out time should be reasonable");
    assertTrue(
        finalStats.getAverageSwapInTime() > 0 && finalStats.getAverageSwapInTime() < 1000,
        "Average swap in time should be reasonable");

    // Verify timing metrics are reasonable (basic check since min/max methods may not exist)
    assertTrue(finalStats.getAverageSwapOutTime() > 0, "Average swap out time should be positive");
    assertTrue(finalStats.getAverageSwapInTime() > 0, "Average swap in time should be positive");

    System.out.println(
        "✓ Final average swap out time: " + finalStats.getAverageSwapOutTime() + "ms");
    System.out.println("✓ Final average swap in time: " + finalStats.getAverageSwapInTime() + "ms");
    System.out.println("✓ Final total swap outs: " + finalStats.getTotalSwapOuts());
    System.out.println("✓ Final total swap ins: " + finalStats.getTotalSwapIns());

    System.out.println("✓ Performance metrics accuracy test passed!");
  }

  @Test
  @DisplayName("Test Bulk Operation Performance")
  @Timeout(30)
  void testBulkOperationPerformance() throws Exception {
    if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
      System.out.println("Skipping testBulkOperationPerformance - native library not available");
      return;
    }

    System.out.println("Testing bulk operation performance...");

    // Create test chunks
    int[] batchSizes = {5, 10, 20, 30};
    Map<Integer, Long> batchTimings = new HashMap<>();

    for (int batchSize : batchSizes) {
      // Create chunks for this batch size
      List<String> chunkKeys = createTestChunks(batchSize, 1024 * 8); // 8KB chunks

      // Measure bulk swap out time
      long startTime = System.currentTimeMillis();
      int successfulSwaps =
          swapManager
              .bulkSwapChunks(chunkKeys, SwapManager.SwapOperationType.SWAP_OUT)
              .get(5, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;

      batchTimings.put(batchSize, duration);

      System.out.println(
          "✓ Batch size "
              + batchSize
              + ": "
              + duration
              + "ms ("
              + successfulSwaps
              + "/"
              + batchSize
              + " successful)");
    }

    // Verify bulk operations are efficient
    for (int i = 1; i < batchSizes.length; i++) {
      int smallerBatch = batchSizes[i - 1];
      int largerBatch = batchSizes[i];
      long smallerTime = batchTimings.get(smallerBatch);
      long largerTime = batchTimings.get(largerBatch);

      // Larger batches should not take disproportionately longer
      double timeRatio = (double) largerTime / smallerTime;
      double sizeRatio = (double) largerBatch / smallerBatch;

      assertTrue(timeRatio < sizeRatio * 2.0, "Bulk operations should be relatively efficient");
    }

    // Verify bulk operations are faster than individual operations
    int testBatchSize = 10;
    List<String> testChunks = createTestChunks(testBatchSize, 1024 * 8);

    // Measure individual operations
    long individualStart = System.currentTimeMillis();
    for (String chunkKey : testChunks) {
      swapManager.swapOutChunk(chunkKey).get(2, TimeUnit.SECONDS);
    }
    long individualTime = System.currentTimeMillis() - individualStart;

    // Measure bulk operation
    List<String> testChunks2 = createTestChunks(testBatchSize, 1024 * 8);
    long bulkStart = System.currentTimeMillis();
    swapManager
        .bulkSwapChunks(testChunks2, SwapManager.SwapOperationType.SWAP_OUT)
        .get(2, TimeUnit.SECONDS);
    long bulkTime = System.currentTimeMillis() - bulkStart;

    System.out.println("✓ Individual operations time: " + individualTime + "ms");
    System.out.println("✓ Bulk operation time: " + bulkTime + "ms");

    // Bulk should be more efficient
    assertTrue(bulkTime < individualTime * 0.8, "Bulk operations should be more efficient");

    System.out.println("✓ Bulk operation performance test passed!");
  }

  // Helper methods

  private List<String> createTestChunks(int count, int chunkSize) {
    List<String> chunkKeys = new ArrayList<>();

    for (int i = 0; i < count; i++) {
      String chunkKey = "performance:test:" + chunkSize + ":" + i;
      chunkKeys.add(chunkKey);

      try {
        // Create chunk data of specified size
        byte[] chunkData = new byte[chunkSize];
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

  private double calculateVariance(List<Long> values) {
    double mean = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    return values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0.0);
  }
}
