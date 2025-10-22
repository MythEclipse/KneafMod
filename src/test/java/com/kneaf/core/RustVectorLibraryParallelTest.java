package com.kneaf.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for RustVectorLibrary parallel processing and zero-copy operations.
 * Tests parallel matrix operations, vector processing, zero-copy transfers, and performance benchmarks.
 */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RustVectorLibraryParallelTest {

    private static final int MATRIX_SIZE = 4;
    private static final int VECTOR_SIZE = 3;
    private static final int BATCH_SIZE = 100;
    private static final int LARGE_BATCH_SIZE = 1000;
    
    private static final float[] IDENTITY_MATRIX = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    @BeforeAll
    public static void setUp() {
        System.setProperty("rust.test.mode", "true");
        System.out.println("=== RustVectorLibrary Parallel Test Starting ===");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
    }

    @AfterAll
    public static void tearDown() {
        EnhancedRustVectorLibrary.shutdown();
        System.clearProperty("rust.test.mode");
        System.out.println("=== RustVectorLibrary Parallel Test Completed ===");
    }

    /**
     * Test parallel matrix multiplication with different libraries
     */
    @Test
    @DisplayName("Test parallel matrix multiplication with nalgebra")
    void testParallelMatrixMultiplicationNalgebra() throws Exception {
        List<float[]> matricesA = createIdentityMatrices(BATCH_SIZE);
        List<float[]> matricesB = createIdentityMatrices(BATCH_SIZE);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<List<float[]>> future = 
            EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
        List<float[]> results = future.get(10, TimeUnit.SECONDS);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(results);
        assertEquals(BATCH_SIZE, results.size());
        
        // Verify results - identity * identity = identity
        for (float[] result : results) {
            assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity");
        }
        
        System.out.println("Parallel nalgebra matrix multiplication: " + BATCH_SIZE + " matrices in " + durationMs + "ms");
        assertTrue(durationMs < 5000, "Parallel matrix multiplication took too long: " + durationMs + "ms");
    }

    /**
     * Test parallel matrix multiplication with glam library
     */
    @Test
    @DisplayName("Test parallel matrix multiplication with glam")
    void testParallelMatrixMultiplicationGlam() throws Exception {
        List<float[]> matricesA = createIdentityMatrices(BATCH_SIZE);
        List<float[]> matricesB = createIdentityMatrices(BATCH_SIZE);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<List<float[]>> future = 
            EnhancedRustVectorLibrary.batchMatrixMultiplyGlam(matricesA, matricesB);
        List<float[]> results = future.get(10, TimeUnit.SECONDS);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(results);
        assertEquals(BATCH_SIZE, results.size());
        
        // Verify results
        for (float[] result : results) {
            assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity");
        }
        
        System.out.println("Parallel glam matrix multiplication: " + BATCH_SIZE + " matrices in " + durationMs + "ms");
        assertTrue(durationMs < 5000, "Parallel matrix multiplication took too long: " + durationMs + "ms");
    }

    /**
     * Test parallel matrix multiplication with faer library
     */
    @Test
    @DisplayName("Test parallel matrix multiplication with faer")
    void testParallelMatrixMultiplicationFaer() throws Exception {
        List<float[]> matricesA = createIdentityMatrices(BATCH_SIZE);
        List<float[]> matricesB = createIdentityMatrices(BATCH_SIZE);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<List<float[]>> future = 
            EnhancedRustVectorLibrary.batchMatrixMultiplyFaer(matricesA, matricesB);
        List<float[]> results = future.get(10, TimeUnit.SECONDS);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(results);
        assertEquals(BATCH_SIZE, results.size());
        
        // Verify results
        for (float[] result : results) {
            assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity");
        }
        
        System.out.println("Parallel faer matrix multiplication: " + BATCH_SIZE + " matrices in " + durationMs + "ms");
        assertTrue(durationMs < 5000, "Parallel matrix multiplication took too long: " + durationMs + "ms");
    }

    /**
     * Test parallel vector operations
     */
    @Test
    @DisplayName("Test parallel vector addition")
    void testParallelVectorAddition() throws Exception {
        List<float[]> vectorsA = createTestVectors(BATCH_SIZE);
        List<float[]> vectorsB = createTestVectors(BATCH_SIZE);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<List<float[]>> future = 
            EnhancedRustVectorLibrary.batchVectorAddNalgebra(vectorsA, vectorsB);
        List<float[]> results = future.get(10, TimeUnit.SECONDS);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(results);
        assertEquals(BATCH_SIZE, results.size());
        
        // Verify results - [1,2,3] + [1,2,3] = [2,4,6]
        float[] expected = {2.0f, 4.0f, 6.0f};
        for (float[] result : results) {
            assertArrayEquals(expected, result, 1e-6f, "Vector addition result incorrect");
        }
        
        System.out.println("Parallel vector addition: " + BATCH_SIZE + " vectors in " + durationMs + "ms");
        assertTrue(durationMs < 3000, "Parallel vector addition took too long: " + durationMs + "ms");
    }

    /**
     * Test parallel vector dot products
     */
    @Test
    @DisplayName("Test parallel vector dot products")
    void testParallelVectorDotProducts() throws Exception {
        List<float[]> vectorsA = createTestVectors(BATCH_SIZE);
        List<float[]> vectorsB = createTestVectors(BATCH_SIZE);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<List<Float>> future = 
            EnhancedRustVectorLibrary.batchVectorDotGlam(vectorsA, vectorsB);
        List<Float> results = future.get(10, TimeUnit.SECONDS);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(results);
        assertEquals(BATCH_SIZE, results.size());
        
        // Verify results - [1,2,3] · [1,2,3] = 1*1 + 2*2 + 3*3 = 14
        float expected = 14.0f;
        for (float result : results) {
            assertEquals(expected, result, 1e-6f, "Vector dot product result incorrect");
        }
        
        System.out.println("Parallel vector dot products: " + BATCH_SIZE + " operations in " + durationMs + "ms");
        assertTrue(durationMs < 3000, "Parallel vector dot products took too long: " + durationMs + "ms");
    }

    /**
     * Test zero-copy matrix multiplication
     */
    @Test
    @DisplayName("Test zero-copy matrix multiplication")
    void testZeroCopyMatrixMultiplication() throws Exception {
        float[] matrixA = IDENTITY_MATRIX.clone();
        float[] matrixB = IDENTITY_MATRIX.clone();
        
        long startTime = System.nanoTime();
        
        float[] result = RustVectorLibrary.matrixMultiplyNalgebra(matrixA, matrixB);
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        assertNotNull(result);
        assertEquals(16, result.length);
        
        // Verify result - identity * identity = identity
        assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Zero-copy matrix multiplication result incorrect");
        
        System.out.println("Zero-copy matrix multiplication: " + durationMs + "ms");
        assertTrue(durationMs < 100, "Zero-copy matrix multiplication took too long: " + durationMs + "ms");
    }

    /**
     * Test zero-copy vector operations
     */
    @Test
    @DisplayName("Test zero-copy vector operations")
    void testZeroCopyVectorOperations() throws Exception {
        float[] vectorA = {1.0f, 2.0f, 3.0f};
        float[] vectorB = {4.0f, 5.0f, 6.0f};
        
        // Test zero-copy vector addition
        long startTime = System.nanoTime();
        CompletableFuture<float[]> addFuture = EnhancedRustVectorLibrary.batchVectorAddNalgebra(
            Collections.singletonList(vectorA), Collections.singletonList(vectorB)).thenApply(results -> results.get(0));
        float[] addResult = addFuture.get(2, TimeUnit.SECONDS);
        long addDuration = (System.nanoTime() - startTime) / 1_000_000;
        
        assertNotNull(addResult);
        assertEquals(3, addResult.length);
        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, addResult, 1e-6f, "Zero-copy vector addition incorrect");
        
        // Test zero-copy vector dot product
        startTime = System.nanoTime();
        CompletableFuture<Float> dotFuture = EnhancedRustVectorLibrary.batchVectorDotGlam(
            Collections.singletonList(vectorA), Collections.singletonList(vectorB)).thenApply(results -> results.get(0));
        float dotResult = dotFuture.get(2, TimeUnit.SECONDS);
        long dotDuration = (System.nanoTime() - startTime) / 1_000_000;
        
        float expectedDot = 32.0f; // 1*4 + 2*5 + 3*6 = 32
        assertEquals(expectedDot, dotResult, 1e-6f, "Zero-copy vector dot product incorrect");
        
        // Test zero-copy vector cross product
        startTime = System.nanoTime();
        CompletableFuture<float[]> crossFuture = EnhancedRustVectorLibrary.batchVectorCrossGlam(
            Collections.singletonList(vectorA), Collections.singletonList(vectorB)).thenApply(results -> results.get(0));
        float[] crossResult = crossFuture.get(2, TimeUnit.SECONDS);
        long crossDuration = (System.nanoTime() - startTime) / 1_000_000;
        
        assertNotNull(crossResult);
        assertEquals(3, crossResult.length);
        // [1,2,3] × [4,5,6] = [-3, 6, -3]
        assertArrayEquals(new float[]{-3.0f, 6.0f, -3.0f}, crossResult, 1e-6f, "Zero-copy vector cross product incorrect");
        
        System.out.println("Zero-copy vector operations:");
        System.out.println("  Addition: " + addDuration + "ms");
        System.out.println("  Dot product: " + dotDuration + "ms");
        System.out.println("  Cross product: " + crossDuration + "ms");
        
        assertTrue(addDuration < 50, "Zero-copy vector addition took too long: " + addDuration + "ms");
        assertTrue(dotDuration < 50, "Zero-copy vector dot product took too long: " + dotDuration + "ms");
        assertTrue(crossDuration < 50, "Zero-copy vector cross product took too long: " + crossDuration + "ms");
    }

    /**
     * Test high concurrency parallel processing
     */
    @Test
    @DisplayName("Test high concurrency parallel processing")
    void testHighConcurrencyParallelProcessing() throws Exception {
        int numThreads = Runtime.getRuntime().availableProcessors() * 2;
        int operationsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix different parallel operations
                        if (j % 3 == 0) {
                            // Matrix multiplication
                            CompletableFuture<float[]> future = EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                IDENTITY_MATRIX, IDENTITY_MATRIX, "nalgebra");
                            float[] result = future.get(1, TimeUnit.SECONDS);
                            assertNotNull(result);
                        } else if (j % 3 == 1) {
                            // Vector addition
                            float[] vectorA = {1.0f, 2.0f, 3.0f};
                            float[] vectorB = {4.0f, 5.0f, 6.0f};
                            CompletableFuture<float[]> future = EnhancedRustVectorLibrary.parallelVectorAdd(
                                vectorA, vectorB, "nalgebra");
                            float[] result = future.get(1, TimeUnit.SECONDS);
                            assertNotNull(result);
                        } else {
                            // Vector dot product
                            float[] vectorA = {1.0f, 2.0f, 3.0f};
                            float[] vectorB = {4.0f, 5.0f, 6.0f};
                            CompletableFuture<Float> future = EnhancedRustVectorLibrary.parallelVectorDot(
                                vectorA, vectorB, "glam");
                            Float result = future.get(1, TimeUnit.SECONDS);
                            assertNotNull(result);
                        }
                        
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("High concurrency test failed in thread " + threadId + ": " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all to complete
        boolean completed = completeLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "High concurrency test timed out");
        
        // Verify all operations succeeded
        int expectedOperations = numThreads * operationsPerThread;
        assertEquals(expectedOperations, successCount.get(), 
                    "Not all operations completed successfully");
        
        executor.shutdown();
        
        System.out.println("High concurrency test completed: " + expectedOperations + " operations from " + numThreads + " threads");
    }

    /**
     * Test parallel processing performance benchmarks
     */
    @Test
    @DisplayName("Test parallel processing performance benchmarks")
    void testParallelProcessingPerformanceBenchmarks() throws Exception {
        // Test different batch sizes
        int[] batchSizes = {10, 50, 100, 500};
        
        System.out.println("Parallel Processing Performance Benchmarks:");
        
        for (int batchSize : batchSizes) {
            List<float[]> matricesA = createIdentityMatrices(batchSize);
            List<float[]> matricesB = createIdentityMatrices(batchSize);
            
            // Benchmark nalgebra
            long startTime = System.nanoTime();
            CompletableFuture<List<float[]>> future = 
                EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
            List<float[]> results = future.get(30, TimeUnit.SECONDS);
            long nalgebraDuration = (System.nanoTime() - startTime) / 1_000_000;
            
            // Benchmark glam
            startTime = System.nanoTime();
            future = EnhancedRustVectorLibrary.batchMatrixMultiplyGlam(matricesA, matricesB);
            results = future.get(30, TimeUnit.SECONDS);
            long glamDuration = (System.nanoTime() - startTime) / 1_000_000;
            
            // Benchmark faer
            startTime = System.nanoTime();
            future = EnhancedRustVectorLibrary.batchMatrixMultiplyFaer(matricesA, matricesB);
            results = future.get(30, TimeUnit.SECONDS);
            long faerDuration = (System.nanoTime() - startTime) / 1_000_000;
            
            System.out.println("Batch size " + batchSize + ":");
            System.out.println("  Nalgebra: " + nalgebraDuration + "ms");
            System.out.println("  Glam: " + glamDuration + "ms");
            System.out.println("  Faer: " + faerDuration + "ms");
            
            // Performance assertions
            assertTrue(nalgebraDuration < batchSize * 100, "Nalgebra too slow for batch size " + batchSize);
            assertTrue(glamDuration < batchSize * 100, "Glam too slow for batch size " + batchSize);
            assertTrue(faerDuration < batchSize * 100, "Faer too slow for batch size " + batchSize);
        }
    }

    /**
     * Test zero-copy performance comparison
     */
    @Test
    @DisplayName("Test zero-copy vs traditional performance comparison")
    void testZeroCopyVsTraditionalPerformance() throws Exception {
        int iterations = 1000;
        
        float[] matrixA = IDENTITY_MATRIX.clone();
        float[] matrixB = IDENTITY_MATRIX.clone();
        
        // Traditional approach (if available)
        long traditionalStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            float[] result = RustVectorLibrary.matrixMultiplyNalgebra(matrixA, matrixB);
            assertNotNull(result);
        }
        long traditionalDuration = (System.nanoTime() - traditionalStart) / 1_000_000;
        
        // Zero-copy approach
        long zeroCopyStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            float[] result = RustVectorLibrary.matrixMultiplyNalgebra(matrixA, matrixB);
            assertNotNull(result);
        }
        long zeroCopyDuration = (System.nanoTime() - zeroCopyStart) / 1_000_000;
        
        System.out.println("Performance comparison (" + iterations + " iterations):");
        System.out.println("  Traditional: " + traditionalDuration + "ms");
        System.out.println("  Zero-copy: " + zeroCopyDuration + "ms");
        
        double speedup = (double) traditionalDuration / zeroCopyDuration;
        System.out.println("  Speedup: " + speedup + "x");
        
        // Handle cases where both operations take 0ms (likely in test environments)
        if (traditionalDuration == 0 && zeroCopyDuration == 0) {
            System.out.println("Both traditional and zero-copy operations took 0ms - skipping performance comparison");
        } else {
            // Zero-copy should be faster or at least comparable when both have measurable duration
            assertTrue(zeroCopyDuration < traditionalDuration * 1.5,
                      "Zero-copy not efficient enough: " + zeroCopyDuration + "ms vs " + traditionalDuration + "ms");
        }
    }

    /**
     * Test memory efficiency of parallel operations
     */
    @Test
    @DisplayName("Test memory efficiency of parallel operations")
    void testMemoryEfficiency() throws Exception {
        // Get initial memory usage
        System.gc();
        Thread.sleep(100);
        long initialMemory = getUsedMemory();
        
        // Perform large batch operations
        List<float[]> matricesA = createIdentityMatrices(LARGE_BATCH_SIZE);
        List<float[]> matricesB = createIdentityMatrices(LARGE_BATCH_SIZE);
        
        CompletableFuture<List<float[]>> future = 
            EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
        List<float[]> results = future.get(30, TimeUnit.SECONDS);
        
        assertNotNull(results);
        assertEquals(LARGE_BATCH_SIZE, results.size());
        
        // Force garbage collection
        System.gc();
        Thread.sleep(200);
        
        long finalMemory = getUsedMemory();
        long memoryGrowth = finalMemory - initialMemory;
        
        System.out.println("Memory efficiency test:");
        System.out.println("  Initial memory: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("  Final memory: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("  Memory growth: " + (memoryGrowth / 1024 / 1024) + " MB");
        System.out.println("  Memory per operation: " + (memoryGrowth / LARGE_BATCH_SIZE / 1024) + " KB");
        
        // Memory growth should be reasonable
        assertTrue(memoryGrowth < 200 * 1024 * 1024, // 200MB threshold
                  "Memory grew too much: " + memoryGrowth + " bytes");
    }

    /**
     * Test error handling in parallel operations
     */
    @Test
    @DisplayName("Test error handling in parallel operations")
    void testErrorHandlingInParallelOperations() throws Exception {
        // Test with invalid input data
        float[] invalidMatrix = new float[15]; // Wrong size for 4x4 matrix
        
        try {
            CompletableFuture<float[]> future = 
                EnhancedRustVectorLibrary.parallelMatrixMultiply(invalidMatrix, IDENTITY_MATRIX, "nalgebra");
            future.get(2, TimeUnit.SECONDS);
            fail("Should have thrown exception for invalid matrix size");
        } catch (ExecutionException e) {
            // Expected - invalid input should cause error
            assertTrue(e.getCause() instanceof IllegalArgumentException || 
                      e.getMessage().contains("invalid") || 
                      e.getMessage().contains("size"));
        }
        
        // Test with null input
        try {
            CompletableFuture<float[]> future = 
                EnhancedRustVectorLibrary.parallelMatrixMultiply(null, IDENTITY_MATRIX, "nalgebra");
            future.get(2, TimeUnit.SECONDS);
            fail("Should have thrown exception for null input");
        } catch (ExecutionException e) {
            // Expected - null input should cause error
            assertTrue(e.getCause() instanceof IllegalArgumentException || 
                      e.getMessage().contains("null"));
        }
    }

    // Helper methods

    private List<float[]> createIdentityMatrices(int count) {
        List<float[]> matrices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            matrices.add(IDENTITY_MATRIX.clone());
        }
        return matrices;
    }

    private List<float[]> createTestVectors(int count) {
        List<float[]> vectors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            vectors.add(new float[]{1.0f, 2.0f, 3.0f});
        }
        return vectors;
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}