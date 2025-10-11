package com.kneaf.core.config;

import com.kneaf.core.config.chunkstorage.ChunkStorageConfig;
import com.kneaf.core.performance.monitoring.PerformanceConfig;
import com.kneaf.core.config.resource.ResourceConfig;
import com.kneaf.core.config.swap.SwapConfig;
import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Objects;

/**
 * Immutable main configuration container that holds all domain-specific configurations.
 * Serves as the central point for accessing all configuration settings.
 */
public final class UnifiedConfiguration {

    private final PerformanceConfig performanceConfig;
    private final ChunkStorageConfig chunkStorageConfig;
    private final SwapConfig swapConfig;
    private final ResourceConfig resourceConfig;

    /**
     * Returns a new Builder for constructing UnifiedConfiguration instances.
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
    private UnifiedConfiguration(Builder builder) throws ConfigurationException {
        this.performanceConfig = builder.performanceConfig;
        this.chunkStorageConfig = builder.chunkStorageConfig;
        this.swapConfig = builder.swapConfig;
        this.resourceConfig = builder.resourceConfig;

        // Perform validation of all sub-configurations
        validate();
    }

    /**
     * Validates all sub-configuration instances.
     *
     * @throws ConfigurationException if any validation fails
     */
    private void validate() throws ConfigurationException {
        if (performanceConfig == null) {
            throw new ConfigurationException("Performance configuration must not be null");
        }

        if (chunkStorageConfig == null) {
            throw new ConfigurationException("Chunk storage configuration must not be null");
        }
        chunkStorageConfig.validate();

        if (swapConfig == null) {
            throw new ConfigurationException("Swap configuration must not be null");
        }
        swapConfig.validate();

        if (resourceConfig == null) {
            throw new ConfigurationException("Resource configuration must not be null");
        }
        resourceConfig.validate();
    }

    /**
     * Returns the performance configuration.
     *
     * @return the performance configuration
     */
    public PerformanceConfig getPerformanceConfig() {
        return performanceConfig;
    }

    /**
     * Returns the chunk storage configuration.
     *
     * @return the chunk storage configuration
     */
    public ChunkStorageConfig getChunkStorageConfig() {
        return chunkStorageConfig;
    }

    /**
     * Returns the swap configuration.
     *
     * @return the swap configuration
     */
    public SwapConfig getSwapConfig() {
        return swapConfig;
    }

    /**
     * Returns the resource configuration.
     *
     * @return the resource configuration
     */
    public ResourceConfig getResourceConfig() {
        return resourceConfig;
    }

    /**
     * Builder class for constructing immutable UnifiedConfiguration instances.
     */
    public static final class Builder {

        private PerformanceConfig performanceConfig;
        private ChunkStorageConfig chunkStorageConfig;
        private SwapConfig swapConfig;
        private ResourceConfig resourceConfig;

        /**
         * Default constructor that initializes with default configurations.
         */
        public Builder() {
            try {
                this.performanceConfig = new PerformanceConfig.Builder().build();
                this.chunkStorageConfig = ChunkStorageConfig.builder().build();
                this.swapConfig = SwapConfig.builder().build();
                this.resourceConfig = ResourceConfig.builder().build();
            } catch (ConfigurationException e) {
                // This should not happen with default values, but if it does, throw runtime exception
                throw new RuntimeException("Failed to create default configuration", e);
            }
        }

        /**
         * Sets the performance configuration.
         *
         * @param performanceConfig the performance configuration
         * @return this Builder for method chaining
         */
        public Builder performanceConfig(PerformanceConfig performanceConfig) {
            this.performanceConfig = performanceConfig;
            return this;
        }

        /**
         * Sets the chunk storage configuration.
         *
         * @param chunkStorageConfig the chunk storage configuration
         * @return this Builder for method chaining
         */
        public Builder chunkStorageConfig(ChunkStorageConfig chunkStorageConfig) {
            this.chunkStorageConfig = chunkStorageConfig;
            return this;
        }

        /**
         * Sets the swap configuration.
         *
         * @param swapConfig the swap configuration
         * @return this Builder for method chaining
         */
        public Builder swapConfig(SwapConfig swapConfig) {
            this.swapConfig = swapConfig;
            return this;
        }

        /**
         * Sets the resource configuration.
         *
         * @param resourceConfig the resource configuration
         * @return this Builder for method chaining
         */
        public Builder resourceConfig(ResourceConfig resourceConfig) {
            this.resourceConfig = resourceConfig;
            return this;
        }

        /**
         * Builds and returns a new UnifiedConfiguration instance.
         *
         * @return the constructed UnifiedConfiguration
         * @throws ConfigurationException if validation fails
         */
        public UnifiedConfiguration build() throws ConfigurationException {
            return new UnifiedConfiguration(this);
        }
    }

    /**
     * Returns a string representation of this UnifiedConfiguration.
     *
     * @return a string representation
     */
    @Override
    public String toString() {
        return "UnifiedConfiguration{" +
                "performanceConfig=" + performanceConfig +
                ", chunkStorageConfig=" + chunkStorageConfig +
                ", swapConfig=" + swapConfig +
                ", resourceConfig=" + resourceConfig +
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
        UnifiedConfiguration that = (UnifiedConfiguration) o;
        return performanceConfig.equals(that.performanceConfig) &&
                chunkStorageConfig.equals(that.chunkStorageConfig) &&
                swapConfig.equals(that.swapConfig) &&
                resourceConfig.equals(that.resourceConfig);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(performanceConfig, chunkStorageConfig, swapConfig, resourceConfig);
    }
}