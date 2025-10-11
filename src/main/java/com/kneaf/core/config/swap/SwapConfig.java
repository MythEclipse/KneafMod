package com.kneaf.core.config.swap;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Objects;

/**
 * Immutable configuration class for swap system settings.
 */
public final class SwapConfig implements ConfigurationProvider {

    private final boolean enabled;
    private final long memoryCheckIntervalMs;
    private final int maxConcurrentSwaps;
    private final int swapBatchSize;
    private final long swapTimeoutMs;
    private final boolean enableAutomaticSwapping;
    private final double criticalMemoryThreshold;
    private final double highMemoryThreshold;
    private final double elevatedMemoryThreshold;
    private final int minSwapChunkAgeMs;
    private final boolean enableSwapStatistics;
    private final boolean enablePerformanceMonitoring;

    /**
     * Returns a new Builder for constructing SwapConfig instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Private constructor to enforce immutability (use Builder instead).
     *
     * @param builder the builder to construct from
     * @throws ConfigurationException if validation fails
     */
    private SwapConfig(Builder builder) throws ConfigurationException {
        this.enabled = builder.enabled;
        this.memoryCheckIntervalMs = builder.memoryCheckIntervalMs;
        this.maxConcurrentSwaps = builder.maxConcurrentSwaps;
        this.swapBatchSize = builder.swapBatchSize;
        this.swapTimeoutMs = builder.swapTimeoutMs;
        this.enableAutomaticSwapping = builder.enableAutomaticSwapping;
        this.criticalMemoryThreshold = builder.criticalMemoryThreshold;
        this.highMemoryThreshold = builder.highMemoryThreshold;
        this.elevatedMemoryThreshold = builder.elevatedMemoryThreshold;
        this.minSwapChunkAgeMs = builder.minSwapChunkAgeMs;
        this.enableSwapStatistics = builder.enableSwapStatistics;
        this.enablePerformanceMonitoring = builder.enablePerformanceMonitoring;

        // Perform validation during construction
        validate();
    }

    @Override
    public void validate() throws ConfigurationException {
        if (memoryCheckIntervalMs <= 0) {
            throw new ConfigurationException("memoryCheckIntervalMs must be greater than 0");
        }
        if (maxConcurrentSwaps <= 0) {
            throw new ConfigurationException("maxConcurrentSwaps must be greater than 0");
        }
        if (swapBatchSize <= 0) {
            throw new ConfigurationException("swapBatchSize must be greater than 0");
        }
        if (swapTimeoutMs <= 0) {
            throw new ConfigurationException("swapTimeoutMs must be greater than 0");
        }
        if (minSwapChunkAgeMs <= 0) {
            throw new ConfigurationException("minSwapChunkAgeMs must be greater than 0");
        }
        
        // Validate threshold ranges (0.0 to 1.0)
        if (criticalMemoryThreshold < 0.0 || criticalMemoryThreshold > 1.0) {
            throw new ConfigurationException("criticalMemoryThreshold must be between 0.0 and 1.0");
        }
        if (highMemoryThreshold < 0.0 || highMemoryThreshold > 1.0) {
            throw new ConfigurationException("highMemoryThreshold must be between 0.0 and 1.0");
        }
        if (elevatedMemoryThreshold < 0.0 || elevatedMemoryThreshold > 1.0) {
            throw new ConfigurationException("elevatedMemoryThreshold must be between 0.0 and 1.0");
        }
        
        // Validate threshold order
        if (criticalMemoryThreshold >= highMemoryThreshold) {
            throw new ConfigurationException("criticalMemoryThreshold must be less than highMemoryThreshold");
        }
        if (highMemoryThreshold >= elevatedMemoryThreshold) {
            throw new ConfigurationException("highMemoryThreshold must be less than elevatedMemoryThreshold");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the memory check interval in milliseconds.
     *
     * @return the memory check interval in milliseconds
     */
    public long getMemoryCheckIntervalMs() {
        return memoryCheckIntervalMs;
    }

    /**
     * Returns the maximum number of concurrent swaps.
     *
     * @return the maximum number of concurrent swaps
     */
    public int getMaxConcurrentSwaps() {
        return maxConcurrentSwaps;
    }

    /**
     * Returns the swap batch size.
     *
     * @return the swap batch size
     */
    public int getSwapBatchSize() {
        return swapBatchSize;
    }

    /**
     * Returns the swap timeout in milliseconds.
     *
     * @return the swap timeout in milliseconds
     */
    public long getSwapTimeoutMs() {
        return swapTimeoutMs;
    }

    /**
     * Returns whether automatic swapping is enabled.
     *
     * @return true if automatic swapping is enabled, false otherwise
     */
    public boolean isEnableAutomaticSwapping() {
        return enableAutomaticSwapping;
    }

    /**
     * Returns the critical memory threshold (0.0 to 1.0).
     *
     * @return the critical memory threshold
     */
    public double getCriticalMemoryThreshold() {
        return criticalMemoryThreshold;
    }

    /**
     * Returns the high memory threshold (0.0 to 1.0).
     *
     * @return the high memory threshold
     */
    public double getHighMemoryThreshold() {
        return highMemoryThreshold;
    }

    /**
     * Returns the elevated memory threshold (0.0 to 1.0).
     *
     * @return the elevated memory threshold
     */
    public double getElevatedMemoryThreshold() {
        return elevatedMemoryThreshold;
    }

    /**
     * Returns the minimum swap chunk age in milliseconds.
     *
     * @return the minimum swap chunk age in milliseconds
     */
    public int getMinSwapChunkAgeMs() {
        return minSwapChunkAgeMs;
    }

    /**
     * Returns whether swap statistics are enabled.
     *
     * @return true if swap statistics are enabled, false otherwise
     */
    public boolean isEnableSwapStatistics() {
        return enableSwapStatistics;
    }

    /**
     * Returns whether performance monitoring is enabled.
     *
     * @return true if performance monitoring is enabled, false otherwise
     */
    public boolean isEnablePerformanceMonitoring() {
        return enablePerformanceMonitoring;
    }

    /**
     * Builder class for constructing immutable SwapConfig instances.
     */
    public static final class Builder {

        private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
        private long memoryCheckIntervalMs = ConfigurationConstants.DEFAULT_SWAP_MEMORY_CHECK_INTERVAL_MS;
        private int maxConcurrentSwaps = ConfigurationConstants.DEFAULT_MAX_CONCURRENT_SWAPS;
        private int swapBatchSize = ConfigurationConstants.DEFAULT_SWAP_BATCH_SIZE;
        private long swapTimeoutMs = ConfigurationConstants.DEFAULT_SWAP_TIMEOUT_MS;
        private boolean enableAutomaticSwapping = ConfigurationConstants.DEFAULT_ENABLE_AUTOMATIC_SWAPPING;
        private double criticalMemoryThreshold = ConfigurationConstants.DEFAULT_CRITICAL_MEMORY_THRESHOLD;
        private double highMemoryThreshold = ConfigurationConstants.DEFAULT_HIGH_MEMORY_THRESHOLD;
        private double elevatedMemoryThreshold = ConfigurationConstants.DEFAULT_ELEVATED_MEMORY_THRESHOLD;
        private int minSwapChunkAgeMs = ConfigurationConstants.DEFAULT_MIN_SWAP_CHUNK_AGE_MS;
        private boolean enableSwapStatistics = ConfigurationConstants.DEFAULT_ENABLE_SWAP_STATISTICS;
        private boolean enablePerformanceMonitoring = ConfigurationConstants.DEFAULT_ENABLE_PERFORMANCE_MONITORING;

        /**
         * Sets whether the configuration is enabled.
         *
         * @param enabled whether the configuration is enabled
         * @return this Builder for method chaining
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the memory check interval in milliseconds.
         *
         * @param memoryCheckIntervalMs the memory check interval in milliseconds
         * @return this Builder for method chaining
         */
        public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) {
            this.memoryCheckIntervalMs = memoryCheckIntervalMs;
            return this;
        }

        /**
         * Sets the maximum number of concurrent swaps.
         *
         * @param maxConcurrentSwaps the maximum number of concurrent swaps
         * @return this Builder for method chaining
         */
        public Builder maxConcurrentSwaps(int maxConcurrentSwaps) {
            this.maxConcurrentSwaps = maxConcurrentSwaps;
            return this;
        }

        /**
         * Sets the swap batch size.
         *
         * @param swapBatchSize the swap batch size
         * @return this Builder for method chaining
         */
        public Builder swapBatchSize(int swapBatchSize) {
            this.swapBatchSize = swapBatchSize;
            return this;
        }

        /**
         * Sets the swap timeout in milliseconds.
         *
         * @param swapTimeoutMs the swap timeout in milliseconds
         * @return this Builder for method chaining
         */
        public Builder swapTimeoutMs(long swapTimeoutMs) {
            this.swapTimeoutMs = swapTimeoutMs;
            return this;
        }

        /**
         * Sets whether automatic swapping is enabled.
         *
         * @param enableAutomaticSwapping whether automatic swapping is enabled
         * @return this Builder for method chaining
         */
        public Builder enableAutomaticSwapping(boolean enableAutomaticSwapping) {
            this.enableAutomaticSwapping = enableAutomaticSwapping;
            return this;
        }

        /**
         * Sets the critical memory threshold (0.0 to 1.0).
         *
         * @param criticalMemoryThreshold the critical memory threshold
         * @return this Builder for method chaining
         */
        public Builder criticalMemoryThreshold(double criticalMemoryThreshold) {
            this.criticalMemoryThreshold = criticalMemoryThreshold;
            return this;
        }

        /**
         * Sets the high memory threshold (0.0 to 1.0).
         *
         * @param highMemoryThreshold the high memory threshold
         * @return this Builder for method chaining
         */
        public Builder highMemoryThreshold(double highMemoryThreshold) {
            this.highMemoryThreshold = highMemoryThreshold;
            return this;
        }

        /**
         * Sets the elevated memory threshold (0.0 to 1.0).
         *
         * @param elevatedMemoryThreshold the elevated memory threshold
         * @return this Builder for method chaining
         */
        public Builder elevatedMemoryThreshold(double elevatedMemoryThreshold) {
            this.elevatedMemoryThreshold = elevatedMemoryThreshold;
            return this;
        }

        /**
         * Sets the minimum swap chunk age in milliseconds.
         *
         * @param minSwapChunkAgeMs the minimum swap chunk age in milliseconds
         * @return this Builder for method chaining
         */
        public Builder minSwapChunkAgeMs(int minSwapChunkAgeMs) {
            this.minSwapChunkAgeMs = minSwapChunkAgeMs;
            return this;
        }

        /**
         * Sets whether swap statistics are enabled.
         *
         * @param enableSwapStatistics whether swap statistics are enabled
         * @return this Builder for method chaining
         */
        public Builder enableSwapStatistics(boolean enableSwapStatistics) {
            this.enableSwapStatistics = enableSwapStatistics;
            return this;
        }

        /**
         * Sets whether performance monitoring is enabled.
         *
         * @param enablePerformanceMonitoring whether performance monitoring is enabled
         * @return this Builder for method chaining
         */
        public Builder enablePerformanceMonitoring(boolean enablePerformanceMonitoring) {
            this.enablePerformanceMonitoring = enablePerformanceMonitoring;
            return this;
        }

        /**
         * Builds and returns a new SwapConfig instance.
         *
         * @return the constructed SwapConfig
         * @throws ConfigurationException if validation fails
         */
        public SwapConfig build() throws ConfigurationException {
            return new SwapConfig(this);
        }
    }

    /**
     * Returns a string representation of this SwapConfig.
     *
     * @return a string representation
     */
    @Override
    public String toString() {
        return "SwapConfig{" +
                "enabled=" + enabled +
                ", memoryCheckIntervalMs=" + memoryCheckIntervalMs +
                ", maxConcurrentSwaps=" + maxConcurrentSwaps +
                ", swapBatchSize=" + swapBatchSize +
                ", swapTimeoutMs=" + swapTimeoutMs +
                ", enableAutomaticSwapping=" + enableAutomaticSwapping +
                ", criticalMemoryThreshold=" + criticalMemoryThreshold +
                ", highMemoryThreshold=" + highMemoryThreshold +
                ", elevatedMemoryThreshold=" + elevatedMemoryThreshold +
                ", minSwapChunkAgeMs=" + minSwapChunkAgeMs +
                ", enableSwapStatistics=" + enableSwapStatistics +
                ", enablePerformanceMonitoring=" + enablePerformanceMonitoring +
                '}';
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param o the reference object with which to compare
     * @return true if this object is the same as the o argument; false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SwapConfig that = (SwapConfig) o;
        return enabled == that.enabled &&
                memoryCheckIntervalMs == that.memoryCheckIntervalMs &&
                maxConcurrentSwaps == that.maxConcurrentSwaps &&
                swapBatchSize == that.swapBatchSize &&
                swapTimeoutMs == that.swapTimeoutMs &&
                enableAutomaticSwapping == that.enableAutomaticSwapping &&
                Double.compare(that.criticalMemoryThreshold, criticalMemoryThreshold) == 0 &&
                Double.compare(that.highMemoryThreshold, highMemoryThreshold) == 0 &&
                Double.compare(that.elevatedMemoryThreshold, elevatedMemoryThreshold) == 0 &&
                minSwapChunkAgeMs == that.minSwapChunkAgeMs &&
                enableSwapStatistics == that.enableSwapStatistics &&
                enablePerformanceMonitoring == that.enablePerformanceMonitoring;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(enabled, memoryCheckIntervalMs, maxConcurrentSwaps, swapBatchSize, swapTimeoutMs,
                enableAutomaticSwapping, criticalMemoryThreshold, highMemoryThreshold, elevatedMemoryThreshold,
                minSwapChunkAgeMs, enableSwapStatistics, enablePerformanceMonitoring);
    }
}