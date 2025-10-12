package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kneaf.core.chunkstorage.database.InMemoryOnlyDatabaseAdapter;

/**
 * Test suite for InMemoryDatabaseAdapter swap functionality.
 * Tests both swapOutChunk and swapInChunk operations with memory-only simulation.
 */
public class InMemorySwapTest {

  private InMemoryOnlyDatabaseAdapter database;
  private static final String TEST_CHUNK_KEY = "test:chunk:123:456";
  private static final int TEST_CHUNK_SIZE = 1024 * 16; // 16KB test chunk
  private byte[] originalTestData;
  private Random random = new Random(42); // Fixed seed for reproducible tests

  @BeforeEach
  void setUp() throws IOException {
    // Initialize in-memory only database adapter for testing
    database = new InMemoryOnlyDatabaseAdapter("test-swap-db");
    
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

    // Perform swap out (will be simulated in-memory)
    boolean swapResult = database.swapOutChunk(TEST_CHUNK_KEY);
    assertTrue(swapResult, "Swap out should succeed");

    // Verify chunk is no longer in memory
    assertFalse(database.hasChunk(TEST_CHUNK_KEY));

    // Verify stats were updated
   Object stats = database.getStats();
   assertEquals(1L, ((Map<?, ?>) stats).get("swapOutCount"));
   assertEquals((long) TEST_CHUNK_SIZE, ((Map<?, ?>) stats).get("swapOutBytes"));
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

    // Verify stats were updated
    Object stats = database.getStats();
    assertEquals(1L, ((Map<?, ?>) stats).get("swapInCount"));
    assertEquals((long) TEST_CHUNK_SIZE, ((Map<?, ?>) stats).get("swapInBytes"));
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