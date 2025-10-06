package com.kneaf.core.performance.monitoring;

import com.mojang.logging.LogUtils;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Manages the thread pool for asynchronous performance optimizations.
 * Separated from main PerformanceManager for better modularity.
 */
public class ThreadPoolManager {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final PerformanceConfig CONFIG = PerformanceConfig.load();

  // Advanced ThreadPoolExecutor with dynamic sizing and monitoring
  private static ThreadPoolExecutor serverTaskExecutor = null;
  private static final Object EXECUTOR_LOCK = new Object();

  // Executor monitoring and metrics
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
          "{\"totalTasksSubmitted\":%d,\"totalTasksCompleted\":%d,\"totalTasksRejected\":%d,"
              + "\"currentQueueSize\":%d,\"currentUtilization\":%.2f,\"currentThreadCount\":%d,"
              + "\"peakThreadCount\":%d,\"scaleUpCount\":%d,\"scaleDownCount\":%d}",
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

  private static final ExecutorMetrics EXECUTOR_METRICS = new ExecutorMetrics();

  public ThreadPoolManager() {}

  /**
   * Get or create the thread pool executor with advanced configuration.
   */
  public ThreadPoolExecutor getExecutor() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null || serverTaskExecutor.isShutdown()) {
        createAdvancedThreadPool();
      }
      return serverTaskExecutor;
    }
  }

  /**
   * Create a thread pool with advanced configuration based on performance settings.
   */
  private static void createAdvancedThreadPool() {
    AtomicInteger threadIndex = new AtomicInteger(0);
    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "kneaf-perf-worker-" + threadIndex.getAndIncrement());
      t.setDaemon(true);
      return t;
    };

    int coreThreads = CONFIG.getMinThreadpoolSize();
    int maxThreads = CONFIG.getMaxThreadpoolSize();

    // CPU-aware sizing if enabled
    if (CONFIG.isCpuAwareThreadSizing()) {
      int availableProcessors = Runtime.getRuntime().availableProcessors();
      double cpuLoad = getSystemCpuLoad();

      if (cpuLoad < CONFIG.getCpuLoadThreshold()) {
        maxThreads = Math.min(maxThreads, availableProcessors);
      } else {
        maxThreads = Math.clamp(availableProcessors / 2, 1, maxThreads);
      }

      coreThreads = Math.min(coreThreads, maxThreads);
    }

    // Adaptive sizing based on available processors if enabled
    if (CONFIG.isAdaptiveThreadPool()) {
      int availableProcessors = Runtime.getRuntime().availableProcessors();
      maxThreads = clamp(availableProcessors - 1, 1, maxThreads);
      coreThreads = Math.min(coreThreads, maxThreads);
    }

    LinkedBlockingQueue<Runnable> workQueue;
    if (CONFIG.isWorkStealingEnabled()) {
      workQueue = new LinkedBlockingQueue<>(CONFIG.getWorkStealingQueueSize());
    } else {
      workQueue = new LinkedBlockingQueue<>();
    }

    serverTaskExecutor = new ThreadPoolExecutor(
        coreThreads,
        maxThreads,
        CONFIG.getThreadPoolKeepAliveSeconds(),
        TimeUnit.SECONDS,
        workQueue,
        factory);

    serverTaskExecutor.allowCoreThreadTimeOut(true);

    EXECUTOR_METRICS.currentThreadCount = coreThreads;
    EXECUTOR_METRICS.peakThreadCount = coreThreads;
  }

  /**
   * Get system CPU load for dynamic thread pool sizing.
   */
  private static double getSystemCpuLoad() {
    try {
      java.lang.management.OperatingSystemMXBean osBean = 
          java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      double systemLoad = osBean.getSystemLoadAverage();
      
      if (systemLoad < 0) {
        int availableProcessors = osBean.getAvailableProcessors();
        return Math.min(1.0, systemLoad / availableProcessors);
      }
      
      int availableProcessors = osBean.getAvailableProcessors();
      return Math.min(1.0, systemLoad / availableProcessors);
    } catch (Exception e) {
      return 0.0;
    }
  }

  /**
   * Helper method to clamp values between min and max.
   */
  public static int clamp(int v, int min, int max) {
    if (v < min) return min;
    if (v > max) return max;
    return v;
  }

  /**
   * Helper method to clamp values between min and max.
   */
  public static double clamp(double v, double min, double max) {
    if (v < min) return min;
    if (v > max) return max;
    return v;
  }

  /**
   * Shutdown the thread pool gracefully.
   */
  public void shutdown() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor != null) {
        try {
          serverTaskExecutor.shutdown();
          if (!serverTaskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            serverTaskExecutor.shutdownNow();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          serverTaskExecutor.shutdownNow();
        } finally {
          serverTaskExecutor = null;
        }
      }
    }
  }

  /**
   * Get current executor metrics for monitoring and debugging.
   */
  public String getExecutorMetrics() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null) {
        return "{\"status\":\"not_initialized\"}";
      }
      return EXECUTOR_METRICS.toJson();
    }
  }

  /**
   * Get current executor status including pool configuration.
   */
  public String getExecutorStatus() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null) {
        return "Executor not initialized";
      }

      return String.format(
          "ThreadPoolExecutor[core=%d, max=%d, current=%d, active=%d, queue=%d, completed=%d]",
          serverTaskExecutor.getCorePoolSize(),
          serverTaskExecutor.getMaximumPoolSize(),
          serverTaskExecutor.getPoolSize(),
          serverTaskExecutor.getActiveCount(),
          serverTaskExecutor.getQueue().size(),
          serverTaskExecutor.getCompletedTaskCount());
    }
  }

  /**
   * Validate executor health and configuration.
   */
  public boolean isExecutorHealthy() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null
          || serverTaskExecutor.isShutdown()
          || serverTaskExecutor.isTerminated()) {
        return false;
      }

      int queueSize = serverTaskExecutor.getQueue().size();
      int maxQueueSize = CONFIG.isWorkStealingEnabled() ? CONFIG.getWorkStealingQueueSize() : 1000;

      if (queueSize > maxQueueSize * 0.9) {
        return false;
      }

      double utilization = getExecutorUtilization();
      if (serverTaskExecutor.getPoolSize() >= serverTaskExecutor.getMaximumPoolSize()
          && utilization > 0.95) {
        return false;
      }

      return true;
    }
  }

  /**
   * Get the current executor queue size for dynamic threshold adjustment.
   */
  public int getExecutorQueueSize() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null) return 0;
      return serverTaskExecutor.getQueue().size();
    }
  }

  /**
   * Get executor utilization for dynamic scaling decisions.
   */
  public double getExecutorUtilization() {
    synchronized (EXECUTOR_LOCK) {
      if (serverTaskExecutor == null) return 0.0;
      int activeThreads = serverTaskExecutor.getActiveCount();
      int poolSize = Math.max(1, serverTaskExecutor.getPoolSize());
      return (double) activeThreads / poolSize;
    }
  }

  /**
   * Submit async optimization task to the thread pool.
   */
  public void submitAsyncOptimizations(
      net.minecraft.server.MinecraftServer server,
      EntityProcessor.EntityDataCollection data,
      boolean shouldProfile) {
    try {
      getExecutor().submit(() -> performAsyncOptimization(server, data, shouldProfile));
    } catch (Exception e) {
      // Fallback to synchronous processing if executor rejects
      LOGGER.debug("Executor rejected task; running synchronously", e);
    }
  }

  /**
   * Perform optimization asynchronously and schedule results application on server thread.
   */
  private void performAsyncOptimization(
      net.minecraft.server.MinecraftServer server,
      EntityProcessor.EntityDataCollection data,
      boolean shouldProfile) {
    try {
      long processingStart = shouldProfile ? System.nanoTime() : 0;
      EntityProcessor entityProcessor = new EntityProcessor();
      EntityProcessor.OptimizationResults results = entityProcessor.processOptimizations(data);
      
      if (shouldProfile) {
        long durationMs = (System.nanoTime() - processingStart) / 1_000_000;
        PerformanceMetricsLogger.logLine(String.format("PERF: async_processing duration=%.2fms", durationMs));
      }

      // Schedule modifications back on server thread to stay thread-safe
      server.execute(() -> applyOptimizationResults(server, results, shouldProfile));
    } catch (Exception e) {
      LOGGER.warn("Error during async processing of optimizations", e);
    }
  }

  /**
   * Apply optimization results on the server thread.
   */
  private void applyOptimizationResults(
      net.minecraft.server.MinecraftServer server,
      EntityProcessor.OptimizationResults results,
      boolean shouldProfile) {
    try {
      long applicationStart = shouldProfile ? System.nanoTime() : 0;
      EntityProcessor entityProcessor = new EntityProcessor();
      entityProcessor.applyOptimizations(server, results);
      
      if (shouldProfile) {
        long durationMs = (System.nanoTime() - applicationStart) / 1_000_000;
        PerformanceMetricsLogger.logLine(String.format("PERF: async_application duration=%.2fms", durationMs));
      }

      entityProcessor.removeItems(server, results.itemResult());
    } catch (Exception e) {
      LOGGER.warn("Error applying optimizations on server thread", e);
    }
  }
}