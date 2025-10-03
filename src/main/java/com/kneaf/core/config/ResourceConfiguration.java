package com.kneaf.core.config;

/**
 * Unified resource configuration that consolidates resource management settings.
 */
public class ResourceConfiguration {
    private final int resourceCleanupIntervalSeconds;
    private final int resourceHealthCheckIntervalSeconds;
    private final int maxResourceAgeMinutes;
    private final boolean resourcePoolEnabled;
    private final int resourcePoolMaxSize;
    private final int resourcePoolInitialSize;

    private ResourceConfiguration(Builder builder) {
        this.resourceCleanupIntervalSeconds = builder.resourceCleanupIntervalSeconds;
        this.resourceHealthCheckIntervalSeconds = builder.resourceHealthCheckIntervalSeconds;
        this.maxResourceAgeMinutes = builder.maxResourceAgeMinutes;
        this.resourcePoolEnabled = builder.resourcePoolEnabled;
        this.resourcePoolMaxSize = builder.resourcePoolMaxSize;
        this.resourcePoolInitialSize = builder.resourcePoolInitialSize;
        
        validate();
    }
    
    private void validate() {
        if (resourceCleanupIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Resource cleanup interval must be positive");
        }
        if (resourceHealthCheckIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Resource health check interval must be positive");
        }
        if (maxResourceAgeMinutes <= 0) {
            throw new IllegalArgumentException("Max resource age must be positive");
        }
        if (resourcePoolMaxSize <= 0) {
            throw new IllegalArgumentException("Resource pool max size must be positive");
        }
        if (resourcePoolInitialSize < 0) {
            throw new IllegalArgumentException("Resource pool initial size cannot be negative");
        }
        if (resourcePoolInitialSize > resourcePoolMaxSize) {
            throw new IllegalArgumentException("Resource pool initial size cannot exceed max size");
        }
    }
    
    // Getters
    public int getResourceCleanupIntervalSeconds() { return resourceCleanupIntervalSeconds; }
    public int getResourceHealthCheckIntervalSeconds() { return resourceHealthCheckIntervalSeconds; }
    public int getMaxResourceAgeMinutes() { return maxResourceAgeMinutes; }
    public boolean isResourcePoolEnabled() { return resourcePoolEnabled; }
    public int getResourcePoolMaxSize() { return resourcePoolMaxSize; }
    public int getResourcePoolInitialSize() { return resourcePoolInitialSize; }
    
    @Override
    public String toString() {
        return String.format("ResourceConfiguration{resourceCleanupIntervalSeconds=%d, " +
                           "resourceHealthCheckIntervalSeconds=%d, maxResourceAgeMinutes=%d, " +
                           "resourcePoolEnabled=%s, resourcePoolMaxSize=%d, resourcePoolInitialSize=%d}",
                           resourceCleanupIntervalSeconds, resourceHealthCheckIntervalSeconds,
                           maxResourceAgeMinutes, resourcePoolEnabled, resourcePoolMaxSize,
                           resourcePoolInitialSize);
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private int resourceCleanupIntervalSeconds = 300; // 5 minutes
        private int resourceHealthCheckIntervalSeconds = 60; // 1 minute
        private int maxResourceAgeMinutes = 30;
        private boolean resourcePoolEnabled = true;
        private int resourcePoolMaxSize = 100;
        private int resourcePoolInitialSize = 10;
        
        public Builder resourceCleanupIntervalSeconds(int resourceCleanupIntervalSeconds) { 
            this.resourceCleanupIntervalSeconds = resourceCleanupIntervalSeconds; return this; 
        }
        public Builder resourceHealthCheckIntervalSeconds(int resourceHealthCheckIntervalSeconds) { 
            this.resourceHealthCheckIntervalSeconds = resourceHealthCheckIntervalSeconds; return this; 
        }
        public Builder maxResourceAgeMinutes(int maxResourceAgeMinutes) { 
            this.maxResourceAgeMinutes = maxResourceAgeMinutes; return this; 
        }
        public Builder resourcePoolEnabled(boolean resourcePoolEnabled) { 
            this.resourcePoolEnabled = resourcePoolEnabled; return this; 
        }
        public Builder resourcePoolMaxSize(int resourcePoolMaxSize) { 
            this.resourcePoolMaxSize = resourcePoolMaxSize; return this; 
        }
        public Builder resourcePoolInitialSize(int resourcePoolInitialSize) { 
            this.resourcePoolInitialSize = resourcePoolInitialSize; return this; 
        }
        
        public ResourceConfiguration build() {
            return new ResourceConfiguration(this);
        }
    }
}