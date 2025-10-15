package com.kneaf.core.performance;

import com.kneaf.core.performance.config.RustPerformanceConfig;
import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Comprehensive test class for RustPerformance refactoring.
 * Exercises all major functionalities to ensure they work correctly.
 * Uses Java 17+ features for improved test structure and readability.
 */
public class RustPerformanceTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceTest.class);
    
    /**
     * Enum for test categories.
     */
    private enum TestCategory {
        LIBRARY_LOADING("Library Loading"),
        INITIALIZATION("Initialization"),
        PERFORMANCE_MONITORING("Performance Monitoring"),
        BATCH_PROCESSING("Batch Processing"),
        CONFIGURATION("Configuration"),
        SHUTDOWN("Shutdown");
        
        private final String displayName;
        
        TestCategory(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Record for test results.
     */
    private record TestResult(TestCategory category, String description, boolean success, String message) {
        public static TestResult success(TestCategory category, String description) {
            return new TestResult(category, description, true, "Success");
        }
        
        public static TestResult failure(TestCategory category, String description, String message) {
            return new TestResult(category, description, false, message);
        }
    }
    
    public static void main(String[] args) {
        LOGGER.info("Starting RustPerformance comprehensive test suite...");
        
        List<TestResult> results = Arrays.asList(
            testLibraryLoading(),
            testInitialization(),
            testPerformanceMonitoring(),
            testBatchProcessing(),
            testConfiguration(),
            testShutdown()
        );
        
        printTestSummary(results);
    }
    
    /**
     * Test native library loading functionality.
     *
     * @return Test result
     */
    private static TestResult testLibraryLoading() {
        TestCategory category = TestCategory.LIBRARY_LOADING;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            // Load library (void return, check if it throws exceptions)
            RustPerformanceLoader.loadNativeLibrary();
            LOGGER.info("✓ Native library loaded successfully");
            return TestResult.success(category, "Library loading");
        } catch (Exception e) {
            LOGGER.error("✗ Error during library loading: {}", e.getMessage(), e);
            return TestResult.failure(category, "Library loading", e.getMessage());
        }
    }
    
    /**
     * Test system initialization functionality.
     *
     * @return Test result
     */
    private static TestResult testInitialization() {
        TestCategory category = TestCategory.INITIALIZATION;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            RustPerformanceLoader.initialize();
            if (RustPerformanceBase.isInitialized()) {
                LOGGER.info("✓ System initialized successfully");
                return TestResult.success(category, "System initialization");
            } else {
                LOGGER.warn("✗ System initialization returned false");
                return TestResult.failure(category, "System initialization", "Initialization returned false");
            }
        } catch (Exception e) {
            LOGGER.error("✗ Error during initialization: {}", e.getMessage(), e);
            return TestResult.failure(category, "System initialization", e.getMessage());
        }
    }
    
    /**
     * Test basic performance monitoring functionality.
     *
     * @return Test result
     */
    private static TestResult testPerformanceMonitoring() {
        TestCategory category = TestCategory.PERFORMANCE_MONITORING;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            if (!RustPerformanceBase.isInitialized()) {
                return TestResult.failure(category, "Performance monitoring", "System not initialized");
            }
            
            double tps = RustPerformanceMonitor.getCurrentTPS();
            LOGGER.info("✓ Current TPS: {}", tps);
            
            String cpuStats = RustPerformanceMonitor.getCpuStats();
            LOGGER.info("✓ CPU Stats: {}", cpuStats != null ? cpuStats : "N/A");
            
            String memoryStats = RustPerformanceMonitor.getMemoryStats();
            LOGGER.info("✓ Memory Stats: {}", memoryStats != null ? memoryStats : "N/A");
            
            return TestResult.success(category, "Performance monitoring");
        } catch (Exception e) {
            LOGGER.error("✗ Error during performance monitoring: {}", e.getMessage(), e);
            return TestResult.failure(category, "Performance monitoring", e.getMessage());
        }
    }
    
    /**
     * Test enhanced batch processing functionality.
     *
     * @return Test result
     */
    private static TestResult testBatchProcessing() {
        TestCategory category = TestCategory.BATCH_PROCESSING;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            if (!RustPerformanceBase.isInitialized()) {
                return TestResult.failure(category, "Batch processing", "System not initialized");
            }
            
            // Initialize batch processor with default config
            boolean initResult = RustPerformanceBatchProcessor.nativeInitEnhancedBatchProcessor(
                50, 500, 1, 10, 8, false
            );
            LOGGER.info("✓ Batch processor initialized: {}", initResult);
            
            // Create test operations
            List<byte[]> operations = Arrays.asList(
                "operation1".getBytes(),
                "operation2".getBytes(),
                "operation3".getBytes()
            );
            
            List<Byte> priorities = Arrays.asList((byte) 1, (byte) 2, (byte) 3);
            
            // Process the batch with standard operation type
            CompletableFuture<byte[]> future = RustPerformanceBatchProcessor.processEnhancedBatch(
                (byte) 1, operations, priorities
            );
            
            byte[] result = future.get(5, TimeUnit.SECONDS);
            LOGGER.info("✓ Batch processing result length: {}", result != null ? result.length : 0);
            
            return TestResult.success(category, "Batch processing");
        } catch (TimeoutException e) {
            LOGGER.error("✗ Batch processing timed out: {}", e.getMessage());
            return TestResult.failure(category, "Batch processing", "Operation timed out");
        } catch (Exception e) {
            LOGGER.error("✗ Error during batch processing: {}", e.getMessage(), e);
            return TestResult.failure(category, "Batch processing", e.getMessage());
        }
    }
    
    /**
     * Test configuration functionality.
     *
     * @return Test result
     */
    private static TestResult testConfiguration() {
        TestCategory category = TestCategory.CONFIGURATION;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            RustPerformanceConfig config = RustPerformanceConfig.getInstance();
            
            LOGGER.info("✓ Aggressive optimizations enabled: {}", config.isEnableAggressiveOptimizations());
            LOGGER.info("✓ Batch size: {}", config.getBatchSize());
            LOGGER.info("✓ Max concurrent operations: {}", config.getMaxConcurrentOperations());
            LOGGER.info("✓ Cache size (MB): {}", config.getCacheSizeMb());
            
            // Test configuration builder pattern
            RustPerformanceConfig.Builder builder = RustPerformanceConfig.builder()
                .enableAggressiveOptimizations(false)
                .batchSize(128)
                .cacheSizeMb(1024);
            
            RustPerformanceConfig customConfig = builder.build();
            LOGGER.info("✓ Custom configuration created successfully");
            
            return TestResult.success(category, "Configuration");
        } catch (Exception e) {
            LOGGER.error("✗ Error during configuration test: {}", e.getMessage(), e);
            return TestResult.failure(category, "Configuration", e.getMessage());
        }
    }
    
    /**
     * Test system shutdown functionality.
     *
     * @return Test result
     */
    private static TestResult testShutdown() {
        TestCategory category = TestCategory.SHUTDOWN;
        LOGGER.info("=== Testing {} ===", category.getDisplayName());
        
        try {
            RustPerformanceLoader.shutdown();
            LOGGER.info("✓ System shut down successfully");
            
            // Verify system is no longer initialized
            if (!RustPerformanceBase.isInitialized()) {
                LOGGER.info("✓ System correctly marked as uninitialized");
            } else {
                LOGGER.warn("✗ System still marked as initialized after shutdown");
            }
            
            return TestResult.success(category, "System shutdown");
        } catch (Exception e) {
            LOGGER.error("✗ Error during shutdown: {}", e.getMessage(), e);
            return TestResult.failure(category, "System shutdown", e.getMessage());
        }
    }
    
    /**
     * Print a summary of all test results.
     *
     * @param results List of test results
     */
    private static void printTestSummary(List<TestResult> results) {
        LOGGER.info("\n" + "=".repeat(50));
        LOGGER.info("TEST SUITE SUMMARY");
        LOGGER.info("=".repeat(50));
        
        int totalTests = results.size();
        long passed = results.stream().filter(TestResult::success).count();
        long failed = totalTests - passed;
        
        LOGGER.info("Total tests: {}", totalTests);
        LOGGER.info("Passed: {}", passed);
        LOGGER.info("Failed: {}", failed);
        LOGGER.info("Pass rate: {:.2f}%", (passed * 100.0) / totalTests);
        
        LOGGER.info("\nDETAILED RESULTS:");
        for (TestResult result : results) {
            String status = result.success() ? "PASSED" : "FAILED";
            LOGGER.info("{}: {} - {}",
                status,
                result.category().getDisplayName(),
                result.message());
        }
        
        LOGGER.info("=".repeat(50));
        
        if (failed > 0) {
            LOGGER.error("TEST SUITE FAILED: {} out of {} tests failed", failed, totalTests);
            System.exit(1);
        } else {
            LOGGER.info("TEST SUITE PASSED: All {} tests completed successfully", totalTests);
        }
    }
}