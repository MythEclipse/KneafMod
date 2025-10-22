package com.kneaf.core.performance;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.HashMap;

/**
 * Integration test for the complete performance monitoring system.
 * Tests end-to-end functionality including all components working together.
 */
public class PerformanceMonitoringIntegrationTest {

    private PerformanceMonitoringSystem monitoringSystem;

    @BeforeEach
    void setUp() {
        monitoringSystem = PerformanceMonitoringSystem.getInstance();
    }

    @Test
    void testCompleteMonitoringWorkflow() throws InterruptedException {
        // Test a complete monitoring workflow from event recording to dashboard generation
        
        // 1. Record various performance events
        String traceId = monitoringSystem.getDistributedTracer().startTrace("IntegrationTest", "complete_workflow", new HashMap<>());
        
        // Simulate entity processing
        monitoringSystem.recordEvent("EntityProcessingService", "entity_update", 1_500_000L, 
            createContext("entity_type", "shadow_zombie", "operation", "ai_update"));
        
        // Simulate Rust vector operations
        monitoringSystem.recordEvent("EnhancedRustVectorLibrary", "matrix_multiplication", 500_000L,
            createContext("matrix_size", 16, "operation", "transform"));
        
        // Simulate optimization operations
        monitoringSystem.recordEvent("OptimizedOptimizationInjector", "pathfinding_optimization", 2_000_000L,
            createContext("path_length", 100, "nodes_explored", 500));
        
        // End the trace
        monitoringSystem.getDistributedTracer().endTrace(traceId, "IntegrationTest", "complete_workflow");
        
        // 2. Wait for metrics aggregation
        Thread.sleep(200);
        
        // 3. Verify metrics were collected and aggregated
        Map<String, Double> aggregatedMetrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
        
        assertTrue(aggregatedMetrics.containsKey("EntityProcessingService.entity_update.duration_ms"));
        assertTrue(aggregatedMetrics.containsKey("EnhancedRustVectorLibrary.matrix_multiplication.duration_ms"));
        assertTrue(aggregatedMetrics.containsKey("OptimizedOptimizationInjector.pathfinding_optimization.duration_ms"));
        
        // 4. Get current metrics for verification
        Map<String, Double> currentMetrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
        
        assertNotNull(currentMetrics);
        assertTrue(currentMetrics.size() > 0);
        
        // 5. Verify trace data
        DistributedTracer.DistributedTrace traceData = monitoringSystem.getDistributedTracer().getTrace(traceId);
        assertNotNull(traceData);
    }

    @Test
    void testErrorHandlingWorkflow() throws InterruptedException {
        // Test complete error handling workflow
        
        // Record some errors with different severities
        Map<String, Object> context1 = createContext("operation", "entity_spawn", "entity_type", "zombie");
        monitoringSystem.recordError("EntityProcessingService", 
            new RuntimeException("Entity spawn failed: insufficient memory"), context1);
        
        Map<String, Object> context2 = createContext("operation", "pathfinding", "path_length", 200);
        monitoringSystem.recordError("OptimizedOptimizationInjector", 
            new IllegalArgumentException("Invalid path parameters"), context2);
        
        Map<String, Object> context3 = createContext("operation", "matrix_calculation", "matrix_size", 16);
        monitoringSystem.recordError("EnhancedRustVectorLibrary", 
            new ArithmeticException("Division by zero in matrix operation"), context3);
        
        Thread.sleep(100);
        
        // Verify error tracking
        ErrorTracker errorTracker = monitoringSystem.getErrorTracker();
        
        // Check recent errors
        List<ErrorTracker.TrackedError> recentErrors = errorTracker.getRecentErrors(10);
        assertEquals(3, recentErrors.size());
        
        // Check error statistics
        Map<String, Long> errorStatsByComponent = errorTracker.getErrorStatisticsByComponent();
        assertTrue(errorStatsByComponent.containsKey("EntityProcessingService"));
        assertTrue(errorStatsByComponent.containsKey("OptimizedOptimizationInjector"));
        assertTrue(errorStatsByComponent.containsKey("EnhancedRustVectorLibrary"));
        
        // Check error patterns
        List<ErrorTracker.ErrorPattern> errorPatterns = errorTracker.getErrorPatterns(5);
        assertTrue(errorPatterns.size() > 0);
        
        // Check error rate statistics
        ErrorTracker.ErrorRateStatistics errorRateStats = errorTracker.getErrorRateStatistics();
        assertEquals(3, errorRateStats.getTotalErrors());
    }

    @Test
    void testAlertingWorkflow() throws InterruptedException {
        // Configure alerting thresholds
        monitoringSystem.configureAlerting(10.0, 0.1, 100.0);
        
        // Record events that should trigger alerts
        for (int i = 0; i < 20; i++) {
            // High latency events
            monitoringSystem.recordEvent("EntityProcessingService", "slow_operation", 20_000_000L,
                createContext("operation", "complex_calculation"));
            
            // High error rate
            if (i % 3 == 0) {
                monitoringSystem.recordError("EntityProcessingService", 
                    new RuntimeException("High error rate simulation"), new HashMap<>());
            }
            
            // Low throughput (few operations)
            Thread.sleep(100);
        }
        
        Thread.sleep(500); // Wait for alerting evaluation
        
        // Verify alerting system is working (alerts would be triggered and logged)
        AlertingSystem alertingSystem = monitoringSystem.getAlertingSystem();
        
        // The alerts would be triggered in the background monitoring thread
        // We can't directly test the alert triggering without mocking, but we can verify
        // that the metrics that would trigger alerts are present
        Map<String, Double> metrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
        
        // Verify high latency metrics are present
        assertTrue(metrics.containsKey("EntityProcessingService.slow_operation.duration_ms"));
        
        // Verify error metrics are present
        assertTrue(metrics.containsKey("system.errors.total"));
    }

    @Test
    void testCrossComponentEventWorkflow() throws InterruptedException {
        // Test cross-component event communication
        
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicInteger rustEventCount = new AtomicInteger(0);
        
        // Subscribe to Java component events
        monitoringSystem.getEventBus().subscribe("EntityProcessingService", "test_subscriber", 
            new CrossComponentEventBus.EventSubscriber() {
                @Override
                public void onEvent(CrossComponentEvent event) {
                    eventCount.incrementAndGet();
                }
            });
        
        // Subscribe to Rust component events
        monitoringSystem.getEventBus().subscribe("EnhancedRustVectorLibrary", "test_subscriber", 
            new CrossComponentEventBus.EventSubscriber() {
                @Override
                public void onEvent(CrossComponentEvent event) {
                    rustEventCount.incrementAndGet();
                }
            });
        
        // Record events from different components
        monitoringSystem.recordEvent("EntityProcessingService", "entity_spawn", 1_000_000L,
            createContext("entity_type", "creeper"));
        
        monitoringSystem.recordEvent("EnhancedRustVectorLibrary", "vector_calculation", 500_000L,
            createContext("vector_size", 1000));
        
        monitoringSystem.recordEvent("OptimizedOptimizationInjector", "optimization", 2_000_000L,
            createContext("optimization_type", "pathfinding"));
        
        Thread.sleep(200);
        
        // Verify events were published and received
        assertEquals(1, eventCount.get()); // EntityProcessingService event
        assertEquals(1, rustEventCount.get()); // EnhancedRustVectorLibrary event
        
        // OptimizedOptimizationInjector event should not be received by either subscriber
        assertEquals(1, eventCount.get());
        assertEquals(1, rustEventCount.get());
    }

    @Test
    void testConcurrentMonitoring() throws InterruptedException {
        // Test concurrent monitoring from multiple threads
        
        int numThreads = 10;
        int operationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Record events from different components
                        String component = "Thread" + threadId;
                        String operation = "operation_" + j;
                        
                        monitoringSystem.recordEvent(component, operation, 100_000L + j * 1000L,
                            createContext("thread_id", threadId, "operation_id", j));
                        
                        successCount.incrementAndGet();
                        
                        // Occasionally record errors
                        if (j % 10 == 0) {
                            monitoringSystem.recordError(component, 
                                new RuntimeException("Simulated error in thread " + threadId),
                                createContext("operation", operation));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error in thread " + threadId + ": " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        completeLatch.await();
        executor.shutdown();
        
        // Verify all operations completed
        assertEquals(numThreads * operationsPerThread, successCount.get());
        
        // Wait for metrics aggregation
        Thread.sleep(300);
        
        // Verify metrics were recorded correctly
        Map<String, Double> metrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
        
        // Should have recorded events from all threads
        boolean hasThreadMetrics = false;
        for (String metricName : metrics.keySet()) {
            if (metricName.startsWith("Thread")) {
                hasThreadMetrics = true;
                break;
            }
        }
        assertTrue(hasThreadMetrics, "Should have metrics from concurrent threads");
        
        // Verify error tracking
        ErrorTracker.ErrorRateStatistics errorStats = monitoringSystem.getErrorTracker().getErrorRateStatistics();
        assertTrue(errorStats.getTotalErrors() >= numThreads * (operationsPerThread / 10));
    }

    @Test
    void testSystemHealthMonitoring() {
        // Test system health monitoring
        
        PerformanceMonitoringSystem.SystemStatus status = monitoringSystem.getSystemStatus();
        
        assertNotNull(status);
        assertTrue(status.isMonitoringEnabled());
        assertTrue(status.isMetricsCollectorHealthy());
        assertTrue(status.isMetricAggregatorHealthy());
        assertTrue(status.isEventBusHealthy());
        assertTrue(status.isErrorTrackerHealthy());
        assertTrue(status.isDashboardHealthy());
        assertTrue(status.isAlertingSystemHealthy());
        assertTrue(status.isDistributedTracerHealthy());
        
        // System should be healthy after initialization
        assertTrue(status.isSystemHealthy());
    }

    @Test
    void testConfigurationManagement() throws InterruptedException {
        // Test configuration changes
        
        // Initial configuration
        monitoringSystem.configureMonitoring(true, true, 50);
        monitoringSystem.configureAlerting(50.0, 0.05, 1000.0);
        
        PerformanceMonitoringSystem.SystemStatus status = monitoringSystem.getSystemStatus();
        assertTrue(status.isMonitoringEnabled());
        assertTrue(status.isTracingEnabled());
        assertEquals(50, status.getSamplingRate());
        
        // Disable monitoring
        monitoringSystem.configureMonitoring(false, false, 0);
        
        Thread.sleep(100);
        
        status = monitoringSystem.getSystemStatus();
        assertFalse(status.isMonitoringEnabled());
        assertFalse(status.isTracingEnabled());
        
        // Re-enable monitoring
        monitoringSystem.configureMonitoring(true, true, 100);
        
        Thread.sleep(100);
        
        status = monitoringSystem.getSystemStatus();
        assertTrue(status.isMonitoringEnabled());
        assertTrue(status.isTracingEnabled());
        assertEquals(100, status.getSamplingRate());
    }

    @Test
    void testPerformanceUnderLoad() throws InterruptedException {
        // Test system performance under heavy load
        
        int numOperations = 10000;
        long startTime = System.nanoTime();
        
        // Record many events quickly
        for (int i = 0; i < numOperations; i++) {
            monitoringSystem.recordEvent("LoadTest", "high_frequency_operation", 100_000L,
                createContext("operation_id", i, "batch_id", i / 100));
            
            if (i % 100 == 0) {
                monitoringSystem.recordError("LoadTest", 
                    new RuntimeException("Load test error " + i), new HashMap<>());
            }
        }
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Verify performance - should complete quickly even under load
        assertTrue(durationMs < 5000, "Load test took too long: " + durationMs + "ms");
        
        // Wait for aggregation
        Thread.sleep(200);
        
        // Verify metrics were recorded
        Map<String, Double> metrics = monitoringSystem.getMetricAggregator().getCurrentMetrics();
        assertTrue(metrics.containsKey("LoadTest.high_frequency_operation.duration_ms"));
        
        // Verify error tracking
        ErrorTracker.ErrorRateStatistics errorStats = monitoringSystem.getErrorTracker().getErrorRateStatistics();
        assertEquals(numOperations / 100, errorStats.getTotalErrors());
    }

    private Map<String, Object> createContext(Object... keyValuePairs) {
        Map<String, Object> context = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            context.put(keyValuePairs[i].toString(), keyValuePairs[i + 1]);
        }
        return context;
    }
}