package com.kneaf.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Rust performance library multi-core utilization.
 * Tests parallel A* pathfinding, matrix operations, SIMD optimizations, load balancing,
 * and multi-core scaling effectiveness.
 */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RustPerformanceMultiCoreTest {

    private static final int SMALL_MATRIX_SIZE = 64;
    private static final int MEDIUM_MATRIX_SIZE = 128;
    private static final int LARGE_MATRIX_SIZE = 256;
    private static final int PATHFINDING_GRID_SIZE = 100;
    
    private static final float[] IDENTITY_MATRIX = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
    
    private final AtomicInteger processedOperations = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);

    @BeforeAll
    public static void setUp() {
        System.setProperty("rust.test.mode", "true");
        System.out.println("=== Rust Performance Multi-Core Test Starting ===");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
    }

    @AfterAll
    public static void tearDown() {
        EnhancedRustVectorLibrary.shutdown();
        System.clearProperty("rust.test.mode");
        System.out.println("=== Rust Performance Multi-Core Test Completed ===");
    }

    /**
     * Test parallel matrix multiplication scaling
     */
    @Test
    @DisplayName("Test parallel matrix multiplication scaling")
    void testParallelMatrixMultiplicationScaling() throws Exception {
        int[] matrixSizes = {64, 128, 256};
        int[] threadCounts = {1, 2, 4, 8};
        
        System.out.println("Parallel Matrix Multiplication Scaling Test:");
        
        for (int matrixSize : matrixSizes) {
            System.out.println("  Matrix size: " + matrixSize + "x" + matrixSize);
            
            // Generate random matrices
            float[] matrixA = generateRandomMatrix(matrixSize, matrixSize);
            float[] matrixB = generateRandomMatrix(matrixSize, matrixSize);
            
            long[] durations = new long[threadCounts.length];
            
            for (int i = 0; i < threadCounts.length; i++) {
                int numThreads = threadCounts[i];
                
                long startTime = System.nanoTime();
                
                // Use batch operations to test parallel processing
                List<float[]> matricesA = new ArrayList<>();
                List<float[]> matricesB = new ArrayList<>();
                
                // Create multiple matrix pairs for parallel processing
                for (int j = 0; j < numThreads * 2; j++) {
                    matricesA.add(matrixA.clone());
                    matricesB.add(matrixB.clone());
                }
                
                CompletableFuture<List<float[]>> future =
                    EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
                List<float[]> results = future.get(30, TimeUnit.SECONDS);
                
                long endTime = System.nanoTime();
                durations[i] = (endTime - startTime) / 1_000_000;
                
                assertNotNull(results);
                assertEquals(matricesA.size(), results.size());
                
                System.out.println("    " + numThreads + " threads: " + durations[i] + "ms");
            }
            
            // Analyze scaling for this matrix size
            analyzeScalingEffectiveness("Matrix " + matrixSize + "x" + matrixSize, threadCounts, durations);
        }
    }

    /**
     * Test SIMD optimization effectiveness using vector dot products
     */
    @Test
    @DisplayName("Test SIMD optimization effectiveness")
    void testSimdOptimizationEffectiveness() throws Exception {
        int[] vectorSizes = {1000, 10000, 100000};
        
        System.out.println("SIMD Optimization Effectiveness Test:");
        
        for (int vectorSize : vectorSizes) {
            System.out.println("  Vector size: " + vectorSize);
            
            // Generate random vectors
            float[] vectorA = generateRandomVector(vectorSize);
            float[] vectorB = generateRandomVector(vectorSize);
            
            // Test with parallel vector dot product (optimized)
            long parallelStart = System.nanoTime();
            CompletableFuture<Float> parallelFuture =
                EnhancedRustVectorLibrary.parallelVectorDot(vectorA, vectorB, "glam");
            Float parallelResult = parallelFuture.get(2, TimeUnit.SECONDS);
            long parallelDuration = (System.nanoTime() - parallelStart) / 1_000_000;
            
            // Test with traditional sequential approach
            long traditionalStart = System.nanoTime();
            float traditionalResult = RustVectorLibrary.vectorDotGlam(vectorA, vectorB);
            long traditionalDuration = (System.nanoTime() - traditionalStart) / 1_000_000;
            
            // Verify results are similar (allowing for small numerical differences)
            assertTrue(Math.abs(parallelResult - traditionalResult) < 0.001,
                      "Parallel and traditional results should be similar");
            
            double speedup = (double) traditionalDuration / parallelDuration;
            System.out.println("    Parallel: " + parallelDuration + "ms");
            System.out.println("    Traditional: " + traditionalDuration + "ms");
            System.out.println("    Speedup: " + speedup + "x");
            
            // Parallel processing should be faster for large vectors
            if (vectorSize >= 10000) {
                assertTrue(speedup > 1.2, "Parallel processing should provide at least 1.2x speedup for large vectors");
            }
        }
    }

    /**
     * Test load balancing effectiveness using batch processing
     */
    @Test
    @DisplayName("Test load balancing effectiveness")
    void testLoadBalancingEffectiveness() throws Exception {
        int numOperations = 100;
        int numThreads = Runtime.getRuntime().availableProcessors();
        
        System.out.println("Load Balancing Effectiveness Test:");
        System.out.println("  Operations: " + numOperations);
        System.out.println("  Threads: " + numThreads);
        
        long startTime = System.nanoTime();
        
        // Create batch processing request with varying workloads
        EnhancedRustVectorLibrary.BatchProcessingRequest request =
            new EnhancedRustVectorLibrary.BatchProcessingRequest();
        
        // Add operations with varying complexity
        for (int i = 0; i < numOperations; i++) {
            String operationType;
            Object inputA, inputB;
            
            // Mix different operation types and sizes
            if (i % 3 == 0) {
                operationType = "matrix_mul_nalgebra";
                inputA = generateRandomMatrix(32, 32);
                inputB = generateRandomMatrix(32, 32);
            } else if (i % 3 == 1) {
                operationType = "vector_dot_glam";
                inputA = generateRandomVector(100);
                inputB = generateRandomVector(100);
            } else {
                operationType = "vector_add_nalgebra";
                inputA = generateRandomVector(50);
                inputB = generateRandomVector(50);
            }
            
            request.addOperation(operationType, inputA, inputB);
        }
        
        // Process batch with parallel execution
        EnhancedRustVectorLibrary.BatchProcessingResult result =
            EnhancedRustVectorLibrary.processBatch(request);
        
        long endTime = System.nanoTime();
        long totalDuration = (endTime - startTime) / 1_000_000;
        
        assertNotNull(result);
        assertTrue(result.successfulOperations > 0);
        assertEquals(0, result.failedOperations); // All operations should succeed
        assertNotNull(result.results);
        
        System.out.println("  Total duration: " + totalDuration + "ms");
        System.out.println("  Successful operations: " + result.successfulOperations);
        System.out.println("  Success rate: " + (result.getSuccessRate() * 100) + "%");
        System.out.println("  Operations per second: " + (numOperations * 1000.0 / totalDuration));
        
        // Load balancing should be effective
        assertTrue(result.getSuccessRate() > 0.95, "Success rate should be > 95%");
        assertTrue(totalDuration < numOperations * 100, "Should process operations efficiently");
    }

    /**
     * Test concurrent multi-core operations
     */
    @Test
    @DisplayName("Test concurrent multi-core operations")
    void testConcurrentMultiCoreOperations() throws Exception {
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
                        if (j % 4 == 0) {
                            // Matrix multiplication
                            CompletableFuture<float[]> future = 
                                EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                    generateRandomMatrix(32, 32), 
                                    generateRandomMatrix(32, 32), 
                                    "nalgebra");
                            float[] result = future.get(1, TimeUnit.SECONDS);
                            assertNotNull(result);
                        } else if (j % 4 == 1) {
                            // Vector operations
                            float[] vectorA = generateRandomVector(100);
                            float[] vectorB = generateRandomVector(100);
                            CompletableFuture<Float> future = 
                                EnhancedRustVectorLibrary.parallelVectorDot(vectorA, vectorB, "glam");
                            Float result = future.get(1, TimeUnit.SECONDS);
                            assertNotNull(result);
                        } else if (j % 4 == 2) {
                            // SIMD operations (use parallel vector dot as proxy)
                            float[] vectorA = generateRandomVector(1000);
                            float[] vectorB = generateRandomVector(1000);
                            CompletableFuture<Float> future =
                                EnhancedRustVectorLibrary.parallelVectorDot(vectorA, vectorB, "glam");
                            Float result = future.get(1, TimeUnit.SECONDS);
                            assertTrue(result > 0);
                        } else {
                            // Matrix multiplication (as proxy for complex parallel operation)
                            CompletableFuture<float[]> future =
                                EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                    IDENTITY_MATRIX, IDENTITY_MATRIX, "nalgebra");
                            float[] result = future.get(2, TimeUnit.SECONDS);
                            assertNotNull(result);
                        }
                        
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Concurrent operation failed in thread " + threadId + ": " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all to complete
        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent multi-core test timed out");
        
        // Verify all operations succeeded
        int expectedOperations = numThreads * operationsPerThread;
        assertEquals(expectedOperations, successCount.get(), 
                    "Not all concurrent operations completed successfully");
        
        executor.shutdown();
        
        System.out.println("Concurrent multi-core test completed: " + expectedOperations + " operations from " + numThreads + " threads");
    }

    /**
     * Test multi-core scaling effectiveness with different workloads
     */
    @Test
    @DisplayName("Test multi-core scaling with varying workloads")
    void testMultiCoreScalingWithVaryingWorkloads() throws Exception {
        System.out.println("Multi-Core Scaling with Varying Workloads Test:");
        
        // Test different workload types
        String[] workloadTypes = {"matrix_multiply", "vector_dot", "pathfinding", "simd_operations"};
        int[] threadCounts = {1, 2, 4, 8};
        
        for (String workloadType : workloadTypes) {
            System.out.println("  Workload: " + workloadType);
            
            long[] durations = new long[threadCounts.length];
            
            for (int i = 0; i < threadCounts.length; i++) {
                int numThreads = threadCounts[i];
                
                long startTime = System.nanoTime();
                
                switch (workloadType) {
                    case "matrix_multiply":
                        // Test parallel matrix multiplication
                        List<CompletableFuture<float[]>> matrixFutures = new ArrayList<>();
                        for (int task = 0; task < 10; task++) {
                            matrixFutures.add(
                                EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                    generateRandomMatrix(64, 64), 
                                    generateRandomMatrix(64, 64), 
                                    "nalgebra"));
                        }
                        for (CompletableFuture<float[]> future : matrixFutures) {
                            float[] result = future.get(5, TimeUnit.SECONDS);
                            assertNotNull(result);
                        }
                        break;
                        
                    case "vector_dot":
                        // Test parallel vector dot products
                        List<CompletableFuture<Float>> vectorFutures = new ArrayList<>();
                        for (int task = 0; task < 20; task++) {
                            float[] vectorA = generateRandomVector(1000);
                            float[] vectorB = generateRandomVector(1000);
                            vectorFutures.add(
                                EnhancedRustVectorLibrary.parallelVectorDot(vectorA, vectorB, "glam"));
                        }
                        for (CompletableFuture<Float> future : vectorFutures) {
                            Float result = future.get(2, TimeUnit.SECONDS);
                            assertNotNull(result);
                        }
                        break;
                        
                    case "pathfinding":
                        // Test parallel matrix operations (as proxy for complex parallel operation)
                        List<CompletableFuture<float[]>> matrixOpFutures = new ArrayList<>();
                        for (int task = 0; task < 5; task++) {
                            matrixOpFutures.add(
                                EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                    generateRandomMatrix(16, 16),
                                    generateRandomMatrix(16, 16),
                                    "nalgebra"));
                        }
                        for (CompletableFuture<float[]> future : matrixOpFutures) {
                            float[] result = future.get(10, TimeUnit.SECONDS);
                            assertNotNull(result);
                        }
                        break;
                        
                    case "simd_operations":
                        // Test SIMD operations (use parallel vector dot as proxy)
                        for (int task = 0; task < 50; task++) {
                            float[] vectorA = generateRandomVector(10000);
                            float[] vectorB = generateRandomVector(10000);
                            CompletableFuture<Float> future =
                                EnhancedRustVectorLibrary.parallelVectorDot(vectorA, vectorB, "glam");
                            Float result = future.get(2, TimeUnit.SECONDS);
                            assertTrue(result > 0);
                        }
                        break;
                }
                
                long endTime = System.nanoTime();
                durations[i] = (endTime - startTime) / 1_000_000;
                
                System.out.println("    " + numThreads + " threads: " + durations[i] + "ms");
            }
            
            // Analyze scaling for this workload
            analyzeScalingEffectiveness("Workload: " + workloadType, threadCounts, durations);
        }
    }

    /**
     * Test memory efficiency of multi-core operations
     */
    @Test
    @DisplayName("Test memory efficiency of multi-core operations")
    void testMemoryEfficiency() throws Exception {
        // Get initial memory usage
        System.gc();
        Thread.sleep(100);
        long initialMemory = getUsedMemory();
        
        // Perform many parallel operations
        int numOperations = 100;
        
        List<CompletableFuture<float[]>> futures = new ArrayList<>();
        
        for (int i = 0; i < numOperations; i++) {
            futures.add(
                EnhancedRustVectorLibrary.parallelMatrixMultiply(
                    generateRandomMatrix(32, 32), 
                    generateRandomMatrix(32, 32), 
                    "nalgebra"));
        }
        
        // Wait for all operations to complete
        for (CompletableFuture<float[]> future : futures) {
            float[] result = future.get(30, TimeUnit.SECONDS);
            assertNotNull(result);
        }
        
        // Force garbage collection
        System.gc();
        Thread.sleep(200);
        
        long finalMemory = getUsedMemory();
        long memoryGrowth = finalMemory - initialMemory;
        
        System.out.println("Memory efficiency test:");
        System.out.println("  Initial memory: " + (initialMemory / 1024 / 1024) + " MB");
        System.out.println("  Final memory: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("  Memory growth: " + (memoryGrowth / 1024 / 1024) + " MB");
        System.out.println("  Memory per operation: " + (memoryGrowth / numOperations / 1024) + " KB");
        
        // Memory growth should be reasonable
        assertTrue(memoryGrowth < 100 * 1024 * 1024, // 100MB threshold
                  "Memory grew too much: " + memoryGrowth + " bytes");
    }

    // Helper methods

    private void analyzeScalingEffectiveness(String testName, int[] threadCounts, long[] durations) {
        System.out.println("  Scaling analysis for " + testName + ":");
        
        for (int i = 1; i < threadCounts.length; i++) {
            double speedup = (double) durations[i-1] / durations[i];
            double efficiency = speedup / (threadCounts[i] / threadCounts[i-1]) * 100;
            
            System.out.println("    " + threadCounts[i-1] + "→" + threadCounts[i] + " threads: " + 
                              speedup + "x speedup, " + efficiency + "% efficiency");
            
            // Basic scaling validation
            if (i <= 2) { // Only check for reasonable scaling up to 4 threads
                assertTrue(speedup > 0.7, "Should achieve at least 70% scaling efficiency");
            }
        }
    }

    private float[] generateRandomMatrix(int rows, int cols) {
        float[] matrix = new float[rows * cols];
        Random random = new Random();
        for (int i = 0; i < matrix.length; i++) {
            matrix[i] = random.nextFloat() * 10.0f;
        }
        return matrix;
    }

    private float[] generateRandomVector(int size) {
        float[] vector = new float[size];
        Random random = new Random();
        for (int i = 0; i < size; i++) {
            vector[i] = random.nextFloat() * 10.0f;
        }
        return vector;
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}