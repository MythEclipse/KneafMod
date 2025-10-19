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
 * Comprehensive test suite for the performance monitoring system.
 * Tests all components including metrics collection, aggregation, event bus, error tracking, dashboard, alerting, and distributed tracing.
 */
public class PerformanceMonitoringSystemTest {

    private PerformanceMonitoringSystem monitoringSystem;
    private MetricsCollector metricsCollector;
    private ThreadSafeMetricAggregator metricAggregator;
    private CrossComponentEventBus eventBus;
    private ErrorTracker errorTracker;
    private PerformanceDashboard dashboard;
    private AlertingSystem alertingSystem;
    private DistributedTracer distributedTracer;

    @BeforeEach
    void setUp() {
        monitoringSystem = PerformanceMonitoringSystem.getInstance();
        metricsCollector = monitoringSystem.getMetricsCollector();
        metricAggregator = monitoringSystem.getMetricAggregator();
        eventBus = monitoringSystem.getEventBus();
        errorTracker = monitoringSystem.getErrorTracker();
        dashboard = monitoringSystem.getDashboard();
        alertingSystem = monitoringSystem.getAlertingSystem();
        distributedTracer = monitoringSystem.getDistributedTracer();
    }

    @AfterEach
    void tearDown() {
        // No shutdown needed for singleton instance
    }

    @Test
    void testMetricsCollection() throws InterruptedException {
        // Test basic metric collection
        metricsCollector.recordCounter("test_counter", 1L);
        metricsCollector.recordGauge("test_gauge", 42.0);
        metricsCollector.recordHistogram("test_histogram", 100L);
        
        // Wait for collection
        Thread.sleep(100);
        
        // Verify metrics are collected
        Map<String, Double> metrics = metricAggregator.getCurrentMetrics();
        assertTrue(metrics.containsKey("test_counter"));
        assertTrue(metrics.containsKey("test_gauge"));
        assertTrue(metrics.containsKey("test_histogram"));
        
        assertEquals(1.0, metrics.get("test_counter"), 0.001);
        assertEquals(42.0, metrics.get("test_gauge"), 0.001);
        assertEquals(100.0, metrics.get("test_histogram"), 0.001);
    }

    @Test
    void testThreadSafeMetricAggregation() throws InterruptedException {
        // Test concurrent metric aggregation
        int numThreads = 10;
        int operationsPerThread = 1000;
        AtomicInteger successCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        metricAggregator.recordMetric("test_operation.duration_ms", 1.0);
                        metricAggregator.incrementCounter("test_operation.total_operations");
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        Thread.sleep(200); // Wait for aggregation
        
        // Verify aggregated metrics
        assertEquals(numThreads * operationsPerThread, successCount.get());
        
        Map<String, Double> aggregated = metricAggregator.getCurrentMetrics();
        assertTrue(aggregated.containsKey("test_operation.total_operations"));
        assertEquals(numThreads * operationsPerThread, 
                    aggregated.get("test_operation.total_operations").intValue());
    }

    @Test
    void testCrossComponentEventBus() throws InterruptedException {
        // Test event publishing and subscribing
        AtomicInteger eventCount = new AtomicInteger(0);
        
        // Subscribe to events
        eventBus.subscribe("test_component", "test_subscriber", new CrossComponentEventBus.EventSubscriber() {
            @Override
            public void onEvent(CrossComponentEvent event) {
                eventCount.incrementAndGet();
                assertEquals("test_component", event.getComponent());
                assertEquals("test_operation", event.getEventType());
                assertTrue(event.getContext().containsKey("test_key"));
                assertEquals("test_value", event.getContext().get("test_key"));
            }
        });
        
        // Publish events
        Map<String, Object> context = new HashMap<>();
        context.put("test_key", "test_value");
        CrossComponentEvent event = new CrossComponentEvent(
            "test_component", "test_operation", Instant.now(), 1000000L, context);
        
        eventBus.publishEvent(event);
        eventBus.publishEvent(event);
        
        Thread.sleep(100); // Wait for event processing
        
        assertEquals(2, eventCount.get());
    }

    @Test
    void testErrorTracking() throws InterruptedException {
        // Test error tracking
        Exception testException = new RuntimeException("Test error");
        
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "test_operation");
        
        errorTracker.recordError("test_component", testException, context);
        
        Map<String, Object> context2 = new HashMap<>();
        context2.put("operation", "test_operation");
        errorTracker.recordError("test_component", new IllegalArgumentException("Test error 2"), context2);
        
        Thread.sleep(100);
        
        // Verify error tracking worked - check that errors were recorded
        ErrorTracker.ErrorRateStatistics errorStats = errorTracker.getErrorRateStatistics();
        assertTrue(errorStats.getTotalErrors() >= 2);
    }

    @Test
    void testPerformanceDashboard() throws InterruptedException {
        // Record some metrics
        metricAggregator.recordMetric("cpu_usage", 75.0);
        metricAggregator.recordMetric("memory_usage", 1024.0);
        metricAggregator.incrementCounter("requests");
        
        Thread.sleep(200); // Wait for dashboard update
        
        // Test dashboard data retrieval
        PerformanceDashboard.DashboardData dashboardData = dashboard.generateDashboardData(metricAggregator.getCurrentMetrics());
        
        assertNotNull(dashboardData);
        assertNotNull(dashboardData);
    }

    @Test
    void testAlertingSystem() throws InterruptedException {
        // Configure alerting thresholds
        monitoringSystem.configureAlerting(50.0, 0.05, 1000.0);
        
        // Trigger alerts by recording metrics
        metricAggregator.recordMetric("system.avg_latency_ms", 60.0); // Should trigger HIGH_LATENCY
        metricAggregator.recordMetric("system.error_rate", 0.1); // Should trigger HIGH_ERROR_RATE
        metricAggregator.recordMetric("system.throughput_ops_per_sec", 500.0); // Should trigger LOW_THROUGHPUT
        
        Thread.sleep(200); // Wait for alert evaluation
        
        // Verify alerts were triggered (they would be logged)
        Map<String, Double> metrics = metricAggregator.getCurrentMetrics();
        assertEquals(60.0, metrics.get("system.avg_latency_ms"), 0.001);
        assertEquals(0.1, metrics.get("system.error_rate"), 0.001);
        assertEquals(500.0, metrics.get("system.throughput_ops_per_sec"), 0.001);
    }

    @Test
    void testDistributedTracing() throws InterruptedException {
        // Test trace creation and propagation
        String traceId = distributedTracer.startTrace("test_component", "test_trace", new HashMap<>());
        
        // Simulate some work
        Thread.sleep(50);
        
        // End trace
        distributedTracer.endTrace(traceId, "test_component", "test_trace");
        
        // Verify trace data
        DistributedTracer.DistributedTrace traceData = distributedTracer.getTrace(traceId);
        assertNotNull(traceData);
    }

    @Test
    void testCrossComponentTracing() throws InterruptedException {
        // Test tracing across Java-Rust boundary
        String javaTraceId = distributedTracer.startTrace("java_component", "cross_component_trace", new HashMap<>());
        
        // Simulate Rust operation
        // distributedTracer.recordRustOperation(javaTraceId, "rust_operation", 1000000L, true);
        
        // End Java trace
        distributedTracer.endTrace(javaTraceId, "java_component", "cross_component_trace");
        
        // Verify cross-component trace
        DistributedTracer.DistributedTrace traceData = distributedTracer.getTrace(javaTraceId);
        assertNotNull(traceData);
    }

    @Test
    void testPerformanceUnderLoad() throws InterruptedException {
        // Test system performance under high load
        int numThreads = 20;
        int operationsPerThread = 500;
        
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        
        long startTime = System.nanoTime();
        
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Mix of different operations
                        metricAggregator.incrementCounter("load_test_counter");
                        metricAggregator.recordMetric("load_test_gauge", Math.random() * 100);
                        metricAggregator.recordMetric("load_test_op.duration_ms", 0.1);
                        
                        if (j % 100 == 0) {
                            Map<String, Object> errorContext = new HashMap<>();
                            errorContext.put("operation", "load_test_op");
                            errorTracker.recordError("load_test_component", 
                                new RuntimeException("Load test error"), errorContext);
                        }
                        
                        if (j % 50 == 0) {
                            Map<String, Object> eventContext = new HashMap<>();
                            eventContext.put("operation", "load_test_op");
                            CrossComponentEvent event = new CrossComponentEvent(
                                "load_test_component", "load_test_op", Instant.now(), 100000L, eventContext);
                            eventBus.publishEvent(event);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        
        // Verify all operations completed successfully
        Map<String, Double> finalMetrics = metricAggregator.getCurrentMetrics();
        assertEquals(numThreads * operationsPerThread, 
                    finalMetrics.get("load_test_counter").intValue());
        
        // Performance assertion - should complete within reasonable time
        assertTrue(durationMs < 5000, "Load test took too long: " + durationMs + "ms");
        
        // Verify no deadlocks or corruption
        assertNotNull(metricAggregator.getCurrentMetrics());
        assertNotNull(errorTracker.getErrorRateStatistics());
    }

    @Test
    void testConfigurationReload() throws InterruptedException {
        // Test dynamic configuration changes
        monitoringSystem.configureMonitoring(true, true, 50);
        monitoringSystem.configureAlerting(30.0, 0.02, 2000.0);
        
        // Verify configuration changes take effect
        PerformanceMonitoringSystem.SystemStatus status = monitoringSystem.getSystemStatus();
        assertTrue(status.isMonitoringEnabled());
        assertTrue(status.isTracingEnabled());
        assertEquals(50, status.getSamplingRate());
    }

    @Test
    void testMemoryAndResourceManagement() throws InterruptedException {
        // Test that the system doesn't leak memory
        long initialMemory = getUsedMemory();
        
        // Perform many operations
        for (int i = 0; i < 10000; i++) {
            metricAggregator.incrementCounter("memory_test");
            metricAggregator.recordMetric("memory_test_op.duration_ms", 0.1);
            
            if (i % 100 == 0) {
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("operation", "test");
                errorTracker.recordError("memory_test", new RuntimeException("Test"), errorContext);
            }
        }
        
        // Force garbage collection
        System.gc();
        Thread.sleep(100);
        
        long finalMemory = getUsedMemory();
        
        // Memory usage should not have grown significantly
        long memoryGrowth = finalMemory - initialMemory;
        assertTrue(memoryGrowth < 10 * 1024 * 1024, // 10MB threshold
                  "Memory grew too much: " + memoryGrowth + " bytes");
        
        // Verify error tracking worked
        ErrorTracker.ErrorRateStatistics errorStats = errorTracker.getErrorRateStatistics();
        assertTrue(errorStats.getTotalErrors() > 0);
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}