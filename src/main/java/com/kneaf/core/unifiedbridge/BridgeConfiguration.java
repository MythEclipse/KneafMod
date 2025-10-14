package com.kneaf.core.unifiedbridge;

import java.util.concurrent.TimeUnit;

/**
 * Comprehensive bridge configuration with validation and builder pattern.
 * Manages all settings for the unified Java-Rust bridge.
 */
public class BridgeConfiguration {
    // Default values
    private static final long DEFAULT_OPERATION_TIMEOUT_MS = 30000; // 30 seconds
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000; // 1 second
    private static final int DEFAULT_MAX_BATCH_SIZE = 1024;
    private static final int DEFAULT_BUFFER_POOL_SIZE = 1048576; // 1MB
    private static final int DEFAULT_WORKER_CONCURRENCY = 4;
    private static final boolean DEFAULT_DEBUG_LOGGING = false;
    private static final int DEFAULT_CONNECTION_POOL_SIZE = 10;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 10000; // 10 seconds

    // Configuration properties
    private final long operationTimeoutMs;
    private final int maxRetries;
    private final long retryDelayMs;
    private final int maxBatchSize;
    private final int bufferPoolSize;
    private final int defaultWorkerConcurrency;
    private final boolean debugLoggingEnabled;
    private final int connectionPoolSize;
    private final long connectionTimeoutMs;

    private BridgeConfiguration(Builder builder) {
        this.operationTimeoutMs = builder.operationTimeoutMs;
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
        this.maxBatchSize = builder.maxBatchSize;
        this.bufferPoolSize = builder.bufferPoolSize;
        this.defaultWorkerConcurrency = builder.defaultWorkerConcurrency;
        this.debugLoggingEnabled = builder.debugLoggingEnabled;
        this.connectionPoolSize = builder.connectionPoolSize;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
    }

    // Getters
    public long getOperationTimeoutMs() { return operationTimeoutMs; }
    public int getMaxRetries() { return maxRetries; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public int getBufferPoolSize() { return bufferPoolSize; }
    public int getDefaultWorkerConcurrency() { return defaultWorkerConcurrency; }
    public boolean isDebugLoggingEnabled() { return debugLoggingEnabled; }
    public int getConnectionPoolSize() { return connectionPoolSize; }
    public long getConnectionTimeoutMs() { return connectionTimeoutMs; }

    /**
     * Create a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for BridgeConfiguration with fluent API and validation.
     */
    public static class Builder {
        private long operationTimeoutMs = DEFAULT_OPERATION_TIMEOUT_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private int bufferPoolSize = DEFAULT_BUFFER_POOL_SIZE;
        private int defaultWorkerConcurrency = DEFAULT_WORKER_CONCURRENCY;
        private boolean debugLoggingEnabled = DEFAULT_DEBUG_LOGGING;
        private int connectionPoolSize = DEFAULT_CONNECTION_POOL_SIZE;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;

        /**
         * Set operation timeout.
         */
        public Builder operationTimeout(long timeout, TimeUnit unit) {
            this.operationTimeoutMs = unit.toMillis(timeout);
            validateTimeout(timeout, unit);
            return this;
        }

        /**
         * Set maximum retry attempts for failed operations.
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = validateRange(maxRetries, 0, 10, "maxRetries");
            return this;
        }

        /**
         * Set delay between retry attempts.
         */
        public Builder retryDelay(long delay, TimeUnit unit) {
            this.retryDelayMs = unit.toMillis(delay);
            validateTimeout(delay, unit);
            return this;
        }

        /**
         * Set maximum batch size for operations.
         */
        public Builder maxBatchSize(int size) {
            this.maxBatchSize = validateRange(size, 1, 10000, "maxBatchSize");
            return this;
        }

        /**
         * Set buffer pool size in bytes.
         */
        public Builder bufferPoolSize(int size) {
            this.bufferPoolSize = validateRange(size, 1024, 100 * 1024 * 1024, "bufferPoolSize"); // 1KB to 100MB
            return this;
        }

        /**
         * Set default worker thread concurrency.
         */
        public Builder defaultWorkerConcurrency(int concurrency) {
            this.defaultWorkerConcurrency = validateRange(concurrency, 1, 64, "defaultWorkerConcurrency");
            return this;
        }

        /**
         * Enable or disable debug logging.
         */
        public Builder enableDebugLogging(boolean enabled) {
            this.debugLoggingEnabled = enabled;
            return this;
        }

        /**
         * Set connection pool size.
         */
        public Builder connectionPoolSize(int size) {
            this.connectionPoolSize = validateRange(size, 1, 100, "connectionPoolSize");
            return this;
        }

        /**
         * Set connection timeout.
         */
        public Builder connectionTimeout(long timeout, TimeUnit unit) {
            this.connectionTimeoutMs = unit.toMillis(timeout);
            validateTimeout(timeout, unit);
            return this;
        }

        /**
         * Build the configuration with validation.
         */
        public BridgeConfiguration build() {
            validateConfiguration();
            return new BridgeConfiguration(this);
        }

        /**
         * Validate timeout values.
         */
        private void validateTimeout(long timeout, TimeUnit unit) {
            long ms = unit.toMillis(timeout);
            if (ms < 100) {
                throw new IllegalArgumentException("Timeout must be at least 100ms");
            }
            if (ms > 300000) { // 5 minutes
                throw new IllegalArgumentException("Timeout cannot exceed 5 minutes");
            }
        }

        /**
         * Validate range values.
         */
        private int validateRange(int value, int min, int max, String fieldName) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(
                    fieldName + " must be between " + min + " and " + max + ", got: " + value);
            }
            return value;
        }

        /**
         * Validate the complete configuration.
         */
        private void validateConfiguration() {
            // Ensure timeouts are reasonable relative to each other
            if (operationTimeoutMs < retryDelayMs * maxRetries) {
                throw new IllegalArgumentException(
                    "Operation timeout should be greater than retry delay * max retries");
            }

            // Ensure buffer pool is adequate for batch size
            if (bufferPoolSize < maxBatchSize * 1024) { // Rough estimate: 1KB per batch item
                System.out.println("Warning: Buffer pool size may be too small for max batch size");
            }
        }
    }

    @Override
    public String toString() {
        return "BridgeConfiguration{" +
                "operationTimeoutMs=" + operationTimeoutMs +
                ", maxRetries=" + maxRetries +
                ", maxBatchSize=" + maxBatchSize +
                ", bufferPoolSize=" + bufferPoolSize +
                ", debugLoggingEnabled=" + debugLoggingEnabled +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BridgeConfiguration)) return false;
        BridgeConfiguration other = (BridgeConfiguration) obj;
        return operationTimeoutMs == other.operationTimeoutMs &&
               maxRetries == other.maxRetries &&
               retryDelayMs == other.retryDelayMs &&
               maxBatchSize == other.maxBatchSize &&
               bufferPoolSize == other.bufferPoolSize &&
               defaultWorkerConcurrency == other.defaultWorkerConcurrency &&
               debugLoggingEnabled == other.debugLoggingEnabled &&
               connectionPoolSize == other.connectionPoolSize &&
               connectionTimeoutMs == other.connectionTimeoutMs;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(operationTimeoutMs) +
               Integer.hashCode(maxRetries) +
               Long.hashCode(retryDelayMs) +
               Integer.hashCode(maxBatchSize) +
               Integer.hashCode(bufferPoolSize) +
               Integer.hashCode(defaultWorkerConcurrency) +
               Boolean.hashCode(debugLoggingEnabled) +
               Integer.hashCode(connectionPoolSize) +
               Long.hashCode(connectionTimeoutMs);
    }
}