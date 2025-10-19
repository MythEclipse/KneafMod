package com.kneaf.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating parallel processing capabilities.
 * Tests all major features: batch processing, zero-copy operations, thread safety, and performance.
 */
public class ParallelProcessingIntegrationTest {

    private static final int LARGE_BATCH_SIZE = 1000;
    private static final int MEDIUM_BATCH_SIZE = 100;
    private static final int SMALL_BATCH_SIZE = 10;

    @BeforeAll
    public static void setUp() {
        System.setProperty("rust.test.mode", "true");
        System.out.println("=== Parallel Processing Integration Test Starting ===");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
    }

    @AfterAll
    public static void tearDown() {
        EnhancedRustVectorLibrary.shutdown();
        System.clearProperty("rust.test.mode");
        System.out.println("=== Parallel Processing Integration Test Completed ===");
    }

    @Test
    @Timeout(60)
    public void testCompleteParallelProcessingWorkflow() throws Exception {
        System.out.println("\n1. Testing complete parallel processing workflow...");

        // Create test data
        List<float[]> matrices = createIdentityMatrices(LARGE_BATCH_SIZE);
        List<float[]> vectors = createTestVectors(LARGE_BATCH_SIZE);

        // Test 1: Batch matrix multiplication
        long startTime = System.currentTimeMillis();
        CompletableFuture<List<float[]>> matrixResults = 
            EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matrices, matrices);
        List<float[]> matrixMultiplicationResults = matrixResults.get(30, TimeUnit.SECONDS);
        long matrixTime = System.currentTimeMillis() - startTime;

        assertNotNull(matrixMultiplicationResults);
        assertEquals(LARGE_BATCH_SIZE, matrixMultiplicationResults.size());
        System.out.println("   ✓ Batch matrix multiplication completed in " + matrixTime + "ms");

        // Test 2: Batch vector operations
        startTime = System.currentTimeMillis();
        CompletableFuture<List<float[]>> vectorAddResults = 
            EnhancedRustVectorLibrary.batchVectorAddNalgebra(vectors, vectors);
        List<float[]> vectorAdditionResults = vectorAddResults.get(30, TimeUnit.SECONDS);
        long vectorTime = System.currentTimeMillis() - startTime;

        assertNotNull(vectorAdditionResults);
        assertEquals(LARGE_BATCH_SIZE, vectorAdditionResults.size());
        System.out.println("   ✓ Batch vector addition completed in " + vectorTime + "ms");

        // Test 3: Batch dot products
        startTime = System.currentTimeMillis();
        CompletableFuture<List<Float>> dotResults = 
            EnhancedRustVectorLibrary.batchVectorDotGlam(vectors, vectors);
        List<Float> dotProductResults = dotResults.get(30, TimeUnit.SECONDS);
        long dotTime = System.currentTimeMillis() - startTime;

        assertNotNull(dotProductResults);
        assertEquals(LARGE_BATCH_SIZE, dotProductResults.size());
        System.out.println("   ✓ Batch vector dot products completed in " + dotTime + "ms");

        // Test 4: Zero-copy operations
        startTime = System.currentTimeMillis();
        float[] zeroCopyResult = EnhancedRustVectorLibrary.matrixMultiplyZeroCopy(
            createIdentityMatrix(), createIdentityMatrix(), "nalgebra");
        long zeroCopyTime = System.currentTimeMillis() - startTime;

        assertNotNull(zeroCopyResult);
        assertEquals(16, zeroCopyResult.length);
        System.out.println("   ✓ Zero-copy matrix multiplication completed in " + zeroCopyTime + "ms");

        // Test 5: Parallel single operations
        startTime = System.currentTimeMillis();
        CompletableFuture<float[]> parallelResult = EnhancedRustVectorLibrary.parallelMatrixMultiply(
            createIdentityMatrix(), createIdentityMatrix(), "nalgebra");
        float[] parallelMatrixResult = parallelResult.get(5, TimeUnit.SECONDS);
        long parallelTime = System.currentTimeMillis() - startTime;

        assertNotNull(parallelMatrixResult);
        assertEquals(16, parallelMatrixResult.length);
        System.out.println("   ✓ Parallel matrix multiplication completed in " + parallelTime + "ms");

        // Performance summary
        long totalTime = matrixTime + vectorTime + dotTime + zeroCopyTime + parallelTime;
        System.out.println("   Total parallel processing time: " + totalTime + "ms");
        System.out.println("   Average operation time: " + (totalTime / 5) + "ms");

        // Verify queue statistics
        ParallelRustVectorProcessor.QueueStatistics stats = EnhancedRustVectorLibrary.getQueueStatistics();
        System.out.println("   Queue statistics: pending=" + stats.pendingOperations + 
                          ", total=" + stats.totalOperations + 
                          ", active_threads=" + stats.activeThreads);
    }

    @Test
    @Timeout(30)
    public void testHighConcurrencyScenario() throws Exception {
        System.out.println("\n2. Testing high concurrency scenario...");

        int numThreads = Runtime.getRuntime().availableProcessors() * 2;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int threadId = 0; threadId < numThreads; threadId++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    try {
                        // Mix different types of operations
                        if (i % 3 == 0) {
                            // Matrix multiplication
                            CompletableFuture<float[]> result = EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                createIdentityMatrix(), createIdentityMatrix(), "nalgebra");
                            float[] matrixResult = result.get(1, TimeUnit.SECONDS);
                            assertNotNull(matrixResult);
                        } else if (i % 3 == 1) {
                            // Vector addition
                            CompletableFuture<float[]> result = EnhancedRustVectorLibrary.parallelVectorAdd(
                                createTestVector(), createTestVector(), "nalgebra");
                            float[] vectorResult = result.get(1, TimeUnit.SECONDS);
                            assertNotNull(vectorResult);
                        } else {
                            // Vector dot product
                            CompletableFuture<Float> result = EnhancedRustVectorLibrary.parallelVectorDot(
                                createTestVector(), createTestVector(), "glam");
                            Float dotResult = result.get(1, TimeUnit.SECONDS);
                            assertNotNull(dotResult);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Concurrent operation failed", e);
                    }
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all concurrent operations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(25, TimeUnit.SECONDS);

        System.out.println("   ✓ High concurrency scenario completed successfully");
        System.out.println("   ✓ Processed " + (numThreads * operationsPerThread) + " concurrent operations");

        executor.shutdown();
    }

    @Test
    @Timeout(30)
    public void testBatchProcessingWithMixedOperations() throws Exception {
        System.out.println("\n3. Testing batch processing with mixed operations...");

        // Create a mixed batch of operations
        EnhancedRustVectorLibrary.BatchProcessingRequest request = new EnhancedRustVectorLibrary.BatchProcessingRequest();

        // Add matrix operations
        for (int i = 0; i < 20; i++) {
            request.addOperation("matrix_mul_nalgebra", createIdentityMatrix(), createIdentityMatrix());
            request.addOperation("matrix_mul_glam", createIdentityMatrix(), createIdentityMatrix());
            request.addOperation("matrix_mul_faer", createIdentityMatrix(), createIdentityMatrix());
        }

        // Add vector operations
        for (int i = 0; i < 30; i++) {
            request.addOperation("vector_add_nalgebra", createTestVector(), createTestVector());
            request.addOperation("vector_dot_glam", createTestVector(), createTestVector());
            request.addOperation("vector_cross_glam", createTestVector(), createTestVector());
        }

        // Process the mixed batch
        long startTime = System.currentTimeMillis();
        EnhancedRustVectorLibrary.BatchProcessingResult result = EnhancedRustVectorLibrary.processBatch(request);
        long processingTime = System.currentTimeMillis() - startTime;

        // Verify results
        assertNotNull(result);
        assertNull(result.error);
        assertTrue(result.successfulOperations > 0);
        assertEquals(0, result.failedOperations); // All operations should succeed
        assertNotNull(result.results);

        System.out.println("   ✓ Mixed batch processing completed in " + processingTime + "ms");
        System.out.println("   ✓ Successful operations: " + result.successfulOperations);
        System.out.println("   ✓ Success rate: " + (result.getSuccessRate() * 100) + "%");
    }

    @Test
    @Timeout(30)
    public void testPerformanceComparison() throws Exception {
        System.out.println("\n4. Testing performance comparison between sequential and parallel processing...");

        int testSize = 200;
        List<float[]> matrices = createIdentityMatrices(testSize);

        // Sequential processing
        long sequentialStart = System.currentTimeMillis();
        List<float[]> sequentialResults = new ArrayList<>();
        for (int i = 0; i < testSize; i++) {
            float[] result = RustVectorLibrary.matrixMultiplyNalgebra(matrices.get(i), matrices.get(i));
            sequentialResults.add(result);
        }
        long sequentialTime = System.currentTimeMillis() - sequentialStart;

        // Parallel processing
        long parallelStart = System.currentTimeMillis();
        CompletableFuture<List<float[]>> parallelFuture = EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matrices, matrices);
        List<float[]> parallelResults = parallelFuture.get(10, TimeUnit.SECONDS);
        long parallelTime = System.currentTimeMillis() - parallelStart;

        // Verify results are equivalent
        assertEquals(sequentialResults.size(), parallelResults.size());
        for (int i = 0; i < testSize; i++) {
            assertArrayEquals(sequentialResults.get(i), parallelResults.get(i), 1e-6f);
        }

        // Performance analysis
        double speedup = (double) sequentialTime / parallelTime;
        System.out.println("   Sequential time: " + sequentialTime + "ms");
        System.out.println("   Parallel time: " + parallelTime + "ms");
        System.out.println("   Speedup: " + speedup + "x");
        System.out.println("   Efficiency: " + (speedup / Runtime.getRuntime().availableProcessors() * 100) + "%");
    }

    @Test
    @Timeout(30)
    public void testMemoryEfficiency() throws Exception {
        System.out.println("\n5. Testing memory efficiency...");

        // Get initial memory usage
        System.gc();
        long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // Process large batch
        List<float[]> largeBatch = createIdentityMatrices(500);
        CompletableFuture<List<float[]>> resultFuture = EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(largeBatch, largeBatch);
        List<float[]> results = resultFuture.get(20, TimeUnit.SECONDS);

        // Force garbage collection and measure memory
        System.gc();
        long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        assertNotNull(results);
        assertEquals(500, results.size());

        long memoryUsed = finalMemory - initialMemory;
        System.out.println("   Memory used for 500 matrix operations: " + (memoryUsed / 1024 / 1024) + " MB");
        System.out.println("   Memory per operation: " + (memoryUsed / 500 / 1024) + " KB");
    }

    @Test
    @Timeout(30)
    public void testErrorHandlingAndRecovery() throws Exception {
        System.out.println("\n6. Testing error handling and recovery...");

        EnhancedRustVectorLibrary.BatchProcessingRequest request = new EnhancedRustVectorLibrary.BatchProcessingRequest();

        // Add some valid operations
        for (int i = 0; i < 10; i++) {
            request.addOperation("matrix_mul_nalgebra", createIdentityMatrix(), createIdentityMatrix());
        }

        // Add an invalid operation (wrong array size)
        float[] invalidMatrix = new float[15]; // Should be 16 for 4x4 matrix
        request.addOperation("matrix_mul_nalgebra", invalidMatrix, createIdentityMatrix());

        // Process batch with mixed valid/invalid operations
        EnhancedRustVectorLibrary.BatchProcessingResult result = EnhancedRustVectorLibrary.processBatch(request);

        // Verify partial success
        assertNotNull(result);
        assertTrue(result.successfulOperations > 0);
        assertTrue(result.failedOperations > 0);
        assertTrue(result.getSuccessRate() > 0.5); // More than 50% should succeed

        System.out.println("   ✓ Error handling test completed");
        System.out.println("   ✓ Success rate with errors: " + (result.getSuccessRate() * 100) + "%");
    }

    // Helper methods
    private static List<float[]> createIdentityMatrices(int count) {
        List<float[]> matrices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            matrices.add(createIdentityMatrix());
        }
        return matrices;
    }

    private static float[] createIdentityMatrix() {
        return new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private static List<float[]> createTestVectors(int count) {
        List<float[]> vectors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            vectors.add(createTestVector());
        }
        return vectors;
    }

    private static float[] createTestVector() {
        return new float[] {1.0f, 2.0f, 3.0f};
    }
}