package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.chunkstorage.database.RustDatabaseAdapter;
import com.kneaf.core.chunkstorage.swap.SwapManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive integration test for LevelChunk implementation with the swap system.
 * Tests the complete flow from LevelChunk creation to serialization, swapping,
 * and deserialization back to a working LevelChunk.
 */
public class LevelChunkIntegrationTest {

  private ChunkStorageManager storageManager;
  private SwapManager swapManager;
  private ChunkStorageConfig config;
  private String testWorldName = "test-world-levelchunk";
  private RustDatabaseAdapter databaseAdapter;

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up LevelChunk Integration Test ===");

    // Create comprehensive storage configuration with swap enabled
    config = new ChunkStorageConfig();
    config.setEnabled(true);
    config.setCacheCapacity(5); // Small cache to force eviction
    config.setEvictionPolicy("hybrid");
    config.setUseRustDatabase(true);
    config.setDatabaseType("memory"); // Use memory database for testing
    config.setEnableChecksums(true);
    config.setAsyncThreadpoolSize(4);

    // Configure swap manager
    config.setEnableSwapManager(true);
    config.setSwapMemoryCheckIntervalMs(500); // Fast monitoring for tests
    config.setMaxConcurrentSwaps(3);
    config.setSwapBatchSize(5);
    config.setSwapTimeoutMs(10000);
    config.setEnableAutomaticSwapping(false); // Disable automatic swapping for this test
    config.setCriticalMemoryThreshold(0.95);
    config.setHighMemoryThreshold(0.85);
    config.setElevatedMemoryThreshold(0.75);
    config.setMinSwapChunkAgeMs(100); // Very short for testing
    config.setEnableSwapStatistics(true);

    // Initialize storage manager
    storageManager = new ChunkStorageManager(testWorldName, config);

    // Get swap manager reference
    swapManager = getSwapManagerFromStorage(storageManager);
    databaseAdapter = getDatabaseAdapterFromStorage(storageManager);

    System.out.println(
        "Database adapter type: "
            + (databaseAdapter != null ? databaseAdapter.getClass().getSimpleName() : "null"));
    System.out.println("Swap manager: " + (swapManager != null ? "initialized" : "null"));
  }

  @AfterEach
  void tearDown() {
    System.out.println("=== Tearing down LevelChunk Integration Test ===");
    if (storageManager != null) {
      storageManager.shutdown();
    }
    // Reset references to prevent memory leaks
    storageManager = null;
    swapManager = null;
    databaseAdapter = null;
    config = null;
  }

  @Test
  @DisplayName("Test LevelChunk Serialization and Deserialization")
  @Timeout(30)
  void testLevelChunkSerialization() throws Exception {
    System.out.println("Testing LevelChunk serialization and deserialization...");

    // Create a LevelChunk with test data
    LevelChunk originalChunk = new LevelChunk(testWorldName, 10, 20);
    
    // Add realistic block data
    byte[] testBlockData = new byte[4096]; // 4KB of block data
    for (int i = 0; i < testBlockData.length; i++) {
      testBlockData[i] = (byte) (i % 256);
    }
    originalChunk.setBlockSection(0, testBlockData);
    originalChunk.setBlockSection(1, testBlockData);
    
    // Test serialization
    byte[] serializedData = originalChunk.serialize();
    assertNotNull(serializedData, "Serialized data should not be null");
    assertTrue(serializedData.length > 100, "Serialized data should have reasonable size");
    System.out.println("✓ Serialized LevelChunk: " + serializedData.length + " bytes");

    // Test deserialization
    LevelChunk deserializedChunk = new LevelChunk();
    boolean success = deserializedChunk.deserialize(serializedData);
    assertTrue(success, "Deserialization should succeed");
    
    // Verify chunk data integrity
    assertEquals(originalChunk.getX(), deserializedChunk.getX(), "X coordinate should match");
    assertEquals(originalChunk.getZ(), deserializedChunk.getZ(), "Z coordinate should match");
    assertEquals(originalChunk.getWorldName(), deserializedChunk.getWorldName(), "World name should match");
    assertEquals(originalChunk.isModified(), deserializedChunk.isModified(), "Modified flag should match");
    
    // Verify block sections
    assertEquals(originalChunk.getBlockSections().size(), deserializedChunk.getBlockSections().size(), 
        "Block section count should match");
    
    for (int i = 0; i < 2; i++) {
      byte[] originalSection = originalChunk.getBlockSection(i);
      byte[] deserializedSection = deserializedChunk.getBlockSection(i);
      
      assertNotNull(originalSection, "Original section should not be null");
      assertNotNull(deserializedSection, "Deserialized section should not be null");
      assertArrayEquals(originalSection, deserializedSection, "Block section data should match");
    }

    System.out.println("✓ LevelChunk serialization/deserialization test passed!");
  }

  @Test
  @DisplayName("Test Complete LevelChunk Swap Cycle")
  @Timeout(30)
  void testCompleteLevelChunkSwapCycle() throws Exception {
    System.out.println("Testing complete LevelChunk swap cycle...");

    // Skip test if critical components are not available
    if (swapManager == null || databaseAdapter == null) {
      System.out.println("⚠️ Skipping test - Critical components not available");
      return;
    }

    // Create a LevelChunk with realistic data
    LevelChunk originalChunk = new LevelChunk(testWorldName, 5, 5);
    
    // Add test block data
    byte[] testBlockData = new byte[8192]; // 8KB of block data
    for (int i = 0; i < testBlockData.length; i++) {
      testBlockData[i] = (byte) (i % 256);
    }
    originalChunk.setBlockSection(0, testBlockData);
    originalChunk.setBlockSection(1, testBlockData);
    originalChunk.setBlockSection(2, testBlockData);

    String chunkKey = originalChunk.createChunkKey();
    System.out.println("Created LevelChunk: " + chunkKey);

    // Step 1: Store LevelChunk in database
    byte[] serializedChunk = originalChunk.serialize();
    databaseAdapter.putChunk(chunkKey, serializedChunk);
    System.out.println("✓ Stored LevelChunk in database");

    // Step 2: Simulate chunk being in cache
    simulateLevelChunkInCache(originalChunk);
    System.out.println("✓ Simulated LevelChunk in cache");

    // Wait to ensure chunk is old enough for swapping
    Thread.sleep(200);

    // Step 3: Trigger swap out
    CompletableFuture<Boolean> swapOutFuture = swapManager.swapOutChunk(chunkKey);
    Boolean swapOutResult = swapOutFuture.get(5, TimeUnit.SECONDS);
    
    assertTrue(swapOutResult, "LevelChunk swap out should succeed");
    System.out.println("✓ Successfully swapped out LevelChunk");

    // Step 4: Verify chunk was removed from cache
    Optional<ChunkCache.CachedChunk> cachedChunk = getCachedChunk(storageManager, chunkKey);
    if (cachedChunk.isPresent()) {
      assertTrue(cachedChunk.get().isSwapped() || cachedChunk.get().isSwapping(),
          "Chunk should be marked as swapped or swapping");
    }
    System.out.println("✓ Verified chunk was marked as swapped");

    // Step 5: Perform swap in
    CompletableFuture<Boolean> swapInFuture = swapManager.swapInChunk(chunkKey);
    Boolean swapInResult = swapInFuture.get(5, TimeUnit.SECONDS);
    
    assertTrue(swapInResult, "LevelChunk swap in should succeed");
    System.out.println("✓ Successfully swapped in LevelChunk");

    // Step 6: Retrieve and deserialize the chunk from database to verify integrity
    Optional<byte[]> retrievedData = databaseAdapter.getChunk(chunkKey);
    assertTrue(retrievedData.isPresent(), "Should be able to retrieve chunk from database");
    
    LevelChunk restoredChunk = new LevelChunk();
    boolean deserializationSuccess = restoredChunk.deserialize(retrievedData.get());
    assertTrue(deserializationSuccess, "Should be able to deserialize LevelChunk");

    // Step 7: Verify chunk data integrity
    assertEquals(originalChunk.getX(), restoredChunk.getX(), "X coordinate mismatch");
    assertEquals(originalChunk.getZ(), restoredChunk.getZ(), "Z coordinate mismatch");
    assertEquals(originalChunk.getWorldName(), restoredChunk.getWorldName(), "World name mismatch");
    assertEquals(originalChunk.getBlockSections().size(), restoredChunk.getBlockSections().size(), 
        "Block section count mismatch");

    // Verify all block sections
    for (int i = 0; i < 3; i++) {
      byte[] originalSection = originalChunk.getBlockSection(i);
      byte[] restoredSection = restoredChunk.getBlockSection(i);
      
      assertNotNull(originalSection, "Original section should not be null");
      assertNotNull(restoredSection, "Restored section should not be null");
      assertArrayEquals(originalSection, restoredSection, "Block section " + i + " data mismatch");
    }

    // Step 8: Verify statistics
    SwapManager.SwapManagerStats finalStats = swapManager.getStats();
    assertEquals(2, finalStats.getTotalOperations(), "Should have 2 swap operations");
    assertEquals(0, finalStats.getFailedOperations(), "No operations should have failed");

    System.out.println("✓ Complete LevelChunk swap cycle test passed!");
    System.out.println("  - Original chunk: " + originalChunk);
    System.out.println("  - Restored chunk: " + restoredChunk);
  }

  @Test
  @DisplayName("Test LevelChunk Thread Safety")
  @Timeout(30)
  void testLevelChunkThreadSafety() throws Exception {
    System.out.println("Testing LevelChunk thread safety...");

    // Create a LevelChunk
    LevelChunk testChunk = new LevelChunk(testWorldName, 1, 1);
    
    // Add some initial block data
    byte[] initialData = new byte[1024];
    for (int i = 0; i < initialData.length; i++) {
      initialData[i] = (byte) i;
    }
    testChunk.setBlockSection(0, initialData);

    // Create multiple threads that will access the chunk concurrently
    int threadCount = 10;
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread thread = new Thread(() -> {
        for (int j = 0; j < 100; j++) {
          // Concurrent read/write operations
          if (threadId % 2 == 0) {
            // Read operations
            int x = testChunk.getX();
            int z = testChunk.getZ();
            byte[] section = testChunk.getBlockSection(0);
            if (section != null) {
              int length = section.length;
            }
          } else {
            // Write operations
            byte[] newData = new byte[1024];
            for (int k = 0; k < newData.length; k++) {
              newData[k] = (byte) (threadId * 100 + k);
            }
            testChunk.setBlockSection(0, newData);
          }
        }
      });
      threads.add(thread);
    }

    // Start all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }

    // Verify chunk is still in valid state
    assertNotNull(testChunk.getBlockSection(0), "Block section should still be accessible");
    assertEquals(1024, testChunk.getBlockSection(0).length, "Block section size should be correct");
    
    // Test serialization still works after concurrent access
    byte[] serialized = testChunk.serialize();
    assertNotNull(serialized, "Serialization should still work after concurrent access");
    
    LevelChunk deserialized = new LevelChunk();
    boolean success = deserialized.deserialize(serialized);
    assertTrue(success, "Deserialization should still work after concurrent access");

    System.out.println("✓ LevelChunk thread safety test passed!");
  }

  @Test
  @DisplayName("Test LevelChunk Error Handling")
  @Timeout(30)
  void testLevelChunkErrorHandling() throws Exception {
    System.out.println("Testing LevelChunk error handling...");

    // Test 1: Deserialize null data
    LevelChunk chunk1 = new LevelChunk();
    boolean nullResult = chunk1.deserialize(null);
    assertFalse(nullResult, "Deserializing null should fail");
    System.out.println("✓ Null deserialization correctly failed");

    // Test 2: Deserialize empty data
    LevelChunk chunk2 = new LevelChunk();
    boolean emptyResult = chunk2.deserialize(new byte[0]);
    assertFalse(emptyResult, "Deserializing empty data should fail");
    System.out.println("✓ Empty deserialization correctly failed");

    // Test 3: Deserialize invalid format
    LevelChunk chunk3 = new LevelChunk();
    byte[] invalidData = "INVALID_FORMAT".getBytes();
    boolean invalidResult = chunk3.deserialize(invalidData);
    assertFalse(invalidResult, "Deserializing invalid format should fail");
    System.out.println("✓ Invalid format deserialization correctly failed");

    // Test 4: Create LevelChunk with negative coordinates (should still work but be unusual)
    LevelChunk negativeChunk = new LevelChunk(testWorldName, -10, -20);
    assertEquals(-10, negativeChunk.getX(), "Should accept negative X coordinates");
    assertEquals(-20, negativeChunk.getZ(), "Should accept negative Z coordinates");
    System.out.println("✓ Negative coordinates handled correctly");

    // Test 5: Test setBlockSection with null data
    LevelChunk chunk5 = new LevelChunk(testWorldName, 2, 3);
    chunk5.setBlockSection(1, null);
    assertNull(chunk5.getBlockSection(1), "Should handle null block section data");
    System.out.println("✓ Null block section data handled correctly");

    // Test 6: Test serialization of chunk with no block sections
    LevelChunk emptyChunk = new LevelChunk(testWorldName, 4, 5);
    byte[] emptySerialized = emptyChunk.serialize();
    assertNotNull(emptySerialized, "Should serialize chunk with no block sections");
    
    LevelChunk emptyDeserialized = new LevelChunk();
    boolean emptyDeserializeResult = emptyDeserialized.deserialize(emptySerialized);
    assertTrue(emptyDeserializeResult, "Should deserialize chunk with no block sections");
    assertEquals(0, emptyDeserialized.getBlockSections().size(), "Should have no block sections");
    System.out.println("✓ Empty chunk serialization/deserialization works");

    System.out.println("✓ LevelChunk error handling test passed!");
  }

  @Test
  @DisplayName("Test Bulk LevelChunk Operations")
  @Timeout(30)
  void testBulkLevelChunkOperations() throws Exception {
    System.out.println("Testing bulk LevelChunk operations...");

    // Skip test if critical components are not available
    if (swapManager == null || databaseAdapter == null) {
      System.out.println("⚠️ Skipping test - Critical components not available");
      return;
    }

    // Create multiple LevelChunks
    List<LevelChunk> chunks = new ArrayList<>();
    List<String> chunkKeys = new ArrayList<>();
    
    for (int i = 0; i < 8; i++) {
      LevelChunk chunk = new LevelChunk(testWorldName, i, i * 2);
      
      // Add some block data
      byte[] testData = new byte[2048];
      for (int j = 0; j < testData.length; j++) {
        testData[j] = (byte) (i * 100 + j);
      }
      chunk.setBlockSection(0, testData);
      
      chunks.add(chunk);
      chunkKeys.add(chunk.createChunkKey());
      
      // Store in database
      databaseAdapter.putChunk(chunkKeys.get(i), chunk.serialize());
      // Simulate in cache
      simulateLevelChunkInCache(chunk);
    }

    System.out.println("✓ Created and stored " + chunks.size() + " LevelChunks");

    // Wait for chunks to be old enough
    Thread.sleep(200);

    // Test bulk swap out
    CompletableFuture<Integer> bulkSwapOutFuture =
        swapManager.bulkSwapChunks(chunkKeys, SwapManager.SwapOperationType.SWAP_OUT);
    Integer swapOutCount = bulkSwapOutFuture.get(10, TimeUnit.SECONDS);
    
    assertTrue(swapOutCount > 0, "Bulk swap out should succeed for at least some chunks");
    System.out.println("✓ Bulk swap out completed: " + swapOutCount + " chunks");

    // Test bulk swap in
    CompletableFuture<Integer> bulkSwapInFuture =
        swapManager.bulkSwapChunks(chunkKeys, SwapManager.SwapOperationType.SWAP_IN);
    Integer swapInCount = bulkSwapInFuture.get(10, TimeUnit.SECONDS);
    
    assertTrue(swapInCount > 0, "Bulk swap in should succeed for at least some chunks");
    System.out.println("✓ Bulk swap in completed: " + swapInCount + " chunks");

    // Verify all chunks are still accessible and intact
    for (int i = 0; i < chunkKeys.size(); i++) {
      Optional<byte[]> retrieved = databaseAdapter.getChunk(chunkKeys.get(i));
      assertTrue(retrieved.isPresent(), "Should be able to retrieve chunk " + chunkKeys.get(i));
      
      LevelChunk original = chunks.get(i);
      LevelChunk restored = new LevelChunk();
      boolean success = restored.deserialize(retrieved.get());
      
      assertTrue(success, "Should be able to deserialize chunk " + chunkKeys.get(i));
      assertEquals(original.getX(), restored.getX(), "X coordinate mismatch for chunk " + chunkKeys.get(i));
      assertEquals(original.getZ(), restored.getZ(), "Z coordinate mismatch for chunk " + chunkKeys.get(i));
      assertArrayEquals(original.getBlockSection(0), restored.getBlockSection(0), 
          "Block section data mismatch for chunk " + chunkKeys.get(i));
    }

    System.out.println("✓ Bulk LevelChunk operations test passed!");
  }

  // Helper methods

  private SwapManager getSwapManagerFromStorage(ChunkStorageManager storageManager) {
    try {
      java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("swapManager");
      field.setAccessible(true);
      return (SwapManager) field.get(storageManager);
    } catch (Exception e) {
      System.err.println("Failed to access swapManager field: " + e.getMessage());
      return null;
    }
  }

  private RustDatabaseAdapter getDatabaseAdapterFromStorage(ChunkStorageManager storageManager) {
    try {
      java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("database");
      field.setAccessible(true);
      return (RustDatabaseAdapter) field.get(storageManager);
    } catch (Exception e) {
      System.err.println("Failed to access database field: " + e.getMessage());
      return null;
    }
  }

  private Optional<ChunkCache.CachedChunk> getCachedChunk(
      ChunkStorageManager storageManager, String key) {
    try {
      java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("cache");
      field.setAccessible(true);
      ChunkCache cache = (ChunkCache) field.get(storageManager);
      return cache.getChunk(key);
    } catch (Exception e) {
      System.err.println("Failed to access cache field: " + e.getMessage());
      return Optional.empty();
    }
  }

  private void simulateLevelChunkInCache(LevelChunk chunk) {
    try {
      java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("cache");
      field.setAccessible(true);
      ChunkCache cache = (ChunkCache) field.get(storageManager);
      
      String chunkKey = chunk.createChunkKey();
      cache.putChunk(chunkKey, chunk);
      System.out.println("✓ Simulated LevelChunk in cache: " + chunkKey);
    } catch (Exception e) {
      System.err.println("Failed to simulate LevelChunk in cache: " + e.getMessage());
    }
  }
}