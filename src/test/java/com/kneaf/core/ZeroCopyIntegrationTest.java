package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import com.kneaf.core.performance.CrossComponentEventBus;
import com.kneaf.core.performance.CrossComponentEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Comprehensive integration test for the zero-copy data transfer system.
 * Tests integration with ParallelRustVectorProcessor, EnhancedRustVectorLibrary,
 * PerformanceMonitoringSystem, and CrossComponentEventBus.
 */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ZeroCopyIntegrationTest {
    
    private ZeroCopyBufferManager bufferManager;
    private ZeroCopyDataTransfer dataTransfer;
    private PerformanceMonitoringSystem performanceMonitor;
    private CrossComponentEventBus eventBus;
    
    @BeforeAll
    void setup() {
        bufferManager = ZeroCopyBufferManager.getInstance();
        dataTransfer = ZeroCopyDataTransfer.getInstance();
        performanceMonitor = PerformanceMonitoringSystem.getInstance();
        eventBus = performanceMonitor.getEventBus();
        
        // Initialize component integration
        dataTransfer.initializeComponentIntegration();
        
        // Verify native library is loaded
        assertTrue(EnhancedRustVectorLibrary.isLibraryLoaded(), "Native library must be loaded for zero-copy tests");
    }
    
    @AfterAll
    void cleanup() {
        if (bufferManager != null) {
            bufferManager.shutdown();
        }
        if (dataTransfer != null) {
            dataTransfer.shutdown();
        }
        if (performanceMonitor != null) {
            performanceMonitor.shutdown();
        }
    }
    
    @Test
    public void testZeroCopyMatrixMultiplicationIntegration() throws Exception {
        // Test data
        float[] matrixA = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        
        float[] matrixB = {
            2.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 2.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 2.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 2.0f
        };
        
        // Perform zero-copy matrix multiplication
        CompletableFuture<float[]> future = EnhancedRustVectorLibrary.matrixMultiplyZeroCopyEnhanced(matrixA, matrixB, "nalgebra");
        float[] result = future.get(5, TimeUnit.SECONDS);
        
        // Verify result (should be identity * 2 = 2 * identity)
        assertNotNull(result);
        assertEquals(16, result.length);
        
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                float expected = (i == j) ? 2.0f : 0.0f;
                assertEquals(expected, result[i * 4 + j], 0.001f, 
                    "Matrix multiplication result incorrect at position [" + i + "," + j + "]");
            }
        }
        
        // Verify performance metrics were recorded
        ZeroCopyBufferManager.BufferStatistics bufferStats = bufferManager.getStatistics();
        assertTrue(bufferStats.totalZeroCopyOperations > 0, "Zero-copy operations should be recorded");
        
        ZeroCopyDataTransfer.TransferStatistics transferStats = dataTransfer.getStatistics();
        assertTrue(transferStats.totalTransfers > 0, "Transfers should be recorded");
    }
    
    @Test
    public void testZeroCopyBatchMatrixMultiplication() throws Exception {
        // Create test matrices
        List<float[]> matricesA = new ArrayList<>();
        List<float[]> matricesB = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            float[] matrixA = new float[16];
            float[] matrixB = new float[16];
            
            // Create identity matrices with scaling factors
            for (int j = 0; j < 4; j++) {
                matrixA[j * 4 + j] = (float)(i + 1);
                matrixB[j * 4 + j] = 2.0f;
            }
            
            matricesA.add(matrixA);
            matricesB.add(matrixB);
        }
        
        // Perform batch zero-copy matrix multiplication
        CompletableFuture<List<float[]>> future = EnhancedRustVectorLibrary.batchMatrixMultiplyZeroCopyEnhanced(
            matricesA, matricesB, "nalgebra");
        List<float[]> results = future.get(10, TimeUnit.SECONDS);
        
        // Verify results
        assertNotNull(results);
        assertEquals(10, results.size());
        
        for (int i = 0; i < 10; i++) {
            float[] result = results.get(i);
            assertNotNull(result);
            assertEquals(16, result.length);
            
            // Expected result: (i+1) * 2 = 2*(i+1) on diagonal
            float expectedScale = 2.0f * (i + 1);
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    float expected = (j == k) ? expectedScale : 0.0f;
                    assertEquals(expected, result[j * 4 + k], 0.001f,
                        "Batch matrix multiplication result incorrect for matrix " + i + " at position [" + j + "," + k + "]");
                }
            }
        }
    }
    
    @Test
    public void testZeroCopyVectorOperations() throws Exception {
        // Test data
        float[] vectorA = {1.0f, 2.0f, 3.0f};
        float[] vectorB = {4.0f, 5.0f, 6.0f};
        
        // Test vector addition
        CompletableFuture<float[]> addFuture = EnhancedRustVectorLibrary.vectorAddZeroCopy(vectorA, vectorB, "nalgebra");
        float[] addResult = addFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(addResult);
        assertEquals(3, addResult.length);
        assertEquals(5.0f, addResult[0], 0.001f);
        assertEquals(7.0f, addResult[1], 0.001f);
        assertEquals(9.0f, addResult[2], 0.001f);
        
        // Test vector dot product
        CompletableFuture<Float> dotFuture = EnhancedRustVectorLibrary.vectorDotZeroCopy(vectorA, vectorB, "glam");
        Float dotResult = dotFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(dotResult);
        float expectedDot = 1.0f * 4.0f + 2.0f * 5.0f + 3.0f * 6.0f; // 4 + 10 + 18 = 32
        assertEquals(expectedDot, dotResult, 0.001f);
        
        // Test vector cross product
        CompletableFuture<float[]> crossFuture = EnhancedRustVectorLibrary.vectorCrossZeroCopy(vectorA, vectorB, "glam");
        float[] crossResult = crossFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(crossResult);
        assertEquals(3, crossResult.length);
        // Cross product of [1,2,3] and [4,5,6] should be [-3, 6, -3]
        assertEquals(-3.0f, crossResult[0], 0.001f);
        assertEquals(6.0f, crossResult[1], 0.001f);
        assertEquals(-3.0f, crossResult[2], 0.001f);
    }
    
    @Test
    public void testSharedBufferCrossComponentCommunication() throws Exception {
        // Create shared buffer
        ZeroCopyBufferManager.SharedBuffer sharedBuffer = 
            EnhancedRustVectorLibrary.createSharedBuffer(1024, "ComponentA", "shared_matrix_data");
        
        assertNotNull(sharedBuffer);
        assertEquals("shared_matrix_data", sharedBuffer.getSharedName());
        assertEquals("ComponentA", sharedBuffer.getCreatorComponent());
        
        // Record access from different components
        sharedBuffer.recordAccess("ComponentB");
        sharedBuffer.recordAccess("ComponentC");
        
        // Verify access tracking
        Map<String, Long> accessTimes = sharedBuffer.getAccessTimes();
        assertEquals(2, accessTimes.size());
        assertTrue(accessTimes.containsKey("ComponentB"));
        assertTrue(accessTimes.containsKey("ComponentC"));
        
        // Test buffer access
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = sharedBuffer.getBuffer();
        assertNotNull(buffer);
        assertFalse(buffer.isReleased());
        
        // Write some data
        ByteBuffer byteBuffer = buffer.getBuffer();
        byteBuffer.asFloatBuffer().put(new float[]{1.0f, 2.0f, 3.0f, 4.0f});
        
        // Verify data integrity
        byteBuffer.rewind();
        float[] readData = new float[4];
        byteBuffer.asFloatBuffer().get(readData);
        
        assertEquals(1.0f, readData[0], 0.001f);
        assertEquals(2.0f, readData[1], 0.001f);
        assertEquals(3.0f, readData[2], 0.001f);
        assertEquals(4.0f, readData[3], 0.001f);
    }
    
    @Test
    public void testEventBusIntegration() throws Exception {
        // Subscribe to zero-copy events
        CountDownLatch eventLatch = new CountDownLatch(3);
        List<CrossComponentEvent> receivedEvents = new ArrayList<>();
        
        eventBus.subscribe("ZeroCopyIntegrationTest", "transfer_completed", new CrossComponentEventBus.EventSubscriber() {
            @Override
            public void onEvent(CrossComponentEvent event) {
                receivedEvents.add(event);
                eventLatch.countDown();
            }
        });
        
        // Perform zero-copy operations that should generate events
        float[] vectorA = {1.0f, 2.0f, 3.0f};
        float[] vectorB = {4.0f, 5.0f, 6.0f};
        
        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> future1 = 
            dataTransfer.transferVectorData("TestComponentA", "TestComponentB", "vector_add", 
                vectorA, vectorB, "nalgebra");
        
        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> future2 = 
            dataTransfer.transferVectorData("TestComponentC", "TestComponentD", "vector_dot", 
                vectorA, vectorB, "glam");
        
        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> future3 = 
            dataTransfer.transferVectorData("TestComponentE", "TestComponentF", "vector_cross", 
                vectorA, vectorB, "glam");
        
        // Wait for operations to complete
        future1.get(5, TimeUnit.SECONDS);
        future2.get(5, TimeUnit.SECONDS);
        future3.get(5, TimeUnit.SECONDS);
        
        // Wait for events
        boolean eventsReceived = eventLatch.await(10, TimeUnit.SECONDS);
        assertTrue(eventsReceived, "Expected events should be received");
        
        // Verify event content
        assertEquals(3, receivedEvents.size());
        for (CrossComponentEvent event : receivedEvents) {
            assertEquals("ZeroCopyDataTransfer", event.getComponent());
            assertEquals("transfer_completed", event.getEventType());
            assertTrue(event.getDurationNs() > 0);
            
            Map<String, Object> context = event.getContext();
            assertTrue(context.containsKey("source_component"));
            assertTrue(context.containsKey("target_component"));
            assertTrue(context.containsKey("operation_type"));
            assertTrue(context.containsKey("success"));
            assertTrue(context.get("success") instanceof Boolean);
            assertTrue((Boolean) context.get("success"));
        }
    }
    
    @Test
    public void testConcurrentZeroCopyOperations() throws Exception {
        int numThreads = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Create test vectors
                        float[] vectorA = {(float)threadId, (float)j, (float)(threadId + j)};
                        float[] vectorB = {(float)(threadId * 2), (float)(j * 2), (float)((threadId + j) * 2)};
                        
                        // Perform zero-copy vector addition
                        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> future = 
                            dataTransfer.transferVectorData(
                                "Thread" + threadId, "RustComponent", "vector_add", 
                                vectorA, vectorB, "nalgebra");
                        
                        ZeroCopyDataTransfer.ZeroCopyTransferResult result = future.get(1, TimeUnit.SECONDS);
                        
                        if (result.success) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
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
        
        // Verify results
        assertEquals(0, failureCount.get(), "No failures should occur during concurrent operations");
        assertEquals(numThreads * operationsPerThread, successCount.get(), 
            "All operations should succeed");
        
        // Verify statistics were updated
        ZeroCopyDataTransfer.TransferStatistics stats = dataTransfer.getStatistics();
        assertTrue(stats.totalTransfers >= numThreads * operationsPerThread);
    }
    
    @Test
    public void testPerformanceMetricsIntegration() throws Exception {
        // Get initial statistics
        ZeroCopyBufferManager.BufferStatistics initialBufferStats = bufferManager.getStatistics();
        ZeroCopyDataTransfer.TransferStatistics initialTransferStats = dataTransfer.getStatistics();
        
        // Perform operations
        float[] matrixA = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        float[] matrixB = {
            2.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 2.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 2.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 2.0f
        };
        
        CompletableFuture<float[]> future = EnhancedRustVectorLibrary.matrixMultiplyZeroCopyEnhanced(matrixA, matrixB, "nalgebra");
        future.get(5, TimeUnit.SECONDS);
        
        // Get final statistics
        ZeroCopyBufferManager.BufferStatistics finalBufferStats = bufferManager.getStatistics();
        ZeroCopyDataTransfer.TransferStatistics finalTransferStats = dataTransfer.getStatistics();
        
        // Verify metrics were updated
        assertTrue(finalBufferStats.totalZeroCopyOperations > initialBufferStats.totalZeroCopyOperations,
            "Buffer operations should increase");
        assertTrue(finalTransferStats.totalTransfers > initialTransferStats.totalTransfers,
            "Transfer operations should increase");
        assertTrue(finalTransferStats.totalBytesTransferred > initialTransferStats.totalBytesTransferred,
            "Bytes transferred should increase");
        
        // Verify throughput metrics
        if (finalTransferStats.totalTransfers > 0) {
            double averageThroughput = finalTransferStats.getAverageThroughputMBps();
            assertTrue(averageThroughput > 0, "Average throughput should be positive");
        }
    }
    
    @Test
    public void testMemoryLeakPrevention() throws Exception {
        // Get initial buffer statistics
        ZeroCopyBufferManager.BufferStatistics initialStats = bufferManager.getStatistics();
        
        // Perform many allocations and releases
        List<ZeroCopyBufferManager.ManagedDirectBuffer> buffers = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            buffers.add(bufferManager.allocateBuffer(1024, "leak_test"));
        }
        
        // Verify buffers were allocated
        ZeroCopyBufferManager.BufferStatistics duringStats = bufferManager.getStatistics();
        assertTrue(duringStats.activeBufferCount > initialStats.activeBufferCount);
        
        // Release all buffers
        for (ZeroCopyBufferManager.ManagedDirectBuffer buffer : buffers) {
            bufferManager.releaseBuffer(buffer, "leak_test");
        }
        
        // Wait for cleanup
        Thread.sleep(1000);
        
        // Verify cleanup occurred
        ZeroCopyBufferManager.BufferStatistics finalStats = bufferManager.getStatistics();
        assertTrue(finalStats.activeBufferCount <= duringStats.activeBufferCount);
        
        // Test that the system can still allocate buffers
        ZeroCopyBufferManager.ManagedDirectBuffer testBuffer = bufferManager.allocateBuffer(512, "post_leak_test");
        assertNotNull(testBuffer);
        bufferManager.releaseBuffer(testBuffer, "post_leak_test");
    }
    
    @Test
    public void testErrorHandlingAndRecovery() throws Exception {
        // Test with invalid parameters
        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> invalidFuture =
            dataTransfer.transferData("Source", "Target", "invalid_operation", null, 0, 100);
        
        ZeroCopyDataTransfer.ZeroCopyTransferResult result = invalidFuture.get(5, TimeUnit.SECONDS);
        
        assertFalse(result.success, "Operation should fail with invalid parameters");
        assertNotNull(result.error, "Error should be recorded");
        
        // Test with invalid buffer access
        ZeroCopyBufferManager.ManagedDirectBuffer buffer = bufferManager.allocateBuffer(100, "test");
        
        CompletableFuture<ZeroCopyDataTransfer.ZeroCopyTransferResult> oversizedFuture =
            dataTransfer.transferData("Source", "Target", "data_copy", buffer, 0, 200); // Request more data than buffer size
        
        ZeroCopyDataTransfer.ZeroCopyTransferResult oversizedResult = oversizedFuture.get(5, TimeUnit.SECONDS);
        
        assertFalse(oversizedResult.success, "Operation should fail with oversized data request");
        assertNotNull(oversizedResult.error, "Error should be recorded for oversized data");
        
        bufferManager.releaseBuffer(buffer, "test");
    }
}