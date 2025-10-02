package com.kneaf.core.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for NativeFloatBuffer buffer pooling functionality
 */
public class NativeFloatBufferPoolTest {

    @BeforeEach
    void setUp() {
        // Check if native library is available with better error handling
        try {
            // First try to load the RustPerformance class to trigger static initialization
            Class.forName("com.kneaf.core.performance.RustPerformance");
            
            // Try to allocate a small buffer to test native availability
            NativeFloatBuffer testBuffer = NativeFloatBuffer.allocateFromNative(1, 1);
            if (testBuffer == null) {
                Assumptions.assumeTrue(false, "Native buffer allocation returned null, skipping tests");
            } else {
                testBuffer.close();
            }
        } catch (ClassNotFoundException e) {
            Assumptions.assumeTrue(false, "RustPerformance class not found, skipping tests: " + e.getMessage());
        } catch (UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "Native library not available, skipping tests: " + e.getMessage());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Unexpected error during native library setup, skipping tests: " + e.getMessage());
        }
        
        // Clear pools before each test to ensure clean state
        try {
            NativeFloatBuffer.clearPools();
        } catch (Exception e) {
            // Ignore errors during pool cleanup
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        NativeFloatBuffer.clearPools();
    }

    @Test
    void testBufferPoolingEnabledByDefault() {
        // Allocate a buffer with pooling enabled (default)
        NativeFloatBuffer buffer1 = NativeFloatBuffer.allocateFromNative(100, 100);
        assertNotNull(buffer1, "Buffer allocation should succeed");
        
        // Get initial stats
        String statsBefore = NativeFloatBuffer.getPoolStats();
        long allocationsBefore = getTotalAllocations(statsBefore);
        
        // Close the buffer (should return to pool)
        buffer1.close();
        
        // Allocate another buffer of same size
        NativeFloatBuffer buffer2 = NativeFloatBuffer.allocateFromNative(100, 100);
        assertNotNull(buffer2, "Second buffer allocation should succeed");
        
        // Get stats after
        String statsAfter = NativeFloatBuffer.getPoolStats();
        long allocationsAfter = getTotalAllocations(statsAfter);
        
        // Should have reused from pool (no new allocation)
        assertEquals(allocationsBefore, allocationsAfter, 
            "Should reuse buffer from pool, no new allocation expected");
        
        buffer2.close();
    }

    @Test
    void testBufferPoolingDisabled() {
        // Allocate with pooling disabled
        NativeFloatBuffer buffer1 = NativeFloatBuffer.allocateFromNative(100, 100, false);
        assertNotNull(buffer1, "Buffer allocation should succeed");
        
        // Get initial stats
        String statsBefore = NativeFloatBuffer.getPoolStats();
        long allocationsBefore = getTotalAllocations(statsBefore);
        
        // Close the buffer (should free, not pool)
        buffer1.close();
        
        // Allocate another buffer with pooling disabled
        NativeFloatBuffer buffer2 = NativeFloatBuffer.allocateFromNative(100, 100, false);
        assertNotNull(buffer2, "Second buffer allocation should succeed");
        
        // Get stats after
        String statsAfter = NativeFloatBuffer.getPoolStats();
        long allocationsAfter = getTotalAllocations(statsAfter);
        
        // Should have allocated new buffer (no pooling)
        assertTrue(allocationsAfter > allocationsBefore, 
            "Should allocate new buffer when pooling is disabled");
        
        buffer2.close();
    }

    @Test
    void testPoolStatistics() {
        // Clear pools first
        NativeFloatBuffer.clearPools();
        
        String initialStats = NativeFloatBuffer.getPoolStats();
        assertTrue(initialStats.contains("Buffer Pool Statistics:"));
        assertTrue(initialStats.contains("Total Allocations: 0"));
        assertTrue(initialStats.contains("Total Reuses: 0"));
        
        // Allocate and close a buffer
        NativeFloatBuffer buffer = NativeFloatBuffer.allocateFromNative(50, 50);
        assertNotNull(buffer);
        buffer.close();
        
        String statsAfterAllocation = NativeFloatBuffer.getPoolStats();
        assertTrue(statsAfterAllocation.contains("Total Allocations: 1"));
        
        // Allocate again to trigger reuse
        NativeFloatBuffer buffer2 = NativeFloatBuffer.allocateFromNative(50, 50);
        assertNotNull(buffer2);
        
        String statsAfterReuse = NativeFloatBuffer.getPoolStats();
        assertTrue(statsAfterReuse.contains("Total Reuses: 1"));
        
        buffer2.close();
    }

    @Test
    void testForceCleanup() {
        // Allocate and close multiple buffers
        for (int i = 0; i < 5; i++) {
            NativeFloatBuffer buffer = NativeFloatBuffer.allocateFromNative(100, 100);
            assertNotNull(buffer);
            buffer.close();
        }
        
        // Force cleanup
        NativeFloatBuffer.forceCleanup();
        
        // Verify cleanup worked (pools should be reduced)
        String stats = NativeFloatBuffer.getPoolStats();
        // Should still have statistics but potentially fewer buffers in pools
        assertTrue(stats.contains("Buffer Pool Statistics:"));
    }

    @Test
    void testClearPools() {
        // Allocate and close some buffers to populate pools
        NativeFloatBuffer buffer1 = NativeFloatBuffer.allocateFromNative(100, 100);
        NativeFloatBuffer buffer2 = NativeFloatBuffer.allocateFromNative(200, 200);
        
        assertNotNull(buffer1);
        assertNotNull(buffer2);
        
        buffer1.close();
        buffer2.close();
        
        // Clear all pools
        NativeFloatBuffer.clearPools();
        
        String stats = NativeFloatBuffer.getPoolStats();
        // Should show no buffers in pools after clear
        assertFalse(stats.contains("Bucket 0 (4096 bytes): 1 buffers"));
        assertFalse(stats.contains("Bucket 1 (8192 bytes): 1 buffers"));
    }

    @Test
    void testMultipleBufferSizes() {
        // Test different buffer sizes to ensure bucket system works
        NativeFloatBuffer smallBuffer = NativeFloatBuffer.allocateFromNative(10, 10);   // 400 bytes
        NativeFloatBuffer mediumBuffer = NativeFloatBuffer.allocateFromNative(100, 100); // 40,000 bytes
        NativeFloatBuffer largeBuffer = NativeFloatBuffer.allocateFromNative(500, 500);  // 1,000,000 bytes
        
        assertNotNull(smallBuffer);
        assertNotNull(mediumBuffer);
        assertNotNull(largeBuffer);
        
        // Close them to return to pools
        smallBuffer.close();
        mediumBuffer.close();
        largeBuffer.close();
        
        // Allocate same sizes again to test reuse
        NativeFloatBuffer smallBuffer2 = NativeFloatBuffer.allocateFromNative(10, 10);
        NativeFloatBuffer mediumBuffer2 = NativeFloatBuffer.allocateFromNative(100, 100);
        NativeFloatBuffer largeBuffer2 = NativeFloatBuffer.allocateFromNative(500, 500);
        
        assertNotNull(smallBuffer2);
        assertNotNull(mediumBuffer2);
        assertNotNull(largeBuffer2);
        
        // Clean up
        smallBuffer2.close();
        mediumBuffer2.close();
        largeBuffer2.close();
    }

    // Helper method to extract total allocations from stats string
    private long getTotalAllocations(String stats) {
        String[] lines = stats.split("\n");
        for (String line : lines) {
            if (line.startsWith("Total Allocations: ")) {
                return Long.parseLong(line.substring("Total Allocations: ".length()));
            }
        }
        return 0;
    }
}