package com.kneaf.core.config;

/** Unified swap configuration that consolidates swap management settings. */
public class SwapConfiguration {
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

  private void validate() {
    if (memoryCheckIntervalMs <= 0) {
      throw new IllegalArgumentException("Memory check interval must be positive");
    }
    if (maxConcurrentSwaps <= 0) {
      throw new IllegalArgumentException("Max concurrent swaps must be positive");
    }
    if (swapBatchSize <= 0) {
      throw new IllegalArgumentException("Swap batch size must be positive");
    }
    if (swapTimeoutMs <= 0) {
      throw new IllegalArgumentException("Swap timeout must be positive");
    }
    if (criticalMemoryThreshold <= 0 || criticalMemoryThreshold > 1.0) {
      throw new IllegalArgumentException("Critical memory threshold must be between 0 and 1.0");
    }
    if (highMemoryThreshold <= 0 || highMemoryThreshold > 1.0) {
      throw new IllegalArgumentException("High memory threshold must be between 0 and 1.0");
    }
    if (elevatedMemoryThreshold <= 0 || elevatedMemoryThreshold > 1.0) {
      throw new IllegalArgumentException("Elevated memory threshold must be between 0 and 1.0");
    }
    if (minSwapChunkAgeMs <= 0) {
      throw new IllegalArgumentException("Min swap chunk age must be positive");
    }
    if (criticalMemoryThreshold <= highMemoryThreshold) {
      throw new IllegalArgumentException(
          "Critical memory threshold must be greater than high memory threshold");
    }
    if (highMemoryThreshold <= elevatedMemoryThreshold) {
      throw new IllegalArgumentException(
          "High memory threshold must be greater than elevated memory threshold");
    }
  }

  // Getters
  public boolean isEnabled() {
    return enabled;
  }

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
    private boolean enabled = true;
    private long memoryCheckIntervalMs = 5000; // 5 seconds
    private int maxConcurrentSwaps = 10;
    private int swapBatchSize = 50;
    private long swapTimeoutMs = 30000; // 30 seconds
    private boolean enableAutomaticSwapping = true;
    private double criticalMemoryThreshold = 0.95; // 95% of max heap
    private double highMemoryThreshold = 0.85; // 85% of max heap
    private double elevatedMemoryThreshold = 0.75; // 75% of max heap
    private int minSwapChunkAgeMs = 60000; // 1 minute
    private boolean enableSwapStatistics = true;
    private boolean enablePerformanceMonitoring = true;

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
