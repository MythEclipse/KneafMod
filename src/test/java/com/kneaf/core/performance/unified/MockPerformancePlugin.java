package com.kneaf.core.performance.unified;

import java.util.Optional;

/**
 * Mock implementation of PerformancePlugin for testing level-specific plugin management.
 */
public class MockPerformancePlugin implements PerformancePlugin {
    private final String pluginId;
    private final String displayName;
    private final MonitoringLevel monitoringLevel;
    private final boolean hasMetricsProvider;
    private final boolean hasAlertHandler;
    private boolean running = false;
    private boolean initialized = false;

    public MockPerformancePlugin(String pluginId, String displayName, MonitoringLevel monitoringLevel,
                                 boolean hasMetricsProvider, boolean hasAlertHandler) {
        this.pluginId = pluginId;
        this.displayName = displayName;
        this.monitoringLevel = monitoringLevel;
        this.hasMetricsProvider = hasMetricsProvider;
        this.hasAlertHandler = hasAlertHandler;
    }

    @Override
    public void initialize(PluginContext context) throws PerformancePluginException {
        this.initialized = true;
    }

    @Override
    public void start() throws PerformancePluginException {
        if (!initialized) {
            throw new PerformancePluginException("Plugin not initialized");
        }
        this.running = true;
    }

    @Override
    public void stop() throws PerformancePluginException {
        this.running = false;
    }

    @Override
    public PluginMetadata getMetadata() {
        return new PluginMetadata.Builder(pluginId, displayName, "1.0.0", "TestAuthor")
                .minimumRequiredVersion("1.0.0")
                .supportedLevel(monitoringLevel)
                .build();
    }

    @Override
    public MonitoringLevel getMonitoringLevel() {
        return monitoringLevel;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Optional<MetricsProvider> getMetricsProvider() {
        if (hasMetricsProvider) {
            return Optional.of(new MockMetricsProvider(pluginId));
        }
        return Optional.empty();
    }

    @Override
    public Optional<AlertHandler> getAlertHandler() {
        if (hasAlertHandler) {
            return Optional.of(new MockAlertHandler(pluginId));
        }
        return Optional.empty();
    }

    public boolean isInitialized() {
        return initialized;
    }

    // Mock MetricsProvider implementation
    private static class MockMetricsProvider implements MetricsProvider {
        private final String pluginId;

        public MockMetricsProvider(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public MetricSnapshot getCurrentSnapshot() {
            return new MetricSnapshot.Builder()
                    .monitoringLevel(MonitoringLevel.BASIC)
                    .source("MockPlugin-" + pluginId)
                    .build();
        }

        @Override
        public java.util.Map<java.time.Instant, MetricSnapshot> getHistoricalMetrics(
                java.time.Instant start, java.time.Instant end) {
            return java.util.Collections.emptyMap();
        }

        @Override
        public java.util.Set<MetricType> getSupportedMetricTypes() {
            return java.util.Collections.singleton(MetricType.CUSTOM);
        }

        @Override
        public void resetMetrics() {
            // Mock implementation - do nothing
        }

        @Override
        public void registerMetricListener(MetricListener listener) {
            // Mock implementation - do nothing
        }

        @Override
        public void unregisterMetricListener(MetricListener listener) {
            // Mock implementation - do nothing
        }
    }

    // Mock AlertHandler implementation
    private static class MockAlertHandler implements AlertHandler {
        private final String pluginId;

        public MockAlertHandler(String pluginId) {
            this.pluginId = pluginId;
        }

        @Override
        public void registerAlertListener(AlertListener listener) {
            // Mock implementation - do nothing
        }

        @Override
        public void unregisterAlertListener(AlertListener listener) {
            // Mock implementation - do nothing
        }

        @Override
        public java.util.Set<AlertType> getSupportedAlertTypes() {
            return java.util.Collections.singleton(AlertType.CUSTOM);
        }

        @Override
        public boolean isAlertTypeEnabled(AlertType alertType) {
            return true;
        }

        @Override
        public void setAlertTypeEnabled(AlertType alertType, boolean enabled) {
            // Mock implementation - do nothing
        }

        @Override
        public AlertHandler.AlertThresholds getAlertThresholds() {
            return new MockAlertThresholds();
        }

        @Override
        public void setAlertThresholds(AlertHandler.AlertThresholds thresholds) {
            // Mock implementation - do nothing
        }
    }

    // Mock AlertThresholds implementation
    private static class MockAlertThresholds implements AlertHandler.AlertThresholds {
        @Override
        public double getCpuUsageThreshold() {
            return 80.0;
        }

        @Override
        public double getMemoryUsageThreshold() {
            return 85.0;
        }

        @Override
        public double getTpsThreshold() {
            return 15.0;
        }

        @Override
        public long getEntityProcessingLatencyThresholdMs() {
            return 50;
        }

        @Override
        public long getJniCallLatencyThresholdMs() {
            return 10;
        }

        @Override
        public int getLockContentionThreshold() {
            return 100;
        }

        @Override
        public long getAllocationRateThreshold() {
            return 1000;
        }

        @Override
        public long getGcRateThreshold() {
            return 10;
        }
    }
}