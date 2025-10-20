package com.kneaf.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple test suite for memory management optimizations
 * Tests basic functionality without complex dependencies
 */
public class SimpleMemoryOptimizationTest {
    
    private ZeroCopyBufferManager bufferManager;
    private static final int TEST_BUFFER_SIZE = 1024 * 1024; // 1MB
    
    @BeforeEach
    void setUp() {
        bufferManager = ZeroCopyBufferManager.getInstance();
    }
    
    @AfterEach
    void tearDown() {
        bufferManager.shutdown();
    }
    
    @Test
    void testBufferAllocationAndRelease() {
        // Test basic buffer allocation and release
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "test_component");
        
        assertNotNull(buffer, "Buffer should be allocated");
        assertEquals(TEST_BUFFER_SIZE, buffer.getSize(), "Buffer size should match requested size");
        assertEquals(1, buffer.getReferenceCount(), "Initial reference count should be 1");
        assertFalse(buffer.isReleased(), "Buffer should not be released initially");
        
        // Release buffer
        bufferManager.releaseBuffer(buffer, "test_component");
        
        // Buffer should be marked for cleanup
        assertTrue(buffer.isReleased(), "Buffer should be released after final release");
    }
    
    @Test
    void testBufferReuse() {
        // Test buffer reuse functionality
        ZeroCopyBufferManager.BufferStatistics initialStats = bufferManager.getStatistics();
        
        // Allocate and release a buffer
        ZeroCopyBufferManager.ManagedDirectBuffer buffer1 = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "reuse_test");
        bufferManager.releaseBuffer(buffer1, "reuse_test");
        
        // Allocate another buffer of same size - should reuse
        ZeroCopyBufferManager.ManagedDirectBuffer buffer2 = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "reuse_test");
        
        ZeroCopyBufferManager.BufferStatistics finalStats = bufferManager.getStatistics();
        
        // Check if reuse occurred
        assertTrue(finalStats.totalReusedBytes > 0, "Buffer reuse should be detected");
        
        // Cleanup
        bufferManager.releaseBuffer(buffer2, "reuse_test");
    }
    
    @Test
    void testConcurrentBufferOperations() throws InterruptedException {
        // Test concurrent access to buffer manager
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        AtomicLong successCount = new AtomicLong(0);
        
        for (int t = 0; t < 4; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
                            bufferManager.allocateBuffer(TEST_BUFFER_SIZE / 4, "concurrent_test");
                        
                        // Simulate some work
                        ByteBuffer byteBuffer = buffer.getBuffer();
                        byteBuffer.putInt(i);
                        
                        bufferManager.releaseBuffer(buffer, "concurrent_test");
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore errors for this test
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(40, successCount.get(), "All concurrent operations should succeed");
    }
    
    @Test
    void testBufferStatistics() {
        // Test buffer statistics collection
        ZeroCopyBufferManager.BufferStatistics stats = bufferManager.getStatistics();
        
        assertNotNull(stats, "Statistics should not be null");
        assertTrue(stats.activeBufferCount >= 0, "Active buffer count should be non-negative");
        assertTrue(stats.totalAllocations >= 0, "Total allocations should be non-negative");
        assertTrue(stats.totalAllocatedBytes >= 0, "Total allocated bytes should be non-negative");
        
        // Test throughput calculation
        double throughputMB = stats.getThroughputMB();
        assertTrue(throughputMB >= 0, "Throughput should be non-negative");
        
        // Test reuse rate calculation
        double reuseRate = stats.getReuseRate();
        assertTrue(reuseRate >= 0 && reuseRate <= 1.0, "Reuse rate should be between 0 and 1");
    }
    
    @Test
    void testMemoryMappedBufferCreation() throws Exception {
        // Test memory-mapped file buffer creation
        String testFile = "test_simple_mmap.dat";
        
        try {
            ZeroCopyBufferManager.ManagedDirectBuffer mmapBuffer = 
                bufferManager.createMemoryMappedBuffer(testFile, TEST_BUFFER_SIZE, "mmap_test");
            
            assertNotNull(mmapBuffer, "Memory-mapped buffer should be created");
            assertEquals(TEST_BUFFER_SIZE, mmapBuffer.getSize(), "Buffer size should match requested size");
            
            // Test data persistence
            ByteBuffer buffer = mmapBuffer.getBuffer();
            buffer.putInt(42);
            buffer.putInt(123);
            
            // Create another buffer from same file to verify persistence
            ZeroCopyBufferManager.ManagedDirectBuffer mmapBuffer2 = 
                bufferManager.createMemoryMappedBuffer(testFile, TEST_BUFFER_SIZE, "mmap_test2");
            
            ByteBuffer buffer2 = mmapBuffer2.getBuffer();
            assertEquals(42, buffer2.getInt(), "First value should persist in memory-mapped file");
            assertEquals(123, buffer2.getInt(), "Second value should persist in memory-mapped file");
            
            // Cleanup
            bufferManager.releaseBuffer(mmapBuffer, "mmap_test");
            bufferManager.releaseBuffer(mmapBuffer2, "mmap_test2");
            
        } finally {
            // Clean up test file
            java.io.File file = new java.io.File(testFile);
            if (file.exists()) {
                file.delete();
            }
        }
    }
    
    @Test
    void testBufferMetadata() {
        // Test buffer metadata tracking
        String testComponent = "metadata_test";
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, testComponent);
        
        // Get buffer info
        java.util.List<ZeroCopyBufferManager.BufferInfo> bufferInfo = bufferManager.getBufferInfoList();
        
        assertFalse(bufferInfo.isEmpty(), "Buffer info list should not be empty");
        
        ZeroCopyBufferManager.BufferInfo info = bufferInfo.get(0);
        assertEquals(buffer.getBufferId(), info.bufferId, "Buffer ID should match");
        assertEquals(TEST_BUFFER_SIZE, info.size, "Buffer size should match");
        assertEquals(testComponent, info.creatorComponent, "Creator component should match");
        assertTrue(info.isValid, "Buffer should be valid");
        assertEquals(1, info.referenceCount, "Reference count should be 1");
        
        // Cleanup
        bufferManager.releaseBuffer(buffer, testComponent);
    }
}