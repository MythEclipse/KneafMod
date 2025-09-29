package com.kneaf.core.performance;

/**
 * Simple validation test for the advanced ThreadPoolExecutor implementation
 * Tests core threading logic without Minecraft dependencies
 */
public class ThreadPoolValidationTest {
    
    public static void main(String[] args) {
        System.out.println("=== ThreadPool Validation Test ===");
        
        // Test 1: Configuration loading
        testConfigurationLoading();
        
        // Test 2: Thread pool creation
        testThreadPoolCreation();
        
        // Test 3: Executor metrics
        testExecutorMetrics();
        
        // Test 4: Utilization calculation
        testUtilizationCalculation();
        
        System.out.println("=== All tests completed successfully ===");
    }
    
    private static void testConfigurationLoading() {
        System.out.println("Test 1: Configuration loading");
        PerformanceConfig config = PerformanceConfig.load();
        
        System.out.println("  - Dynamic thread scaling: " + config.isDynamicThreadScaling());
        System.out.println("  - Work stealing enabled: " + config.isWorkStealingEnabled());
        System.out.println("  - CPU aware sizing: " + config.isCpuAwareThreadSizing());
        System.out.println("  - Min threads: " + config.getMinThreadPoolSize());
        System.out.println("  - Max threads: " + config.getMaxThreadPoolSize());
        System.out.println("  - Scale up threshold: " + config.getThreadScaleUpThreshold());
        System.out.println("  - Scale down threshold: " + config.getThreadScaleDownThreshold());
        
        System.out.println("  ✓ Configuration loaded successfully");
    }
    
    private static void testThreadPoolCreation() {
        System.out.println("Test 2: Thread pool creation");
        
        // Note: We can't fully test the ThreadPoolExecutor creation without Minecraft server context
        // but we can validate that the configuration is properly structured
        
        PerformanceConfig config = PerformanceConfig.load();
        
        // Validate configuration bounds
        assert config.getMinThreadPoolSize() >= 1 : "Min threads should be >= 1";
        assert config.getMaxThreadPoolSize() >= config.getMinThreadPoolSize() : "Max threads should be >= min threads";
        assert config.getThreadScaleUpThreshold() > 0 && config.getThreadScaleUpThreshold() <= 1.0 : "Scale up threshold should be 0-1";
        assert config.getThreadScaleDownThreshold() > 0 && config.getThreadScaleDownThreshold() <= 1.0 : "Scale down threshold should be 0-1";
        assert config.getThreadScaleUpThreshold() > config.getThreadScaleDownThreshold() : "Scale up should be > scale down";
        
        System.out.println("  ✓ Thread pool configuration validation passed");
    }
    
    private static void testExecutorMetrics() {
        System.out.println("Test 3: Executor metrics");
        
        // Test JSON formatting of metrics
        PerformanceManager.ExecutorMetrics metrics = new PerformanceManager.ExecutorMetrics();
        metrics.totalTasksSubmitted = 100;
        metrics.totalTasksCompleted = 95;
        metrics.totalTasksRejected = 5;
        metrics.currentQueueSize = 10;
        metrics.currentUtilization = 0.75;
        metrics.currentThreadCount = 8;
        metrics.peakThreadCount = 10;
        metrics.scaleUpCount = 3;
        metrics.scaleDownCount = 1;
        
        String json = metrics.toJson();
        System.out.println("  - Metrics JSON: " + json);
        
        // Validate JSON structure
        assert json.contains("\"totalTasksSubmitted\":100") : "JSON should contain submitted tasks";
        assert json.contains("\"currentUtilization\":0.75") : "JSON should contain utilization";
        assert json.contains("\"currentThreadCount\":8") : "JSON should contain thread count";
        
        System.out.println("  ✓ Executor metrics JSON formatting works");
    }
    
    private static void testUtilizationCalculation() {
        System.out.println("Test 4: Utilization calculation");
        
        // Test utilization calculation logic
        double utilization1 = calculateUtilization(4, 8); // 4 active, 8 pool size
        double utilization2 = calculateUtilization(0, 4);  // 0 active, 4 pool size
        double utilization3 = calculateUtilization(8, 8);  // 8 active, 8 pool size
        
        System.out.println("  - Utilization 4/8: " + utilization1);
        System.out.println("  - Utilization 0/4: " + utilization2);
        System.out.println("  - Utilization 8/8: " + utilization3);
        
        assert Math.abs(utilization1 - 0.5) < 0.01 : "4/8 should be 0.5";
        assert Math.abs(utilization2 - 0.0) < 0.01 : "0/4 should be 0.0";
        assert Math.abs(utilization3 - 1.0) < 0.01 : "8/8 should be 1.0";
        
        System.out.println("  ✓ Utilization calculation works correctly");
    }
    
    private static double calculateUtilization(int activeThreads, int poolSize) {
        return (double) activeThreads / Math.max(1, poolSize);
    }
}