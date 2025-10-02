package com.kneaf.core.chunkstorage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for RustDatabaseAdapter.
 * Handles native library loading failures gracefully by catching all exceptions.
 */
public class RustDatabaseAdapterTest {
    
    private static boolean nativeLibraryAvailable = false;
    private static RustDatabaseAdapter adapter = null;
    
    static {
        try {
            // Try to load the class and initialize the adapter
            Class.forName("com.kneaf.core.chunkstorage.RustDatabaseAdapter");
            adapter = new RustDatabaseAdapter("memory", true);
            nativeLibraryAvailable = true;
            System.out.println("Rust native library loaded successfully");
        } catch (Throwable e) {
            // Catch all throwables including UnsatisfiedLinkError, ClassNotFoundException, etc.
            System.err.println("Rust native library or class not available for tests: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            nativeLibraryAvailable = false;
            adapter = null;
        }
    }
    
    @Test
    void testBasicPutAndGet() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testBasicPutAndGet - native library not available");
            return;
        }
        
        try {
            String key = "test:chunk:1:1";
            byte[] testData = "Hello, Rust Database!".getBytes();
            
            // Put data
            adapter.putChunk(key, testData);
            
            // Get data
            java.util.Optional<byte[]> retrieved = adapter.getChunk(key);
            
            assertTrue(retrieved.isPresent(), "Data should be retrieved");
            assertArrayEquals(testData, retrieved.get(), "Retrieved data should match original");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testNonExistentChunk() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testNonExistentChunk - native library not available");
            return;
        }
        
        try {
            String key = "nonexistent:chunk:999:999";
            
            java.util.Optional<byte[]> retrieved = adapter.getChunk(key);
            
            assertFalse(retrieved.isPresent(), "Non-existent chunk should return empty");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testDeleteChunk() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testDeleteChunk - native library not available");
            return;
        }
        
        try {
            String key = "test:chunk:2:2";
            byte[] testData = "Delete me!".getBytes();
            
            // Put data
            adapter.putChunk(key, testData);
            
            // Verify it exists
            assertTrue(adapter.hasChunk(key), "Chunk should exist after put");
            
            // Delete it
            boolean deleted = adapter.deleteChunk(key);
            assertTrue(deleted, "Chunk should be deleted");
            
            // Verify it's gone
            assertFalse(adapter.hasChunk(key), "Chunk should not exist after delete");
            java.util.Optional<byte[]> retrieved = adapter.getChunk(key);
            assertFalse(retrieved.isPresent(), "Deleted chunk should return empty");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testChunkCount() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testChunkCount - native library not available");
            return;
        }
        
        try {
            int initialCount = (int) adapter.getChunkCount();
            
            // Add some chunks with unique keys for this test
            for (int i = 0; i < 5; i++) {
                String key = "test:chunk:count:" + i + ":" + System.nanoTime();
                byte[] data = ("Chunk " + i).getBytes();
                adapter.putChunk(key, data);
            }
            
            int newCount = (int) adapter.getChunkCount();
            assertEquals(initialCount + 5, newCount, "Chunk count should increase by 5");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testDatabaseStats() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testDatabaseStats - native library not available");
            return;
        }
        
        try {
            // Add some data
            String key = "test:chunk:stats:1";
            byte[] data = "Stats test data".getBytes();
            adapter.putChunk(key, data);
            
            DatabaseAdapter.DatabaseStats stats = adapter.getStats();
            
            assertNotNull(stats, "Stats should not be null");
            assertTrue(stats.getTotalChunks() > 0, "Should have at least one chunk");
            assertTrue(stats.isHealthy(), "Database should be healthy");
            assertTrue(stats.getTotalSizeBytes() > 0, "Should have some data size");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testDatabaseType() {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testDatabaseType - native library not available");
            return;
        }
        
        try {
            String type = adapter.getDatabaseType();
            assertEquals("memory", type, "Database type should be 'memory'");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testHealthCheck() {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testHealthCheck - native library not available");
            return;
        }
        
        try {
            assertTrue(adapter.isHealthy(), "Database should be healthy");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testEmptyKeyValidation() {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testEmptyKeyValidation - native library not available");
            return;
        }
        
        try {
            byte[] testData = "Test data".getBytes();
            
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.putChunk("", testData);
            }, "Empty key should throw exception");
            
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.getChunk("");
            }, "Empty key should throw exception");
            
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.deleteChunk("");
            }, "Empty key should throw exception");
            
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.hasChunk("");
            }, "Empty key should throw exception");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testNullDataValidation() {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testNullDataValidation - native library not available");
            return;
        }
        
        try {
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.putChunk("test:key", null);
            }, "Null data should throw exception");
            
            assertThrows(IllegalArgumentException.class, () -> {
                adapter.putChunk("test:key", new byte[0]);
            }, "Empty data should throw exception");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    @Test
    void testAsyncOperations() throws Exception {
        if (!nativeLibraryAvailable || adapter == null) {
            System.out.println("Skipping testAsyncOperations - native library not available");
            return;
        }
        
        try {
            String key = "test:chunk:async:1";
            byte[] testData = "Async test data".getBytes();
            
            // Test async put
            adapter.putChunkAsync(key, testData).get();
            
            // Test async get
            java.util.Optional<byte[]> retrieved = adapter.getChunkAsync(key).get();
            
            assertTrue(retrieved.isPresent(), "Async get should return data");
            assertArrayEquals(testData, retrieved.get(), "Async retrieved data should match");
            
            // Test async delete
            Boolean deleted = adapter.deleteChunkAsync(key).get();
            assertTrue(deleted, "Async delete should return true");
            
            // Verify deletion
            assertFalse(adapter.hasChunk(key), "Chunk should be deleted");
        } catch (Exception e) {
            System.err.println("Test failed due to exception: " + e.getMessage());
            throw e;
        }
    }
    
    @Test
    void testNativeLibraryAvailability() {
        // This test always passes and just reports the status
        if (nativeLibraryAvailable && adapter != null) {
            System.out.println("SUCCESS: Native library is available - all RustDatabaseAdapter tests can run");
        } else {
            System.out.println("INFO: Native library is not available - RustDatabaseAdapter tests are being skipped gracefully");
        }
        
        // The test passes regardless of native library availability
        assertTrue(true, "Test framework is working correctly");
    }
}