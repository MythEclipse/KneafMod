package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.chunkstorage.database.InMemoryDatabaseAdapter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test suite for InMemoryDatabaseAdapter swap functionality.
 * Tests both swapOutChunk and swapInChunk operations with real disk I/O.
 */
public class InMemorySwapTest {

  private InMemoryDatabaseAdapter database;
  private static final String TEST_CHUNK_KEY = "test:chunk:123:456";
  private static final int TEST_CHUNK_SIZE = 1024 * 16; // 16KB test chunk
  private byte[] originalTestData;
  private Random random = new Random(42); // Fixed seed for reproducible tests

  @BeforeEach
  void setUp() throws IOException {
    // Create test directory if it doesn't exist
    File swapDir = new File("swap_storage");
    if (!swapDir.exists()) {
      swapDir.mkdirs();
    }

    // Clean up any existing swap files from previous tests
    if (swapDir.exists()) {
      for (File file : swapDir.listFiles()) {
        if (file.getName().endsWith(".dat")) {
          file.delete();
        }
      }
    }

    // Initialize database
    database = new InMemoryDatabaseAdapter("test-swap-db");

    // Create test data
    originalTestData = new byte[TEST_CHUNK_SIZE];
    random.nextBytes(originalTestData);

    // Store initial chunk in memory
    database.putChunk(TEST_CHUNK_KEY, originalTestData);
  }

  @AfterEach
  void tearDown() throws IOException {
    if (database != null) {
      database.close();
    }

    // Clean up swap directory
    File swapDir = new File("swap_storage");
    if (swapDir.exists()) {
      for (File file : swapDir.listFiles()) {
        if (file.getName().endsWith(".dat")) {
          file.delete();
        }
      }
    }
  }

  @Test
  @DisplayName("Test basic swapOutChunk functionality")
  void testSwapOutChunk() throws IOException {
    // Verify chunk exists in memory initially
    assertTrue(database.hasChunk(TEST_CHUNK_KEY));
    
    // Verify we can retrieve it from memory
    var optionalData = database.getChunk(TEST_CHUNK_KEY);
    assertTrue(optionalData.isPresent());
    assertArrayEquals(originalTestData, optionalData.get());

    // Perform swap out
    boolean swapResult = database.swapOutChunk(TEST_CHUNK_KEY);
    assertTrue(swapResult, "Swap out should succeed");

    // Verify chunk is no longer in memory
    assertFalse(database.hasChunk(TEST_CHUNK_KEY));
    
    // Verify chunk file was created on disk
    File swapDir = new File("swap_storage");
    File[] swapFiles = swapDir.listFiles((dir, name) -> name.endsWith(".dat"));
    assertNotNull(swapFiles, "Swap files should exist");
    assertTrue(swapFiles.length > 0, "At least one swap file should exist");
    
    // Verify swap file has correct size (header + data + footer)
    long expectedMinSize = 100 + TEST_CHUNK_SIZE; // Header/footer + data
    assertTrue(swapFiles[0].length() >= expectedMinSize, 
              "Swap file size should be reasonable");

    // Verify stats were updated
    Object stats = database.getStats();
    assertEquals(1, ((Map<?, ?>) stats).get("swapOutCount"));
    assertEquals(TEST_CHUNK_SIZE, ((Map<?, ?>) stats).get("swapOutBytes"));
  }

  @Test
  @DisplayName("Test basic swapInChunk functionality")
  void testSwapInChunk() throws IOException {
    // First swap out the chunk
    database.swapOutChunk(TEST_CHUNK_KEY);
    
    // Verify chunk is not in memory
    assertFalse(database.hasChunk(TEST_CHUNK_KEY));

    // Perform swap in
    var optionalData = database.swapInChunk(TEST_CHUNK_KEY);
    assertTrue(optionalData.isPresent(), "Swap in should succeed");
    
    // Verify retrieved data matches original
    byte[] retrievedData = optionalData.get();
    assertArrayEquals(originalTestData, retrievedData, 
                   "Retrieved data should match original");

    // Verify chunk is now back in memory
    assertTrue(database.hasChunk(TEST_CHUNK_KEY));
    
    // Verify swap file was deleted
    File swapDir = new File("swap_storage");
    File[] swapFiles = swapDir.listFiles((dir, name) -> name.endsWith(".dat"));
    assertNull(swapFiles, "No swap files should remain after swap in");

    // Verify stats were updated
    Object stats = database.getStats();
    assertEquals(1, ((Map<?, ?>) stats).get("swapInCount"));
    assertEquals(TEST_CHUNK_SIZE, ((Map<?, ?>) stats).get("swapInBytes"));
  }

  @Test
  @DisplayName("Test full swap cycle (swapOut -> swapIn)")
  void testFullSwapCycle() throws IOException {
    // Initial state: chunk in memory
    assertTrue(database.hasChunk(TEST_CHUNK_KEY));

    // Swap out
    boolean swapOutResult = database.swapOutChunk(TEST_CHUNK_KEY);
    assertTrue(swapOutResult);
    assertFalse(database.hasChunk(TEST_CHUNK_KEY));

    // Swap in
    var swapInResult = database.swapInChunk(TEST_CHUNK_KEY);
    assertTrue(swapInResult.isPresent());
    assertArrayEquals(originalTestData, swapInResult.get());
    assertTrue(database.hasChunk(TEST_CHUNK_KEY));

    // Verify we can still retrieve the chunk from memory
    var memoryResult = database.getChunk(TEST_CHUNK_KEY);
    assertTrue(memoryResult.isPresent());
    assertArrayEquals(originalTestData, memoryResult.get());
  }

  @Test
  @DisplayName("Test swapOut of non-existent chunk")
  void testSwapOutNonExistent() throws IOException {
    String nonExistentKey = "nonexistent:chunk:789";
    boolean result = database.swapOutChunk(nonExistentKey);
    assertFalse(result, "Swap out of non-existent chunk should fail");
  }

  @Test
  @DisplayName("Test swapIn of non-existent chunk")
  void testSwapInNonExistent() throws IOException {
    String nonExistentKey = "nonexistent:chunk:789";
    var result = database.swapInChunk(nonExistentKey);
    assertFalse(result.isPresent(), "Swap in of non-existent chunk should return empty");
  }

  @Test
  @DisplayName("Test swapOut with invalid key format")
  void testSwapOutInvalidKey() {
    String invalidKey = "invalid/key:with/slashes";
    assertThrows(IllegalArgumentException.class, 
               () -> database.swapOutChunk(invalidKey));
  }

  @Test
  @DisplayName("Test concurrent swap operations")
  void testConcurrentSwapOperations() throws Exception {
    // Create multiple test chunks
    int numChunks = 5;
    String[] chunkKeys = new String[numChunks];
    byte[][] originalData = new byte[numChunks][];

    for (int i = 0; i < numChunks; i++) {
      chunkKeys[i] = "test:chunk:" + i + ":" + i;
      originalData[i] = new byte[TEST_CHUNK_SIZE];
      random.nextBytes(originalData[i]);
      database.putChunk(chunkKeys[i], originalData[i]);
    }

    // Perform concurrent swap out operations
    CountDownLatch startLatch = new CountDownLatch(1);
    CompletableFuture[] swapOutFutures = new CompletableFuture[numChunks];

    for (int i = 0; i < numChunks; i++) {
      final int index = i;
      swapOutFutures[i] = CompletableFuture.runAsync(() -> {
        try {
          startLatch.await();
          database.swapOutChunk(chunkKeys[index]);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }

    startLatch.countDown();
    CompletableFuture.allOf(swapOutFutures).get(10, TimeUnit.SECONDS);

    // Verify all chunks were swapped out
    for (int i = 0; i < numChunks; i++) {
      assertFalse(database.hasChunk(chunkKeys[i]));
    }

    // Perform concurrent swap in operations
    CompletableFuture[] swapInFutures = new CompletableFuture[numChunks];
    CountDownLatch swapInLatch = new CountDownLatch(1);

    for (int i = 0; i < numChunks; i++) {
      final int index = i;
      swapInFutures[i] = CompletableFuture.supplyAsync(() -> {
        try {
          swapInLatch.await();
          return database.swapInChunk(chunkKeys[index]);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }

    swapInLatch.countDown();

    startLatch.countDown();
    CompletableFuture.allOf(swapInFutures).get(10, TimeUnit.SECONDS);

    // Verify all chunks were swapped back in correctly
    for (int i = 0; i < numChunks; i++) {
      assertTrue(database.hasChunk(chunkKeys[i]));
      var result = database.getChunk(chunkKeys[i]);
      assertTrue(result.isPresent());
      assertArrayEquals(originalData[i], result.get());
    }
  }

  @Test
  @DisplayName("Test swap operations preserve data integrity")
  void testDataIntegrity() throws IOException {
    // Create chunk with known pattern
    byte[] patternData = new byte[TEST_CHUNK_SIZE];
    for (int i = 0; i < patternData.length; i++) {
      patternData[i] = (byte) (i % 256);
    }
    database.putChunk(TEST_CHUNK_KEY, patternData);

    // Swap out and back in
    database.swapOutChunk(TEST_CHUNK_KEY);
    var result = database.swapInChunk(TEST_CHUNK_KEY);
    
    // Verify data integrity
    assertTrue(result.isPresent());
    assertArrayEquals(patternData, result.get());
    
    // Verify we can still retrieve it from memory
    var memoryResult = database.getChunk(TEST_CHUNK_KEY);
    assertTrue(memoryResult.isPresent());
    assertArrayEquals(patternData, memoryResult.get());
  }

  @Test
  @DisplayName("Test swap operations with large chunks")
  void testLargeChunkSwap() throws IOException {
    // Create very large chunk (1MB)
    int largeSize = 1024 * 1024;
    byte[] largeData = new byte[largeSize];
    random.nextBytes(largeData);
    database.putChunk(TEST_CHUNK_KEY, largeData);

    // Swap out and back in
    assertTrue(database.swapOutChunk(TEST_CHUNK_KEY));
    var result = database.swapInChunk(TEST_CHUNK_KEY);
    
    // Verify data integrity
    assertTrue(result.isPresent());
    assertArrayEquals(largeData, result.get());
  }
}