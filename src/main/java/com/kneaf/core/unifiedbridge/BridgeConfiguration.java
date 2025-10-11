package com.kneaf.core.unifiedbridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Configuration class for bridge behavior and optimization.
 * Centralizes all configurable parameters for the unified bridge system.
 */
public class BridgeConfiguration {
    // Core configuration
    private final int maxBatchSize;
    private final int minBatchSize;
    private final int bufferPoolSize;
    private final int maxBufferSize;
    private final int connectionPoolSize;
    private final int maxConnectionPoolSize;
    
    // Timeout configuration
    private final long operationTimeoutMillis;
    private final long connectionAcquisitionTimeoutMillis;
    private final long bufferAcquisitionTimeoutMillis;
    
    // Performance optimization
    private final int bufferThresholdForDirectAllocation;
    private final boolean enableZeroCopy;
    private final boolean enableBufferPooling;
    private final boolean enableConnectionPooling;
    private final boolean enableBatching;
    
    // Monitoring and debugging
    private final boolean enableDetailedMetrics;
    private final boolean enableDebugLogging;
    private final String logPrefix;
    
    // Worker configuration
    private final int defaultWorkerConcurrency;
    private final long workerKeepAliveMillis;
    
    // Memory pressure configuration
    private final double highMemoryPressureThreshold;
    private final double criticalMemoryPressureThreshold;
    
    // Resource leak detection configuration
    private final boolean enableResourceLeakDetection;
    private final long resourceLeakDetectionThresholdMs;
    private final long resourceLeakDetectionIntervalMs;
    
    // Thread pool configuration
    private final int threadpoolSize;
    
    /**
     * Create a new BridgeConfiguration instance using the builder.
     * @param builder Builder instance containing configuration parameters
     */
    private BridgeConfiguration(Builder builder) {
        this.maxBatchSize = builder.maxBatchSize;
        this.minBatchSize = builder.minBatchSize;
        this.bufferPoolSize = builder.bufferPoolSize;
        this.maxBufferSize = builder.maxBufferSize;
        this.connectionPoolSize = builder.connectionPoolSize;
        this.maxConnectionPoolSize = builder.maxConnectionPoolSize;
        
        this.operationTimeoutMillis = builder.operationTimeoutMillis;
        this.connectionAcquisitionTimeoutMillis = builder.connectionAcquisitionTimeoutMillis;
        this.bufferAcquisitionTimeoutMillis = builder.bufferAcquisitionTimeoutMillis;
        
        this.bufferThresholdForDirectAllocation = builder.bufferThresholdForDirectAllocation;
        this.enableZeroCopy = builder.enableZeroCopy;
        this.enableBufferPooling = builder.enableBufferPooling;
        this.enableConnectionPooling = builder.enableConnectionPooling;
        this.enableBatching = builder.enableBatching;
        
        this.enableDetailedMetrics = builder.enableDetailedMetrics;
        this.enableDebugLogging = builder.enableDebugLogging;
        this.logPrefix = builder.logPrefix;
        
        this.defaultWorkerConcurrency = builder.defaultWorkerConcurrency;
        this.workerKeepAliveMillis = builder.workerKeepAliveMillis;
        
        this.highMemoryPressureThreshold = builder.highMemoryPressureThreshold;
        this.criticalMemoryPressureThreshold = builder.criticalMemoryPressureThreshold;
        
        this.enableResourceLeakDetection = builder.enableResourceLeakDetection;
        this.resourceLeakDetectionThresholdMs = builder.resourceLeakDetectionThresholdMs;
        this.resourceLeakDetectionIntervalMs = builder.resourceLeakDetectionIntervalMs;
        
        this.threadpoolSize = builder.threadpoolSize;
    }

    // Getters for all configuration parameters
    
    /**
     * Get maximum batch size for batch operations.
     * @return Maximum batch size
     */
    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * Get minimum batch size for batch operations.
     * @return Minimum batch size
     */
    public int getMinBatchSize() {
        return minBatchSize;
    }

    /**
     * Get buffer pool size.
     * @return Buffer pool size
     */
    public int getBufferPoolSize() {
        return bufferPoolSize;
    }

    /**
     * Get maximum buffer size for pooling.
     * @return Maximum buffer size
     */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    /**
     * Get connection pool size.
     * @return Connection pool size
     */
    public int getConnectionPoolSize() {
        return connectionPoolSize;
    }

    /**
     * Get maximum connection pool size.
     * @return Maximum connection pool size
     */
    public int getMaxConnectionPoolSize() {
        return maxConnectionPoolSize;
    }

    /**
     * Get operation timeout in milliseconds.
     * @return Operation timeout
     */
    public long getOperationTimeoutMillis() {
        return operationTimeoutMillis;
    }

    /**
     * Get connection acquisition timeout in milliseconds.
     * @return Connection acquisition timeout
     */
    public long getConnectionAcquisitionTimeoutMillis() {
        return connectionAcquisitionTimeoutMillis;
    }

    /**
     * Get buffer acquisition timeout in milliseconds.
     * @return Buffer acquisition timeout
     */
    public long getBufferAcquisitionTimeoutMillis() {
        return bufferAcquisitionTimeoutMillis;
    }

    /**
     * Get buffer threshold for direct allocation (in bytes).
     * @return Buffer threshold
     */
    public int getBufferThresholdForDirectAllocation() {
        return bufferThresholdForDirectAllocation;
    }

    /**
     * Check if zero-copy operations are enabled.
     * @return true if zero-copy is enabled, false otherwise
     */
    public boolean isEnableZeroCopy() {
        return enableZeroCopy;
    }

    /**
     * Check if buffer pooling is enabled.
     * @return true if buffer pooling is enabled, false otherwise
     */
    public boolean isEnableBufferPooling() {
        return enableBufferPooling;
    }

    /**
     * Check if connection pooling is enabled.
     * @return true if connection pooling is enabled, false otherwise
     */
    public boolean isEnableConnectionPooling() {
        return enableConnectionPooling;
    }

    /**
     * Check if batching is enabled.
     * @return true if batching is enabled, false otherwise
     */
    public boolean isEnableBatching() {
        return enableBatching;
    }

    /**
     * Check if detailed metrics are enabled.
     * @return true if detailed metrics are enabled, false otherwise
     */
    public boolean isEnableDetailedMetrics() {
        return enableDetailedMetrics;
    }

    /**
     * Check if debug logging is enabled.
     * @return true if debug logging is enabled, false otherwise
     */
    public boolean isEnableDebugLogging() {
        return enableDebugLogging;
    }

    /**
     * Get log prefix for bridge operations.
     * @return Log prefix
     */
    public String getLogPrefix() {
        return logPrefix;
    }

    /**
     * Get default worker concurrency level.
     * @return Default worker concurrency
     */
    public int getDefaultWorkerConcurrency() {
        return defaultWorkerConcurrency;
    }

    /**
     * Get worker keep-alive time in milliseconds.
     * @return Worker keep-alive time
     */
    public long getWorkerKeepAliveMillis() {
        return workerKeepAliveMillis;
    }

    /**
     * Get high memory pressure threshold (0.0-1.0).
     * @return High memory pressure threshold
     */
    public double getHighMemoryPressureThreshold() {
        return highMemoryPressureThreshold;
    }

    /**
     * Get critical memory pressure threshold (0.0-1.0).
     * @return Critical memory pressure threshold
     */
    public double getCriticalMemoryPressureThreshold() {
        return criticalMemoryPressureThreshold;
    }

    /**
     * Check if resource leak detection is enabled.
     * @return true if enabled, false otherwise
     */
    public boolean isEnableResourceLeakDetection() {
        return enableResourceLeakDetection;
    }

    /**
     * Get resource leak detection threshold in milliseconds.
     * @return Threshold in milliseconds
     */
    public long getResourceLeakDetectionThresholdMs() {
        return resourceLeakDetectionThresholdMs;
    }

    /**
     * Get resource leak detection interval in milliseconds.
     * @return Interval in milliseconds
     */
    public long getResourceLeakDetectionIntervalMs() {
        return resourceLeakDetectionIntervalMs;
    }

    /**
     * Get thread pool size.
     * @return Thread pool size
     */
    public int getThreadpoolSize() {
        return threadpoolSize;
    }
    
    /**
     * Check if migration support is enabled.
     * @return true if enabled, false otherwise
     */
    public boolean isEnableMigrationSupport() {
        return false; // Default implementation, can be overridden
    }
    
    /**
     * Check if backward compatibility is enabled.
     * @return true if enabled, false otherwise
     */
    public boolean isEnableBackwardCompatibility() {
        return false; // Default implementation, can be overridden
    }

    /**
     * Convert configuration to map for serialization.
     * @return Map containing all configuration parameters
     */
    public Map<String, Object> toMap() {
        Map<String, Object> configMap = new java.util.HashMap<>();
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

    /**
     * Create a new BridgeConfiguration.Builder instance.
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating BridgeConfiguration instances.
     * Provides sensible defaults and type safety.
     */
    public static final class Builder {
        // Default values based on performance testing and best practices
        private int maxBatchSize = 100;
        private int minBatchSize = 10;
        private int bufferPoolSize = 32;
        private int maxBufferSize = 1024 * 1024; // 1MB
        private int connectionPoolSize = 8;
        private int maxConnectionPoolSize = 32;
        
        private long operationTimeoutMillis = TimeUnit.SECONDS.toMillis(30);
        private long connectionAcquisitionTimeoutMillis = TimeUnit.SECONDS.toMillis(5);
        private long bufferAcquisitionTimeoutMillis = TimeUnit.MILLISECONDS.toMillis(100);
        
        private int bufferThresholdForDirectAllocation = 4096; // 4KB
        private boolean enableZeroCopy = true;
        private boolean enableBufferPooling = true;
        private boolean enableConnectionPooling = true;
        private boolean enableBatching = true;
        
        private boolean enableDetailedMetrics = false;
        private boolean enableDebugLogging = false;
        private String logPrefix = "[UnifiedBridge]";
        
        private int defaultWorkerConcurrency = 4;
        private long workerKeepAliveMillis = TimeUnit.MINUTES.toMillis(5);
        
        private double highMemoryPressureThreshold = 0.85;
        private double criticalMemoryPressureThreshold = 0.95;
        
        private boolean enableResourceLeakDetection = true;
        private long resourceLeakDetectionThresholdMs = 30000; // 30 seconds
        private long resourceLeakDetectionIntervalMs = 60000; // 1 minute
        
        private int threadpoolSize = 4;

        private Builder() {}

        /**
         * Set maximum batch size for batch operations.
         * @param maxBatchSize Maximum batch size
         * @return Builder instance
         */
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = Math.max(1, maxBatchSize);
            return this;
        }

        /**
         * Set minimum batch size for batch operations.
         * @param minBatchSize Minimum batch size
         * @return Builder instance
         */
        public Builder minBatchSize(int minBatchSize) {
            this.minBatchSize = Math.max(1, minBatchSize);
            return this;
        }

        /**
         * Set buffer pool size.
         * @param bufferPoolSize Buffer pool size
         * @return Builder instance
         */
        public Builder bufferPoolSize(int bufferPoolSize) {
            this.bufferPoolSize = Math.max(4, bufferPoolSize);
            return this;
        }

        /**
         * Set maximum buffer size for pooling (in bytes).
         * @param maxBufferSize Maximum buffer size
         * @return Builder instance
         */
        public Builder maxBufferSize(int maxBufferSize) {
            this.maxBufferSize = Math.max(1024, maxBufferSize); // Minimum 1KB
            return this;
        }

        /**
         * Set connection pool size.
         * @param connectionPoolSize Connection pool size
         * @return Builder instance
         */
        public Builder connectionPoolSize(int connectionPoolSize) {
            this.connectionPoolSize = Math.max(2, connectionPoolSize);
            return this;
        }

        /**
         * Set maximum connection pool size.
         * @param maxConnectionPoolSize Maximum connection pool size
         * @return Builder instance
         */
        public Builder maxConnectionPoolSize(int maxConnectionPoolSize) {
            this.maxConnectionPoolSize = Math.max(connectionPoolSize, maxConnectionPoolSize);
            return this;
        }

        /**
         * Set operation timeout.
         * @param timeout Timeout value
         * @param unit Time unit
         * @return Builder instance
         */
        public Builder operationTimeout(long timeout, TimeUnit unit) {
            this.operationTimeoutMillis = unit.toMillis(timeout);
            return this;
        }

        /**
         * Set connection acquisition timeout.
         * @param timeout Timeout value
         * @param unit Time unit
         * @return Builder instance
         */
        public Builder connectionAcquisitionTimeout(long timeout, TimeUnit unit) {
            this.connectionAcquisitionTimeoutMillis = unit.toMillis(timeout);
            return this;
        }

        /**
         * Set buffer acquisition timeout.
         * @param timeout Timeout value
         * @param unit Time unit
         * @return Builder instance
         */
        public Builder bufferAcquisitionTimeout(long timeout, TimeUnit unit) {
            this.bufferAcquisitionTimeoutMillis = unit.toMillis(timeout);
            return this;
        }

        /**
         * Set buffer threshold for direct allocation (in bytes).
         * Buffers larger than this threshold will use direct allocation.
         * @param threshold Buffer threshold
         * @return Builder instance
         */
        public Builder bufferThresholdForDirectAllocation(int threshold) {
            this.bufferThresholdForDirectAllocation = Math.max(1024, threshold); // Minimum 1KB
            return this;
        }

        /**
         * Enable or disable zero-copy operations.
         * @param enableZeroCopy true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableZeroCopy(boolean enableZeroCopy) {
            this.enableZeroCopy = enableZeroCopy;
            return this;
        }

        /**
         * Enable or disable buffer pooling.
         * @param enableBufferPooling true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableBufferPooling(boolean enableBufferPooling) {
            this.enableBufferPooling = enableBufferPooling;
            return this;
        }

        /**
         * Enable or disable connection pooling.
         * @param enableConnectionPooling true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableConnectionPooling(boolean enableConnectionPooling) {
            this.enableConnectionPooling = enableConnectionPooling;
            return this;
        }

        /**
         * Enable or disable batching.
         * @param enableBatching true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableBatching(boolean enableBatching) {
            this.enableBatching = enableBatching;
            return this;
        }

        /**
         * Enable or disable detailed metrics collection.
         * @param enableDetailedMetrics true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableDetailedMetrics(boolean enableDetailedMetrics) {
            this.enableDetailedMetrics = enableDetailedMetrics;
            return this;
        }

        /**
         * Enable or disable debug logging.
         * @param enableDebugLogging true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableDebugLogging(boolean enableDebugLogging) {
            this.enableDebugLogging = enableDebugLogging;
            return this;
        }

        /**
         * Set log prefix for bridge operations.
         * @param logPrefix Log prefix string
         * @return Builder instance
         */
        public Builder logPrefix(String logPrefix) {
            this.logPrefix = Objects.requireNonNull(logPrefix);
            return this;
        }

        /**
         * Set default worker concurrency level.
         * @param concurrency Concurrency level
         * @return Builder instance
         */
        public Builder defaultWorkerConcurrency(int concurrency) {
            this.defaultWorkerConcurrency = Math.max(1, concurrency);
            return this;
        }

        /**
         * Set worker keep-alive time.
         * @param keepAlive Keep-alive time
         * @param unit Time unit
         * @return Builder instance
         */
        public Builder workerKeepAlive(long keepAlive, TimeUnit unit) {
            this.workerKeepAliveMillis = unit.toMillis(keepAlive);
            return this;
        }

        /**
         * Set high memory pressure threshold (0.0-1.0).
         * @param threshold High memory pressure threshold
         * @return Builder instance
         */
        public Builder highMemoryPressureThreshold(double threshold) {
            this.highMemoryPressureThreshold = Math.min(Math.max(0.0, threshold), 1.0);
            return this;
        }

        /**
         * Set critical memory pressure threshold (0.0-1.0).
         * @param threshold Critical memory pressure threshold
         * @return Builder instance
         */
        public Builder criticalMemoryPressureThreshold(double threshold) {
            this.criticalMemoryPressureThreshold = Math.min(Math.max(0.0, threshold), 1.0);
            return this;
        }

        /**
         * Enable or disable resource leak detection.
         * @param enableResourceLeakDetection true to enable, false to disable
         * @return Builder instance
         */
        public Builder enableResourceLeakDetection(boolean enableResourceLeakDetection) {
            this.enableResourceLeakDetection = enableResourceLeakDetection;
            return this;
        }

        /**
         * Set resource leak detection threshold in milliseconds.
         * @param threshold Threshold in milliseconds
         * @return Builder instance
         */
        public Builder resourceLeakDetectionThresholdMs(long threshold) {
            this.resourceLeakDetectionThresholdMs = threshold;
            return this;
        }

        /**
         * Set resource leak detection interval in milliseconds.
         * @param interval Interval in milliseconds
         * @return Builder instance
         */
        public Builder resourceLeakDetectionIntervalMs(long interval) {
            this.resourceLeakDetectionIntervalMs = interval;
            return this;
        }

        /**
         * Set thread pool size.
         * @param threadpoolSize Thread pool size
         * @return Builder instance
         */
        public Builder threadpoolSize(int threadpoolSize) {
            this.threadpoolSize = threadpoolSize;
            return this;
        }

        /**
         * Build and return a new BridgeConfiguration instance.
         * @return Immutable BridgeConfiguration
         */
        public BridgeConfiguration build() {
            // Validate configuration consistency
            if (minBatchSize > maxBatchSize) {
                throw new IllegalArgumentException("minBatchSize cannot be greater than maxBatchSize");
            }
            if (connectionPoolSize > maxConnectionPoolSize) {
                throw new IllegalArgumentException("connectionPoolSize cannot be greater than maxConnectionPoolSize");
            }
            if (criticalMemoryPressureThreshold <= highMemoryPressureThreshold) {
                throw new IllegalArgumentException("criticalMemoryPressureThreshold must be greater than highMemoryPressureThreshold");
            }
            
            return new BridgeConfiguration(this);
        }
    }

    /**
     * Get a default configuration instance with sensible defaults.
     * @return Default BridgeConfiguration
     */
    public static BridgeConfiguration getDefault() {
        return builder().build();
    }

    /**
     * Get a configuration optimized for low-latency operations.
     * @return Low-latency optimized configuration
     */
    public static BridgeConfiguration getLowLatencyConfig() {
        return builder()
                .maxBatchSize(50)
                .minBatchSize(5)
                .bufferPoolSize(16)
                .operationTimeout(10, TimeUnit.SECONDS)
                .bufferThresholdForDirectAllocation(8192) // 8KB
                .build();
    }

    /**
     * Get a configuration optimized for high-throughput operations.
     * @return High-throughput optimized configuration
     */
    public static BridgeConfiguration getHighThroughputConfig() {
        return builder()
                .maxBatchSize(200)
                .minBatchSize(20)
                .bufferPoolSize(64)
                .operationTimeout(2, TimeUnit.MINUTES)
                .bufferThresholdForDirectAllocation(1024) // 1KB
                .build();
    }
}