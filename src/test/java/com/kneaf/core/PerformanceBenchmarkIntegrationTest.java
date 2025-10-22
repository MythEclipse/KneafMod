package com.kneaf.core;

import com.kneaf.core.performance.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test with performance benchmarks.
 * Tests complete system performance, scalability, and optimization effectiveness.
 */
@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PerformanceBenchmarkIntegrationTest {

    private static final int WARMUP_ITERATIONS = 5;
    private static final int BENCHMARK_ITERATIONS = 20;
    private static final int LARGE_SCALE_OPERATIONS = 1000;
    
    private PerformanceManager performanceManager;
    private PerformanceMonitoringSystem monitoringSystem;
    private final AtomicInteger operationCount = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);

    @BeforeAll
    public static void setUp() {
        System.setProperty("rust.test.mode", "true");
        System.out.println("=== Performance Benchmark Integration Test Starting ===");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");
    }

    @AfterAll
    public static void tearDown() {
        EnhancedRustVectorLibrary.shutdown();
        System.clearProperty("rust.test.mode");
        System.out.println("=== Performance Benchmark Integration Test Completed ===");
    }

    @BeforeEach
    void setUpTest() {
        performanceManager = PerformanceManager.getInstance();
        monitoringSystem = PerformanceMonitoringSystem.getInstance();
        operationCount.set(0);
        totalProcessingTime.set(0);
    }

    /**
     * Test complete system initialization performance
     */
    @Test
    @DisplayName("Test system initialization performance")
    void testSystemInitializationPerformance() throws Exception {
        System.out.println("System Initialization Performance Test:");
        
        long[] initializationTimes = new long[BENCHMARK_ITERATIONS];
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long startTime = System.nanoTime();
            
            // Simulate system initialization
            CompletableFuture<Void> configFuture = performanceManager.loadConfigurationAsync();
            configFuture.get(5, TimeUnit.SECONDS);
            
            // Verify components are ready
            assertTrue(performanceManager.isEntityThrottlingEnabled());
            assertTrue(monitoringSystem.getSystemStatus().isSystemHealthy());
            
            long endTime = System.nanoTime();
            initializationTimes[i] = (endTime - startTime) / 1_000_000; // Convert to milliseconds
            
            System.out.println("  Initialization " + (i+1) + ": " + initializationTimes[i] + "ms");
        }
        
        // Calculate performance statistics
        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;
        
        for (long time : initializationTimes) {
            totalTime += time;
            minTime = Math.min(minTime, time);
            maxTime = Math.max(maxTime, time);
        }
        
        double avgTime = (double) totalTime / BENCHMARK_ITERATIONS;
        
        System.out.println("  Average initialization time: " + avgTime + "ms");
        System.out.println("  Min time: " + minTime + "ms");
        System.out.println("  Max time: " + maxTime + "ms");
        
        // Performance assertions
        assertTrue(avgTime < 1000, "Average initialization should be under 1 second: " + avgTime + "ms");
        assertTrue(maxTime < 2000, "Maximum initialization should be under 2 seconds: " + maxTime + "ms");
    }

    /**
     * Test parallel processing scalability benchmarks
     */
    @Test
    @DisplayName("Test parallel processing scalability benchmarks")
    void testParallelProcessingScalabilityBenchmarks() throws Exception {
        System.out.println("Parallel Processing Scalability Benchmarks:");
        
        int[] operationCounts = {10, 50, 100, 500, 1000};
        int[] threadCounts = {1, 2, 4, 8};
        
        for (int operationCount : operationCounts) {
            System.out.println("  Operations: " + operationCount);
            
            long[] threadTimings = new long[threadCounts.length];
            
            for (int t = 0; t < threadCounts.length; t++) {
                int numThreads = threadCounts[t];
                
                long startTime = System.nanoTime();
                
                // Create thread pool
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);
                CountDownLatch latch = new CountDownLatch(operationCount);
                AtomicInteger successfulOps = new AtomicInteger(0);
                
                for (int i = 0; i < operationCount; i++) {
                    executor.submit(() -> {
                        try {
                            // Mix different parallel operations
                            if (ThreadLocalRandom.current().nextInt(3) == 0) {
                                // Matrix multiplication
                                CompletableFuture<float[]> future = 
                                    EnhancedRustVectorLibrary.parallelMatrixMultiply(
                                        createIdentityMatrix(), createIdentityMatrix(), "nalgebra");
                                float[] result = future.get(500, TimeUnit.MILLISECONDS);
                                if (result != null) successfulOps.incrementAndGet();
                                
                            } else if (ThreadLocalRandom.current().nextInt(3) == 1) {
                                // Vector operations
                                CompletableFuture<Float> future = 
                                    EnhancedRustVectorLibrary.parallelVectorDot(
                                        createTestVector(), createTestVector(), "glam");
                                Float result = future.get(500, TimeUnit.MILLISECONDS);
                                if (result != null) successfulOps.incrementAndGet();
                                
                            } else {
                                // Batch operations
                                List<float[]> matricesA = new ArrayList<>();
                                List<float[]> matricesB = new ArrayList<>();
                                
                                for (int j = 0; j < 5; j++) {
                                    matricesA.add(createIdentityMatrix());
                                    matricesB.add(createIdentityMatrix());
                                }
                                
                                CompletableFuture<List<float[]>> batchFuture = 
                                    EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
                                List<float[]> results = batchFuture.get(500, TimeUnit.MILLISECONDS);
                                if (results != null && results.size() == 5) successfulOps.incrementAndGet();
                            }
                            
                        } catch (Exception e) {
                            // Operations might fail under load
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                
                // Wait for all operations to complete
                boolean completed = latch.await(30, TimeUnit.SECONDS);
                executor.shutdown();
                
                long endTime = System.nanoTime();
                threadTimings[t] = (endTime - startTime) / 1_000_000;
                
                System.out.println("    " + numThreads + " threads: " + threadTimings[t] + "ms (" + 
                                  successfulOps.get() + "/" + operationCount + " successful)");
                
                // Verify reasonable success rate
                double successRate = (double) successfulOps.get() / operationCount;
                assertTrue(successRate > 0.8, "Should maintain >80% success rate: " + successRate);
            }
            
            // Analyze scaling effectiveness
            analyzeScalingEffectiveness(operationCount + " operations", threadCounts, threadTimings);
        }
    }

    /**
     * Test traditional vector performance benchmarks (replaces zero-copy test)
     */
    @Test
    @DisplayName("Test traditional vector performance benchmarks")
    void testTraditionalVectorPerformanceBenchmarks() throws Exception {
        System.out.println("Traditional Vector Performance Benchmarks:");
        
        int[] dataSizes = {100, 1000, 10000, 100000};
        
        for (int dataSize : dataSizes) {
            System.out.println("  Data size: " + dataSize + " elements");
            
            long[] traditionalTimes = new long[BENCHMARK_ITERATIONS];
            
            float[] vectorA = generateRandomVector(dataSize);
            float[] vectorB = generateRandomVector(dataSize);
            
            // Warm up
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RustVectorLibrary.vectorDotGlam(vectorA, vectorB);
            }
            
            // Benchmark traditional operations
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                long startTime = System.nanoTime();
                float traditionalResult = RustVectorLibrary.vectorDotGlam(vectorA, vectorB);
                long endTime = System.nanoTime();
                traditionalTimes[i] = (endTime - startTime) / 1_000_000;
                
                assertTrue(traditionalResult > 0, "Traditional result should be positive");
            }
            
            // Calculate performance statistics
            double avgTraditionalTime = calculateAverage(traditionalTimes);
            
            System.out.println("    Traditional average: " + avgTraditionalTime + "ms");
            
            // Performance assertion
            assertTrue(avgTraditionalTime < 100, "Traditional vector operation should complete within reasonable time: " + avgTraditionalTime + "ms");
        }
    }

    /**
     * Test memory efficiency benchmarks
     */
    @Test
    @DisplayName("Test memory efficiency benchmarks")
    void testMemoryEfficiencyBenchmarks() throws Exception {
        System.out.println("Memory Efficiency Benchmarks:");
        
        // Get baseline memory usage
        System.gc();
        Thread.sleep(200);
        long baselineMemory = getUsedMemory();
        
        int operationCount = 500;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        
        long startTime = System.nanoTime();
        
        // Perform many operations
        for (int i = 0; i < operationCount; i++) {
            if (i % 3 == 0) {
                // Matrix operations
                CompletableFuture<float[]> future = 
                    EnhancedRustVectorLibrary.parallelMatrixMultiply(
                        createIdentityMatrix(), createIdentityMatrix(), "nalgebra");
                futures.add(future);
                
            } else if (i % 3 == 1) {
                // Vector operations
                CompletableFuture<Float> future = 
                    EnhancedRustVectorLibrary.parallelVectorDot(
                        createTestVector(), createTestVector(), "glam");
                futures.add(future);
                
            } else {
                // Batch operations
                List<float[]> matricesA = new ArrayList<>();
                List<float[]> matricesB = new ArrayList<>();
                
                for (int j = 0; j < 5; j++) {
                    matricesA.add(createIdentityMatrix());
                    matricesB.add(createIdentityMatrix());
                }
                
                CompletableFuture<List<float[]>> future = 
                    EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
                futures.add(future);
            }
        }
        
        // Wait for all operations to complete
        int completedOperations = 0;
        for (CompletableFuture<?> future : futures) {
            try {
                Object result = future.get(5, TimeUnit.SECONDS);
                if (result != null) {
                    completedOperations++;
                }
            } catch (Exception e) {
                // Some operations might fail
            }
        }
        
        long endTime = System.nanoTime();
        long processingDuration = (endTime - startTime) / 1_000_000;
        
        // Force garbage collection
        System.gc();
        Thread.sleep(500);
        
        long finalMemory = getUsedMemory();
        long memoryGrowth = finalMemory - baselineMemory;
        
        System.out.println("  Operations performed: " + completedOperations + "/" + operationCount);
        System.out.println("  Processing duration: " + processingDuration + "ms");
        System.out.println("  Memory growth: " + (memoryGrowth / 1024 / 1024) + " MB");
        System.out.println("  Memory per operation: " + (memoryGrowth / completedOperations / 1024) + " KB");
        
        // Memory efficiency assertions
        assertTrue(memoryGrowth < 200 * 1024 * 1024, "Memory growth should be reasonable: " + memoryGrowth + " bytes");
        assertTrue(completedOperations > operationCount * 0.8, "Should complete >80% of operations: " + completedOperations);
    }

    /**
     * Test concurrent cross-component performance benchmarks
     */
    @Test
    @DisplayName("Test concurrent cross-component performance benchmarks")
    void testConcurrentCrossComponentPerformanceBenchmarks() throws Exception {
        System.out.println("Concurrent Cross-Component Performance Benchmarks:");
        
        int numThreads = Runtime.getRuntime().availableProcessors() * 2;
        int operationsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(numThreads);
        AtomicInteger successfulOperations = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize start
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix operations across different components
                        if (j % 5 == 0) {
                            // PerformanceManager
                            boolean currentState = performanceManager.isEntityThrottlingEnabled();
                            performanceManager.setEntityThrottlingEnabled(!currentState);
                            successfulOperations.incrementAndGet();
                            
                        } else if (j % 5 == 1) {
                            // Rust operations
                            CompletableFuture<float[]> future = 
                                EnhancedRustVectorLibrary.parallelVectorAdd(
                                    createTestVector(), createTestVector(), "nalgebra");
                            float[] result = future.get(200, TimeUnit.MILLISECONDS);
                            if (result != null) successfulOperations.incrementAndGet();
                            
                        } else if (j % 5 == 2) {
                            // Monitoring
                            Map<String, Object> context = new HashMap<>();
                            context.put("thread_id", threadId);
                            context.put("operation_id", j);
                            monitoringSystem.recordEvent("ConcurrentBenchmark", "cross_component_op", 
                                50_000L, context);
                            successfulOperations.incrementAndGet();
                            
                        } else if (j % 5 == 3) {
                            // Traditional vector operation
                            CompletableFuture<Float> future =
                                EnhancedRustVectorLibrary.parallelVectorDot(
                                    createTestVector(), createTestVector(), "glam");
                            Float result = future.get(200, TimeUnit.MILLISECONDS);
                            if (result != null) successfulOperations.incrementAndGet();
                            
                        } else {
                            // Batch processing
                            List<float[]> matricesA = new ArrayList<>();
                            List<float[]> matricesB = new ArrayList<>();
                            
                            for (int k = 0; k < 3; k++) {
                                matricesA.add(createIdentityMatrix());
                                matricesB.add(createIdentityMatrix());
                            }
                            
                            CompletableFuture<List<float[]>> batchFuture = 
                                EnhancedRustVectorLibrary.batchMatrixMultiplyNalgebra(matricesA, matricesB);
                            List<float[]> results = batchFuture.get(500, TimeUnit.MILLISECONDS);
                            if (results != null && results.size() == 3) successfulOperations.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // Operations might fail under concurrent load
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all to complete
        boolean completed = completeLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        
        long endTime = System.nanoTime();
        long totalDuration = (endTime - startTime) / 1_000_000;
        
        int expectedOperations = numThreads * operationsPerThread;
        int actualOperations = successfulOperations.get();
        double successRate = (double) actualOperations / expectedOperations * 100;
        
        System.out.println("  Threads: " + numThreads);
        System.out.println("  Operations per thread: " + operationsPerThread);
        System.out.println("  Total operations: " + expectedOperations);
        System.out.println("  Successful operations: " + actualOperations);
        System.out.println("  Success rate: " + successRate + "%");
        System.out.println("  Total duration: " + totalDuration + "ms");
        System.out.println("  Operations per second: " + (actualOperations * 1000.0 / totalDuration));
        
        // Performance assertions
        assertTrue(completed, "All concurrent operations should complete");
        assertTrue(successRate > 75.0, "Should maintain >75% success rate under concurrent load: " + successRate + "%");
        assertTrue(totalDuration < expectedOperations * 50, "Should process operations efficiently: " + totalDuration + "ms");
    }

    /**
     * Test end-to-end system performance benchmarks
     */
    @Test
    @DisplayName("Test end-to-end system performance benchmarks")
    void testEndToEndSystemPerformanceBenchmarks() throws Exception {
        System.out.println("End-to-End System Performance Benchmarks:");
        
        int benchmarkIterations = 10;
        long[] endToEndTimes = new long[benchmarkIterations];
        
        for (int iteration = 0; iteration < benchmarkIterations; iteration++) {
            System.out.println("  Iteration " + (iteration + 1) + ":");
            
            long startTime = System.nanoTime();
            
            // Step 1: System initialization
            System.out.println("    Step 1: System initialization...");
            CompletableFuture<Void> configFuture = performanceManager.loadConfigurationAsync();
            configFuture.get(3, TimeUnit.SECONDS);
            
            // Step 2: Parallel processing workload
            System.out.println("    Step 2: Parallel processing workload...");
            List<CompletableFuture<?>> processingFutures = new ArrayList<>();
            
            for (int i = 0; i < 20; i++) {
                if (i % 2 == 0) {
                    processingFutures.add(
                        EnhancedRustVectorLibrary.parallelMatrixMultiply(
                            createIdentityMatrix(), createIdentityMatrix(), "nalgebra"));
                } else {
                    processingFutures.add(
                        EnhancedRustVectorLibrary.parallelVectorDot(
                            createTestVector(), createTestVector(), "glam"));
                }
            }
            
            for (CompletableFuture<?> future : processingFutures) {
                future.get(2, TimeUnit.SECONDS);
            }
            
            // Step 3: Entity processing simulation
            System.out.println("    Step 3: Entity processing simulation...");
            List<CompletableFuture<EntityProcessingService.EntityProcessingResult>> entityFutures = new ArrayList<>();
            
            for (int i = 0; i < 10; i++) {
                EntityProcessingService.EntityPhysicsData physicsData =
                    new EntityProcessingService.EntityPhysicsData(0.1 * i, -0.05, 0.2 * i);
                // Create mock processing results
                entityFutures.add(
                    CompletableFuture.completedFuture(
                        new EntityProcessingService.EntityProcessingResult(true, "Mock processing successful", physicsData)));
            }
            
            for (CompletableFuture<EntityProcessingService.EntityProcessingResult> future : entityFutures) {
                future.get(2, TimeUnit.SECONDS);
            }
            
            // Step 4: Performance monitoring and metrics collection
            System.out.println("    Step 4: Performance monitoring...");
            Map<String, Double> metrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
            PerformanceDashboard.DashboardData dashboardData = 
                monitoringSystem.getDashboard().generateDashboardData(metrics);
            
            assertNotNull(dashboardData);
            assertTrue(metrics.size() > 0);
            
            // Step 5: System health verification
            System.out.println("    Step 5: System health verification...");
            PerformanceMonitoringSystem.SystemStatus status = monitoringSystem.getSystemStatus();
            assertTrue(status.isSystemHealthy());
            
            long endTime = System.nanoTime();
            endToEndTimes[iteration] = (endTime - startTime) / 1_000_000;
            
            System.out.println("    Total time: " + endToEndTimes[iteration] + "ms");
        }
        
        // Calculate end-to-end performance statistics
        double avgEndToEndTime = calculateAverage(endToEndTimes);
        long minTime = Arrays.stream(endToEndTimes).min().orElse(0);
        long maxTime = Arrays.stream(endToEndTimes).max().orElse(0);
        
        System.out.println("End-to-End Performance Summary:");
        System.out.println("  Average time: " + avgEndToEndTime + "ms");
        System.out.println("  Min time: " + minTime + "ms");
        System.out.println("  Max time: " + maxTime + "ms");
        
        // Final performance assertions
        assertTrue(avgEndToEndTime < 5000, "Average end-to-end time should be under 5 seconds: " + avgEndToEndTime + "ms");
        assertTrue(maxTime < 10000, "Maximum end-to-end time should be under 10 seconds: " + maxTime + "ms");
        
        System.out.println("✓ End-to-end system performance benchmarks completed successfully");
    }

    // Helper methods

    private void analyzeScalingEffectiveness(String testName, int[] threadCounts, long[] durations) {
        System.out.println("  Scaling analysis for " + testName + ":");
        
        for (int i = 1; i < threadCounts.length; i++) {
            double speedup = (double) durations[i-1] / durations[i];
            double efficiency = speedup / (threadCounts[i] / threadCounts[i-1]) * 100;
            
            System.out.println("    " + threadCounts[i-1] + "→" + threadCounts[i] + " threads: " + 
                              speedup + "x speedup, " + efficiency + "% efficiency");
        }
    }

    private double calculateAverage(long[] times) {
        long total = 0;
        for (long time : times) {
            total += time;
        }
        return (double) total / times.length;
    }

    private float[] createIdentityMatrix() {
        return new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private float[] createTestVector() {
        return new float[] {1.0f, 2.0f, 3.0f};
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