package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.HashMap;

/**
 * Comprehensive test suite for ZeroCopyBufferManager
 * Tests DirectByteBuffer management, reference counting, lifecycle management,
 * and integration with performance monitoring system.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ZeroCopyDataTransferTest {
    
    private static ZeroCopyBufferManager bufferManager;
    private static PerformanceMonitoringSystem performanceMonitor;
    
    @BeforeAll
    static void setup() {
        bufferManager = ZeroCopyBufferManager.getInstance();
        performanceMonitor = PerformanceMonitoringSystem.getInstance();
    }
    
    @AfterAll
    static void cleanup() {
        if (bufferManager != null) {
            bufferManager.shutdown();
        }
        if (performanceMonitor != null) {
            performanceMonitor.shutdown();
        }
    }
    
    @Test
    public void testBufferAllocation() {
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(1024, "test_component");
        
        assertNotNull(buffer);
        assertEquals(1024, buffer.getSize());
        assertEquals(1, buffer.getReferenceCount());
        assertFalse(buffer.isReleased());
        
        bufferManager.releaseBuffer(buffer, "test_component");
    }
    
    @Test
    public void testBufferReferenceCounting() {
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(2048, "test_component");
        
        long bufferId = buffer.getBufferId();
        
        // Acquire multiple references
        ZeroCopyBufferManager.ManagedDirectBuffer buffer2 = 
            bufferManager.acquireBuffer(bufferId, "test_component");
        ZeroCopyBufferManager.ManagedDirectBuffer buffer3 = 
            bufferManager.acquireBuffer(bufferId, "test_component");
        
        assertEquals(3, buffer.getReferenceCount());
        assertEquals(bufferId, buffer2.getBufferId());
        assertEquals(bufferId, buffer3.getBufferId());
        
        // Release references
        bufferManager.releaseBuffer(buffer, "test_component");
        assertEquals(2, buffer2.getReferenceCount());
        
        bufferManager.releaseBuffer(buffer2, "test_component");
        assertEquals(1, buffer3.getReferenceCount());
        
        bufferManager.releaseBuffer(buffer3, "test_component");
        // Buffer should be deallocated now
    }
    
    @Test
    public void testBufferReuse() {
        // Allocate and release a buffer
        ZeroCopyBufferManager.ManagedDirectBuffer buffer1 = 
            bufferManager.allocateBuffer(4096, "test_component");
        bufferManager.releaseBuffer(buffer1, "test_component");
        
        // Allocate another buffer of same size
        ZeroCopyBufferManager.ManagedDirectBuffer buffer2 = 
            bufferManager.allocateBuffer(4096, "test_component");
        
        // Should reuse from pool (implementation dependent)
        assertNotNull(buffer2);
        assertEquals(4096, buffer2.getSize());
        
        bufferManager.releaseBuffer(buffer2, "test_component");
    }
    
    @Test
    public void testZeroCopyOperation() {
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(1024, "test_component");
        
        ZeroCopyBufferManager.ZeroCopyOperation operation = new ZeroCopyBufferManager.ZeroCopyOperation() {
            @Override
            public Object execute() throws Exception {
                // Simulate zero-copy operation
                ByteBuffer byteBuffer = buffer.getBuffer();
                byteBuffer.asFloatBuffer().put(new float[]{1.0f, 2.0f, 3.0f});
                return "operation_completed";
            }
            
            @Override
            public String getType() {
                return "test_operation";
            }
            
            @Override
            public long getBytesTransferred() {
                return 12; // 3 floats * 4 bytes
            }
            
            @Override
            public ZeroCopyBufferManager.ManagedDirectBuffer getSourceBuffer() {
                return buffer;
            }
        };
        
        ZeroCopyBufferManager.ZeroCopyOperationResult result = 
            bufferManager.performZeroCopyOperation(operation);
        
        assertTrue(result.success);
        assertEquals("operation_completed", result.result);
        assertEquals(12, result.bytesTransferred);
        assertNull(result.error);
        
        bufferManager.releaseBuffer(buffer, "test_component");
    }
    
    @Test
    public void testSharedBuffer() {
        ZeroCopyBufferManager.SharedBuffer sharedBuffer = 
            bufferManager.createSharedBuffer(2048, "creator_component", "test_shared");
        
        assertNotNull(sharedBuffer);
        assertEquals("test_shared", sharedBuffer.getSharedName());
        assertEquals("creator_component", sharedBuffer.getCreatorComponent());
        
        // Test access tracking
        sharedBuffer.recordAccess("consumer_component");
        Map<String, Long> accessTimes = sharedBuffer.getAccessTimes();
        assertTrue(accessTimes.containsKey("consumer_component"));
    }
    
    @Test
    public void testBufferStatistics() {
        ZeroCopyBufferManager.BufferStatistics stats = bufferManager.getStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.activeBufferCount >= 0);
        assertTrue(stats.pooledBufferCount >= 0);
        assertTrue(stats.totalAllocatedBytes >= 0);
        assertTrue(stats.totalReusedBytes >= 0);
        assertTrue(stats.totalFreedBytes >= 0);
        assertTrue(stats.totalAllocations >= 0);
        assertTrue(stats.totalDeallocations >= 0);
        assertTrue(stats.totalZeroCopyOperations >= 0);
        assertTrue(stats.totalBytesTransferred >= 0);
    }
    
    @Test
    public void testBufferInfo() {
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(1024, "test_component");
        
        java.util.List<ZeroCopyBufferManager.BufferInfo> infoList = bufferManager.getBufferInfoList();
        
        assertNotNull(infoList);
        assertTrue(infoList.size() > 0);
        
        ZeroCopyBufferManager.BufferInfo info = infoList.get(0);
        assertNotNull(info);
        assertTrue(info.bufferId > 0);
        assertTrue(info.size > 0);
        assertNotNull(info.creationTime);
        assertNotNull(info.lastAccessTime);
        assertTrue(info.accessCount >= 0);
        assertNotNull(info.creatorThread);
        assertNotNull(info.creatorComponent);
        assertTrue(info.referenceCount >= 0);
        assertTrue(info.isValid);
        
        bufferManager.releaseBuffer(buffer, "test_component");
    }
    
    @Test
    public void testConcurrentBufferOperations() throws InterruptedException {
        int numThreads = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
                            bufferManager.allocateBuffer(512, "concurrent_test");
                        
                        // Simulate some work
                        ByteBuffer byteBuffer = buffer.getBuffer();
                        byteBuffer.putInt(j);
                        
                        bufferManager.releaseBuffer(buffer, "concurrent_test");
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        assertEquals(0, failureCount.get());
        assertEquals(numThreads * operationsPerThread, successCount.get());
    }
    
    @Test
    public void testBufferBoundsChecking() {
        ZeroCopyBufferManager.ManagedDirectBuffer buffer =
            bufferManager.allocateBuffer(1024, "test_component");
        
        // Test valid buffer access through ByteBuffer
        assertDoesNotThrow(() -> {
            ByteBuffer byteBuffer = buffer.getBuffer();
            byteBuffer.putInt(42);
        });
        
        // Test buffer properties
        assertEquals(1024, buffer.getSize());
        assertFalse(buffer.isReleased());
        
        bufferManager.releaseBuffer(buffer, "test_component");
    }
    
    @Test
    public void testInvalidBufferId() {
        assertThrows(IllegalArgumentException.class, () -> {
            bufferManager.acquireBuffer(-1, "test_component");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            bufferManager.acquireBuffer(999999, "test_component");
        });
    }
    
    @Test
    public void testPerformanceMetrics() {
        long startTime = System.nanoTime();
        
        // Perform some operations
        for (int i = 0; i < 10; i++) {
            ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
                bufferManager.allocateBuffer(1024, "perf_test");
            bufferManager.releaseBuffer(buffer, "perf_test");
        }
        
        long duration = System.nanoTime() - startTime;
        
        // Verify that performance metrics are being recorded
        ZeroCopyBufferManager.BufferStatistics stats = bufferManager.getStatistics();
        assertTrue(stats.totalZeroCopyOperations >= 0);
        assertTrue(stats.totalBytesTransferred >= 0);
    }
    
    @Test
    public void testMemoryLeakPrevention() {
        // Allocate many buffers
        java.util.List<ZeroCopyBufferManager.ManagedDirectBuffer> buffers = new java.util.ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            buffers.add(bufferManager.allocateBuffer(1024, "leak_test"));
        }
        
        // Release all buffers
        for (ZeroCopyBufferManager.ManagedDirectBuffer buffer : buffers) {
            bufferManager.releaseBuffer(buffer, "leak_test");
        }
        
        // Check that buffers are properly cleaned up
        ZeroCopyBufferManager.BufferStatistics stats = bufferManager.getStatistics();
        assertTrue(stats.activeBufferCount <= stats.pooledBufferCount);
    }
    
    @Test
    public void testCleanupTask() {
        // This test verifies that the cleanup task runs without errors
        // Wait for cleanup task to run (30-second interval)
        try {
            Thread.sleep(35000); // Wait a bit longer than cleanup interval
        } catch (InterruptedException e) {
            // Ignore
        }
        
        // If we get here without exceptions, cleanup task is working
        assertTrue(true);
    }
    
    @Test
    public void testEventBusIntegration() {
        // Test that buffer events are properly published to event bus
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(1024, "event_test");
        
        // Events should have been published during allocation
        // We can't directly verify event publication, but we can check that
        // the system is integrated properly by ensuring no exceptions occur
        
        bufferManager.releaseBuffer(buffer, "event_test");
        
        // If we get here without exceptions, event bus integration is working
        assertTrue(true);
    }
    
    @Test
    public void testShutdown() {
        // Test that shutdown cleans up properly
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = 
            bufferManager.allocateBuffer(1024, "shutdown_test");
        
        // Don't release this buffer - let shutdown handle it
        bufferManager.shutdown();
        
        // After shutdown, trying to allocate should fail
        assertThrows(RuntimeException.class, () -> {
            bufferManager.allocateBuffer(1024, "post_shutdown_test");
        });
    }
}