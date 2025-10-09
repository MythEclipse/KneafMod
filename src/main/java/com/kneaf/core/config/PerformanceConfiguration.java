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

  // Extreme performance configuration
  private final boolean enableExtremeAvx512;
  private final boolean enableLockFreePooling;
  private final double memoryPressureThreshold;
  private final boolean enableAggressivePreallocation;
  private final int preallocationBufferSize;
  private final boolean enableSafetyChecks;
  private final boolean enableMemoryLeakDetection;
  private final boolean enablePerformanceMonitoring;
  private final boolean enableErrorRecovery;
  private final boolean enableMinimalMonitoring;
  private final int monitoringSampleRate;
  private final boolean enablePerformanceWarnings;
  private final boolean enableFeatureFlags;
  private final boolean enableAutoRollback;
  private final double rollbackThreshold;
  private final int rollbackCheckInterval;
  
  // Ultra-performance configuration
  private final boolean enableUltraPerformanceMode;
  private final boolean enableAggressiveOptimizations;
  private final int maxConcurrentOperations;
  private final int batchSize;
  private final int prefetchDistance;
  private final int cacheSizeMb;
  private final boolean enableSafetyChecksUltra;
  private final boolean enableBoundsCheckingUltra;
  private final boolean enableNullChecksUltra;
  private final boolean enableAggressiveInlining;
  private final boolean enableLoopUnrolling;
  private final boolean enableVectorization;
  private final boolean enableMemoryPoolingUltra;
  private final int poolSizeMbUltra;
  private final boolean enableSimdUltra;
  private final int simdBatchSizeUltra;
  private final boolean enableBranchHints;
  private final boolean enableCachePrefetching;

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

    // Extreme performance configuration
    this.enableExtremeAvx512 = builder.enableExtremeAvx512;
    this.enableLockFreePooling = builder.enableLockFreePooling;
    this.memoryPressureThreshold = builder.memoryPressureThreshold;
    this.enableAggressivePreallocation = builder.enableAggressivePreallocation;
    this.preallocationBufferSize = builder.preallocationBufferSize;
    this.enableSafetyChecks = builder.enableSafetyChecks;
    this.enableMemoryLeakDetection = builder.enableMemoryLeakDetection;
    this.enablePerformanceMonitoring = builder.enablePerformanceMonitoring;
    this.enableErrorRecovery = builder.enableErrorRecovery;
    this.enableMinimalMonitoring = builder.enableMinimalMonitoring;
    this.monitoringSampleRate = builder.monitoringSampleRate;
    this.enablePerformanceWarnings = builder.enablePerformanceWarnings;
    this.enableFeatureFlags = builder.enableFeatureFlags;
    this.enableAutoRollback = builder.enableAutoRollback;
    this.rollbackThreshold = builder.rollbackThreshold;
    this.rollbackCheckInterval = builder.rollbackCheckInterval;
    
    // Ultra-performance configuration
    this.enableUltraPerformanceMode = builder.enableUltraPerformanceMode;
    this.enableAggressiveOptimizations = builder.enableAggressiveOptimizations;
    this.maxConcurrentOperations = builder.maxConcurrentOperations;
    this.batchSize = builder.batchSize;
    this.prefetchDistance = builder.prefetchDistance;
    this.cacheSizeMb = builder.cacheSizeMb;
    this.enableSafetyChecksUltra = builder.enableSafetyChecksUltra;
    this.enableBoundsCheckingUltra = builder.enableBoundsCheckingUltra;
    this.enableNullChecksUltra = builder.enableNullChecksUltra;
    this.enableAggressiveInlining = builder.enableAggressiveInlining;
    this.enableLoopUnrolling = builder.enableLoopUnrolling;
    this.enableVectorization = builder.enableVectorization;
    this.enableMemoryPoolingUltra = builder.enableMemoryPoolingUltra;
    this.poolSizeMbUltra = builder.poolSizeMbUltra;
    this.enableSimdUltra = builder.enableSimdUltra;
    this.simdBatchSizeUltra = builder.simdBatchSizeUltra;
    this.enableBranchHints = builder.enableBranchHints;
    this.enableCachePrefetching = builder.enableCachePrefetching;

    validate();
  }

  private void validate() {
    // Validate basic thread pool constraints
    if (minThreadpoolSize > maxThreadpoolSize) {
      throw new IllegalArgumentException(
          "minThreadpoolSize ("
              + minThreadpoolSize
              + ") cannot be greater than maxThreadpoolSize ("
              + maxThreadpoolSize
              + ")");
    }
    
    // Validate thread scaling thresholds
    if (threadScaleUpThreshold <= threadScaleDownThreshold) {
      throw new IllegalArgumentException(
          "threadScaleUpThreshold ("
              + threadScaleUpThreshold
              + ") must be greater than threadScaleDownThreshold ("
              + threadScaleDownThreshold
              + ")");
    }
    
    // Validate interval constraints
    if (scanIntervalTicks < 1 || scanIntervalTicks > 100) {
      throw new IllegalArgumentException(
          "scanIntervalTicks must be between 1 and 100, got: " + scanIntervalTicks);
    }
    
    // Validate TPS threshold
    if (tpsThresholdForAsync < 10.0 || tpsThresholdForAsync > 20.0) {
      throw new IllegalArgumentException(
          "tpsThresholdForAsync must be between 10.0 and 20.0, got: " + tpsThresholdForAsync);
    }
    
    // Cross-validate thread scaling configurations (High Priority Fix)
    validateThreadScalingConfiguration();
    
    // Validate configuration consistency
    validateConfigurationConsistency();
  }
  
  /**
   * Cross-validate dynamicThreadScaling and adaptiveThreadPool configurations
   * to prevent inconsistent thread management strategies.
   */
  private void validateThreadScalingConfiguration() {
    // Both dynamicThreadScaling and adaptiveThreadPool enabled - validate compatibility
    if (dynamicThreadScaling && adaptiveThreadPool) {
      if (threadpoolSize < minThreadpoolSize) {
        throw new IllegalArgumentException(
            "When both dynamicThreadScaling and adaptiveThreadPool are enabled, " +
            "threadpoolSize (" + threadpoolSize + ") must be >= minThreadpoolSize (" + minThreadpoolSize + ")");
      }
      
      if (maxThreadpoolSize < threadpoolSize) {
        throw new IllegalArgumentException(
            "When both dynamicThreadScaling and adaptiveThreadPool are enabled, " +
            "maxThreadpoolSize (" + maxThreadpoolSize + ") must be >= threadpoolSize (" + threadpoolSize + ")");
      }
    }
    
    // When only dynamicThreadScaling is enabled, ensure proper min/max configuration
    if (dynamicThreadScaling && !adaptiveThreadPool) {
      if (minThreadpoolSize == maxThreadpoolSize) {
        throw new IllegalArgumentException(
            "When dynamicThreadScaling is enabled without adaptiveThreadPool, " +
            "minThreadpoolSize and maxThreadpoolSize must be different to allow scaling");
      }
    }
    
    // When only adaptiveThreadPool is enabled, ensure proper configuration
    if (!dynamicThreadScaling && adaptiveThreadPool) {
      if (threadpoolSize == minThreadpoolSize && threadpoolSize == maxThreadpoolSize) {
        throw new IllegalArgumentException(
            "When adaptiveThreadPool is enabled without dynamicThreadScaling, " +
            "threadpoolSize should be different from minThreadpoolSize/maxThreadpoolSize to allow adaptation");
      }
    }
  }
  
  /**
   * Validate overall configuration consistency and prevent conflicting settings.
   */
  private void validateConfigurationConsistency() {
    // Validate work stealing configuration
    if (workStealingEnabled && workStealingQueueSize <= 0) {
      throw new IllegalArgumentException(
          "workStealingQueueSize must be positive when workStealingEnabled is true, got: " + workStealingQueueSize);
    }
    
    // Validate CPU awareness configuration
    if (cpuAwareThreadSizing && cpuLoadThreshold < 0.0 || cpuLoadThreshold > 1.0) {
      throw new IllegalArgumentException(
          "cpuLoadThreshold must be between 0.0 and 1.0 when cpuAwareThreadSizing is enabled, got: " + cpuLoadThreshold);
    }
    
    // Validate thread pool keep alive configuration
    if (threadPoolKeepAliveSeconds < 0) {
      throw new IllegalArgumentException(
          "threadPoolKeepAliveSeconds must be non-negative, got: " + threadPoolKeepAliveSeconds);
    }
    
    // Validate distance calculation configuration
    if (distanceCacheSize <= 0) {
      throw new IllegalArgumentException(
          "distanceCacheSize must be positive, got: " + distanceCacheSize);
    }
    
    // Validate interval multipliers
    if (itemProcessingIntervalMultiplier <= 0) {
      throw new IllegalArgumentException(
          "itemProcessingIntervalMultiplier must be positive, got: " + itemProcessingIntervalMultiplier);
    }

    // Validate extreme performance configuration
    if (memoryPressureThreshold < 0.0 || memoryPressureThreshold > 1.0) {
      throw new IllegalArgumentException(
          "memoryPressureThreshold must be between 0.0 and 1.0, got: " + memoryPressureThreshold);
    }

    if (preallocationBufferSize < 0) {
      throw new IllegalArgumentException(
          "preallocationBufferSize must be non-negative, got: " + preallocationBufferSize);
    }

    if (rollbackThreshold < 0.0 || rollbackThreshold > 100.0) {
      throw new IllegalArgumentException(
          "rollbackThreshold must be between 0.0 and 100.0, got: " + rollbackThreshold);
    }

    if (rollbackCheckInterval <= 0) {
      throw new IllegalArgumentException(
          "rollbackCheckInterval must be positive, got: " + rollbackCheckInterval);
    }

    if (monitoringSampleRate <= 0) {
      throw new IllegalArgumentException(
          "monitoringSampleRate must be positive, got: " + monitoringSampleRate);
    }
  }
  
  /**
   * Check if the configuration is consistent at runtime.
   * This method should be called periodically during operation
   * to detect and resolve configuration drift.
   */
  public void checkRuntimeConsistency() {
    // Check for thread pool size consistency
    if (dynamicThreadScaling && getCurrentThreadpoolSize() < minThreadpoolSize) {
      throw new IllegalStateException(
          "Runtime threadpool size (" + getCurrentThreadpoolSize() + ") is below minThreadpoolSize (" + minThreadpoolSize + ")");
    }
    
    if (adaptiveThreadPool && getCurrentThreadpoolSize() > maxThreadpoolSize) {
      throw new IllegalStateException(
          "Runtime threadpool size (" + getCurrentThreadpoolSize() + ") exceeds maxThreadpoolSize (" + maxThreadpoolSize + ")");
    }
    
    // Check for configuration drift
    if (dynamicThreadScaling != isDynamicThreadScaling()) {
      throw new IllegalStateException(
          "Dynamic thread scaling configuration has drifted - runtime value differs from configured value");
    }
    
    if (adaptiveThreadPool != isAdaptiveThreadPool()) {
      throw new IllegalStateException(
          "Adaptive thread pool configuration has drifted - runtime value differs from configured value");
    }
  }
  
  /**
   * Get the current actual thread pool size from runtime.
   * This is a placeholder that should be implemented based on actual runtime thread pool access.
   */
  private int getCurrentThreadpoolSize() {
    // In a real implementation, this would return the actual current thread pool size
    // For demonstration purposes, we'll return the configured threadpoolSize
    return threadpoolSize;
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

  // Extreme performance configuration getters
  public boolean isEnableExtremeAvx512() {
    return enableExtremeAvx512;
  }

  public boolean isEnableLockFreePooling() {
    return enableLockFreePooling;
  }

  public double getMemoryPressureThreshold() {
    return memoryPressureThreshold;
  }

  public boolean isEnableAggressivePreallocation() {
    return enableAggressivePreallocation;
  }

  public int getPreallocationBufferSize() {
    return preallocationBufferSize;
  }

  public boolean isEnableSafetyChecks() {
    return enableSafetyChecks;
  }

  public boolean isEnableMemoryLeakDetection() {
    return enableMemoryLeakDetection;
  }

  public boolean isEnablePerformanceMonitoring() {
    return enablePerformanceMonitoring;
  }

  public boolean isEnableErrorRecovery() {
    return enableErrorRecovery;
  }

  public boolean isEnableMinimalMonitoring() {
    return enableMinimalMonitoring;
  }

  public int getMonitoringSampleRate() {
    return monitoringSampleRate;
  }

  public boolean isEnablePerformanceWarnings() {
    return enablePerformanceWarnings;
  }

  public boolean isEnableFeatureFlags() {
    return enableFeatureFlags;
  }

  public boolean isEnableAutoRollback() {
    return enableAutoRollback;
  }

  public double getRollbackThreshold() {
    return rollbackThreshold;
  }

  public int getRollbackCheckInterval() {
    return rollbackCheckInterval;
  }

  // Ultra-performance configuration getters
  public boolean isEnableUltraPerformanceMode() {
    return enableUltraPerformanceMode;
  }

  public boolean isEnableAggressiveOptimizations() {
    return enableAggressiveOptimizations;
  }

  public int getMaxConcurrentOperations() {
    return maxConcurrentOperations;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getPrefetchDistance() {
    return prefetchDistance;
  }

  public int getCacheSizeMb() {
    return cacheSizeMb;
  }

  public boolean isEnableSafetyChecksUltra() {
    return enableSafetyChecksUltra;
  }

  public boolean isEnableBoundsCheckingUltra() {
    return enableBoundsCheckingUltra;
  }

  public boolean isEnableNullChecksUltra() {
    return enableNullChecksUltra;
  }

  public boolean isEnableAggressiveInlining() {
    return enableAggressiveInlining;
  }

  public boolean isEnableLoopUnrolling() {
    return enableLoopUnrolling;
  }

  public boolean isEnableVectorization() {
    return enableVectorization;
  }

  public boolean isEnableMemoryPoolingUltra() {
    return enableMemoryPoolingUltra;
  }

  public int getPoolSizeMbUltra() {
    return poolSizeMbUltra;
  }

  public boolean isEnableSimdUltra() {
    return enableSimdUltra;
  }

  public int getSimdBatchSizeUltra() {
    return simdBatchSizeUltra;
  }

  public boolean isEnableBranchHints() {
    return enableBranchHints;
  }

  public boolean isEnableCachePrefetching() {
    return enableCachePrefetching;
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

    // Extreme performance configuration defaults
    private boolean enableExtremeAvx512 = false;
    private boolean enableLockFreePooling = false;
    private double memoryPressureThreshold = 0.9;
    private boolean enableAggressivePreallocation = false;
    private int preallocationBufferSize = 512;
    private boolean enableSafetyChecks = true;
    private boolean enableMemoryLeakDetection = true;
    private boolean enablePerformanceMonitoring = true;
    private boolean enableErrorRecovery = true;
    private boolean enableMinimalMonitoring = false;
    private int monitoringSampleRate = 100;
    private boolean enablePerformanceWarnings = true;
    private boolean enableFeatureFlags = false;
    private boolean enableAutoRollback = false;
    private double rollbackThreshold = 20.0;
    private int rollbackCheckInterval = 1000;
    
    // Ultra-performance configuration defaults
    private boolean enableUltraPerformanceMode = false;
    private boolean enableAggressiveOptimizations = false;
    private int maxConcurrentOperations = 32;
    private int batchSize = 64;
    private int prefetchDistance = 8;
    private int cacheSizeMb = 512;
    private boolean enableSafetyChecksUltra = false;
    private boolean enableBoundsCheckingUltra = false;
    private boolean enableNullChecksUltra = false;
    private boolean enableAggressiveInlining = false;
    private boolean enableLoopUnrolling = false;
    private boolean enableVectorization = false;
    private boolean enableMemoryPoolingUltra = false;
    private int poolSizeMbUltra = 256;
    private boolean enableSimdUltra = false;
    private int simdBatchSizeUltra = 16;
    private boolean enableBranchHints = false;
    private boolean enableCachePrefetching = false;

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

    // Extreme performance configuration setters
    public Builder enableExtremeAvx512(boolean v) {
      this.enableExtremeAvx512 = v;
      return this;
    }

    public Builder enableLockFreePooling(boolean v) {
      this.enableLockFreePooling = v;
      return this;
    }

    public Builder memoryPressureThreshold(double v) {
      this.memoryPressureThreshold = v;
      return this;
    }

    public Builder enableAggressivePreallocation(boolean v) {
      this.enableAggressivePreallocation = v;
      return this;
    }

    public Builder preallocationBufferSize(int v) {
      this.preallocationBufferSize = v;
      return this;
    }

    public Builder enableSafetyChecks(boolean v) {
      this.enableSafetyChecks = v;
      return this;
    }

    public Builder enableMemoryLeakDetection(boolean v) {
      this.enableMemoryLeakDetection = v;
      return this;
    }

    public Builder enablePerformanceMonitoring(boolean v) {
      this.enablePerformanceMonitoring = v;
      return this;
    }

    public Builder enableErrorRecovery(boolean v) {
      this.enableErrorRecovery = v;
      return this;
    }

    public Builder enableMinimalMonitoring(boolean v) {
      this.enableMinimalMonitoring = v;
      return this;
    }

    public Builder monitoringSampleRate(int v) {
      this.monitoringSampleRate = v;
      return this;
    }

    public Builder enablePerformanceWarnings(boolean v) {
      this.enablePerformanceWarnings = v;
      return this;
    }

    public Builder enableFeatureFlags(boolean v) {
      this.enableFeatureFlags = v;
      return this;
    }

    public Builder enableAutoRollback(boolean v) {
      this.enableAutoRollback = v;
      return this;
    }

    public Builder rollbackThreshold(double v) {
      this.rollbackThreshold = v;
      return this;
    }

    public Builder rollbackCheckInterval(int v) {
      this.rollbackCheckInterval = v;
      return this;
    }

    // Ultra-performance configuration setters
    public Builder enableUltraPerformanceMode(boolean v) {
      this.enableUltraPerformanceMode = v;
      return this;
    }

    public Builder enableAggressiveOptimizations(boolean v) {
      this.enableAggressiveOptimizations = v;
      return this;
    }

    public Builder maxConcurrentOperations(int v) {
      this.maxConcurrentOperations = v;
      return this;
    }

    public Builder batchSize(int v) {
      this.batchSize = v;
      return this;
    }

    public Builder prefetchDistance(int v) {
      this.prefetchDistance = v;
      return this;
    }

    public Builder cacheSizeMb(int v) {
      this.cacheSizeMb = v;
      return this;
    }

    public Builder enableSafetyChecksUltra(boolean v) {
      this.enableSafetyChecksUltra = v;
      return this;
    }

    public Builder enableBoundsCheckingUltra(boolean v) {
      this.enableBoundsCheckingUltra = v;
      return this;
    }

    public Builder enableNullChecksUltra(boolean v) {
      this.enableNullChecksUltra = v;
      return this;
    }

    public Builder enableAggressiveInlining(boolean v) {
      this.enableAggressiveInlining = v;
      return this;
    }

    public Builder enableLoopUnrolling(boolean v) {
      this.enableLoopUnrolling = v;
      return this;
    }

    public Builder enableVectorization(boolean v) {
      this.enableVectorization = v;
      return this;
    }

    public Builder enableMemoryPoolingUltra(boolean v) {
      this.enableMemoryPoolingUltra = v;
      return this;
    }

    public Builder poolSizeMbUltra(int v) {
      this.poolSizeMbUltra = v;
      return this;
    }

    public Builder enableSimdUltra(boolean v) {
      this.enableSimdUltra = v;
      return this;
    }

    public Builder simdBatchSizeUltra(int v) {
      this.simdBatchSizeUltra = v;
      return this;
    }

    public Builder enableBranchHints(boolean v) {
      this.enableBranchHints = v;
      return this;
    }

    public Builder enableCachePrefetching(boolean v) {
      this.enableCachePrefetching = v;
      return this;
    }

    public PerformanceConfiguration build() {
      return new PerformanceConfiguration(this);
    }
  }
}
