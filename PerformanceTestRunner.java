import com.kneaf.core.chunkstorage.SwapPerformanceValidationTest;
import com.kneaf.core.chunkstorage.serialization.FastNbtBenchmark;

/**
 * Simple runner for performance tests to bypass Gradle test discovery issues.
 */
public class PerformanceTestRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Starting Performance Test Runner ===");
        
        // Run Swap Performance Validation Tests
        System.out.println("\n1. Running Swap Performance Validation Tests...");
        SwapPerformanceValidationTest swapTest = new SwapPerformanceValidationTest();
        
        // Skip setup/teardown since they're JUnit lifecycle methods
        // Just run the test methods directly
        swapTest.testSwapOperationTimingMetrics();
        swapTest.testThroughputPerformance();
        swapTest.testLatencyPerformance();
        swapTest.testPerformanceUnderLoad();
        swapTest.testPerformanceMetricsAccuracy();
        swapTest.testBulkOperationPerformance();
        
        // Run FastNBT Benchmark
        System.out.println("\n2. Running FastNBT Benchmark...");
        FastNbtBenchmark benchmark = new FastNbtBenchmark();
        benchmark.runComprehensiveBenchmark();
        
        System.out.println("\n=== All Performance Tests Completed ===");
    }
}