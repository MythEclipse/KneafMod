package com.kneaf.core.config.performance;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Centralized configuration for performance-related settings.
 * Consolidates all performance configuration properties in one place.
 */
public class PerformanceConfiguration {
    // Core performance settings
    private int maxBatchSize = 100;
    private int minBatchSize = 10;
    private int bufferPoolSize = 32;
    private int maxBufferSize = 1024 * 1024; // 1MB
    private int connectionPoolSize = 8;
    private int maxConnectionPoolSize = 32;
    
    // Timeout configuration
    private long operationTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
    private long connectionAcquisitionTimeoutMillis = TimeUnit.SECONDS.toMillis(5);
    private long bufferAcquisitionTimeoutMillis = TimeUnit.MILLISECONDS.toMillis(100);
    
    // Performance optimization
    private int bufferThresholdForDirectAllocation = 4096; // 4KB
    private boolean enableZeroCopy = true;
    private boolean enableBufferPooling = true;
    private boolean enableConnectionPooling = true;
    private boolean enableBatching = true;
    
    // Monitoring and debugging
    private boolean enableDetailedMetrics = false;
    private boolean enableDebugLogging = false;
    private String logPrefix = "[UnifiedBridge]";
    
    // Worker configuration
    private int defaultWorkerConcurrency = 4;
    private long workerKeepAliveMillis = TimeUnit.MINUTES.toMillis(5);
    
    // Memory pressure configuration
    private double highMemoryPressureThreshold = 0.85;
    private double criticalMemoryPressureThreshold = 0.95;
    
    // Resource leak detection configuration
    private boolean enableResourceLeakDetection = true;
    private long resourceLeakDetectionThresholdMs = 30000; // 30 seconds
    private long resourceLeakDetectionIntervalMs = 60000; // 1 minute
    
    // Thread pool configuration
    private int threadpoolSize = 4;

    // Getters and setters for all configuration properties
    
    /**
     * Get maximum batch size for batch operations.
     * @return Maximum batch size
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Set maximum batch size for batch operations.
     * @param maxBatchSize Maximum batch size
     */
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * Get minimum batch size for batch operations.
     * @return Minimum batch size
     */
    public int getMinBatchSize() {
        return minBatchSize;
    }

    /**
     * Set minimum batch size for batch operations.
     * @param minBatchSize Minimum batch size
     */
    public void setMinBatchSize(int minBatchSize) {
        this.minBatchSize = minBatchSize;
    }

    /**
     * Get buffer pool size.
     * @return Buffer pool size
     */
    public int getBufferPoolSize() {
        return bufferPoolSize;
    }

    /**
     * Set buffer pool size.
     * @param bufferPoolSize Buffer pool size
     */
    public void setBufferPoolSize(int bufferPoolSize) {
        this.bufferPoolSize = bufferPoolSize;
    }

    /**
     * Get maximum buffer size for pooling.
     * @return Maximum buffer size
     */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    /**
     * Set maximum buffer size for pooling.
     * @param maxBufferSize Maximum buffer size
     */
    public void setMaxBufferSize(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    /**
     * Get connection pool size.
     * @return Connection pool size
     */
    public int getConnectionPoolSize() {
        return connectionPoolSize;
    }

    /**
     * Set connection pool size.
     * @param connectionPoolSize Connection pool size
     */
    public void setConnectionPoolSize(int connectionPoolSize) {
        this.connectionPoolSize = connectionPoolSize;
    }

    /**
     * Get maximum connection pool size.
     * @return Maximum connection pool size
     */
    public int getMaxConnectionPoolSize() {
        return maxConnectionPoolSize;
    }

    /**
     * Set maximum connection pool size.
     * @param maxConnectionPoolSize Maximum connection pool size
     */
    public void setMaxConnectionPoolSize(int maxConnectionPoolSize) {
        this.maxConnectionPoolSize = maxConnectionPoolSize;
    }

    /**
     * Get operation timeout in milliseconds.
     * @return Operation timeout
     */
    public long getOperationTimeoutMillis() {
        return operationTimeoutMillis;
    }

    /**
     * Set operation timeout in milliseconds.
     * @param operationTimeoutMillis Operation timeout
     */
    public void setOperationTimeoutMillis(long operationTimeoutMillis) {
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /**
     * Get connection acquisition timeout in milliseconds.
     * @return Connection acquisition timeout
     */
    public long getConnectionAcquisitionTimeoutMillis() {
        return connectionAcquisitionTimeoutMillis;
    }

    /**
     * Set connection acquisition timeout in milliseconds.
     * @param connectionAcquisitionTimeoutMillis Connection acquisition timeout
     */
    public void setConnectionAcquisitionTimeoutMillis(long connectionAcquisitionTimeoutMillis) {
        this.connectionAcquisitionTimeoutMillis = connectionAcquisitionTimeoutMillis;
    }

    /**
     * Get buffer acquisition timeout in milliseconds.
     * @return Buffer acquisition timeout
     */
    public long getBufferAcquisitionTimeoutMillis() {
        return bufferAcquisitionTimeoutMillis;
    }

    /**
     * Set buffer acquisition timeout in milliseconds.
     * @param bufferAcquisitionTimeoutMillis Buffer acquisition timeout
     */
    public void setBufferAcquisitionTimeoutMillis(long bufferAcquisitionTimeoutMillis) {
        this.bufferAcquisitionTimeoutMillis = bufferAcquisitionTimeoutMillis;
    }

    /**
     * Get buffer threshold for direct allocation (in bytes).
     * @return Buffer threshold
     */
    public int getBufferThresholdForDirectAllocation() {
        return bufferThresholdForDirectAllocation;
    }

    /**
     * Set buffer threshold for direct allocation (in bytes).
     * @param bufferThresholdForDirectAllocation Buffer threshold
     */
    public void setBufferThresholdForDirectAllocation(int bufferThresholdForDirectAllocation) {
        this.bufferThresholdForDirectAllocation = bufferThresholdForDirectAllocation;
    }

    /**
     * Check if zero-copy operations are enabled.
     * @return true if zero-copy is enabled, false otherwise
     */
    public boolean isEnableZeroCopy() {
        return enableZeroCopy;
    }

    /**
     * Enable or disable zero-copy operations.
     * @param enableZeroCopy true to enable, false to disable
     */
    public void setEnableZeroCopy(boolean enableZeroCopy) {
        this.enableZeroCopy = enableZeroCopy;
    }

    /**
     * Check if buffer pooling is enabled.
     * @return true if buffer pooling is enabled, false otherwise
     */
    public boolean isEnableBufferPooling() {
        return enableBufferPooling;
    }

    /**
     * Enable or disable buffer pooling.
     * @param enableBufferPooling true to enable, false to disable
     */
    public void setEnableBufferPooling(boolean enableBufferPooling) {
        this.enableBufferPooling = enableBufferPooling;
    }

    /**
     * Check if connection pooling is enabled.
     * @return true if connection pooling is enabled, false otherwise
     */
    public boolean isEnableConnectionPooling() {
        return enableConnectionPooling;
    }

    /**
     * Enable or disable connection pooling.
     * @param enableConnectionPooling true to enable, false to disable
     */
    public void setEnableConnectionPooling(boolean enableConnectionPooling) {
        this.enableConnectionPooling = enableConnectionPooling;
    }

    /**
     * Check if batching is enabled.
     * @return true if batching is enabled, false otherwise
     */
    public boolean isEnableBatching() {
        return enableBatching;
    }

    /**
     * Enable or disable batching.
     * @param enableBatching true to enable, false to disable
     */
    public void setEnableBatching(boolean enableBatching) {
        this.enableBatching = enableBatching;
    }

    /**
     * Check if detailed metrics are enabled.
     * @return true if detailed metrics are enabled, false otherwise
     */
    public boolean isEnableDetailedMetrics() {
        return enableDetailedMetrics;
    }

    /**
     * Enable or disable detailed metrics.
     * @param enableDetailedMetrics true to enable, false to disable
     */
    public void setEnableDetailedMetrics(boolean enableDetailedMetrics) {
        this.enableDetailedMetrics = enableDetailedMetrics;
    }

    /**
     * Check if debug logging is enabled.
     * @return true if debug logging is enabled, false otherwise
     */
    public boolean isEnableDebugLogging() {
        return enableDebugLogging;
    }

    /**
     * Enable or disable debug logging.
     * @param enableDebugLogging true to enable, false to disable
     */
    public void setEnableDebugLogging(boolean enableDebugLogging) {
        this.enableDebugLogging = enableDebugLogging;
    }

    /**
     * Get log prefix for bridge operations.
     * @return Log prefix
     */
    public String getLogPrefix() {
        return logPrefix;
    }

    /**
     * Set log prefix for bridge operations.
     * @param logPrefix Log prefix string
     */
    public void setLogPrefix(String logPrefix) {
        this.logPrefix = logPrefix;
    }

    /**
     * Get default worker concurrency level.
     * @return Default worker concurrency
     */
    public int getDefaultWorkerConcurrency() {
        return defaultWorkerConcurrency;
    }

    /**
     * Set default worker concurrency level.
     * @param defaultWorkerConcurrency Concurrency level
     */
    public void setDefaultWorkerConcurrency(int defaultWorkerConcurrency) {
        this.defaultWorkerConcurrency = defaultWorkerConcurrency;
    }

    /**
     * Get worker keep-alive time in milliseconds.
     * @return Worker keep-alive time
     */
    public long getWorkerKeepAliveMillis() {
        return workerKeepAliveMillis;
    }

    /**
     * Set worker keep-alive time in milliseconds.
     * @param workerKeepAliveMillis Worker keep-alive time
     */
    public void setWorkerKeepAliveMillis(long workerKeepAliveMillis) {
        this.workerKeepAliveMillis = workerKeepAliveMillis;
    }

    /**
     * Get high memory pressure threshold (0.0-1.0).
     * @return High memory pressure threshold
     */
    public double getHighMemoryPressureThreshold() {
        return highMemoryPressureThreshold;
    }

    /**
     * Set high memory pressure threshold (0.0-1.0).
     * @param highMemoryPressureThreshold High memory pressure threshold
     */
    public void setHighMemoryPressureThreshold(double highMemoryPressureThreshold) {
        this.highMemoryPressureThreshold = highMemoryPressureThreshold;
    }

    /**
     * Get critical memory pressure threshold (0.0-1.0).
     * @return Critical memory pressure threshold
     */
    public double getCriticalMemoryPressureThreshold() {
        return criticalMemoryPressureThreshold;
    }

    /**
     * Set critical memory pressure threshold (0.0-1.0).
     * @param criticalMemoryPressureThreshold Critical memory pressure threshold
     */
    public void setCriticalMemoryPressureThreshold(double criticalMemoryPressureThreshold) {
        this.criticalMemoryPressureThreshold = criticalMemoryPressureThreshold;
    }

    /**
     * Check if resource leak detection is enabled.
     * @return true if enabled, false otherwise
     */
    public boolean isEnableResourceLeakDetection() {
        return enableResourceLeakDetection;
    }

    /**
     * Enable or disable resource leak detection.
     * @param enableResourceLeakDetection true to enable, false to disable
     */
    public void setEnableResourceLeakDetection(boolean enableResourceLeakDetection) {
        this.enableResourceLeakDetection = enableResourceLeakDetection;
    }

    /**
     * Get resource leak detection threshold in milliseconds.
     * @return Threshold in milliseconds
     */
    public long getResourceLeakDetectionThresholdMs() {
        return resourceLeakDetectionThresholdMs;
    }

    /**
     * Set resource leak detection threshold in milliseconds.
     * @param resourceLeakDetectionThresholdMs Threshold in milliseconds
     */
    public void setResourceLeakDetectionThresholdMs(long resourceLeakDetectionThresholdMs) {
        this.resourceLeakDetectionThresholdMs = resourceLeakDetectionThresholdMs;
    }

    /**
     * Get resource leak detection interval in milliseconds.
     * @return Interval in milliseconds
     */
    public long getResourceLeakDetectionIntervalMs() {
        return resourceLeakDetectionIntervalMs;
    }

    /**
     * Set resource leak detection interval in milliseconds.
     * @param resourceLeakDetectionIntervalMs Interval in milliseconds
     */
    public void setResourceLeakDetectionIntervalMs(long resourceLeakDetectionIntervalMs) {
        this.resourceLeakDetectionIntervalMs = resourceLeakDetectionIntervalMs;
    }

    /**
     * Get thread pool size.
     * @return Thread pool size
     */
    public int getThreadpoolSize() {
        return threadpoolSize;
    }

    /**
     * Set thread pool size.
     * @param threadpoolSize Thread pool size
     */
    public void setThreadpoolSize(int threadpoolSize) {
        this.threadpoolSize = threadpoolSize;
    }

    /**
     * Load configuration from properties.
     * @param properties Properties to load from
     * @return PerformanceConfiguration instance
     */
    public static PerformanceConfiguration fromProperties(Properties properties) {
        PerformanceConfiguration config = new PerformanceConfiguration();
        
        // Load core performance settings
        config.setMaxBatchSize(Integer.parseInt(properties.getProperty("maxBatchSize", "100")));
        config.setMinBatchSize(Integer.parseInt(properties.getProperty("minBatchSize", "10")));
        config.setBufferPoolSize(Integer.parseInt(properties.getProperty("bufferPoolSize", "32")));
        config.setMaxBufferSize(Integer.parseInt(properties.getProperty("maxBufferSize", "1048576"))); // 1MB
        config.setConnectionPoolSize(Integer.parseInt(properties.getProperty("connectionPoolSize", "8")));
        config.setMaxConnectionPoolSize(Integer.parseInt(properties.getProperty("maxConnectionPoolSize", "32")));
        
        // Load timeout configuration
        config.setOperationTimeoutMillis(Long.parseLong(properties.getProperty("operationTimeoutMillis", "30000")));
        config.setConnectionAcquisitionTimeoutMillis(Long.parseLong(properties.getProperty("connectionAcquisitionTimeoutMillis", "5000")));
        config.setBufferAcquisitionTimeoutMillis(Long.parseLong(properties.getProperty("bufferAcquisitionTimeoutMillis", "100")));
        
        // Load performance optimization settings
        config.setBufferThresholdForDirectAllocation(Integer.parseInt(properties.getProperty("bufferThresholdForDirectAllocation", "4096")));
        config.setEnableZeroCopy(Boolean.parseBoolean(properties.getProperty("enableZeroCopy", "true")));
        config.setEnableBufferPooling(Boolean.parseBoolean(properties.getProperty("enableBufferPooling", "true")));
        config.setEnableConnectionPooling(Boolean.parseBoolean(properties.getProperty("enableConnectionPooling", "true")));
        config.setEnableBatching(Boolean.parseBoolean(properties.getProperty("enableBatching", "true")));
        
        // Load monitoring and debugging settings
        config.setEnableDetailedMetrics(Boolean.parseBoolean(properties.getProperty("enableDetailedMetrics", "false")));
        config.setEnableDebugLogging(Boolean.parseBoolean(properties.getProperty("enableDebugLogging", "false")));
        config.setLogPrefix(properties.getProperty("logPrefix", "[UnifiedBridge]"));
        
        // Load worker configuration
        config.setDefaultWorkerConcurrency(Integer.parseInt(properties.getProperty("defaultWorkerConcurrency", "4")));
        config.setWorkerKeepAliveMillis(Long.parseLong(properties.getProperty("workerKeepAliveMillis", "300000")));
        
        // Load memory pressure configuration
        config.setHighMemoryPressureThreshold(Double.parseDouble(properties.getProperty("highMemoryPressureThreshold", "0.85")));
        config.setCriticalMemoryPressureThreshold(Double.parseDouble(properties.getProperty("criticalMemoryPressureThreshold", "0.95")));
        
        // Load resource leak detection configuration
        config.setEnableResourceLeakDetection(Boolean.parseBoolean(properties.getProperty("enableResourceLeakDetection", "true")));
        config.setResourceLeakDetectionThresholdMs(Long.parseLong(properties.getProperty("resourceLeakDetectionThresholdMs", "30000")));
        config.setResourceLeakDetectionIntervalMs(Long.parseLong(properties.getProperty("resourceLeakDetectionIntervalMs", "60000")));
        
        // Load thread pool configuration
        config.setThreadpoolSize(Integer.parseInt(properties.getProperty("threadpoolSize", "4")));
        
        return config;
    }

    /**
     * Convert configuration to map for serialization.
     * @return Map containing all configuration parameters
     */
    public Map<String, Object> toMap() {
        java.util.Map<String, Object> configMap = new java.util.HashMap<>();
        configMap.put("maxBatchSize", maxBatchSize);
        configMap.put("minBatchSize", minBatchSize);
        configMap.put("bufferPoolSize", bufferPoolSize);
        configMap.put("maxBufferSize", maxBufferSize);
        configMap.put("connectionPoolSize", connectionPoolSize);
        configMap.put("maxConnectionPoolSize", maxConnectionPoolSize);
        configMap.put("operationTimeoutMillis", operationTimeoutMillis);
        configMap.put("connectionAcquisitionTimeoutMillis", connectionAcquisitionTimeoutMillis);
        configMap.put("bufferAcquisitionTimeoutMillis", bufferAcquisitionTimeoutMillis);
        configMap.put("bufferThresholdForDirectAllocation", bufferThresholdForDirectAllocation);
        configMap.put("enableZeroCopy", enableZeroCopy);
        configMap.put("enableBufferPooling", enableBufferPooling);
        configMap.put("enableConnectionPooling", enableConnectionPooling);
        configMap.put("enableBatching", enableBatching);
        configMap.put("enableDetailedMetrics", enableDetailedMetrics);
        configMap.put("enableDebugLogging", enableDebugLogging);
        configMap.put("logPrefix", logPrefix);
        configMap.put("defaultWorkerConcurrency", defaultWorkerConcurrency);
        configMap.put("workerKeepAliveMillis", workerKeepAliveMillis);
        configMap.put("highMemoryPressureThreshold", highMemoryPressureThreshold);
        configMap.put("criticalMemoryPressureThreshold", criticalMemoryPressureThreshold);
        configMap.put("enableResourceLeakDetection", enableResourceLeakDetection);
        configMap.put("resourceLeakDetectionThresholdMs", resourceLeakDetectionThresholdMs);
        configMap.put("resourceLeakDetectionIntervalMs", resourceLeakDetectionIntervalMs);
        configMap.put("threadpoolSize", threadpoolSize);
        return java.util.Collections.unmodifiableMap(configMap);
    }
}
