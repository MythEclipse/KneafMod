package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

/**
 * Performance dashboard with visualizations for key metrics.
 * Provides real-time dashboard data generation and metric visualization.
 */
public final class PerformanceDashboard {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Dashboard configuration
    private final AtomicInteger maxDataPoints = new AtomicInteger(1000);
    private final AtomicInteger refreshIntervalMs = new AtomicInteger(1000); // 1 second
    private final AtomicBoolean isEnabled = new AtomicBoolean(true);
    
    // Dashboard data storage
    private final ConcurrentLinkedQueue<DashboardDataPoint> dataPoints = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, MetricVisualization> visualizations = new ConcurrentHashMap<>();
    
    // Dashboard statistics
    private final AtomicLong dashboardRequests = new AtomicLong(0);
    private final AtomicLong dataPointsGenerated = new AtomicLong(0);
    private final AtomicLong dashboardErrors = new AtomicLong(0);
    
    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulGeneration = new AtomicLong(System.currentTimeMillis());
    
    // Visualization templates
    private final Map<String, VisualizationTemplate> templates = new HashMap<>();
    
    public PerformanceDashboard() {
        LOGGER.info("Initializing PerformanceDashboard");
        initializeTemplates();
    }
    
    /**
     * Initialize visualization templates
     */
    private void initializeTemplates() {
        // Latency visualization template
        templates.put("latency", new VisualizationTemplate(
            "Latency Metrics", 
            "line_chart", 
            new String[]{"avg_latency_ms", "p50_latency_ms", "p95_latency_ms", "p99_latency_ms"},
            new String[]{"Average", "50th Percentile", "95th Percentile", "99th Percentile"},
            new String[]{"#00FF00", "#FFFF00", "#FFA500", "#FF0000"} // Green, Yellow, Orange, Red
        ));
        
        // Throughput visualization template
        templates.put("throughput", new VisualizationTemplate(
            "Throughput Metrics",
            "bar_chart",
            new String[]{"throughput_ops_per_sec", "operations_total"},
            new String[]{"Operations/Second", "Total Operations"},
            new String[]{"#00BFFF", "#32CD32"} // Blue, Green
        ));
        
        // Error rate visualization template
        templates.put("error_rate", new VisualizationTemplate(
            "Error Rate Metrics",
            "gauge_chart",
            new String[]{"error_rate", "success_rate"},
            new String[]{"Error Rate", "Success Rate"},
            new String[]{"#FF0000", "#00FF00"} // Red, Green
        ));
        
        // Resource utilization template
        templates.put("resource_utilization", new VisualizationTemplate(
            "Resource Utilization",
            "area_chart",
            new String[]{"resource_utilization", "active_threads", "active_processors"},
            new String[]{"CPU Utilization", "Active Threads", "Active Processors"},
            new String[]{"#FF6347", "#4169E1", "#32CD32"} // Tomato, Royal Blue, Green
        ));
        
        // Component-specific templates
        templates.put("optimization_injector", new VisualizationTemplate(
            "Optimization Injector Metrics",
            "line_chart",
            new String[]{"async_hits", "async_misses", "async_errors", "hit_rate"},
            new String[]{"Async Hits", "Async Misses", "Async Errors", "Hit Rate"},
            new String[]{"#00FF00", "#FFA500", "#FF0000", "#00BFFF"} // Green, Orange, Red, Blue
        ));
        
        templates.put("rust_vector_library", new VisualizationTemplate(
            "Rust Vector Library Metrics",
            "area_chart",
            new String[]{"pending_operations", "total_operations", "active_threads"},
            new String[]{"Pending Operations", "Total Operations", "Active Threads"},
            new String[]{"#FFD700", "#32CD32", "#4169E1"} // Gold, Green, Royal Blue
        ));
        
        templates.put("entity_processing", new VisualizationTemplate(
            "Entity Processing Metrics",
            "stacked_chart",
            new String[]{"processed_entities", "queued_entities", "active_processors"},
            new String[]{"Processed Entities", "Queued Entities", "Active Processors"},
            new String[]{"#32CD32", "#FFA500", "#4169E1"} // Green, Orange, Royal Blue
        ));
    }
    
    /**
     * Generate dashboard data from current metrics
     */
    public DashboardData generateDashboardData(Map<String, Double> currentMetrics) {
        if (!isEnabled.get()) {
            return createEmptyDashboardData();
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            dashboardRequests.incrementAndGet();
            
            // Create dashboard data
            DashboardData dashboardData = new DashboardData();
            dashboardData.timestamp = Instant.now();
            dashboardData.metrics = new HashMap<>(currentMetrics);
            
            // Generate visualizations
            dashboardData.visualizations = generateVisualizations(currentMetrics);
            
            // Generate system overview
            dashboardData.systemOverview = generateSystemOverview(currentMetrics);
            
            // Generate component summaries
            dashboardData.componentSummaries = generateComponentSummaries(currentMetrics);
            
            // Generate alerts
            dashboardData.alerts = generateAlerts(currentMetrics);
            
            // Store data point for history
            storeDataPoint(dashboardData);
            
            dataPointsGenerated.incrementAndGet();
            lastSuccessfulGeneration.set(startTime);
            
            updateHealthStatus();
            
            return dashboardData;
            
        } catch (Exception e) {
            LOGGER.error("Error generating dashboard data", e);
            dashboardErrors.incrementAndGet();
            isHealthy.set(false);
            return createErrorDashboardData(e);
        }
    }
    
    /**
     * Generate visualizations for metrics
     */
    private Map<String, MetricVisualization> generateVisualizations(Map<String, Double> metrics) {
        Map<String, MetricVisualization> visualizations = new HashMap<>();
        
        for (Map.Entry<String, VisualizationTemplate> entry : templates.entrySet()) {
            String templateName = entry.getKey();
            VisualizationTemplate template = entry.getValue();
            
            try {
                MetricVisualization visualization = createVisualization(template, metrics);
                if (visualization != null) {
                    visualizations.put(templateName, visualization);
                }
            } catch (Exception e) {
                LOGGER.warn("Error creating visualization for template '{}': {}", templateName, e.getMessage());
            }
        }
        
        return visualizations;
    }
    
    /**
     * Create individual visualization
     */
    private MetricVisualization createVisualization(VisualizationTemplate template, Map<String, Double> metrics) {
        List<DataPoint> dataPoints = new ArrayList<>();
        
        for (int i = 0; i < template.metricNames.length; i++) {
            String metricName = template.metricNames[i];
            String seriesName = template.seriesNames[i];
            String color = template.colors[i];
            
            Double value = metrics.get(metricName);
            if (value != null) {
                dataPoints.add(new DataPoint(seriesName, value, color));
            }
        }
        
        if (dataPoints.isEmpty()) {
            return null;
        }
        
        return new MetricVisualization(
            template.title,
            template.chartType,
            dataPoints,
            calculateVisualizationStats(dataPoints)
        );
    }
    
    /**
     * Calculate visualization statistics
     */
    private VisualizationStatistics calculateVisualizationStats(List<DataPoint> dataPoints) {
        if (dataPoints.isEmpty()) {
            return new VisualizationStatistics(0, 0, 0, 0);
        }
        
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0;
        
        for (DataPoint point : dataPoints) {
            double value = point.getValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
        }
        
        double avg = sum / dataPoints.size();
        
        return new VisualizationStatistics(min, max, avg, sum);
    }
    
    /**
     * Generate system overview
     */
    private SystemOverview generateSystemOverview(Map<String, Double> metrics) {
        SystemOverview overview = new SystemOverview();
        
        // Extract key system metrics
        overview.avgLatencyMs = metrics.getOrDefault("system.avg_latency_ms", 0.0);
        overview.throughputOpsPerSec = metrics.getOrDefault("system.throughput_ops_per_sec", 0.0);
        overview.errorRate = metrics.getOrDefault("system.error_rate", 0.0);
        overview.successRate = metrics.getOrDefault("system.success_rate", 1.0);
        overview.resourceUtilization = metrics.getOrDefault("system.resource_utilization", 0.0);
        
        // Calculate system health score (0-100)
        overview.healthScore = calculateHealthScore(overview);
        
        // Determine system status
        overview.status = determineSystemStatus(overview);
        
        return overview;
    }
    
    /**
     * Calculate system health score (0-100)
     */
    private int calculateHealthScore(SystemOverview overview) {
        double score = 100.0;
        
        // Penalize high latency
        if (overview.avgLatencyMs > 100) {
            score -= Math.min(30, (overview.avgLatencyMs - 100) / 10);
        }
        
        // Penalize high error rate
        score -= Math.min(40, overview.errorRate * 100 * 2);
        
        // Penalize low success rate
        score -= Math.min(30, (1.0 - overview.successRate) * 100 * 1.5);
        
        // Penalize high resource utilization
        if (overview.resourceUtilization > 0.9) {
            score -= Math.min(20, (overview.resourceUtilization - 0.9) * 200);
        }
        
        // Penalize low throughput (if expected throughput is known)
        if (overview.throughputOpsPerSec < 100) {
            score -= Math.min(10, (100 - overview.throughputOpsPerSec) / 10);
        }
        
        return Math.max(0, (int) score);
    }
    
    /**
     * Determine system status based on health score
     */
    private String determineSystemStatus(SystemOverview overview) {
        if (overview.healthScore >= 90) {
            return "EXCELLENT";
        } else if (overview.healthScore >= 80) {
            return "GOOD";
        } else if (overview.healthScore >= 70) {
            return "FAIR";
        } else if (overview.healthScore >= 60) {
            return "POOR";
        } else {
            return "CRITICAL";
        }
    }
    
    /**
     * Generate component summaries
     */
    private Map<String, ComponentSummary> generateComponentSummaries(Map<String, Double> metrics) {
        Map<String, ComponentSummary> summaries = new HashMap<>();
        
        // PerformanceManager summary
        summaries.put("performance_manager", createComponentSummary(
            "Performance Manager",
            metrics,
            new String[]{"performance_manager.entity_throttling", "performance_manager.rust_integration_enabled"},
            new String[]{"Entity Throttling", "Rust Integration"}
        ));
        
        // OptimizedOptimizationInjector summary
        summaries.put("optimization_injector", createComponentSummary(
            "Optimization Injector",
            metrics,
            new String[]{"optimization_injector.async_hits", "optimization_injector.async_errors", "optimization_injector.hit_rate"},
            new String[]{"Async Hits", "Async Errors", "Hit Rate"}
        ));
        
        // EnhancedRustVectorLibrary summary
        summaries.put("rust_vector_library", createComponentSummary(
            "Rust Vector Library",
            metrics,
            new String[]{"rust_vector_library.pending_operations", "rust_vector_library.active_threads"},
            new String[]{"Pending Operations", "Active Threads"}
        ));
        
        // EntityProcessingService summary
        summaries.put("entity_processing", createComponentSummary(
            "Entity Processing Service",
            metrics,
            new String[]{"entity_processing.processed_entities", "entity_processing.queue_size"},
            new String[]{"Processed Entities", "Queue Size"}
        ));
        
        return summaries;
    }
    
    /**
     * Create component summary
     */
    private ComponentSummary createComponentSummary(String name, Map<String, Double> metrics, 
                                                   String[] metricNames, String[] labels) {
        ComponentSummary summary = new ComponentSummary();
        summary.name = name;
        summary.metrics = new HashMap<>();
        
        for (int i = 0; i < metricNames.length; i++) {
            Double value = metrics.get(metricNames[i]);
            if (value != null) {
                summary.metrics.put(labels[i], value);
            }
        }
        
        // Determine component health
        summary.isHealthy = !summary.metrics.isEmpty();
        summary.status = summary.isHealthy ? "ONLINE" : "OFFLINE";
        
        return summary;
    }
    
    /**
     * Generate alerts based on metrics
     */
    private List<String> generateAlerts(Map<String, Double> metrics) {
        List<String> alerts = new ArrayList<>();
        
        // Check for high latency
        Double avgLatency = metrics.get("system.avg_latency_ms");
        if (avgLatency != null && avgLatency > 100) {
            alerts.add(String.format("HIGH_LATENCY: Average latency %.2fms exceeds 100ms threshold", avgLatency));
        }
        
        // Check for high error rate
        Double errorRate = metrics.get("system.error_rate");
        if (errorRate != null && errorRate > 0.05) { // 5%
            alerts.add(String.format("HIGH_ERROR_RATE: Error rate %.2f%% exceeds 5%% threshold", errorRate * 100));
        }
        
        // Check for low success rate
        Double successRate = metrics.get("system.success_rate");
        if (successRate != null && successRate < 0.95) { // 95%
            alerts.add(String.format("LOW_SUCCESS_RATE: Success rate %.2f%% below 95%% threshold", successRate * 100));
        }
        
        // Check for high resource utilization
        Double resourceUtilization = metrics.get("system.resource_utilization");
        if (resourceUtilization != null && resourceUtilization > 0.9) { // 90%
            alerts.add(String.format("HIGH_RESOURCE_UTILIZATION: Resource utilization %.2f%% exceeds 90%% threshold", 
                resourceUtilization * 100));
        }
        
        // Check for low throughput
        Double throughput = metrics.get("system.throughput_ops_per_sec");
        if (throughput != null && throughput < 100) {
            alerts.add(String.format("LOW_THROUGHPUT: Throughput %.2f ops/sec below 100 ops/sec threshold", throughput));
        }
        
        return alerts;
    }
    
    /**
     * Store data point for historical tracking
     */
    private void storeDataPoint(DashboardData dashboardData) {
        DashboardDataPoint dataPoint = new DashboardDataPoint(
            dashboardData.timestamp,
            dashboardData.systemOverview.healthScore,
            dashboardData.systemOverview.avgLatencyMs,
            dashboardData.systemOverview.throughputOpsPerSec,
            dashboardData.systemOverview.errorRate
        );
        
        dataPoints.offer(dataPoint);
        
        // Remove old data points
        while (dataPoints.size() > maxDataPoints.get()) {
            dataPoints.poll();
        }
    }
    
    /**
     * Create empty dashboard data
     */
    private DashboardData createEmptyDashboardData() {
        DashboardData data = new DashboardData();
        data.timestamp = Instant.now();
        data.metrics = new HashMap<>();
        data.visualizations = new HashMap<>();
        data.systemOverview = new SystemOverview();
        data.componentSummaries = new HashMap<>();
        data.alerts = new ArrayList<>();
        return data;
    }
    
    /**
     * Create error dashboard data
     */
    private DashboardData createErrorDashboardData(Exception error) {
        DashboardData data = createEmptyDashboardData();
        data.alerts.add("DASHBOARD_ERROR: " + error.getMessage());
        data.systemOverview.status = "ERROR";
        data.systemOverview.healthScore = 0;
        return data;
    }
    
    /**
     * Get dashboard statistics
     */
    public DashboardStatistics getStatistics() {
        return new DashboardStatistics(
            dashboardRequests.get(),
            dataPointsGenerated.get(),
            dashboardErrors.get(),
            dataPoints.size(),
            visualizations.size()
        );
    }
    
    /**
     * Get historical data points
     */
    public List<DashboardDataPoint> getHistoricalData(int maxPoints) {
        List<DashboardDataPoint> result = new ArrayList<>();
        Iterator<DashboardDataPoint> iterator = dataPoints.iterator();
        
        int count = 0;
        while (iterator.hasNext() && count < maxPoints) {
            result.add(iterator.next());
            count++;
        }
        
        return result;
    }
    
    /**
     * Get visualization by name
     */
    public MetricVisualization getVisualization(String name) {
        return visualizations.get(name);
    }
    
    /**
     * Get all visualizations
     */
    public Map<String, MetricVisualization> getAllVisualizations() {
        return new HashMap<>(visualizations);
    }
    
    /**
     * Clear all dashboard data
     */
    public void clearAllData() {
        dataPoints.clear();
        visualizations.clear();
        dashboardRequests.set(0);
        dataPointsGenerated.set(0);
        dashboardErrors.set(0);
        LOGGER.info("All dashboard data cleared");
    }
    
    /**
     * Enable/disable dashboard
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        if (!enabled) {
            clearAllData();
        }
        LOGGER.info("PerformanceDashboard {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Set maximum data points to store
     */
    public void setMaxDataPoints(int maxPoints) {
        this.maxDataPoints.set(Math.max(100, maxPoints)); // Minimum 100
        LOGGER.info("Max data points set to {}", maxPoints);
    }
    
    /**
     * Set refresh interval in milliseconds
     */
    public void setRefreshInterval(int intervalMs) {
        this.refreshIntervalMs.set(Math.max(100, intervalMs)); // Minimum 100ms
        LOGGER.info("Refresh interval set to {}ms", intervalMs);
    }
    
    /**
     * Check if dashboard is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() && 
               (System.currentTimeMillis() - lastSuccessfulGeneration.get()) < 30000; // 30 second timeout
    }
    
    /**
     * Shutdown the dashboard
     */
    public void shutdown() {
        LOGGER.info("Shutting down PerformanceDashboard");
        setEnabled(false);
        clearAllData();
    }
    
    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = dashboardErrors.get() < 10 && // Less than 10 errors
                         (System.currentTimeMillis() - lastSuccessfulGeneration.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }
    
    /**
     * Dashboard data structure
     */
    public static class DashboardData {
        public Instant timestamp;
        public Map<String, Double> metrics;
        public Map<String, MetricVisualization> visualizations;
        public SystemOverview systemOverview;
        public Map<String, ComponentSummary> componentSummaries;
        public List<String> alerts;
        
        @Override
        public String toString() {
            return String.format("DashboardData{timestamp=%s, metrics=%d, visualizations=%d, alerts=%d}",
                    timestamp, metrics.size(), visualizations.size(), alerts.size());
        }
    }
    
    /**
     * Dashboard data point for historical tracking
     */
    public static class DashboardDataPoint {
        public final Instant timestamp;
        public final double healthScore;
        public final double avgLatency;
        public final double throughput;
        public final double errorRate;
        
        public DashboardDataPoint(Instant timestamp, double healthScore, double avgLatency, 
                                 double throughput, double errorRate) {
            this.timestamp = timestamp;
            this.healthScore = healthScore;
            this.avgLatency = avgLatency;
            this.throughput = throughput;
            this.errorRate = errorRate;
        }
    }
    
    /**
     * Metric visualization
     */
    public static class MetricVisualization {
        public final String title;
        public final String chartType;
        public final List<DataPoint> dataPoints;
        public final VisualizationStatistics statistics;
        
        public MetricVisualization(String title, String chartType, List<DataPoint> dataPoints, 
                                  VisualizationStatistics statistics) {
            this.title = title;
            this.chartType = chartType;
            this.dataPoints = dataPoints;
            this.statistics = statistics;
        }
    }
    
    /**
     * Data point for visualization
     */
    public static class DataPoint {
        private final String name;
        private final double value;
        private final String color;
        
        public DataPoint(String name, double value, String color) {
            this.name = name;
            this.value = value;
            this.color = color;
        }
        
        public String getName() { return name; }
        public double getValue() { return value; }
        public String getColor() { return color; }
    }
    
    /**
     * Visualization statistics
     */
    public static class VisualizationStatistics {
        public final double min;
        public final double max;
        public final double avg;
        public final double sum;
        
        public VisualizationStatistics(double min, double max, double avg, double sum) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.sum = sum;
        }
    }
    
    /**
     * System overview
     */
    public static class SystemOverview {
        public double avgLatencyMs;
        public double throughputOpsPerSec;
        public double errorRate;
        public double successRate;
        public double resourceUtilization;
        public int healthScore;
        public String status;
        
        @Override
        public String toString() {
            return String.format("SystemOverview{healthScore=%d, status='%s', avgLatency=%.2fms, throughput=%.2f ops/sec, errorRate=%.2f%%}",
                    healthScore, status, avgLatencyMs, throughputOpsPerSec, errorRate * 100);
        }
    }
    
    /**
     * Component summary
     */
    public static class ComponentSummary {
        public String name;
        public Map<String, Double> metrics;
        public boolean isHealthy;
        public String status;
        
        @Override
        public String toString() {
            return String.format("ComponentSummary{name='%s', status='%s', healthy=%b, metrics=%d}",
                    name, status, isHealthy, metrics.size());
        }
    }
    
    /**
     * Visualization template
     */
    private static class VisualizationTemplate {
        final String title;
        final String chartType;
        final String[] metricNames;
        final String[] seriesNames;
        final String[] colors;
        
        public VisualizationTemplate(String title, String chartType, String[] metricNames, 
                                    String[] seriesNames, String[] colors) {
            this.title = title;
            this.chartType = chartType;
            this.metricNames = metricNames;
            this.seriesNames = seriesNames;
            this.colors = colors;
        }
    }
    
    /**
     * Dashboard statistics
     */
    public static class DashboardStatistics {
        private final long dashboardRequests;
        private final long dataPointsGenerated;
        private final long dashboardErrors;
        private final int dataPointsStored;
        private final int visualizationsActive;
        
        public DashboardStatistics(long dashboardRequests, long dataPointsGenerated, 
                                  long dashboardErrors, int dataPointsStored, 
                                  int visualizationsActive) {
            this.dashboardRequests = dashboardRequests;
            this.dataPointsGenerated = dataPointsGenerated;
            this.dashboardErrors = dashboardErrors;
            this.dataPointsStored = dataPointsStored;
            this.visualizationsActive = visualizationsActive;
        }
        
        public long getDashboardRequests() { return dashboardRequests; }
        public long getDataPointsGenerated() { return dataPointsGenerated; }
        public long getDashboardErrors() { return dashboardErrors; }
        public int getDataPointsStored() { return dataPointsStored; }
        public int getVisualizationsActive() { return visualizationsActive; }
        public double getErrorRate() { 
            return dashboardRequests > 0 ? (double) dashboardErrors / dashboardRequests : 0.0; 
        }
    }
}