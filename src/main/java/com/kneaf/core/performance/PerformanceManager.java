package com.kneaf.core.performance;

/**
 * Lightweight compatibility shim exposing ExecutorMetrics used by tests. The real implementation
 * lives in com.kneaf.core.performance.monitoring.PerformanceManager.
 */
public final class PerformanceManager {
  private PerformanceManager() {}

  public static final class ExecutorMetrics {
    public long totalTasksSubmitted = 0;
    public long totalTasksCompleted = 0;
    public long totalTasksRejected = 0;
    public long currentQueueSize = 0;
    public double currentUtilization = 0.0;
    public int currentThreadCount = 0;
    public int peakThreadCount = 0;
    public long lastScaleUpTime = 0;
    public long lastScaleDownTime = 0;
    public int scaleUpCount = 0;
    public int scaleDownCount = 0;

    public String toJson() {
      return String.format(
          "{\"totalTasksSubmitted\":%d,\"totalTasksCompleted\":%d,\"totalTasksRejected\":%d,\"currentQueueSize\":%d,\"currentUtilization\":%.2f,\"currentThreadCount\":%d,\"peakThreadCount\":%d,\"scaleUpCount\":%d,\"scaleDownCount\":%d}",
          totalTasksSubmitted,
          totalTasksCompleted,
          totalTasksRejected,
          currentQueueSize,
          currentUtilization,
          currentThreadCount,
          peakThreadCount,
          scaleUpCount,
          scaleDownCount);
    }
  }
}
