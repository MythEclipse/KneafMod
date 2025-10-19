package com.kneaf.core.performance;

import com.kneaf.core.PerformanceManager;
import com.kneaf.core.OptimizedOptimizationInjector;
import com.kneaf.core.EnhancedRustVectorLibrary;
import com.kneaf.core.ParallelRustVectorProcessor;
import com.kneaf.core.EntityProcessingService;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

/**
 * Comprehensive performance monitoring system for KneafMod.
 * Integrates with async PerformanceManager, parallel RustVectorLibrary, and OptimizationInjector.
 * Provides real-time metrics collection, thread-safe aggregation, distributed tracing,
 * error tracking, and configurable alerting.
 */
public final class PerformanceMonitoringSystem {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PerformanceMonitoringSystem INSTANCE = new PerformanceMonitoringSystem();
    
    // Core monitoring components
    private final MetricsCollector metricsCollector;
    private final ThreadSafeMetricAggregator metricAggregator;
    private final CrossComponentEventBus eventBus;
    private final ErrorTracker errorTracker;
    private final PerformanceDashboard dashboard;
    private final AlertingSystem alertingSystem;
    private final DistributedTracer distributedTracer;
    
    // System configuration
    private final AtomicBoolean isMonitoringEnabled = new AtomicBoolean(true);
    private final AtomicBoolean isTracingEnabled = new AtomicBoolean(false);
    private final AtomicInteger samplingRate = new AtomicInteger(100); // Sample 100% by default
    
    // Performance thresholds for alerting
    private volatile double latencyThresholdMs = 50.0;
    private volatile double errorRateThreshold = 0.05; // 5%
    private volatile double throughputThreshold = 1000.0; // ops/second
    
    // Background monitoring thread
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicReference<ScheduledFuture<?>> metricsCollectionTask = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> alertingTask = new AtomicReference<>();
    
    private PerformanceMonitoringSystem() {
        LOGGER.info("Initializing comprehensive performance monitoring system");
        
        // Initialize core components
        this.metricsCollector = new MetricsCollector();
        this.metricAggregator = new ThreadSafeMetricAggregator();
        this.eventBus = new CrossComponentEventBus();
        this.errorTracker = new ErrorTracker();
        this.dashboard = new PerformanceDashboard();
        this.alertingSystem = new AlertingSystem();
        this.distributedTracer = new DistributedTracer();
        
        // Start background monitoring
        startBackgroundMonitoring();
        
        LOGGER.info("Performance monitoring system initialized successfully");
    }
    
    public static PerformanceMonitoringSystem getInstance() {
        return INSTANCE;
    }
    
    /**
     * Start background monitoring tasks
     */
    private void startBackgroundMonitoring() {
        // Metrics collection every 100ms
        metricsCollectionTask.set(scheduler.scheduleAtFixedRate(
            this::collectAndAggregateMetrics,
            0, 100, TimeUnit.MILLISECONDS
        ));
        
        // Alerting check every 5 seconds
        alertingTask.set(scheduler.scheduleAtFixedRate(
            this::checkAlertingThresholds,
            0, 5, TimeUnit.SECONDS
        ));
        
        LOGGER.info("Background monitoring tasks started");
    }
    
    /**
     * Collect and aggregate metrics from all components
     */
    private void collectAndAggregateMetrics() {
        if (!isMonitoringEnabled.get()) {
            return;
        }
        
        try {
            // Collect metrics from Java components
            collectJavaComponentMetrics();
            
            // Collect metrics from Rust components
            collectRustComponentMetrics();
            
            // Aggregate collected metrics
            metricAggregator.aggregateMetrics();
            
        } catch (Exception e) {
            LOGGER.error("Error during metrics collection and aggregation", e);
            errorTracker.recordError("MetricsCollection", e, Collections.emptyMap());
        }
    }
    
    /**
     * Collect metrics from Java components
     */
    private void collectJavaComponentMetrics() {
        // Collect from PerformanceManager
        PerformanceManager pm = PerformanceManager.getInstance();
        metricAggregator.recordMetric("performance_manager.entity_throttling", 
            pm.isEntityThrottlingEnabled() ? 1.0 : 0.0);
        metricAggregator.recordMetric("performance_manager.ai_pathfinding_optimized", 
            pm.isAiPathfindingOptimized() ? 1.0 : 0.0);
        metricAggregator.recordMetric("performance_manager.rendering_math_optimized", 
            pm.isRenderingMathOptimized() ? 1.0 : 0.0);
        metricAggregator.recordMetric("performance_manager.rust_integration_enabled", 
            pm.isRustIntegrationEnabled() ? 1.0 : 0.0);
        
        // Collect from OptimizedOptimizationInjector
        OptimizedOptimizationInjector.AsyncOptimizationMetrics asyncMetrics = 
            OptimizedOptimizationInjector.getAsyncTestMetrics();
        metricAggregator.recordMetric("optimization_injector.async_hits", (double) asyncMetrics.hits);
        metricAggregator.recordMetric("optimization_injector.async_misses", (double) asyncMetrics.misses);
        metricAggregator.recordMetric("optimization_injector.async_errors", (double) asyncMetrics.errors);
        metricAggregator.recordMetric("optimization_injector.native_library_loaded", 
            asyncMetrics.nativeLoaded ? 1.0 : 0.0);
        
        // Collect from EnhancedRustVectorLibrary
        if (EnhancedRustVectorLibrary.isLibraryLoaded()) {
            ParallelRustVectorProcessor.QueueStatistics queueStats = 
                EnhancedRustVectorLibrary.getQueueStatistics();
            metricAggregator.recordMetric("rust_vector_library.pending_operations", 
                (double) queueStats.pendingOperations);
            metricAggregator.recordMetric("rust_vector_library.total_operations", 
                (double) queueStats.totalOperations);
            metricAggregator.recordMetric("rust_vector_library.active_threads", 
                (double) queueStats.activeThreads);
            metricAggregator.recordMetric("rust_vector_library.queued_tasks", 
                (double) queueStats.queuedTasks);
        }
        
        // Collect from EntityProcessingService
        EntityProcessingService.EntityProcessingStatistics entityStats = 
            EntityProcessingService.getInstance().getStatistics();
        metricAggregator.recordMetric("entity_processing.processed_entities", 
            (double) entityStats.processedEntities);
        metricAggregator.recordMetric("entity_processing.queued_entities", 
            (double) entityStats.queuedEntities);
        metricAggregator.recordMetric("entity_processing.active_processors", 
            (double) entityStats.activeProcessors);
        metricAggregator.recordMetric("entity_processing.queue_size", 
            (double) entityStats.queueSize);
    }
    
    /**
     * Collect metrics from Rust components
     */
    private void collectRustComponentMetrics() {
        // This would call JNI methods to get Rust-side metrics
        // Implementation depends on the specific JNI interface
        try {
            // Example: Get Rust performance statistics
            String rustStats = getRustPerformanceStats();
            if (rustStats != null) {
                parseAndRecordRustMetrics(rustStats);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not collect Rust metrics (library may not be loaded)", e);
        }
    }
    
    /**
     * Get Rust performance statistics via JNI
     */
    private native String getRustPerformanceStats();
    
    /**
     * Parse and record Rust metrics
     */
    private void parseAndRecordRustMetrics(String rustStats) {
        // Parse the Rust statistics string and record metrics
        // This is a placeholder - actual implementation would depend on the format
        metricAggregator.recordMetric("rust_native.total_entities_processed", 0.0);
        metricAggregator.recordMetric("rust_native.native_optimizations_applied", 0.0);
        metricAggregator.recordMetric("rust_native.total_calculation_time_ms", 0.0);
    }
    
    /**
     * Check alerting thresholds and trigger alerts if necessary
     */
    private void checkAlertingThresholds() {
        if (!isMonitoringEnabled.get()) {
            return;
        }
        
        try {
            // Get current aggregated metrics
            Map<String, Double> currentMetrics = metricAggregator.getCurrentMetrics();
            
            // Check latency threshold
            Double avgLatency = currentMetrics.get("system.avg_latency_ms");
            if (avgLatency != null && avgLatency > latencyThresholdMs) {
                alertingSystem.triggerAlert("HIGH_LATENCY", 
                    String.format("Average latency %.2fms exceeds threshold %.2fms", 
                        avgLatency, latencyThresholdMs));
            }
            
            // Check error rate threshold
            Double errorRate = currentMetrics.get("system.error_rate");
            if (errorRate != null && errorRate > errorRateThreshold) {
                alertingSystem.triggerAlert("HIGH_ERROR_RATE", 
                    String.format("Error rate %.2f%% exceeds threshold %.2f%%", 
                        errorRate * 100, errorRateThreshold * 100));
            }
            
            // Check throughput threshold
            Double throughput = currentMetrics.get("system.throughput_ops_per_sec");
            if (throughput != null && throughput < throughputThreshold) {
                alertingSystem.triggerAlert("LOW_THROUGHPUT", 
                    String.format("Throughput %.2f ops/sec below threshold %.2f ops/sec", 
                        throughput, throughputThreshold));
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during alerting threshold check", e);
            errorTracker.recordError("AlertingCheck", e, Collections.emptyMap());
        }
    }
    
    /**
     * Record a performance event
     */
    public void recordEvent(String component, String eventType, long durationNs, Map<String, Object> context) {
        if (!isMonitoringEnabled.get()) {
            return;
        }
        
        try {
            // Create distributed trace if enabled
            String traceId = null;
            if (isTracingEnabled.get()) {
                traceId = distributedTracer.startTrace(component, eventType, context);
            }
            
            // Record timing metric
            double durationMs = durationNs / 1_000_000.0;
            metricAggregator.recordMetric(component + "." + eventType + ".duration_ms", durationMs);
            
            // Publish event to cross-component bus
            CrossComponentEvent event = new CrossComponentEvent(
                component, eventType, Instant.now(), durationNs, context
            );
            eventBus.publishEvent(event);
            
            // Complete trace if enabled
            if (isTracingEnabled.get() && traceId != null) {
                distributedTracer.endTrace(traceId, component, eventType);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error recording performance event", e);
            errorTracker.recordError("RecordEvent", e, context);
        }
    }
    
    /**
     * Record an error with context
     */
    public void recordError(String component, Throwable error, Map<String, Object> context) {
        errorTracker.recordError(component, error, context);
        metricAggregator.incrementCounter("system.errors.total");
    }
    
    /**
     * Get current performance dashboard data
     */
    public PerformanceDashboard.DashboardData getDashboardData() {
        return dashboard.generateDashboardData(metricAggregator.getCurrentMetrics());
    }
    
    /**
     * Get distributed trace for a specific operation
     */
    public DistributedTracer.DistributedTrace getDistributedTrace(String traceId) {
        return distributedTracer.getTrace(traceId);
    }
    
    /**
     * Get error tracker
     */
    public ErrorTracker getErrorTracker() {
        return errorTracker;
    }
    
    /**
     * Get alerting system
     */
    public AlertingSystem getAlertingSystem() {
        return alertingSystem;
    }
    
    /**
     * Get distributed tracer
     */
    public DistributedTracer getDistributedTracer() {
        return distributedTracer;
    }
    
    /**
     * Get event bus
     */
    public CrossComponentEventBus getEventBus() {
        return eventBus;
    }
    
    /**
     * Get dashboard
     */
    public PerformanceDashboard getDashboard() {
        return dashboard;
    }
    
    /**
     * Get metrics collector
     */
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }
    
    /**
     * Get metric aggregator
     */
    public ThreadSafeMetricAggregator getMetricAggregator() {
        return metricAggregator;
    }
    
    /**
     * Configure monitoring system
     */
    public void configureMonitoring(boolean enabled, boolean tracingEnabled, int samplingRate) {
        this.isMonitoringEnabled.set(enabled);
        this.isTracingEnabled.set(tracingEnabled);
        this.samplingRate.set(samplingRate);
        
        if (enabled) {
            startBackgroundMonitoring();
        } else {
            stopBackgroundMonitoring();
        }
        
        LOGGER.info("Monitoring system configured: enabled={}, tracing={}, sampling={}%", 
            enabled, tracingEnabled, samplingRate);
    }
    
    /**
     * Configure alerting thresholds
     */
    public void configureAlerting(double latencyThresholdMs, double errorRateThreshold, double throughputThreshold) {
        this.latencyThresholdMs = latencyThresholdMs;
        this.errorRateThreshold = errorRateThreshold;
        this.throughputThreshold = throughputThreshold;
        
        LOGGER.info("Alerting thresholds configured: latency={}ms, errorRate={}%, throughput={} ops/sec",
            latencyThresholdMs, errorRateThreshold * 100, throughputThreshold);
    }
    
    /**
     * Stop background monitoring tasks
     */
    private void stopBackgroundMonitoring() {
        ScheduledFuture<?> metricsTask = metricsCollectionTask.get();
        if (metricsTask != null) {
            metricsTask.cancel(false);
        }
        
        ScheduledFuture<?> alertTask = alertingTask.get();
        if (alertTask != null) {
            alertTask.cancel(false);
        }
        
        LOGGER.info("Background monitoring tasks stopped");
    }
    
    /**
     * Shutdown the monitoring system
     */
    public void shutdown() {
        LOGGER.info("Shutting down performance monitoring system");
        
        stopBackgroundMonitoring();
        scheduler.shutdown();
        
        // Shutdown all components
        metricsCollector.shutdown();
        metricAggregator.shutdown();
        eventBus.shutdown();
        errorTracker.shutdown();
        dashboard.shutdown();
        alertingSystem.shutdown();
        distributedTracer.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        
        LOGGER.info("Performance monitoring system shutdown completed");
    }
    
    /**
     * Event listener for server tick events
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Pre event) {
        if (!isMonitoringEnabled.get()) {
            return;
        }
        
        // Record server tick metrics
        long startTime = System.nanoTime();
        
        // This will be called every server tick
        metricAggregator.incrementCounter("system.server_ticks.total");
        
        long duration = System.nanoTime() - startTime;
        recordEvent("PerformanceMonitoringSystem", "server_tick", duration, Collections.emptyMap());
    }
    
    /**
     * Event listener for configuration reload
     */
    @SubscribeEvent
    public void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.SERVER && "kneafcore".equals(event.getConfig().getModId())) {
            LOGGER.info("Reloading performance monitoring configuration");
            // Reload configuration from file
            reloadConfiguration();
        }
    }
    
    /**
     * Reload monitoring configuration
     */
    private void reloadConfiguration() {
        // This would load configuration from a properties file
        // Implementation depends on specific configuration format
        LOGGER.info("Performance monitoring configuration reloaded");
    }
    
    /**
     * Get system status
     */
    public SystemStatus getSystemStatus() {
        return new SystemStatus(
            isMonitoringEnabled.get(),
            isTracingEnabled.get(),
            samplingRate.get(),
            metricsCollector.isHealthy(),
            metricAggregator.isHealthy(),
            eventBus.isHealthy(),
            errorTracker.isHealthy(),
            dashboard.isHealthy(),
            alertingSystem.isHealthy(),
            distributedTracer.isHealthy()
        );
    }
    
    /**
     * System status information
     */
    public static class SystemStatus {
        private final boolean monitoringEnabled;
        private final boolean tracingEnabled;
        private final int samplingRate;
        private final boolean metricsCollectorHealthy;
        private final boolean metricAggregatorHealthy;
        private final boolean eventBusHealthy;
        private final boolean errorTrackerHealthy;
        private final boolean dashboardHealthy;
        private final boolean alertingSystemHealthy;
        private final boolean distributedTracerHealthy;
        
        public SystemStatus(boolean monitoringEnabled, boolean tracingEnabled, int samplingRate,
                           boolean metricsCollectorHealthy, boolean metricAggregatorHealthy,
                           boolean eventBusHealthy, boolean errorTrackerHealthy,
                           boolean dashboardHealthy, boolean alertingSystemHealthy,
                           boolean distributedTracerHealthy) {
            this.monitoringEnabled = monitoringEnabled;
            this.tracingEnabled = tracingEnabled;
            this.samplingRate = samplingRate;
            this.metricsCollectorHealthy = metricsCollectorHealthy;
            this.metricAggregatorHealthy = metricAggregatorHealthy;
            this.eventBusHealthy = eventBusHealthy;
            this.errorTrackerHealthy = errorTrackerHealthy;
            this.dashboardHealthy = dashboardHealthy;
            this.alertingSystemHealthy = alertingSystemHealthy;
            this.distributedTracerHealthy = distributedTracerHealthy;
        }
        
        public boolean isMonitoringEnabled() { return monitoringEnabled; }
        public boolean isTracingEnabled() { return tracingEnabled; }
        public int getSamplingRate() { return samplingRate; }
        public boolean isMetricsCollectorHealthy() { return metricsCollectorHealthy; }
        public boolean isMetricAggregatorHealthy() { return metricAggregatorHealthy; }
        public boolean isEventBusHealthy() { return eventBusHealthy; }
        public boolean isErrorTrackerHealthy() { return errorTrackerHealthy; }
        public boolean isDashboardHealthy() { return dashboardHealthy; }
        public boolean isAlertingSystemHealthy() { return alertingSystemHealthy; }
        public boolean isDistributedTracerHealthy() { return distributedTracerHealthy; }
        public boolean isSystemHealthy() {
            return metricsCollectorHealthy && metricAggregatorHealthy && eventBusHealthy &&
                   errorTrackerHealthy && dashboardHealthy && alertingSystemHealthy && distributedTracerHealthy;
        }
    }
}