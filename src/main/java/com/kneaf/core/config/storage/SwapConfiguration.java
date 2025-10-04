package com.kneaf.core.config.storage;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.core.ConfigurationUtils;

/** Unified swap configuration that consolidates swap management settings. */
public class SwapConfiguration implements ConfigurationProvider {
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

  private SwapConfiguration(Builder builder) {
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

    validate();
  }

  @Override
  public void validate() {
    ConfigurationUtils.validatePositive(memoryCheckIntervalMs, "Memory check interval");
    ConfigurationUtils.validatePositive(maxConcurrentSwaps, "Max concurrent swaps");
    ConfigurationUtils.validatePositive(swapBatchSize, "Swap batch size");
    ConfigurationUtils.validatePositive(swapTimeoutMs, "Swap timeout");
    ConfigurationUtils.validateRange(criticalMemoryThreshold, 0, 1.0, "Critical memory threshold");
    ConfigurationUtils.validateRange(highMemoryThreshold, 0, 1.0, "High memory threshold");
    ConfigurationUtils.validateRange(elevatedMemoryThreshold, 0, 1.0, "Elevated memory threshold");
    ConfigurationUtils.validatePositive(minSwapChunkAgeMs, "Min swap chunk age");
    ConfigurationUtils.validateMinMax(
        elevatedMemoryThreshold,
        highMemoryThreshold,
        "elevatedMemoryThreshold",
        "highMemoryThreshold");
    ConfigurationUtils.validateMinMax(
        highMemoryThreshold,
        criticalMemoryThreshold,
        "highMemoryThreshold",
        "criticalMemoryThreshold");
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  // Getters
  public long getMemoryCheckIntervalMs() {
    return memoryCheckIntervalMs;
  }

  public int getMaxConcurrentSwaps() {
    return maxConcurrentSwaps;
  }

  public int getSwapBatchSize() {
    return swapBatchSize;
  }

  public long getSwapTimeoutMs() {
    return swapTimeoutMs;
  }

  public boolean isEnableAutomaticSwapping() {
    return enableAutomaticSwapping;
  }

  public double getCriticalMemoryThreshold() {
    return criticalMemoryThreshold;
  }

  public double getHighMemoryThreshold() {
    return highMemoryThreshold;
  }

  public double getElevatedMemoryThreshold() {
    return elevatedMemoryThreshold;
  }

  public int getMinSwapChunkAgeMs() {
    return minSwapChunkAgeMs;
  }

  public boolean isEnableSwapStatistics() {
    return enableSwapStatistics;
  }

  public boolean isEnablePerformanceMonitoring() {
    return enablePerformanceMonitoring;
  }

  @Override
  public String toString() {
    return String.format(
        "SwapConfiguration{enabled=%s, memoryCheckIntervalMs=%d, maxConcurrentSwaps=%d, "
            + "swapBatchSize=%d, swapTimeoutMs=%d, enableAutomaticSwapping=%s, "
            + "criticalMemoryThreshold=%.2f, highMemoryThreshold=%.2f, elevatedMemoryThreshold=%.2f, "
            + "minSwapChunkAgeMs=%d, enableSwapStatistics=%s, enablePerformanceMonitoring=%s}",
        enabled,
        memoryCheckIntervalMs,
        maxConcurrentSwaps,
        swapBatchSize,
        swapTimeoutMs,
        enableAutomaticSwapping,
        criticalMemoryThreshold,
        highMemoryThreshold,
        elevatedMemoryThreshold,
        minSwapChunkAgeMs,
        enableSwapStatistics,
        enablePerformanceMonitoring);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
    private long memoryCheckIntervalMs =
        ConfigurationConstants.DEFAULT_SWAP_MEMORY_CHECK_INTERVAL_MS;
    private int maxConcurrentSwaps = ConfigurationConstants.DEFAULT_MAX_CONCURRENT_SWAPS;
    private int swapBatchSize = ConfigurationConstants.DEFAULT_SWAP_BATCH_SIZE;
    private long swapTimeoutMs = ConfigurationConstants.DEFAULT_SWAP_TIMEOUT_MS;
    private boolean enableAutomaticSwapping =
        ConfigurationConstants.DEFAULT_ENABLE_AUTOMATIC_SWAPPING;
    private double criticalMemoryThreshold =
        ConfigurationConstants.DEFAULT_CRITICAL_MEMORY_THRESHOLD;
    private double highMemoryThreshold = ConfigurationConstants.DEFAULT_HIGH_MEMORY_THRESHOLD;
    private double elevatedMemoryThreshold =
        ConfigurationConstants.DEFAULT_ELEVATED_MEMORY_THRESHOLD;
    private int minSwapChunkAgeMs = ConfigurationConstants.DEFAULT_MIN_SWAP_CHUNK_AGE_MS;
    private boolean enableSwapStatistics = ConfigurationConstants.DEFAULT_ENABLE_SWAP_STATISTICS;
    private boolean enablePerformanceMonitoring =
        ConfigurationConstants.DEFAULT_ENABLE_PERFORMANCE_MONITORING;

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) {
      this.memoryCheckIntervalMs = memoryCheckIntervalMs;
      return this;
    }

    public Builder maxConcurrentSwaps(int maxConcurrentSwaps) {
      this.maxConcurrentSwaps = maxConcurrentSwaps;
      return this;
    }

    public Builder swapBatchSize(int swapBatchSize) {
      this.swapBatchSize = swapBatchSize;
      return this;
    }

    public Builder swapTimeoutMs(long swapTimeoutMs) {
      this.swapTimeoutMs = swapTimeoutMs;
      return this;
    }

    public Builder enableAutomaticSwapping(boolean enableAutomaticSwapping) {
      this.enableAutomaticSwapping = enableAutomaticSwapping;
      return this;
    }

    public Builder criticalMemoryThreshold(double criticalMemoryThreshold) {
      this.criticalMemoryThreshold = criticalMemoryThreshold;
      return this;
    }

    public Builder highMemoryThreshold(double highMemoryThreshold) {
      this.highMemoryThreshold = highMemoryThreshold;
      return this;
    }

    public Builder elevatedMemoryThreshold(double elevatedMemoryThreshold) {
      this.elevatedMemoryThreshold = elevatedMemoryThreshold;
      return this;
    }

    public Builder minSwapChunkAgeMs(int minSwapChunkAgeMs) {
      this.minSwapChunkAgeMs = minSwapChunkAgeMs;
      return this;
    }

    public Builder enableSwapStatistics(boolean enableSwapStatistics) {
      this.enableSwapStatistics = enableSwapStatistics;
      return this;
    }

    public Builder enablePerformanceMonitoring(boolean enablePerformanceMonitoring) {
      this.enablePerformanceMonitoring = enablePerformanceMonitoring;
      return this;
    }

    public SwapConfiguration build() {
      return new SwapConfiguration(this);
    }
  }
}
