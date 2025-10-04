package com.kneaf.core.async;

import com.kneaf.core.exceptions.processing.AsyncProcessingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic async processor that consolidates CompletableFuture.supplyAsync patterns with consistent
 * error handling, logging, and timeout management.
 */
public class AsyncProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(AsyncProcessor.class);

  private final ExecutorService executor;
  private final long defaultTimeoutMs;
  private final boolean enableLogging;
  private final boolean enableMetrics;
  private final String processorName;

  private final ConcurrentMap<String, AsyncMetrics> metricsMap = new ConcurrentHashMap<>();
  private final AtomicLong totalOperations = new AtomicLong(0);
  private final AtomicLong failedOperations = new AtomicLong(0);

  /** Metrics for async operations. */
  public static class AsyncMetrics {
    private final AtomicLong operations = new AtomicLong(0);
    private final AtomicLong failures = new AtomicLong(0);
    private final AtomicLong totalDuration = new AtomicLong(0);
    private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDuration = new AtomicLong(0);

    public void recordOperation(long duration, boolean success) {
      operations.incrementAndGet();
      totalDuration.addAndGet(duration);

      if (success) {
        updateMinMax(duration);
      } else {
        failures.incrementAndGet();
      }
    }

    private void updateMinMax(long duration) {
      long currentMin, currentMax;
      do {
        currentMin = minDuration.get();
        currentMax = maxDuration.get();
      } while (duration < currentMin && !minDuration.compareAndSet(currentMin, duration)
          || duration > currentMax && !maxDuration.compareAndSet(currentMax, duration));
    }

    public long getOperations() {
      return operations.get();
    }

    public long getFailures() {
      return failures.get();
    }

    public long getTotalDuration() {
      return totalDuration.get();
    }

    public long getMinDuration() {
      return operations.get() > 0 ? minDuration.get() : 0;
    }

    public long getMaxDuration() {
      return maxDuration.get();
    }

    public double getAverageDuration() {
      long ops = operations.get();
      return ops > 0 ? (double) totalDuration.get() / ops : 0.0;
    }

    public double getFailureRate() {
      long ops = operations.get();
      return ops > 0 ? (double) failures.get() / ops : 0.0;
    }
  }

  /** Configuration for async processor. */
  public static class AsyncConfig {
    private ExecutorService executor;
    private long defaultTimeoutMs = 30000; // 30 seconds
    private boolean enableLogging = true;
    private boolean enableMetrics = true;
    private String processorName = "AsyncProcessor";

    public AsyncConfig executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public AsyncConfig defaultTimeoutMs(long defaultTimeoutMs) {
      this.defaultTimeoutMs = defaultTimeoutMs;
      return this;
    }

    public AsyncConfig enableLogging(boolean enableLogging) {
      this.enableLogging = enableLogging;
      return this;
    }

    public AsyncConfig enableMetrics(boolean enableMetrics) {
      this.enableMetrics = enableMetrics;
      return this;
    }

    public AsyncConfig processorName(String processorName) {
      this.processorName = processorName;
      return this;
    }

    public ExecutorService getExecutor() {
      return executor;
    }

    public long getDefaultTimeoutMs() {
      return defaultTimeoutMs;
    }

    public boolean isEnableLogging() {
      return enableLogging;
    }

    public boolean isEnableMetrics() {
      return enableMetrics;
    }

    public String getProcessorName() {
      return processorName;
    }
  }

  private AsyncProcessor(AsyncConfig config) {
    this.executor =
        config.getExecutor() != null
            ? config.getExecutor()
            : Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    this.defaultTimeoutMs = config.getDefaultTimeoutMs();
    this.enableLogging = config.isEnableLogging();
    this.enableMetrics = config.isEnableMetrics();
    this.processorName = config.getProcessorName();
  }

  /** Create a new AsyncProcessor with default configuration. */
  public static AsyncProcessor create() {
    return new AsyncProcessor(new AsyncConfig());
  }

  /** Create a new AsyncProcessor with custom configuration. */
  public static AsyncProcessor create(AsyncConfig config) {
    return new AsyncProcessor(config);
  }

  /** Execute a supplier asynchronously with default timeout. */
  public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    return supplyAsync(supplier, defaultTimeoutMs);
  }

  /** Execute a supplier asynchronously with specified timeout. */
  public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, long timeoutMs) {
    return supplyAsync(supplier, timeoutMs, "async-operation");
  }

  /** Execute a supplier asynchronously with specified timeout and operation name. */
  public <T> CompletableFuture<T> supplyAsync(
      Supplier<T> supplier, long timeoutMs, String operationName) {
    totalOperations.incrementAndGet();
    long startTime = System.currentTimeMillis();

    if (enableLogging) {
      LOGGER.debug("{ }: Starting async operation '{ }'", processorName, operationName);
    }

    CompletableFuture<T> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                T result = supplier.get();

                long duration = System.currentTimeMillis() - startTime;
                if (enableMetrics) {
                  AsyncMetrics metrics =
                      metricsMap.computeIfAbsent(operationName, k -> new AsyncMetrics());
                  metrics.recordOperation(duration, true);
                }

                if (enableLogging) {
                  LOGGER.debug(
                      "{ }: Completed async operation '{ }' in { }ms",
                      processorName,
                      operationName,
                      duration);
                }

                return result;
              } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                failedOperations.incrementAndGet();

                if (enableMetrics) {
                  AsyncMetrics metrics =
                      metricsMap.computeIfAbsent(operationName, k -> new AsyncMetrics());
                  metrics.recordOperation(duration, false);
                }

                if (enableLogging) {
                  LOGGER.error(
                      "{ }: Failed async operation '{ }' after { }ms",
                      processorName,
                      operationName,
                      duration,
                      e);
                }

                throw AsyncProcessingException.builder()
                    .errorType(AsyncProcessingException.AsyncErrorType.SUPPLY_ASYNC_FAILED)
                    .taskType(operationName)
                    .message("Async operation '" + operationName + "' failed: " + e.getMessage())
                    .cause(e)
                    .build();
              }
            },
            executor);

    // Apply timeout if specified
    if (timeoutMs > 0) {
      future =
          future
              .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
              .exceptionally(
                  throwable -> {
                    if (throwable instanceof TimeoutException) {
                      failedOperations.incrementAndGet();

                      if (enableMetrics) {
                        AsyncMetrics metrics =
                            metricsMap.computeIfAbsent(operationName, k -> new AsyncMetrics());
                        metrics.recordOperation(System.currentTimeMillis() - startTime, false);
                      }

                      if (enableLogging) {
                        LOGGER.error(
                            "{ }: Async operation '{ }' timed out after { }ms",
                            processorName,
                            operationName,
                            timeoutMs);
                      }

                      throw AsyncProcessingException.timeoutExceeded(
                          operationName, timeoutMs, null);
                    }
                    throw new CompletionException(throwable);
                  });
    }

    return future;
  }

  /** Execute a callable asynchronously with default timeout. */
  public <T> CompletableFuture<T> callAsync(Callable<T> callable) {
    return callAsync(callable, defaultTimeoutMs);
  }

  /** Execute a callable asynchronously with specified timeout. */
  public <T> CompletableFuture<T> callAsync(Callable<T> callable, long timeoutMs) {
    return callAsync(callable, timeoutMs, "async-callable");
  }

  /** Execute a callable asynchronously with specified timeout and operation name. */
  public <T> CompletableFuture<T> callAsync(
      Callable<T> callable, long timeoutMs, String operationName) {
    return supplyAsync(
        () -> {
          try {
            return callable.call();
          } catch (Exception e) {
            throw new RuntimeException("Callable execution failed", e);
          }
        },
        timeoutMs,
        operationName);
  }

  /** Execute a runnable asynchronously with default timeout. */
  public CompletableFuture<Void> runAsync(Runnable runnable) {
    return runAsync(runnable, defaultTimeoutMs);
  }

  /** Execute a runnable asynchronously with specified timeout. */
  public CompletableFuture<Void> runAsync(Runnable runnable, long timeoutMs) {
    return runAsync(runnable, timeoutMs, "async-runnable");
  }

  /** Execute a runnable asynchronously with specified timeout and operation name. */
  public CompletableFuture<Void> runAsync(Runnable runnable, long timeoutMs, String operationName) {
    return supplyAsync(
        () -> {
          runnable.run();
          return null;
        },
        timeoutMs,
        operationName);
  }

  /** Execute multiple suppliers in parallel and wait for all to complete. */
  public <T> CompletableFuture<List<T>> supplyAllAsync(List<Supplier<T>> suppliers) {
    return supplyAllAsync(suppliers, defaultTimeoutMs);
  }

  /** Execute multiple suppliers in parallel with specified timeout. */
  public <T> CompletableFuture<List<T>> supplyAllAsync(
      List<Supplier<T>> suppliers, long timeoutMs) {
    if (suppliers == null || suppliers.isEmpty()) {
      return CompletableFuture.completedFuture(new ArrayList<>());
    }

    List<CompletableFuture<T>> futures = new ArrayList<>();
    for (int i = 0; i < suppliers.size(); i++) {
      final int index = i;
      futures.add(supplyAsync(suppliers.get(i), timeoutMs, "batch-operation-" + index));
    }

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(
            v -> {
              List<T> results = new ArrayList<>();
              for (CompletableFuture<T> future : futures) {
                try {
                  results.add(future.get());
                } catch (Exception e) {
                  throw AsyncProcessingException.builder()
                      .errorType(AsyncProcessingException.AsyncErrorType.COMPLETION_EXCEPTION)
                      .taskType("batch-operation")
                      .message("Failed to get result from batch operation")
                      .cause(e)
                      .build();
                }
              }
              return results;
            });
  }

  /** Execute a function with retry logic. */
  public <T> CompletableFuture<T> supplyWithRetry(
      Supplier<T> supplier, int maxRetries, long retryDelayMs) {
    return supplyWithRetry(supplier, maxRetries, retryDelayMs, "retry-operation");
  }

  /** Execute a function with retry logic and operation name. */
  public <T> CompletableFuture<T> supplyWithRetry(
      Supplier<T> supplier, int maxRetries, long retryDelayMs, String operationName) {
    return supplyAsync(
        () -> {
          Exception lastException = null;
          for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
              if (enableLogging && attempt > 1) {
                LOGGER.debug(
                    "{ }: Retry attempt { } for operation '{ }'",
                    processorName,
                    attempt,
                    operationName);
              }
              return supplier.get();
            } catch (Exception e) {
              lastException = e;
              if (attempt < maxRetries) {
                try {
                  Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  throw AsyncProcessingException.batchRequestInterrupted(operationName, ie);
                }
              }
            }
          }

          throw AsyncProcessingException.builder()
              .errorType(AsyncProcessingException.AsyncErrorType.EXECUTOR_SHUTDOWN)
              .taskType(operationName)
              .message("Operation '" + operationName + "' failed after " + maxRetries + " attempts")
              .cause(lastException)
              .build();
        },
        defaultTimeoutMs * maxRetries,
        operationName);
  }

  /** Execute a function with custom error handler. */
  public <T> CompletableFuture<T> supplyAsyncWithErrorHandler(
      Supplier<T> supplier, Function<Exception, T> errorHandler) {
    return supplyAsyncWithErrorHandler(supplier, errorHandler, defaultTimeoutMs);
  }

  /** Execute a function with custom error handler and timeout. */
  public <T> CompletableFuture<T> supplyAsyncWithErrorHandler(
      Supplier<T> supplier, Function<Exception, T> errorHandler, long timeoutMs) {
    return supplyAsyncWithErrorHandler(
        supplier, errorHandler, timeoutMs, "error-handler-operation");
  }

  /** Execute a function with custom error handler, timeout, and operation name. */
  public <T> CompletableFuture<T> supplyAsyncWithErrorHandler(
      Supplier<T> supplier,
      Function<Exception, T> errorHandler,
      long timeoutMs,
      String operationName) {
    return supplyAsync(
        () -> {
          try {
            return supplier.get();
          } catch (Exception e) {
            if (enableLogging) {
              LOGGER.warn(
                  "{ }: Error in operation '{ }', applying error handler",
                  processorName,
                  operationName,
                  e);
            }
            return errorHandler.apply(e);
          }
        },
        timeoutMs,
        operationName);
  }

  /** Get metrics for a specific operation. */
  public AsyncMetrics getMetrics(String operationName) {
    return metricsMap.getOrDefault(operationName, new AsyncMetrics());
  }

  /** Get all metrics. */
  public ConcurrentMap<String, AsyncMetrics> getAllMetrics() {
    return new ConcurrentHashMap<>(metricsMap);
  }

  /** Get overall processor statistics. */
  public ProcessorStats getProcessorStats() {
    return new ProcessorStats(
        totalOperations.get(),
        failedOperations.get(),
        executor instanceof ThreadPoolExecutor
            ? ((ThreadPoolExecutor) executor).getActiveCount()
            : -1,
        executor instanceof ThreadPoolExecutor
            ? ((ThreadPoolExecutor) executor).getQueue().size()
            : -1);
  }

  /** Shutdown the processor and release resources. */
  public void shutdown() {
    if (executor != null && !executor.isShutdown()) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    if (enableLogging) {
      LOGGER.info(
          "{ }: Shutdown completed. Total operations: { }, Failed: { }",
          processorName,
          totalOperations.get(),
          failedOperations.get());
    }
  }

  /** Processor statistics. */
  public static class ProcessorStats {
    private final long totalOperations;
    private final long failedOperations;
    private final int activeThreads;
    private final int queueSize;

    public ProcessorStats(
        long totalOperations, long failedOperations, int activeThreads, int queueSize) {
      this.totalOperations = totalOperations;
      this.failedOperations = failedOperations;
      this.activeThreads = activeThreads;
      this.queueSize = queueSize;
    }

    public long getTotalOperations() {
      return totalOperations;
    }

    public long getFailedOperations() {
      return failedOperations;
    }

    public int getActiveThreads() {
      return activeThreads;
    }

    public int getQueueSize() {
      return queueSize;
    }

    public double getFailureRate() {
      return totalOperations > 0 ? (double) failedOperations / totalOperations : 0.0;
    }

    @Override
    public String toString() {
      return String.format(
          "ProcessorStats{total=%d, failed=%d, failureRate=%.2f%%, activeThreads=%d, queueSize=%d}",
          totalOperations, failedOperations, getFailureRate() * 100, activeThreads, queueSize);
    }
  }
}
