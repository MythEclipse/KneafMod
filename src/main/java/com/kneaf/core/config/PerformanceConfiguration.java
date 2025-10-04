package com.kneaf.core.config;

import java.util.Arrays;

/** Unified performance configuration that consolidates all performance-related settings. */
public class PerformanceConfiguration {
  private final boolean enabled;
  private final int threadpoolSize;
  private final int logIntervalTicks;
  private final int scanIntervalTicks;
  private final double tpsThresholdForAsync;
  private final int maxEntitiesToCollect;
  private final double entityDistanceCutoff;
  private final long maxLogBytes;
  private final boolean adaptiveThreadPool;
  private final int maxThreadpoolSize;
  private final String[] excludedEntityTypes;
  private final int networkExecutorpoolSize;
  private final boolean profilingEnabled;
  private final long slowTickThresholdMs;
  private final int profilingSampleRate;

  // Advanced parallelism configuration
  private final int minThreadpoolSize;
  private final boolean dynamicThreadScaling;
  private final double threadScaleUpThreshold;
  private final double threadScaleDownThreshold;
  private final int threadScaleUpDelayTicks;
  private final int threadScaleDownDelayTicks;
  private final boolean workStealingEnabled;
  private final int workStealingQueueSize;
  private final boolean cpuAwareThreadSizing;
  private final double cpuLoadThreshold;
  private final int threadPoolKeepAliveSeconds;

  // Distance & processing optimizations
  private final int distanceCalculationInterval;
  private final boolean distanceApproximationEnabled;
  private final int distanceCacheSize;
  private final int itemProcessingIntervalMultiplier;
  private final int spatialGridUpdateInterval;
  private final boolean incrementalSpatialUpdates;

  private PerformanceConfiguration(Builder builder) {
    this.enabled = builder.enabled;
    this.threadpoolSize = builder.threadpoolSize;
    this.logIntervalTicks = builder.logIntervalTicks;
    this.scanIntervalTicks = builder.scanIntervalTicks;
    this.tpsThresholdForAsync = builder.tpsThresholdForAsync;
    this.maxEntitiesToCollect = builder.maxEntitiesToCollect;
    this.entityDistanceCutoff = builder.entityDistanceCutoff;
    this.maxLogBytes = builder.maxLogBytes;
    this.adaptiveThreadPool = builder.adaptiveThreadPool;
    this.maxThreadpoolSize = builder.maxThreadpoolSize;
    this.excludedEntityTypes =
        builder.excludedEntityTypes == null ? new String[0] : builder.excludedEntityTypes.clone();
    this.networkExecutorpoolSize = builder.networkExecutorpoolSize;
    this.profilingEnabled = builder.profilingEnabled;
    this.slowTickThresholdMs = builder.slowTickThresholdMs;
    this.profilingSampleRate = builder.profilingSampleRate;

    // Advanced parallelism configuration
    this.minThreadpoolSize = builder.minThreadpoolSize;
    this.dynamicThreadScaling = builder.dynamicThreadScaling;
    this.threadScaleUpThreshold = builder.threadScaleUpThreshold;
    this.threadScaleDownThreshold = builder.threadScaleDownThreshold;
    this.threadScaleUpDelayTicks = builder.threadScaleUpDelayTicks;
    this.threadScaleDownDelayTicks = builder.threadScaleDownDelayTicks;
    this.workStealingEnabled = builder.workStealingEnabled;
    this.workStealingQueueSize = builder.workStealingQueueSize;
    this.cpuAwareThreadSizing = builder.cpuAwareThreadSizing;
    this.cpuLoadThreshold = builder.cpuLoadThreshold;
    this.threadPoolKeepAliveSeconds = builder.threadPoolKeepAliveSeconds;

    // Distance & processing optimizations
    this.distanceCalculationInterval = builder.distanceCalculationInterval;
    this.distanceApproximationEnabled = builder.distanceApproximationEnabled;
    this.distanceCacheSize = builder.distanceCacheSize;
    this.itemProcessingIntervalMultiplier = builder.itemProcessingIntervalMultiplier;
    this.spatialGridUpdateInterval = builder.spatialGridUpdateInterval;
    this.incrementalSpatialUpdates = builder.incrementalSpatialUpdates;

    validate();
  }

  private void validate() {
    if (minThreadpoolSize > maxThreadpoolSize) {
      throw new IllegalArgumentException(
          "minThreadpoolSize ("
              + minThreadpoolSize
              + ") cannot be greater than maxThreadpoolSize ("
              + maxThreadpoolSize
              + ")");
    }
    if (threadScaleUpThreshold <= threadScaleDownThreshold) {
      throw new IllegalArgumentException(
          "threadScaleUpThreshold ("
              + threadScaleUpThreshold
              + ") must be greater than threadScaleDownThreshold ("
              + threadScaleDownThreshold
              + ")");
    }
    if (scanIntervalTicks < 1 || scanIntervalTicks > 100) {
      throw new IllegalArgumentException(
          "scanIntervalTicks must be between 1 and 100, got: " + scanIntervalTicks);
    }
    if (tpsThresholdForAsync < 10.0 || tpsThresholdForAsync > 20.0) {
      throw new IllegalArgumentException(
          "tpsThresholdForAsync must be between 10.0 and 20.0, got: " + tpsThresholdForAsync);
    }
  }

  // Getters
  public boolean isEnabled() {
    return enabled;
  }

  public int getThreadpoolSize() {
    return threadpoolSize;
  }

  public int getLogIntervalTicks() {
    return logIntervalTicks;
  }

  public int getScanIntervalTicks() {
    return scanIntervalTicks;
  }

  public double getTpsThresholdForAsync() {
    return tpsThresholdForAsync;
  }

  public int getMaxEntitiesToCollect() {
    return maxEntitiesToCollect;
  }

  public double getEntityDistanceCutoff() {
    return entityDistanceCutoff;
  }

  public long getMaxLogBytes() {
    return maxLogBytes;
  }

  public boolean isAdaptiveThreadPool() {
    return adaptiveThreadPool;
  }

  public int getMaxThreadpoolSize() {
    return maxThreadpoolSize;
  }

  public String[] getExcludedEntityTypes() {
    return excludedEntityTypes.clone();
  }

  public int getNetworkExecutorpoolSize() {
    return networkExecutorpoolSize;
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled;
  }

  public long getSlowTickThresholdMs() {
    return slowTickThresholdMs;
  }

  public int getProfilingSampleRate() {
    return profilingSampleRate;
  }

  // Advanced parallelism getters
  public int getMinThreadpoolSize() {
    return minThreadpoolSize;
  }

  public boolean isDynamicThreadScaling() {
    return dynamicThreadScaling;
  }

  public double getThreadScaleUpThreshold() {
    return threadScaleUpThreshold;
  }

  public double getThreadScaleDownThreshold() {
    return threadScaleDownThreshold;
  }

  public int getThreadScaleUpDelayTicks() {
    return threadScaleUpDelayTicks;
  }

  public int getThreadScaleDownDelayTicks() {
    return threadScaleDownDelayTicks;
  }

  public boolean isWorkStealingEnabled() {
    return workStealingEnabled;
  }

  public int getWorkStealingQueueSize() {
    return workStealingQueueSize;
  }

  public boolean isCpuAwareThreadSizing() {
    return cpuAwareThreadSizing;
  }

  public double getCpuLoadThreshold() {
    return cpuLoadThreshold;
  }

  public int getThreadPoolKeepAliveSeconds() {
    return threadPoolKeepAliveSeconds;
  }

  // Distance calculation optimization getters
  public int getDistanceCalculationInterval() {
    return distanceCalculationInterval;
  }

  public boolean isDistanceApproximationEnabled() {
    return distanceApproximationEnabled;
  }

  public int getDistanceCacheSize() {
    return distanceCacheSize;
  }

  // Item processing optimization getters
  public int getItemProcessingIntervalMultiplier() {
    return itemProcessingIntervalMultiplier;
  }

  // Spatial grid optimization getters
  public int getSpatialGridUpdateInterval() {
    return spatialGridUpdateInterval;
  }

  public boolean isIncrementalSpatialUpdates() {
    return incrementalSpatialUpdates;
  }

  @Override
  public String toString() {
    return "PerformanceConfiguration{"
        + "enabled="
        + enabled
        + ", threadpoolSize="
        + threadpoolSize
        + ", logIntervalTicks="
        + logIntervalTicks
        + ", scanIntervalTicks="
        + scanIntervalTicks
        + ", tpsThresholdForAsync="
        + tpsThresholdForAsync
        + ", maxEntitiesToCollect="
        + maxEntitiesToCollect
        + ", entityDistanceCutoff="
        + entityDistanceCutoff
        + ", maxLogBytes="
        + maxLogBytes
        + ", adaptiveThreadPool="
        + adaptiveThreadPool
        + ", maxThreadpoolSize="
        + maxThreadpoolSize
        + ", excludedEntityTypes="
        + Arrays.toString(excludedEntityTypes)
        + ", profilingEnabled="
        + profilingEnabled
        + ", slowTickThresholdMs="
        + slowTickThresholdMs
        + ", profilingSampleRate="
        + profilingSampleRate
        + '}';
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    // Basic performance settings
    private boolean enabled = true;
    private int threadpoolSize = 4;
    private int logIntervalTicks = 100;
    private int scanIntervalTicks = 1;
    private double tpsThresholdForAsync = 19.0;
    private int maxEntitiesToCollect = 20000;
    private double entityDistanceCutoff = 256.0;
    private long maxLogBytes = 10L * 1024 * 1024;
    private boolean adaptiveThreadPool = false;
    private int maxThreadpoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private String[] excludedEntityTypes = new String[0];
    private int networkExecutorpoolSize =
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private boolean profilingEnabled = true;
    private long slowTickThresholdMs = 50L;
    private int profilingSampleRate = 100;

    // Advanced parallelism configuration
    private int minThreadpoolSize = 2;
    private boolean dynamicThreadScaling = true;
    private double threadScaleUpThreshold = 0.8;
    private double threadScaleDownThreshold = 0.3;
    private int threadScaleUpDelayTicks = 100;
    private int threadScaleDownDelayTicks = 200;
    private boolean workStealingEnabled = true;
    private int workStealingQueueSize = 100;
    private boolean cpuAwareThreadSizing = false;
    private double cpuLoadThreshold = 0.7;
    private int threadPoolKeepAliveSeconds = 60;

    // Distance & processing optimizations
    private int distanceCalculationInterval = 1;
    private boolean distanceApproximationEnabled = true;
    private int distanceCacheSize = 100;
    private int itemProcessingIntervalMultiplier = 1;
    private int spatialGridUpdateInterval = 1;
    private boolean incrementalSpatialUpdates = true;

    // Basic performance setters
    public Builder enabled(boolean v) {
      this.enabled = v;
      return this;
    }

    public Builder threadpoolSize(int v) {
      this.threadpoolSize = v;
      return this;
    }

    public Builder logIntervalTicks(int v) {
      this.logIntervalTicks = v;
      return this;
    }

    public Builder scanIntervalTicks(int v) {
      this.scanIntervalTicks = v;
      return this;
    }

    public Builder tpsThresholdForAsync(double v) {
      this.tpsThresholdForAsync = v;
      return this;
    }

    public Builder maxEntitiesToCollect(int v) {
      this.maxEntitiesToCollect = v;
      return this;
    }

    public Builder entityDistanceCutoff(double v) {
      this.entityDistanceCutoff = v;
      return this;
    }

    public Builder maxLogBytes(long v) {
      this.maxLogBytes = v;
      return this;
    }

    public Builder adaptiveThreadPool(boolean v) {
      this.adaptiveThreadPool = v;
      return this;
    }

    public Builder maxThreadpoolSize(int v) {
      this.maxThreadpoolSize = v;
      return this;
    }

    public Builder excludedEntityTypes(String[] v) {
      this.excludedEntityTypes = v == null ? new String[0] : v.clone();
      return this;
    }

    public Builder networkExecutorpoolSize(int v) {
      this.networkExecutorpoolSize = v;
      return this;
    }

    public Builder profilingEnabled(boolean v) {
      this.profilingEnabled = v;
      return this;
    }

    public Builder slowTickThresholdMs(long v) {
      this.slowTickThresholdMs = v;
      return this;
    }

    public Builder profilingSampleRate(int v) {
      this.profilingSampleRate = v;
      return this;
    }

    // Advanced parallelism setters
    public Builder minThreadpoolSize(int v) {
      this.minThreadpoolSize = v;
      return this;
    }

    public Builder dynamicThreadScaling(boolean v) {
      this.dynamicThreadScaling = v;
      return this;
    }

    public Builder threadScaleUpThreshold(double v) {
      this.threadScaleUpThreshold = v;
      return this;
    }

    public Builder threadScaleDownThreshold(double v) {
      this.threadScaleDownThreshold = v;
      return this;
    }

    public Builder threadScaleUpDelayTicks(int v) {
      this.threadScaleUpDelayTicks = v;
      return this;
    }

    public Builder threadScaleDownDelayTicks(int v) {
      this.threadScaleDownDelayTicks = v;
      return this;
    }

    public Builder workStealingEnabled(boolean v) {
      this.workStealingEnabled = v;
      return this;
    }

    public Builder workStealingQueueSize(int v) {
      this.workStealingQueueSize = v;
      return this;
    }

    public Builder cpuAwareThreadSizing(boolean v) {
      this.cpuAwareThreadSizing = v;
      return this;
    }

    public Builder cpuLoadThreshold(double v) {
      this.cpuLoadThreshold = v;
      return this;
    }

    public Builder threadPoolKeepAliveSeconds(int v) {
      this.threadPoolKeepAliveSeconds = v;
      return this;
    }

    // Distance & processing optimization setters
    public Builder distanceCalculationInterval(int v) {
      this.distanceCalculationInterval = v;
      return this;
    }

    public Builder distanceApproximationEnabled(boolean v) {
      this.distanceApproximationEnabled = v;
      return this;
    }

    public Builder distanceCacheSize(int v) {
      this.distanceCacheSize = v;
      return this;
    }

    public Builder itemProcessingIntervalMultiplier(int v) {
      this.itemProcessingIntervalMultiplier = v;
      return this;
    }

    public Builder spatialGridUpdateInterval(int v) {
      this.spatialGridUpdateInterval = v;
      return this;
    }

    public Builder incrementalSpatialUpdates(boolean v) {
      this.incrementalSpatialUpdates = v;
      return this;
    }

    public PerformanceConfiguration build() {
      return new PerformanceConfiguration(this);
    }
  }
}
