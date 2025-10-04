package com.kneaf.core.config.resource;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.core.ConfigurationUtils;

/** Unified resource configuration that consolidates resource management settings. */
public class ResourceConfiguration implements ConfigurationProvider {
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

  @Override
  public void validate() {
    ConfigurationUtils.validatePositive(
        resourceCleanupIntervalSeconds, "Resource cleanup interval");
    ConfigurationUtils.validatePositive(
        resourceHealthCheckIntervalSeconds, "Resource health check interval");
    ConfigurationUtils.validatePositive(maxResourceAgeMinutes, "Max resource age");
    ConfigurationUtils.validatePositive(resourcePoolMaxSize, "Resource pool max size");
    if (resourcePoolInitialSize < 0) {
      throw new IllegalArgumentException("Resource pool initial size cannot be negative");
    }
    ConfigurationUtils.validateMinMax(
        resourcePoolInitialSize,
        resourcePoolMaxSize,
        "resourcePoolInitialSize",
        "resourcePoolMaxSize");
  }

  @Override
  public boolean isEnabled() {
    return true;
  } // Resource configuration is always enabled

  // Getters
  public int getResourceCleanupIntervalSeconds() {
    return resourceCleanupIntervalSeconds;
  }

  public int getResourceHealthCheckIntervalSeconds() {
    return resourceHealthCheckIntervalSeconds;
  }

  public int getMaxResourceAgeMinutes() {
    return maxResourceAgeMinutes;
  }

  public boolean isResourcePoolEnabled() {
    return resourcePoolEnabled;
  }

  public int getResourcePoolMaxSize() {
    return resourcePoolMaxSize;
  }

  public int getResourcePoolInitialSize() {
    return resourcePoolInitialSize;
  }

  @Override
  public String toString() {
    return String.format(
        "ResourceConfiguration{resourceCleanupIntervalSeconds=%d, "
            + "resourceHealthCheckIntervalSeconds=%d, maxResourceAgeMinutes=%d, "
            + "resourcePoolEnabled=%s, resourcePoolMaxSize=%d, resourcePoolInitialSize=%d}",
        resourceCleanupIntervalSeconds,
        resourceHealthCheckIntervalSeconds,
        maxResourceAgeMinutes,
        resourcePoolEnabled,
        resourcePoolMaxSize,
        resourcePoolInitialSize);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private int resourceCleanupIntervalSeconds =
        ConfigurationConstants.DEFAULT_RESOURCE_CLEANUP_INTERVAL_SECONDS;
    private int resourceHealthCheckIntervalSeconds =
        ConfigurationConstants.DEFAULT_RESOURCE_HEALTH_CHECK_INTERVAL_SECONDS;
    private int maxResourceAgeMinutes = ConfigurationConstants.DEFAULT_MAX_RESOURCE_AGE_MINUTES;
    private boolean resourcePoolEnabled = ConfigurationConstants.DEFAULT_RESOURCE_POOL_ENABLED;
    private int resourcePoolMaxSize = ConfigurationConstants.DEFAULT_RESOURCE_POOL_MAX_SIZE;
    private int resourcePoolInitialSize = ConfigurationConstants.DEFAULT_RESOURCE_POOL_INITIAL_SIZE;

    public Builder resourceCleanupIntervalSeconds(int resourceCleanupIntervalSeconds) {
      this.resourceCleanupIntervalSeconds = resourceCleanupIntervalSeconds;
      return this;
    }

    public Builder resourceHealthCheckIntervalSeconds(int resourceHealthCheckIntervalSeconds) {
      this.resourceHealthCheckIntervalSeconds = resourceHealthCheckIntervalSeconds;
      return this;
    }

    public Builder maxResourceAgeMinutes(int maxResourceAgeMinutes) {
      this.maxResourceAgeMinutes = maxResourceAgeMinutes;
      return this;
    }

    public Builder resourcePoolEnabled(boolean resourcePoolEnabled) {
      this.resourcePoolEnabled = resourcePoolEnabled;
      return this;
    }

    public Builder resourcePoolMaxSize(int resourcePoolMaxSize) {
      this.resourcePoolMaxSize = resourcePoolMaxSize;
      return this;
    }

    public Builder resourcePoolInitialSize(int resourcePoolInitialSize) {
      this.resourcePoolInitialSize = resourcePoolInitialSize;
      return this;
    }

    public ResourceConfiguration build() {
      return new ResourceConfiguration(this);
    }
  }
}
