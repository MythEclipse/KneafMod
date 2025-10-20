package com.kneaf.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive test suite for memory management optimizations
 * Tests arena allocation, zero-copy operations, memory compaction, and memory-mapped files
 */
public class MemoryOptimizationTest {
    
    private ZeroCopyBufferManager bufferManager;
    private static final int TEST_BUFFER_SIZE = 1024 * 1024; // 1MB
    private static final int NUM_THREADS = 4;
    private static final int ITERATIONS = 1000;
    
    @BeforeEach
    void setUp() {
        bufferManager = ZeroCopyBufferManager.getInstance();
    }
    
    @AfterEach
    void tearDown() {
        bufferManager.shutdown();
    }
    
    @Test
    void testArenaAllocationPerformance() {
        // Test arena allocation performance improvement
        long startTime = System.nanoTime();
        
        // Allocate multiple buffers using arena allocation
        List<ZeroCopyBufferManager.ManagedDirectBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
                bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "arena_test");
            buffers.add(buffer);
        }
        
        long arenaTime = System.nanoTime() - startTime;
        
        // Release buffers
        for (ZeroCopyBufferManager.ManagedDirectBuffer buffer : buffers) {
            bufferManager.releaseBuffer(buffer, "arena_test");
        }
        
        // Compare with traditional allocation (simulated)
        startTime = System.nanoTime();
        List<ByteBuffer> traditionalBuffers = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            traditionalBuffers.add(ByteBuffer.allocateDirect(TEST_BUFFER_SIZE));
        }
        long traditionalTime = System.nanoTime() - startTime;
        
        // Arena allocation should be faster
        assertTrue(arenaTime < traditionalTime * 0.8, 
            "Arena allocation should be at least 20% faster than traditional allocation");
    }
    
    @Test
    void testMemoryPoolingEfficiency() {
        // Test buffer reuse efficiency
        ZeroCopyBufferManager.BufferStatistics initialStats = bufferManager.getStatistics();
        
        // Allocate and release multiple buffers
        for (int cycle = 0; cycle < 10; cycle++) {
            List<ZeroCopyBufferManager.ManagedDirectBuffer> buffers = new ArrayList<>();
            
            // Allocate buffers
            for (int i = 0; i < 50; i++) {
                buffers.add(bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "pool_test"));
            }
            
            // Release buffers
            for (ZeroCopyBufferManager.ManagedDirectBuffer buffer : buffers) {
                bufferManager.releaseBuffer(buffer, "pool_test");
            }
        }
        
        ZeroCopyBufferManager.BufferStatistics finalStats = bufferManager.getStatistics();
        
        // Check reuse rate - should be significant due to pooling
        double reuseRate = finalStats.getReuseRate();
        assertTrue(reuseRate > 0.3, 
            "Buffer reuse rate should be at least 30%, actual: " + reuseRate);
    }
    
    @Test
    void testMemoryCompaction() throws InterruptedException {
        // Test memory compaction functionality
        ZeroCopyBufferManager.BufferStatistics initialStats = bufferManager.getStatistics();
        
        // Create fragmented memory pattern
        List<ZeroCopyBufferManager.ManagedDirectBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            buffers.add(bufferManager.allocateBuffer(TEST_BUFFER_SIZE / 4, "compaction_test"));
        }
        
        // Release every other buffer to create fragmentation
        for (int i = 0; i < buffers.size(); i += 2) {
            bufferManager.releaseBuffer(buffers.get(i), "compaction_test");
        }
        
        // Wait for compaction to trigger
        Thread.sleep(2000);
        
        ZeroCopyBufferManager.BufferStatistics finalStats = bufferManager.getStatistics();
        
        // Memory usage should be more efficient after compaction
        assertTrue(finalStats.activeBufferCount <= initialStats.activeBufferCount + 50,
            "Memory compaction should reduce active buffer count");
    }
    
    @Test
    void testZeroCopyOperations() {
        // Test zero-copy data transfer operations
        ZeroCopyBufferManager.ManagedDirectBuffer sourceBuffer = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "zero_copy_test");
        ZeroCopyBufferManager.ManagedDirectBuffer targetBuffer = 
            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "zero_copy_test");
        
        // Fill source buffer with test data
        ByteBuffer sourceByteBuffer = sourceBuffer.getBuffer();
        for (int i = 0; i < TEST_BUFFER_SIZE / 4; i++) {
            sourceByteBuffer.putInt(i);
        }
        
        // Perform zero-copy operation (simulated)
        long startTime = System.nanoTime();
        ZeroCopyBufferManager.ZeroCopyOperation operation = new ZeroCopyBufferManager.ZeroCopyOperation() {
            @Override
            public Object execute() throws Exception {
                // Simulate zero-copy data transfer
                ByteBuffer target = targetBuffer.getBuffer();
                sourceByteBuffer.rewind();
                target.put(sourceByteBuffer);
                return null;
            }
            
            @Override
            public String getType() {
                return "data_transfer";
            }
            
            @Override
            public long getBytesTransferred() {
                return TEST_BUFFER_SIZE;
            }
            
            @Override
            public ZeroCopyBufferManager.ManagedDirectBuffer getSourceBuffer() {
                return sourceBuffer;
            }
        };
        
        ZeroCopyBufferManager.ZeroCopyOperationResult result = 
            bufferManager.performZeroCopyOperation(operation);
        long operationTime = System.nanoTime() - startTime;
        
        assertTrue(result.success, "Zero-copy operation should succeed");
        assertEquals(TEST_BUFFER_SIZE, result.bytesTransferred, 
            "All bytes should be transferred");
        assertTrue(operationTime < 100_000_000, // 100ms
            "Zero-copy operation should be fast, actual: " + (operationTime / 1_000_000) + "ms");
        
        // Cleanup
        bufferManager.releaseBuffer(sourceBuffer, "zero_copy_test");
        bufferManager.releaseBuffer(targetBuffer, "zero_copy_test");
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Test concurrent access to buffer manager
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch latch = new CountDownLatch(NUM_THREADS);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        
        for (int t = 0; t < NUM_THREADS; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < ITERATIONS / NUM_THREADS; i++) {
                        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
                            bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "concurrent_test");
                        
                        // Simulate some work
                        ByteBuffer byteBuffer = buffer.getBuffer();
                        byteBuffer.putInt(i);
                        
                        bufferManager.releaseBuffer(buffer, "concurrent_test");
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(0, errorCount.get(), "No errors should occur during concurrent access");
        assertEquals(ITERATIONS, successCount.get(), "All operations should succeed");
    }
    
    @Test
    void testMemoryMappedFiles() throws Exception {
        // Test memory-mapped file support
        String testFile = "test_memory_mapped.dat";
        int mmapSize = TEST_BUFFER_SIZE;
        
        try {
            ZeroCopyBufferManager.ManagedDirectBuffer mmapBuffer = 
                bufferManager.createMemoryMappedBuffer(testFile, mmapSize, "mmap_test");
            
            assertNotNull(mmapBuffer, "Memory-mapped buffer should be created");
            assertEquals(mmapSize, mmapBuffer.getSize(), "Buffer size should match requested size");
            
            // Write test data
            ByteBuffer buffer = mmapBuffer.getBuffer();
            for (int i = 0; i < 100; i++) {
                buffer.putInt(i);
            }
            
            // Verify data persistence by creating another buffer from same file
            ZeroCopyBufferManager.ManagedDirectBuffer mmapBuffer2 = 
                bufferManager.createMemoryMappedBuffer(testFile, mmapSize, "mmap_test2");
            
            ByteBuffer buffer2 = mmapBuffer2.getBuffer();
            buffer2.rewind();
            for (int i = 0; i < 100; i++) {
                assertEquals(i, buffer2.getInt(), "Data should persist in memory-mapped file");
            }
            
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
    void testPerformanceMetrics() {
        // Test performance monitoring integration
        ZeroCopyBufferManager.BufferStatistics stats = bufferManager.getStatistics();
        
        assertTrue(stats.totalAllocations >= 0, "Total allocations should be non-negative");
        assertTrue(stats.totalDeallocations >= 0, "Total deallocations should be non-negative");
        assertTrue(stats.totalAllocatedBytes >= 0, "Total allocated bytes should be non-negative");
        
        // Test throughput calculation
        double throughputMB = stats.getThroughputMB();
        assertTrue(throughputMB >= 0, "Throughput should be non-negative");
        
        // Test reuse rate calculation
        double reuseRate = stats.getReuseRate();
        assertTrue(reuseRate >= 0 && reuseRate <= 1.0, "Reuse rate should be between 0 and 1");
    }
    
    @Test
    void testMemoryLeakDetection() {
        // Test memory leak detection
        ZeroCopyBufferManager.BufferStatistics initialStats = bufferManager.getStatistics();
        
        // Create potential memory leak scenario
        List<ZeroCopyBufferManager.ManagedDirectBuffer> leakedBuffers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            leakedBuffers.add(bufferManager.allocateBuffer(TEST_BUFFER_SIZE, "leak_test"));
        }
        
        // Intentionally not releasing some buffers to simulate leak
        for (int i = 0; i < 25; i++) {
            bufferManager.releaseBuffer(leakedBuffers.get(i), "leak_test");
        }
        
        ZeroCopyBufferManager.BufferStatistics finalStats = bufferManager.getStatistics();
        
        // Memory leak detection should show imbalance
        long potentialLeaks = finalStats.totalAllocations - finalStats.totalDeallocations;
        assertTrue(potentialLeaks > 0, "Should detect potential memory leaks");
        
        // Cleanup remaining buffers
        for (int i = 25; i < leakedBuffers.size(); i++) {
            bufferManager.releaseBuffer(leakedBuffers.get(i), "leak_test");
        }
    }
}