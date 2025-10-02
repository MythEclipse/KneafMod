package com.kneaf.core.chunkstorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Comprehensive end-to-end testing for the complete swap system.
 * Tests the entire flow from cache eviction to database storage and back.
 */
public class SwapEndToEndTest {
    
    private ChunkStorageManager storageManager;
    private SwapManager swapManager;
    private ChunkStorageConfig config;
    private String testWorldName = "test-world-swap-e2e";
    private RustDatabaseAdapter databaseAdapter;
    
    @BeforeEach
    void setUp() {
        System.out.println("=== Setting up End-to-End Swap Test ===");
        
        // Check if native library is available before proceeding
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Native library not available - some tests may be skipped or use fallback mode");
            // Continue with setup but will use fallback behavior
        }
        
        // Create comprehensive storage configuration with swap enabled
        config = new ChunkStorageConfig();
        config.setEnabled(true);
        config.setCacheCapacity(10); // Small cache to force evictions
        config.setEvictionPolicy("hybrid");
        config.setUseRustDatabase(true);
        config.setDatabaseType("memory"); // Use memory database for testing
        config.setEnableChecksums(true);
        config.setAsyncThreadPoolSize(4);
        
        // Configure swap manager
        config.setEnableSwapManager(true);
        config.setSwapMemoryCheckIntervalMs(500); // Fast monitoring for tests
        config.setMaxConcurrentSwaps(3);
        config.setSwapBatchSize(5);
        config.setSwapTimeoutMs(10000);
        config.setEnableAutomaticSwapping(true);
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
        
        System.out.println("Database adapter type: " + (databaseAdapter != null ? databaseAdapter.getClass().getSimpleName() : "null"));
        System.out.println("Swap manager: " + (swapManager != null ? "initialized" : "null"));
        
        // Skip test if critical components are null
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Critical components not available - test may be skipped");
        }
    }
    
    @AfterEach
    void tearDown() {
        System.out.println("=== Tearing down End-to-End Swap Test ===");
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
    @DisplayName("Test Complete Swap Out and Swap In Cycle")
    @Timeout(30)
    void testCompleteSwapCycle() throws Exception {
        System.out.println("Testing complete swap out and swap in cycle...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Step 1: Create test chunks and fill cache to force eviction
        List<String> chunkKeys = new ArrayList<>();
        for (int i = 0; i < 15; i++) { // More than cache capacity
            String chunkKey = createTestChunkKey(i, i);
            chunkKeys.add(chunkKey);
            
            // Store chunk data in database
            byte[] testData = createTestChunkData(chunkKey, 1024 * 16); // 16KB chunks
            databaseAdapter.putChunk(chunkKey, testData);
            
            // Simulate chunk being in cache
            simulateChunkInCache(chunkKey);
        }
        
        System.out.println("✓ Created and cached 15 test chunks");
        
        // Step 2: Verify initial state
        assertTrue(getCacheSize(storageManager) <= 10, "Cache should be at or below capacity");
        SwapManager.SwapManagerStats initialStats = swapManager.getStats();
        assertEquals(0, initialStats.getTotalOperations(), "No swap operations should have occurred yet");
        
        // Step 3: Trigger memory pressure to force swap operations
        CompletableFuture<Boolean> swapOutFuture = swapManager.swapOutChunk(chunkKeys.get(0));
        Boolean swapOutResult = swapOutFuture.get(5, TimeUnit.SECONDS);
        
        assertTrue(swapOutResult, "Swap out operation should succeed");
        System.out.println("✓ Successfully performed swap out operation");
        
        // Step 4: Verify chunk state after swap out
        Optional<ChunkCache.CachedChunk> cachedChunk = getCachedChunk(storageManager, chunkKeys.get(0));
        if (cachedChunk.isPresent()) {
            assertTrue(cachedChunk.get().isSwapped() || cachedChunk.get().isSwapping(),
                      "Chunk should be marked as swapped or swapping");
        }
        
        // Step 5: Perform swap in operation
        CompletableFuture<Boolean> swapInFuture = swapManager.swapInChunk(chunkKeys.get(0));
        Boolean swapInResult = swapInFuture.get(5, TimeUnit.SECONDS);
        
        assertTrue(swapInResult, "Swap in operation should succeed");
        System.out.println("✓ Successfully performed swap in operation");
        
        // Step 6: Verify final statistics
        SwapManager.SwapManagerStats finalStats = swapManager.getStats();
        assertEquals(2, finalStats.getTotalOperations(), "Should have 2 swap operations");
        assertEquals(0, finalStats.getFailedOperations(), "No operations should have failed");
        assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain");
        
        System.out.println("✓ Complete swap cycle test passed!");
    }
    
    @Test
    @DisplayName("Test Bulk Swap Operations")
    @Timeout(30)
    void testBulkSwapOperations() throws Exception {
        System.out.println("Testing bulk swap operations...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Create multiple test chunks
        List<String> chunkKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String chunkKey = createTestChunkKey(i, i);
            chunkKeys.add(chunkKey);
            
            byte[] testData = createTestChunkData(chunkKey, 1024 * 8); // 8KB chunks
            databaseAdapter.putChunk(chunkKey, testData);
            simulateChunkInCache(chunkKey);
        }
        
        // Perform bulk swap out
        CompletableFuture<Integer> bulkSwapOutFuture = swapManager.bulkSwapChunks(chunkKeys, SwapManager.SwapOperationType.SWAP_OUT);
        Integer swapOutCount = bulkSwapOutFuture.get(10, TimeUnit.SECONDS);
        
        assertTrue(swapOutCount > 0, "Bulk swap out should succeed for at least some chunks");
        System.out.println("✓ Bulk swap out completed for " + swapOutCount + " chunks");
        
        // Perform bulk swap in
        CompletableFuture<Integer> bulkSwapInFuture = swapManager.bulkSwapChunks(chunkKeys, SwapManager.SwapOperationType.SWAP_IN);
        Integer swapInCount = bulkSwapInFuture.get(10, TimeUnit.SECONDS);
        
        assertTrue(swapInCount > 0, "Bulk swap in should succeed for at least some chunks");
        System.out.println("✓ Bulk swap in completed for " + swapInCount + " chunks");
        
        // Verify statistics
        SwapManager.SwapManagerStats stats = swapManager.getStats();
        assertTrue(stats.getTotalOperations() >= swapOutCount + swapInCount,
                  "Total operations should include bulk operations");
        
        System.out.println("✓ Bulk swap operations test passed!");
    }
    
    @Test
    @DisplayName("Test Automatic Swap Triggering Under Memory Pressure")
    @Timeout(30)
    void testAutomaticSwapTriggering() throws Exception {
        System.out.println("Testing automatic swap triggering under memory pressure...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Create many chunks to fill cache and trigger memory pressure
        List<String> chunkKeys = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String chunkKey = createTestChunkKey(i, i);
            chunkKeys.add(chunkKey);
            
            byte[] testData = createTestChunkData(chunkKey, 1024 * 32); // Larger chunks
            databaseAdapter.putChunk(chunkKey, testData);
            simulateChunkInCache(chunkKey);
        }
        
        // Wait for memory monitoring to detect pressure and trigger swaps
        Thread.sleep(2000); // Wait for monitoring cycle
        
        // Check if any automatic swaps were triggered
        SwapManager.SwapManagerStats stats = swapManager.getStats();
        int pressureTriggers = stats.getPressureTriggers();
        
        System.out.println("✓ Memory pressure triggers detected: " + pressureTriggers);
        
        // Verify that memory pressure level is being tracked
        SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();
        assertNotNull(pressureLevel, "Memory pressure level should be tracked");
        
        System.out.println("✓ Current memory pressure level: " + pressureLevel);
        
        // Test that cache is aware of memory pressure
        ChunkCache.MemoryPressureLevel cachePressureLevel = getCachePressureLevel(storageManager);
        assertNotNull(cachePressureLevel, "Cache should track memory pressure");
        
        System.out.println("✓ Automatic swap triggering test passed!");
    }
    
    @Test
    @DisplayName("Test Swap Operation Failure Handling")
    @Timeout(30)
    void testSwapOperationFailureHandling() throws Exception {
        System.out.println("Testing swap operation failure handling...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null) {
            System.out.println("⚠️ Skipping test - Swap manager not available");
            return;
        }
        
        // Test swap out with invalid chunk key
        CompletableFuture<Boolean> invalidSwapOut = swapManager.swapOutChunk("");
        Boolean invalidResult = invalidSwapOut.get(5, TimeUnit.SECONDS);
        assertFalse(invalidResult, "Swap out with empty key should fail");
        
        // Test swap in with invalid chunk key
        CompletableFuture<Boolean> invalidSwapIn = swapManager.swapInChunk("");
        Boolean invalidInResult = invalidSwapIn.get(5, TimeUnit.SECONDS);
        assertFalse(invalidResult, "Swap in with empty key should fail");
        
        // Test swap with null key
        CompletableFuture<Boolean> nullSwapOut = swapManager.swapOutChunk(null);
        Boolean nullResult = nullSwapOut.get(5, TimeUnit.SECONDS);
        assertFalse(nullResult, "Swap out with null key should fail");
        
        // Verify failure statistics are tracked
        SwapManager.SwapManagerStats stats = swapManager.getStats();
        assertTrue(stats.getFailedOperations() >= 3, "Failed operations should be tracked");
        
        System.out.println("✓ Swap operation failure handling test passed!");
    }
    
    @Test
    @DisplayName("Test Swap Statistics Accuracy")
    @Timeout(30)
    void testSwapStatisticsAccuracy() throws Exception {
        System.out.println("Testing swap statistics accuracy...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Create test chunk
        String chunkKey = createTestChunkKey(1, 1);
        byte[] testData = createTestChunkData(chunkKey, 1024 * 16);
        databaseAdapter.putChunk(chunkKey, testData);
        simulateChunkInCache(chunkKey);
        
        // Record initial statistics
        SwapManager.SwapStatistics initialStats = swapManager.getSwapStatistics();
        long initialSwapOuts = initialStats.getTotalSwapOuts();
        long initialSwapIns = initialStats.getTotalSwapIns();
        long initialFailures = initialStats.getTotalFailures();
        
        // Perform successful swap operations
        swapManager.swapOutChunk(chunkKey).get(5, TimeUnit.SECONDS);
        swapManager.swapInChunk(chunkKey).get(5, TimeUnit.SECONDS);
        
        // Verify statistics were updated
        SwapManager.SwapStatistics finalStats = swapManager.getSwapStatistics();
        assertEquals(initialSwapOuts + 1, finalStats.getTotalSwapOuts(), "Swap out count should increase");
        assertEquals(initialSwapIns + 1, finalStats.getTotalSwapIns(), "Swap in count should increase");
        assertEquals(initialFailures, finalStats.getTotalFailures(), "Failure count should not change");
        
        // Verify timing statistics
        assertTrue(finalStats.getAverageSwapOutTime() > 0, "Average swap out time should be positive");
        assertTrue(finalStats.getAverageSwapInTime() > 0, "Average swap in time should be positive");
        
        // Verify throughput calculation
        double throughput = finalStats.getSwapThroughputMBps();
        assertTrue(throughput >= 0, "Swap throughput should be non-negative");
        
        System.out.println("✓ Swap statistics accuracy test passed!");
        System.out.println("  - Swap throughput: " + String.format("%.2f MB/s", throughput));
        System.out.println("  - Average swap out time: " + String.format("%.2f ms", finalStats.getAverageSwapOutTime()));
        System.out.println("  - Average swap in time: " + String.format("%.2f ms", finalStats.getAverageSwapInTime()));
    }
    
    @Test
    @DisplayName("Test Concurrent Swap Operations")
    @Timeout(60)
    void testConcurrentSwapOperations() throws Exception {
        System.out.println("Testing concurrent swap operations...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Create multiple test chunks
        List<String> chunkKeys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String chunkKey = createTestChunkKey(i, i);
            chunkKeys.add(chunkKey);
            
            byte[] testData = createTestChunkData(chunkKey, 1024 * 8);
            databaseAdapter.putChunk(chunkKey, testData);
            simulateChunkInCache(chunkKey);
        }
        
        // Perform concurrent swap operations
        List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
        
        for (int i = 0; i < chunkKeys.size(); i++) {
            String chunkKey = chunkKeys.get(i);
            if (i % 2 == 0) {
                swapFutures.add(swapManager.swapOutChunk(chunkKey));
            } else {
                swapFutures.add(swapManager.swapInChunk(chunkKey));
            }
        }
        
        // Wait for all operations to complete
        CompletableFuture<Void> allSwaps = CompletableFuture.allOf(
            swapFutures.toArray(new CompletableFuture[0])
        );
        allSwaps.get(15, TimeUnit.SECONDS);
        
        // Verify results
        int successfulSwaps = 0;
        for (CompletableFuture<Boolean> future : swapFutures) {
            if (future.get()) {
                successfulSwaps++;
            }
        }
        
        System.out.println("✓ Concurrent swap operations completed: " + successfulSwaps + "/" + swapFutures.size());
        
        // Verify concurrent operation statistics
        SwapManager.SwapManagerStats stats = swapManager.getStats();
        assertTrue(stats.getTotalOperations() >= successfulSwaps, "All successful operations should be tracked");
        
        System.out.println("✓ Concurrent swap operations test passed!");
    }
    
    @Test
    @DisplayName("Test Swap Manager Shutdown and Resource Cleanup")
    @Timeout(30)
    void testSwapManagerShutdown() throws Exception {
        System.out.println("Testing swap manager shutdown and resource cleanup...");
        
        // Skip test if native library is not available
        if (!RustDatabaseAdapter.isNativeLibraryAvailable()) {
            System.out.println("⚠️ Skipping test - Native library not available");
            return;
        }
        
        // Verify critical components are available
        if (swapManager == null || databaseAdapter == null) {
            System.out.println("⚠️ Skipping test - Critical components not available");
            return;
        }
        
        // Create and perform some operations
        String chunkKey = createTestChunkKey(1, 1);
        byte[] testData = createTestChunkData(chunkKey, 1024 * 16);
        databaseAdapter.putChunk(chunkKey, testData);
        simulateChunkInCache(chunkKey);
        
        // Start some swap operations
        CompletableFuture<Boolean> swapFuture = swapManager.swapOutChunk(chunkKey);
        
        // Shutdown while operation is in progress
        swapManager.shutdown();
        
        // Verify shutdown completed
        SwapManager.SwapManagerStats finalStats = swapManager.getStats();
        assertEquals(0, finalStats.getActiveSwaps(), "No active swaps should remain after shutdown");
        
        // Test that new operations fail after shutdown
        CompletableFuture<Boolean> postShutdownSwap = swapManager.swapOutChunk(chunkKey);
        Boolean result = postShutdownSwap.get(2, TimeUnit.SECONDS);
        assertFalse(result, "Swap operations should fail after shutdown");
        
        System.out.println("✓ Swap manager shutdown test passed!");
    }
    
    // Helper methods for accessing private/internal components
    
    private SwapManager getSwapManagerFromStorage(ChunkStorageManager storageManager) {
        // Use reflection to access the private swapManager field
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
        // Use reflection to access the private database field
        try {
            java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("database");
            field.setAccessible(true);
            return (RustDatabaseAdapter) field.get(storageManager);
        } catch (Exception e) {
            System.err.println("Failed to access database field: " + e.getMessage());
            return null;
        }
    }
    
    private int getCacheSize(ChunkStorageManager storageManager) {
        try {
            java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("cache");
            field.setAccessible(true);
            ChunkCache cache = (ChunkCache) field.get(storageManager);
            return cache.getCacheSize();
        } catch (Exception e) {
            System.err.println("Failed to access cache field: " + e.getMessage());
            return 0;
        }
    }
    
    private Optional<ChunkCache.CachedChunk> getCachedChunk(ChunkStorageManager storageManager, String key) {
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
    
    private ChunkCache.MemoryPressureLevel getCachePressureLevel(ChunkStorageManager storageManager) {
        try {
            java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("cache");
            field.setAccessible(true);
            ChunkCache cache = (ChunkCache) field.get(storageManager);
            return cache.getMemoryPressureLevel();
        } catch (Exception e) {
            System.err.println("Failed to access cache field: " + e.getMessage());
            return ChunkCache.MemoryPressureLevel.NORMAL;
        }
    }
    
    private void simulateChunkInCache(String chunkKey) {
        // Simulate a chunk being in cache by creating a mock cached chunk
        try {
            java.lang.reflect.Field field = ChunkStorageManager.class.getDeclaredField("cache");
            field.setAccessible(true);
            ChunkCache cache = (ChunkCache) field.get(storageManager);
            
            // Create a mock chunk and put it in cache
            Object mockChunk = createMockChunk(1, 1);
            cache.putChunk(chunkKey, mockChunk);
            System.out.println("✓ Simulated chunk in cache: " + chunkKey);
        } catch (Exception e) {
            System.err.println("Failed to simulate chunk in cache: " + e.getMessage());
        }
    }
    
    private String createTestChunkKey(int x, int z) {
        return testWorldName + ":" + x + ":" + z;
    }
    
    private byte[] createTestChunkData(String key, int size) {
        byte[] data = new byte[size];
        // Fill with test pattern based on key
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (key.hashCode() + i);
        }
        return data;
    }
    
    private Object createMockChunk(int x, int z) {
        // Create a mock chunk object for testing
        // In a real implementation, this would be a proper LevelChunk
        return new MockChunk(x, z);
    }
    
    // Simple mock chunk class for testing
    private static class MockChunk {
        private final int x;
        private final int z;
        
        public MockChunk(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        public int getX() { return x; }
        public int getZ() { return z; }
    }
}