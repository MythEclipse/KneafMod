package com.kneaf.core.config.resource;

import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Objects;

/**
 * Immutable configuration class for resource management settings.
 */
public final class ResourceConfig implements ConfigurationProvider {

    /**
     * Enum representing resource load priorities.
     */
    public enum LoadPriority {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    private final long maxMemoryUsage;
    private final int resourceCacheSize;
    private final LoadPriority loadPriority;

    /**
     * Returns a new Builder for constructing ResourceConfig instances.
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
     */
    private ResourceConfig(Builder builder) throws ConfigurationException {
        this.maxMemoryUsage = builder.maxMemoryUsage;
        this.resourceCacheSize = builder.resourceCacheSize;
        this.loadPriority = builder.loadPriority;
        
        // Perform validation during construction
        validate();
    }

    /**
     * Validates the configuration values.
     *
     * @throws ConfigurationException if validation fails
     */
    @Override
    public void validate() throws ConfigurationException {
        if (maxMemoryUsage <= 0) {
            throw new ConfigurationException("maxMemoryUsage must be greater than 0");
        }
        if (resourceCacheSize <= 0) {
            throw new ConfigurationException("resourceCacheSize must be greater than 0");
        }
        if (loadPriority == null) {
            throw new ConfigurationException("loadPriority must be specified");
        }
    }

    /**
     * Returns the maximum memory usage in MB.
     *
     * @return the maximum memory usage
     */
    public long getMaxMemoryUsage() {
        return maxMemoryUsage;
    }

    /**
     * Returns the size of the resource cache in number of entries.
     *
     * @return the resource cache size
     */
    public int getResourceCacheSize() {
        return resourceCacheSize;
    }

    @Override
    public boolean isEnabled() {
        return true; // Resource configuration is always enabled by default
    }

    /**
     * Returns the resource load priority.
     *
     * @return the load priority
     */
    public LoadPriority getLoadPriority() {
        return loadPriority;
    }

    /**
     * Builder class for constructing immutable ResourceConfig instances.
     */
    public static final class Builder {

        private long maxMemoryUsage;
        private int resourceCacheSize;
        private LoadPriority loadPriority;

        /**
         * Sets the maximum memory usage in MB.
         *
         * @param maxMemoryUsage the maximum memory usage
         * @return this Builder for method chaining
         */
        public Builder maxMemoryUsage(long maxMemoryUsage) {
            this.maxMemoryUsage = maxMemoryUsage;
            return this;
        }

        /**
         * Sets the size of the resource cache in number of entries.
         *
         * @param resourceCacheSize the resource cache size
         * @return this Builder for method chaining
         */
        public Builder resourceCacheSize(int resourceCacheSize) {
            this.resourceCacheSize = resourceCacheSize;
            return this;
        }

        /**
         * Sets the resource load priority.
         *
         * @param loadPriority the load priority
         * @return this Builder for method chaining
         */
        public Builder loadPriority(LoadPriority loadPriority) {
            this.loadPriority = loadPriority;
            return this;
        }

        /**
         * Builds and returns a new ResourceConfig instance.
         *
         * @return the constructed ResourceConfig
         * @throws ConfigurationException if validation fails
         */
        public ResourceConfig build() {
            try {
                return new ResourceConfig(this);
            } catch (ConfigurationException e) {
                throw new RuntimeException("Failed to build ResourceConfig: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Returns a string representation of this ResourceConfig.
     *
     * @return a string representation
     */
    @Override
    public String toString() {
        return "ResourceConfig{" +
                "maxMemoryUsage=" + maxMemoryUsage +
                ", resourceCacheSize=" + resourceCacheSize +
                ", loadPriority=" + loadPriority +
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
        ResourceConfig that = (ResourceConfig) o;
        return maxMemoryUsage == that.maxMemoryUsage &&
                resourceCacheSize == that.resourceCacheSize &&
                loadPriority == that.loadPriority;
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(maxMemoryUsage, resourceCacheSize, loadPriority);
    }
}