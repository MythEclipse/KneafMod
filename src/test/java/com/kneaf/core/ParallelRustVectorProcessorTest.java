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
 * Comprehensive test suite for ParallelRustVectorProcessor.
 * Tests parallel processing, batch operations, zero-copy operations, and thread safety.
 */
public class ParallelRustVectorProcessorTest {

    private static final float[] IDENTITY_MATRIX = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    private static final float[] TEST_VECTOR_A = {1.0f, 2.0f, 3.0f};
    private static final float[] TEST_VECTOR_B = {4.0f, 5.0f, 6.0f};
    
    private static ParallelRustVectorProcessor processor;
    private static ExecutorService testExecutor;

    @BeforeAll
    public static void setUp() {
        System.setProperty("rust.test.mode", "true");
        processor = new ParallelRustVectorProcessor();
        testExecutor = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    public static void tearDown() {
        if (processor != null) {
            processor.shutdown();
        }
        if (testExecutor != null) {
            testExecutor.shutdown();
        }
        System.clearProperty("rust.test.mode");
    }

    @Test
    @Timeout(30)
    public void testParallelMatrixMultiplication() throws Exception {
        System.out.println("Testing parallel matrix multiplication...");
        
        // Create multiple matrix pairs for parallel processing
        List<float[]> matricesA = new ArrayList<>();
        List<float[]> matricesB = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            matricesA.add(IDENTITY_MATRIX.clone());
            matricesB.add(IDENTITY_MATRIX.clone());
        }
        
        // Process batch in parallel
        CompletableFuture<List<float[]>> resultFuture = processor.batchMatrixMultiply(matricesA, matricesB, "nalgebra");
        List<float[]> results = resultFuture.get(10, TimeUnit.SECONDS);
        
        assertNotNull(results);
        assertEquals(100, results.size());
        
        // Verify all results are identity matrices
        for (float[] result : results) {
            assertNotNull(result);
            assertEquals(16, result.length);
            assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f);
        }
        
        System.out.println("✓ Parallel matrix multiplication passed");
    }

    @Test
    @Timeout(30)
    public void testParallelVectorOperations() throws Exception {
        System.out.println("Testing parallel vector operations...");
        
        // Test parallel vector addition
        CompletableFuture<Object> addResult = processor.parallelVectorOperation(TEST_VECTOR_A, TEST_VECTOR_B, "vectorAdd");
        float[] addResultArray = (float[]) addResult.get(5, TimeUnit.SECONDS);
        
        assertNotNull(addResultArray);
        assertEquals(3, addResultArray.length);
        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, addResultArray, 1e-6f);
        
        // Test parallel vector dot product
        CompletableFuture<Object> dotResult = processor.parallelVectorOperation(TEST_VECTOR_A, TEST_VECTOR_B, "vectorDot");
        Float dotProduct = (Float) dotResult.get(5, TimeUnit.SECONDS);
        
        assertEquals(32.0f, dotProduct.floatValue(), 1e-6f);
        
        System.out.println("✓ Parallel vector operations passed");
    }

    @Test
    @Timeout(30)
    public void testZeroCopyOperations() {
        System.out.println("Testing zero-copy operations...");
        
        // Test zero-copy matrix multiplication
        float[] result = processor.matrixMultiplyZeroCopy(IDENTITY_MATRIX, IDENTITY_MATRIX, "nalgebra");
        
        assertNotNull(result);
        assertEquals(16, result.length);
        assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f);
        
        System.out.println("✓ Zero-copy operations passed");
    }

    @Test
    @Timeout(30)
    public void testThreadSafety() throws Exception {
        System.out.println("Testing thread safety...");
        
        int numThreads = 10;
        int operationsPerThread = 50;
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < numThreads; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        // Perform parallel matrix multiplication
                        CompletableFuture<Object> result = processor.parallelVectorOperation(
                            TEST_VECTOR_A, TEST_VECTOR_B, "vectorAdd");
                        float[] vectorResult = (float[]) result.get(1, TimeUnit.SECONDS);
                        
                        assertNotNull(vectorResult);
                        assertEquals(3, vectorResult.length);
                        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, vectorResult, 1e-6f);
                        
                    } catch (Exception e) {
                        throw new RuntimeException("Thread safety test failed", e);
                    }
                }
            }, testExecutor);
            
            futures.add(future);
        }
        
        // Wait for all threads to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        
        System.out.println("✓ Thread safety test passed");
    }

    @Test
    @Timeout(30)
    public void testBatchProcessingPerformance() throws Exception {
        System.out.println("Testing batch processing performance...");
        
        int batchSize = 1000;
        List<float[]> matricesA = new ArrayList<>(batchSize);
        List<float[]> matricesB = new ArrayList<>(batchSize);
        
        // Create large batch of matrices
        for (int i = 0; i < batchSize; i++) {
            matricesA.add(createRandomMatrix());
            matricesB.add(createRandomMatrix());
        }
        
        long startTime = System.currentTimeMillis();
        
        // Process batch in parallel
        CompletableFuture<List<float[]>> resultFuture = processor.batchMatrixMultiply(matricesA, matricesB, "nalgebra");
        List<float[]> results = resultFuture.get(30, TimeUnit.SECONDS);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        assertNotNull(results);
        assertEquals(batchSize, results.size());
        
        System.out.println("✓ Batch processing performance test completed in " + duration + "ms");
    }

    @Test
    @Timeout(30)
    public void testQueueStatistics() {
        System.out.println("Testing queue statistics...");
        
        ParallelRustVectorProcessor.QueueStatistics stats = processor.getQueueStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.pendingOperations >= 0);
        assertTrue(stats.totalOperations >= 0);
        assertTrue(stats.activeThreads >= 0);
        assertTrue(stats.queuedTasks >= 0);
        
        System.out.println("✓ Queue statistics test passed");
    }

    @Test
    @Timeout(30)
    public void testSafeMemoryManagement() {
        System.out.println("Testing safe memory management...");
        
        // Test safe native operation
        float[] testData = new float[16];
        for (int i = 0; i < 16; i++) {
            testData[i] = (float) i;
        }
        
        float[] result = processor.safeNativeOperation(testData, "matrix");
        
        assertNotNull(result);
        assertEquals(16, result.length);
        
        System.out.println("✓ Safe memory management test passed");
    }

    @Test
    @Timeout(30)
    public void testOperationQueue() throws Exception {
        System.out.println("Testing operation queue...");
        
        // Create test operation
        ParallelRustVectorProcessor.VectorOperation testOperation = 
            new ParallelRustVectorProcessor.VectorOperation() {
                @Override
                public ParallelRustVectorProcessor.VectorOperationResult execute() {
                    try {
                        float[] result = RustVectorLibrary.vectorAddNalgebra(TEST_VECTOR_A, TEST_VECTOR_B);
                        return new ParallelRustVectorProcessor.VectorOperationResult(
                            getOperationId(), result, System.nanoTime());
                    } catch (Exception e) {
                        return new ParallelRustVectorProcessor.VectorOperationResult(
                            getOperationId(), e);
                    }
                }
                
                @Override
                public int getEstimatedWorkload() {
                    return 10;
                }
            };
        
        // Submit operation to queue
        CompletableFuture<ParallelRustVectorProcessor.VectorOperationResult> future = 
            processor.submitOperation(testOperation);
        
        // Wait for result
        ParallelRustVectorProcessor.VectorOperationResult result = future.get(5, TimeUnit.SECONDS);
        
        assertNotNull(result);
        assertNull(result.error);
        assertNotNull(result.result);
        
        float[] vectorResult = (float[]) result.result;
        assertEquals(3, vectorResult.length);
        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, vectorResult, 1e-6f);
        
        System.out.println("✓ Operation queue test passed");
    }

    @Test
    @Timeout(30)
    public void testErrorHandling() {
        System.out.println("Testing error handling...");
        
        // Test with invalid operation type
        assertThrows(IllegalArgumentException.class, () -> {
            processor.matrixMultiplyZeroCopy(TEST_VECTOR_A, TEST_VECTOR_B, "invalid_type");
        });
        
        // Test with mismatched batch sizes
        List<float[]> matricesA = Arrays.asList(IDENTITY_MATRIX);
        List<float[]> matricesB = Arrays.asList(IDENTITY_MATRIX, IDENTITY_MATRIX);
        
        assertThrows(IllegalArgumentException.class, () -> {
            processor.batchMatrixMultiply(matricesA, matricesB, "nalgebra");
        });
        
        System.out.println("✓ Error handling test passed");
    }

    @Test
    @Timeout(30)
    public void testConcurrentBatchProcessing() throws Exception {
        System.out.println("Testing concurrent batch processing...");
        
        int numConcurrentBatches = 5;
        List<CompletableFuture<List<float[]>>> futures = new ArrayList<>();
        
        for (int i = 0; i < numConcurrentBatches; i++) {
            List<float[]> matricesA = new ArrayList<>();
            List<float[]> matricesB = new ArrayList<>();
            
            for (int j = 0; j < 50; j++) {
                matricesA.add(IDENTITY_MATRIX.clone());
                matricesB.add(IDENTITY_MATRIX.clone());
            }
            
            CompletableFuture<List<float[]>> future = processor.batchMatrixMultiply(matricesA, matricesB, "nalgebra");
            futures.add(future);
        }
        
        // Wait for all concurrent batches to complete
        List<List<float[]>> results = new ArrayList<>();
        for (CompletableFuture<List<float[]>> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        
        // Verify all results
        for (List<float[]> batchResult : results) {
            assertNotNull(batchResult);
            assertEquals(50, batchResult.size());
            for (float[] result : batchResult) {
                assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f);
            }
        }
        
        System.out.println("✓ Concurrent batch processing test passed");
    }

    private static float[] createRandomMatrix() {
        float[] matrix = new float[16];
        Random random = new Random();
        for (int i = 0; i < 16; i++) {
            matrix[i] = random.nextFloat() * 10.0f;
        }
        return matrix;
    }

    private static void printArray(float[] array) {
        System.out.println(Arrays.toString(array));
    }
}