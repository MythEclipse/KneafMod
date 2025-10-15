package com.kneaf.core.performance;

import com.kneaf.core.performance.config.RustPerformanceConfig;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test class for RustPerformance refactoring.
 * Exercises all major functionalities to ensure they work correctly.
 */
public class RustPerformanceTest {
    public static void main(String[] args) {
        System.out.println("Starting RustPerformance test...");
        
        try {
            // Test 1: Library loading
            System.out.println("\n=== Test 1: Library Loading ===");
            RustPerformanceLoader.loadNativeLibrary();
            System.out.println("Native library loaded successfully");
            
            // Test 2: Initialization
            System.out.println("\n=== Test 2: Initialization ===");
            RustPerformanceLoader.initialize();
            System.out.println("Rust performance monitoring initialized successfully");
            
            // Test 3: Basic performance monitoring
            System.out.println("\n=== Test 3: Basic Performance Monitoring ===");
            double tps = RustPerformanceMonitor.getCurrentTPS();
            System.out.println("Current TPS: " + tps);
            
            String cpuStats = RustPerformanceMonitor.getCpuStats();
            System.out.println("CPU Stats: " + cpuStats);
            
            String memoryStats = RustPerformanceMonitor.getMemoryStats();
            System.out.println("Memory Stats: " + memoryStats);
            
            // Test 4: Batch processing
            System.out.println("\n=== Test 4: Batch Processing ===");
            
            // Initialize batch processor with default config
            boolean initResult = RustPerformanceBatchProcessor.nativeInitEnhancedBatchProcessor(
                50, 500, 1, 10, 8, false
            );
            System.out.println("Batch processor initialized: " + initResult);
            
            // Create a simple batch operation
            List<byte[]> operations = Arrays.asList(
                "operation1".getBytes(),
                "operation2".getBytes(),
                "operation3".getBytes()
            );
            
            List<Byte> priorities = Arrays.asList((byte) 1, (byte) 2, (byte) 3);
            
            // Process the batch
            CompletableFuture<byte[]> future = RustPerformanceBatchProcessor.processEnhancedBatch(
                (byte) 1, operations, priorities
            );
            
            // Wait for result with timeout
            byte[] result = future.get(5, TimeUnit.SECONDS);
            System.out.println("Batch processing result length: " + (result != null ? result.length : 0));
            
            // Test 5: Configuration
            System.out.println("\n=== Test 5: Configuration ===");
            RustPerformanceConfig config = RustPerformanceConfig.getInstance();
            System.out.println("Aggressive optimizations enabled: " + config.isEnableAggressiveOptimizations());
            System.out.println("Batch size: " + config.getBatchSize());
            
            // Test 6: Shutdown
            System.out.println("\n=== Test 6: Shutdown ===");
            RustPerformanceLoader.shutdown();
            System.out.println("Rust performance monitoring shut down successfully");
            
            System.out.println("\nAll tests completed successfully!");
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}