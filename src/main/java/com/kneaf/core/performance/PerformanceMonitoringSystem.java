package com.kneaf.core.performance;

import com.kneaf.core.PerformanceManager;
import com.kneaf.core.OptimizationInjector;
import com.kneaf.core.ParallelRustVectorProcessor;
import com.kneaf.core.performance.model.QueueStatistics;
import com.kneaf.core.async.AsyncMetricsCollector;
import com.kneaf.core.EntityProcessingService;
import com.kneaf.core.model.EntityProcessingStatistics;
import com.kneaf.core.RustNativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Minecraft-specific imports commented out for test compatibility
// import com.mojang.logging.LogUtils;
// import net.neoforged.fml.config.ModConfig;
// import net.neoforged.fml.event.config.ModConfigEvent;
// import net.neoforged.neoforge.event.tick.ServerTickEvent;
// import net.neoforged.bus.api.SubscribeEvent;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;

/**
 * Optimized performance monitoring system for KneafMod dengan adaptive
 * monitoring,
 * sampling strategies, dan streaming aggregation untuk overhead minimal.
 * Integrates dengan async PerformanceManager, ParallelRustVectorProcessor, dan
 * OptimizationInjector.
 * Provides real-time metrics collection, lock-free aggregation, distributed
 * tracing,
 * error tracking, dan configurable alerting dengan adaptive frequency.
 */
public final class PerformanceMonitoringSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceMonitoringSystem.class);
    private static final PerformanceMonitoringSystem INSTANCE = new PerformanceMonitoringSystem();

    // Core monitoring components
    private final AsyncMetricsCollector metricsCollector;
    private final ThreadSafeMetricAggregator metricAggregator;
    private final CrossComponentEventBus eventBus;
    private final ErrorTracker errorTracker;
    private final AlertingSystem alertingSystem;
    private final DistributedTracer distributedTracer;

    // System configuration dengan adaptive monitoring
    private final AtomicBoolean isMonitoringEnabled = new AtomicBoolean(true);
    private final AtomicBoolean isTracingEnabled = new AtomicBoolean(false);
    private final AtomicInteger samplingRate = new AtomicInteger(100); // Sample 100% by default
    private final AtomicInteger adaptiveSamplingRate = new AtomicInteger(100); // Adaptive sampling rate
    private final AtomicInteger systemLoad = new AtomicInteger(0); // System load indicator (0-100)

    // Performance thresholds for alerting
    private volatile double latencyThresholdMs = 50.0;
    private volatile double errorRateThreshold = 0.05; // 5%
    private volatile double throughputThreshold = 1000.0; // ops/second

    // Adaptive monitoring configuration
    private volatile int highLoadThreshold = 80; // Threshold untuk high load
    private volatile int mediumLoadThreshold = 60; // Threshold untuk medium load
    private volatile int minSamplingRate = 10; // Minimum sampling rate saat load tinggi
    private volatile int adaptiveCollectionInterval = 100; // Default 100ms

    // Background monitoring thread dengan adaptive frequency
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicReference<ScheduledFuture<?>> metricsCollectionTask = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> alertingTask = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> adaptiveMonitoringTask = new AtomicReference<>();

    // Streaming aggregation components
    private final ConcurrentLinkedQueue<MetricEvent> metricEventQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong lastMetricAggregationTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger metricEventCounter = new AtomicInteger(0);

    /**
     * Metric event class untuk streaming aggregation
     */
    private static class MetricEvent {
        private final String name;
        private final double value;
        private final String type;
        private final long timestamp;

        public MetricEvent(String name, double value, String type, long timestamp) {
            this.name = name;
            this.value = value;
            this.type = type;
            this.timestamp = timestamp;
        }

        public String getName() {
            return name;
        }

        public double getValue() {
            return value;
        }

        public String getType() {
            return type;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    private PerformanceMonitoringSystem() {
        LOGGER.info("Initializing comprehensive performance monitoring system");

        // Initialize core components
        this.metricsCollector = AsyncMetricsCollector.getInstance();
        this.metricAggregator = new ThreadSafeMetricAggregator();
        this.eventBus = new CrossComponentEventBus();
        this.errorTracker = new ErrorTracker();
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
     * Start background monitoring tasks dengan adaptive frequency
     */
    private void startBackgroundMonitoring() {
        // Metrics collection dengan adaptive frequency
        startAdaptiveMetricsCollection();

        // Alerting check every 5 seconds
        alertingTask.set(scheduler.scheduleAtFixedRate(
                this::checkAlertingThresholds,
                0, 5, TimeUnit.SECONDS));

        // Adaptive monitoring task untuk menyesuaikan collection frequency
        adaptiveMonitoringTask.set(scheduler.scheduleAtFixedRate(
                () -> adjustMonitoringParameters(),
                0, 1, TimeUnit.SECONDS));

        LOGGER.info("Background monitoring tasks started dengan adaptive frequency");
    }

    /**
     * Start adaptive metrics collection dengan frequency yang menyesuaikan load
     */
    private void startAdaptiveMetricsCollection() {
        int initialInterval = getAdaptiveCollectionInterval();

        metricsCollectionTask.set(scheduler.scheduleAtFixedRate(
                () -> collectAndAggregateMetricsWithSampling(),
                0, initialInterval, TimeUnit.MILLISECONDS));

        LOGGER.info("Adaptive metrics collection started dengan interval {}ms", initialInterval);
    }

    /**
     * Adjust monitoring parameters berdasarkan system load
     */
    private void adjustMonitoringParameters() {
        int currentLoad = systemLoad.get();
        int newSamplingRate = getAdaptiveSamplingRate();
        int newCollectionInterval = getAdaptiveCollectionInterval();

        // Update adaptive sampling rate
        if (newSamplingRate != adaptiveSamplingRate.get()) {
            adaptiveSamplingRate.set(newSamplingRate);
            LOGGER.info("Adaptive sampling rate adjusted to {}% (load: {}%)", newSamplingRate, currentLoad);
        }

        // Restart collection task dengan new interval jika perlu
        if (newCollectionInterval != adaptiveCollectionInterval) {
            restartMetricsCollectionWithNewInterval(newCollectionInterval);
            adaptiveCollectionInterval = newCollectionInterval;
            LOGGER.info("Adaptive collection interval adjusted to {}ms (load: {}%)", newCollectionInterval,
                    currentLoad);
        }
    }

    /**
     * Restart metrics collection dengan new interval
     */
    private void restartMetricsCollectionWithNewInterval(int newInterval) {
        // Cancel existing task
        ScheduledFuture<?> existingTask = metricsCollectionTask.get();
        if (existingTask != null) {
            existingTask.cancel(false);
        }

        // Start new task dengan new interval
        metricsCollectionTask.set(scheduler.scheduleAtFixedRate(
                () -> collectAndAggregateMetricsWithSampling(),
                0, newInterval, TimeUnit.MILLISECONDS));
    }

    /**
     * Collect and aggregate metrics dengan sampling strategies
     */
    private void collectAndAggregateMetricsWithSampling() {
        if (!isMonitoringEnabled.get()) {
            return;
        }

        // Check adaptive sampling rate
        if (!shouldSample()) {
            return; // Skip collection jika tidak melewati sampling check
        }

        try {
            // Collect metrics dari Java components dengan sampling
            collectJavaComponentMetricsWithSampling();

            // Collect metrics dari Rust components dengan sampling
            collectRustComponentMetricsWithSampling();

            // Aggregate collected metrics dengan streaming aggregation
            aggregateMetricsWithStreaming();

            // Update metric event counter untuk sampling decision
            metricEventCounter.incrementAndGet();

            // Collect metrics dari Java components dengan sampling
            collectJavaComponentMetricsWithSampling();

            // Collect metrics dari Rust components dengan sampling
            collectRustComponentMetricsWithSampling();

        } catch (Exception e) {
            LOGGER.error("Error during adaptive metrics collection and aggregation", e);
            errorTracker.recordError("MetricsCollection", e, Collections.emptyMap());
        }
    }

    /**
     * Determine if should sample based on adaptive sampling rate
     */
    private boolean shouldSample() {
        int currentRate = getAdaptiveSamplingRate();

        if (currentRate >= 100) {
            return true; // Always sample jika rate 100%
        }

        // Deterministic sampling tanpa RNG overhead
        int counter = metricEventCounter.get();

        // Sample berdasarkan counter modulo 100
        return (counter % 100) < currentRate;
    }

    /**
     * Get adaptive sampling rate berdasarkan system load
     */
    private int getAdaptiveSamplingRate() {
        int baseRate = samplingRate.get();
        int currentLoad = systemLoad.get();

        if (currentLoad >= highLoadThreshold) {
            // Saat load tinggi, gunakan minimum sampling rate
            return Math.max(minSamplingRate, baseRate / 10);
        } else if (currentLoad > mediumLoadThreshold) {
            // Saat load medium, kurangi sampling rate
            return Math.max(minSamplingRate, baseRate / 2);
        } else {
            // Saat load rendah, gunakan base rate
            return baseRate;
        }
    }

    /**
     * Get adaptive collection interval berdasarkan system load
     */
    private int getAdaptiveCollectionInterval() {
        int currentLoad = systemLoad.get();
        int baseInterval = 100; // Base interval 100ms

        if (currentLoad >= highLoadThreshold) {
            // Saat load tinggi, kurangi frequency (lebih lambat)
            return baseInterval * 3; // 300ms
        } else if (currentLoad > mediumLoadThreshold) {
            // Saat load medium, sedikit kurangi frequency
            return baseInterval * 2; // 200ms
        } else {
            // Saat load rendah, gunakan frequency normal
            return baseInterval; // 100ms
        }
    }

    /**
     * Aggregate metrics dengan streaming algorithms tanpa buffering seluruh data
     */
    private void aggregateMetricsWithStreaming() {
        long currentTime = System.currentTimeMillis();
        long lastAggregation = lastMetricAggregationTime.get();

        // Aggregate hanya jika sudah cukup waktu berlalu
        if (currentTime - lastAggregation < 50) { // Minimum 50ms antara aggregations
            return;
        }

        // Process metric events dari queue dengan streaming
        int eventsProcessed = 0;
        MetricEvent event;
        while ((event = metricEventQueue.poll()) != null && eventsProcessed < 100) {
            // Aggregate individual event tanpa menyimpan seluruh data
            aggregateMetricEvent(event);
            eventsProcessed++;
        }

        if (eventsProcessed > 0) {
            lastMetricAggregationTime.set(currentTime);
            // Removed debug log to reduce console spam
            // LOGGER.debug("Stream aggregated {} metric events", eventsProcessed);
        }
    }

    /**
     * Aggregate individual metric event dengan streaming algorithms
     */
    private void aggregateMetricEvent(MetricEvent event) {
        // Update running statistics tanpa buffering data
        switch (event.getType()) {
            case "counter":
                metricAggregator.incrementCounter(event.getName());
                break;
            case "gauge":
                metricAggregator.recordMetric(event.getName(), event.getValue());
                break;
            case "histogram":
                // Convert double ke long untuk histogram
                metricAggregator.recordMetric(event.getName() + ".histogram", event.getValue());
                break;
            case "timer":
                // Convert double ke long untuk timer
                metricAggregator.recordMetric(event.getName() + ".timer", event.getValue());
                break;
        }
    }

    /**
     * Collect metrics dari Java components dengan sampling
     */
    private void collectJavaComponentMetricsWithSampling() {
        // Skip expensive operations jika sampling rate rendah
        int currentRate = getAdaptiveSamplingRate();

        // Collect dari PerformanceManager
        if (shouldCollectComponentMetric("performance_manager", currentRate)) {
            PerformanceManager pm = PerformanceManager.getInstance();
            recordMetricEvent("performance_manager.ai_pathfinding_optimized",
                    pm.isAiPathfindingOptimized() ? 1.0 : 0.0, "gauge");
            recordMetricEvent("performance_manager.rust_integration_enabled",
                    pm.isRustIntegrationEnabled() ? 1.0 : 0.0, "gauge");
            recordMetricEvent("performance_manager.advanced_physics_optimized",
                    pm.isAdvancedPhysicsOptimized() ? 1.0 : 0.0, "gauge");
        }

        if (shouldCollectComponentMetric("optimization_injector", currentRate)) {
            // OptimizationInjector metrics - track native library loading status
            if (com.kneaf.core.OptimizationInjector.isNativeLibraryLoaded()) {
                recordMetricEvent("optimization_injector.native_library_loaded", 1.0, "gauge");
            }
        }

        // Collect dari ParallelRustVectorProcessor (formerly EnhancedRustVectorLibrary)
        if (shouldCollectComponentMetric("rust_vector_library", currentRate) &&
                com.kneaf.core.RustNativeLoader.isLibraryLoaded()) {
            QueueStatistics queueStats = ParallelRustVectorProcessor.getInstance()
                    .getQueueStatistics();
            recordMetricEvent("rust_vector_library.pending_operations",
                    (double) queueStats.pendingOperations, "gauge");
            recordMetricEvent("rust_vector_library.total_operations",
                    (double) queueStats.totalOperations, "counter");
            recordMetricEvent("rust_vector_library.active_threads",
                    (double) queueStats.activeThreads, "gauge");
            recordMetricEvent("rust_vector_library.queued_tasks",
                    (double) queueStats.queuedTasks, "gauge");
        }

        // Collect dari EntityProcessingService
        if (shouldCollectComponentMetric("entity_processing", currentRate)) {
            EntityProcessingStatistics entityStats = EntityProcessingService.getInstance()
                    .getStatistics();
            recordMetricEvent("entity_processing.processed_entities",
                    (double) entityStats.processedEntities, "counter");
            recordMetricEvent("entity_processing.queued_entities",
                    (double) entityStats.queuedEntities, "gauge");
            recordMetricEvent("entity_processing.active_processors",
                    (double) entityStats.activeProcessors, "gauge");
            recordMetricEvent("entity_processing.queue_size",
                    (double) entityStats.queueSize, "gauge");
        }
    }

    /**
     * Collect metrics dari Rust components dengan sampling
     */
    private void collectRustComponentMetricsWithSampling() {
        // Check sampling rate untuk Rust metrics
        int currentRate = getAdaptiveSamplingRate();
        if (!shouldCollectComponentMetric("rust_native", currentRate)) {
            return;
        }

        try {
            String rustStats = getRustPerformanceStats();
            if (rustStats != null) {
                // Parse Rust metrics dengan sampling
                recordMetricEvent("rust_native.total_entities_processed", 0.0, "counter");
                recordMetricEvent("rust_native.native_optimizations_applied", 0.0, "counter");
                recordMetricEvent("rust_native.total_calculation_time_ms", 0.0, "timer");
            }
        } catch (Exception e) {
            LOGGER.debug("Could not collect Rust metrics (library may not be loaded)", e);
        }
    }

    /**
     * Get Rust performance statistics via JNI (delegates to RustNativeLoader)
     */
    private String getRustPerformanceStats() {
        return com.kneaf.core.RustNativeLoader.getRustPerformanceStats();
    }

    /**
     * Parse and record Rust metrics
     */
    private void parseAndRecordRustMetrics(String rustStats) {
        try {
            // Parse the Rust statistics string format:
            // "entities_processed=X,optimizations_applied=Y,calculation_time_ms=Z"
            String[] parts = rustStats.split(",");

            double entitiesProcessed = 0.0;
            double optimizationsApplied = 0.0;
            double calculationTimeMs = 0.0;

            for (String part : parts) {
                String[] keyValue = part.split("=");
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim();
                    String value = keyValue[1].trim();

                    try {
                        switch (key) {
                            case "entities_processed":
                                entitiesProcessed = Double.parseDouble(value);
                                break;
                            case "optimizations_applied":
                                optimizationsApplied = Double.parseDouble(value);
                                break;
                            case "calculation_time_ms":
                                calculationTimeMs = Double.parseDouble(value);
                                break;
                        }
                    } catch (NumberFormatException e) {
                        // Skip invalid numeric values
                    }
                }
            }

            // Record the parsed metrics
            metricAggregator.recordMetric("rust_native.total_entities_processed", entitiesProcessed);
            metricAggregator.recordMetric("rust_native.native_optimizations_applied", optimizationsApplied);
            metricAggregator.recordMetric("rust_native.total_calculation_time_ms", calculationTimeMs);

        } catch (Exception e) {
            LOGGER.debug("Error parsing Rust metrics: {}", rustStats, e);
            // Fallback to zero values if parsing fails
            metricAggregator.recordMetric("rust_native.total_entities_processed", 0.0);
            metricAggregator.recordMetric("rust_native.native_optimizations_applied", 0.0);
            metricAggregator.recordMetric("rust_native.total_calculation_time_ms", 0.0);
        }
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
                    component, eventType, Instant.now(), durationNs, context);
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
    public Object getDashboardData() {
        return new Object();
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
    public Object getDashboard() {
        return new Object();
    }

    /**
     * Get metrics collector
     */
    public AsyncMetricsCollector getMetricsCollector() {
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
     * Minecraft-specific event handling disabled for test compatibility
     */
    public void onServerTick(Object event) { // Changed from ServerTickEvent.Pre to Object
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
     * Minecraft-specific event handling disabled for test compatibility
     */
    public void onConfigReload(Object event) { // Changed from ModConfigEvent.Reloading to Object
        LOGGER.info("Reloading performance monitoring configuration");
        // Reload configuration from file
        reloadConfiguration();
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
     * Determine if should collect component metric berdasarkan sampling rate
     */
    private boolean shouldCollectComponentMetric(String component, int samplingRate) {
        if (samplingRate >= 100) {
            return true;
        }

        // Deterministic sampling berdasarkan component name hash
        int componentHash = component.hashCode();
        int decision = Math.abs(componentHash + metricEventCounter.get()) % 100;

        return decision < samplingRate;
    }

    /**
     * Record metric event ke queue untuk streaming aggregation
     */
    private void recordMetricEvent(String name, double value, String type) {
        MetricEvent event = new MetricEvent(name, value, type, System.currentTimeMillis());
        metricEventQueue.offer(event);
    }

    /**
     * Get system status
     */
    public SystemStatus getSystemStatus() {
        return new SystemStatus(
                isMonitoringEnabled.get(),
                isTracingEnabled.get(),
                samplingRate.get(),
                adaptiveSamplingRate.get(),
                systemLoad.get(),
                metricsCollector.isHealthy(),
                metricAggregator.isHealthy(),
                eventBus.isHealthy(),
                errorTracker.isHealthy(),
                true,
                alertingSystem.isHealthy(),
                distributedTracer.isHealthy());
    }

    /**
     * Enhanced System status information dengan adaptive monitoring
     */
    public static class SystemStatus {
        private final boolean monitoringEnabled;
        private final boolean tracingEnabled;
        private final int baseSamplingRate;
        private final int adaptiveSamplingRate;
        private final int systemLoad;
        private final boolean metricsCollectorHealthy;
        private final boolean metricAggregatorHealthy;
        private final boolean eventBusHealthy;
        private final boolean errorTrackerHealthy;
        private final boolean dashboardHealthy;
        private final boolean alertingSystemHealthy;
        private final boolean distributedTracerHealthy;

        public SystemStatus(boolean monitoringEnabled, boolean tracingEnabled, int baseSamplingRate,
                int adaptiveSamplingRate, int systemLoad,
                boolean metricsCollectorHealthy, boolean metricAggregatorHealthy,
                boolean eventBusHealthy, boolean errorTrackerHealthy,
                boolean dashboardHealthy, boolean alertingSystemHealthy,
                boolean distributedTracerHealthy) {
            this.monitoringEnabled = monitoringEnabled;
            this.tracingEnabled = tracingEnabled;
            this.baseSamplingRate = baseSamplingRate;
            this.adaptiveSamplingRate = adaptiveSamplingRate;
            this.systemLoad = systemLoad;
            this.metricsCollectorHealthy = metricsCollectorHealthy;
            this.metricAggregatorHealthy = metricAggregatorHealthy;
            this.eventBusHealthy = eventBusHealthy;
            this.errorTrackerHealthy = errorTrackerHealthy;
            this.dashboardHealthy = dashboardHealthy;
            this.alertingSystemHealthy = alertingSystemHealthy;
            this.distributedTracerHealthy = distributedTracerHealthy;
        }

        public boolean isMonitoringEnabled() {
            return monitoringEnabled;
        }

        public boolean isTracingEnabled() {
            return tracingEnabled;
        }

        public int getBaseSamplingRate() {
            return baseSamplingRate;
        }

        public int getAdaptiveSamplingRate() {
            return adaptiveSamplingRate;
        }

        public int getSystemLoad() {
            return systemLoad;
        }

        public boolean isMetricsCollectorHealthy() {
            return metricsCollectorHealthy;
        }

        public boolean isMetricAggregatorHealthy() {
            return metricAggregatorHealthy;
        }

        public boolean isEventBusHealthy() {
            return eventBusHealthy;
        }

        public boolean isErrorTrackerHealthy() {
            return errorTrackerHealthy;
        }

        public boolean isDashboardHealthy() {
            return dashboardHealthy;
        }

        public boolean isAlertingSystemHealthy() {
            return alertingSystemHealthy;
        }

        public boolean isDistributedTracerHealthy() {
            return distributedTracerHealthy;
        }

        public boolean isSystemHealthy() {
            return metricsCollectorHealthy && metricAggregatorHealthy && eventBusHealthy &&
                    errorTrackerHealthy && dashboardHealthy && alertingSystemHealthy && distributedTracerHealthy;
        }

        public int getSamplingRate() {
            return adaptiveSamplingRate; // Return adaptive sampling rate untuk backward compatibility
        }

        public String getAdaptiveMonitoringStatus() {
            return String.format("Load: %d%%, Base Sampling: %d%%, Adaptive Sampling: %d%%",
                    systemLoad, baseSamplingRate, adaptiveSamplingRate);
        }
    }
}