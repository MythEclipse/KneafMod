package com.kneaf.core.parallel;

import com.kneaf.core.unifiedbridge.UnifiedBridgeImpl;
import com.kneaf.core.unifiedbridge.BridgeConfiguration;
import com.kneaf.core.unifiedbridge.BridgeException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Full implementation of parallel executor with JNI integration to Rust.
 * Provides work-stealing thread pools, task scheduling, and native parallel processing.
 */
public class RustParallelExecutor implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RustParallelExecutor.class.getName());

    // Native library integration
    private static final String LIB_NAME = "kneaf_parallel";
    private static volatile boolean nativeLibraryLoaded = false;

    // Thread pool configuration
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final TimeUnit keepAliveUnit;
    private final int queueCapacity;

    // Work-stealing executor for CPU-bound tasks
    private final ForkJoinPool workStealingPool;

    // Thread pool executor for I/O-bound tasks
    private final ThreadPoolExecutor ioBoundPool;

    // Native bridge for parallel operations
    private final UnifiedBridgeImpl bridge;

    // Task management
    private final ConcurrentHashMap<Long, CompletableFuture<?>> activeTasks = new ConcurrentHashMap<>();
    private final AtomicLong taskIdGenerator = new AtomicLong(0);

    // Statistics
    private final AtomicLong totalTasksExecuted = new AtomicLong(0);
    private final AtomicLong totalTasksFailed = new AtomicLong(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);

    // Shutdown management
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    static {
        loadNativeLibrary();
    }

    /**
     * Load the native parallel library.
     */
    private static void loadNativeLibrary() {
        if (nativeLibraryLoaded) {
            return;
        }

        try {
            System.loadLibrary(LIB_NAME);
            nativeLibraryLoaded = true;
            LOGGER.info("Native parallel library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.log(Level.SEVERE, "Failed to load native parallel library: " + LIB_NAME, e);
            throw new RuntimeException("Native parallel library not available", e);
        }
    }

    /**
     * Create a new RustParallelExecutor with default configuration.
     */
    public RustParallelExecutor() throws BridgeException {
        this(Runtime.getRuntime().availableProcessors(),
             Runtime.getRuntime().availableProcessors() * 2,
             60L, TimeUnit.SECONDS,
             1000);
    }

    /**
     * Create a new RustParallelExecutor with custom configuration.
     *
     * @param corePoolSize Core pool size for I/O-bound tasks
     * @param maxPoolSize Maximum pool size for I/O-bound tasks
     * @param keepAliveTime Keep alive time for idle threads
     * @param keepAliveUnit Time unit for keep alive time
     * @param queueCapacity Task queue capacity
     */
    public RustParallelExecutor(int corePoolSize, int maxPoolSize, long keepAliveTime,
                               TimeUnit keepAliveUnit, int queueCapacity) throws BridgeException {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.keepAliveUnit = keepAliveUnit;
        this.queueCapacity = queueCapacity;

        // Initialize work-stealing pool for CPU-bound tasks
        this.workStealingPool = new ForkJoinPool(
            Runtime.getRuntime().availableProcessors(),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> LOGGER.log(Level.SEVERE, "Uncaught exception in work-stealing pool", e),
            true // Enable async mode
        );

        // Initialize thread pool for I/O-bound tasks
        this.ioBoundPool = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            keepAliveUnit,
            new LinkedBlockingQueue<>(queueCapacity),
            r -> {
                Thread t = new Thread(r, "rust-parallel-io-" + r.hashCode());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Initialize bridge for native operations
        BridgeConfiguration bridgeConfig = BridgeConfiguration.builder()
            .operationTimeout(30, TimeUnit.SECONDS)
            .maxBatchSize(1000)
            .bufferPoolSize(10 * 1024 * 1024) // 10MB buffer pool
            .defaultWorkerConcurrency(Runtime.getRuntime().availableProcessors())
            .enableDebugLogging(false)
            .build();

        this.bridge = new UnifiedBridgeImpl(bridgeConfig);

        LOGGER.info("RustParallelExecutor initialized with " +
                   "workStealingPool: " + workStealingPool.getParallelism() + " threads, " +
                   "ioBoundPool: " + corePoolSize + "-" + maxPoolSize + " threads");
    }

    /**
     * Submit a CPU-bound task for parallel execution using work-stealing.
     *
     * @param task The task to execute
     * @param <T> Task result type
     * @return CompletableFuture for the task result
     */
    public <T> CompletableFuture<T> submitCpuBound(Callable<T> task) {
        ensureNotShutdown();

        long taskId = taskIdGenerator.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();

        activeTasks.put(taskId, future);

        workStealingPool.submit(() -> {
            long startTime = System.nanoTime();
            try {
                T result = task.call();
                long executionTime = System.nanoTime() - startTime;

                totalTasksExecuted.incrementAndGet();
                totalExecutionTime.addAndGet(executionTime);

                future.complete(result);
                LOGGER.fine("CPU-bound task " + taskId + " completed in " + (executionTime / 1_000_000) + "ms");

            } catch (Exception e) {
                totalTasksFailed.incrementAndGet();
                future.completeExceptionally(e);
                LOGGER.log(Level.WARNING, "CPU-bound task " + taskId + " failed", e);
            } finally {
                activeTasks.remove(taskId);
            }
        });

        return future;
    }

    /**
     * Submit an I/O-bound task for execution.
     *
     * @param task The task to execute
     * @param <T> Task result type
     * @return CompletableFuture for the task result
     */
    public <T> CompletableFuture<T> submitIoBound(Callable<T> task) {
        ensureNotShutdown();

        long taskId = taskIdGenerator.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();

        activeTasks.put(taskId, future);

        ioBoundPool.submit(() -> {
            long startTime = System.nanoTime();
            try {
                T result = task.call();
                long executionTime = System.nanoTime() - startTime;

                totalTasksExecuted.incrementAndGet();
                totalExecutionTime.addAndGet(executionTime);

                future.complete(result);
                LOGGER.fine("I/O-bound task " + taskId + " completed in " + (executionTime / 1_000_000) + "ms");

            } catch (Exception e) {
                totalTasksFailed.incrementAndGet();
                future.completeExceptionally(e);
                LOGGER.log(Level.WARNING, "I/O-bound task " + taskId + " failed", e);
            } finally {
                activeTasks.remove(taskId);
            }
        });

        return future;
    }

    /**
     * Submit a native parallel operation using Rust SIMD processing.
     *
     * @param operationType Type of operation (e.g., "vector_add", "matrix_multiply")
     * @param inputData Input data as byte array
     * @param parallelismLevel Desired parallelism level
     * @return CompletableFuture with result data
     */
    public CompletableFuture<byte[]> submitNativeParallel(String operationType, byte[] inputData, int parallelismLevel) {
        ensureNotShutdown();

        return CompletableFuture.supplyAsync(() -> {
            try {
                return nativeExecuteParallel(operationType, inputData, parallelismLevel);
            } catch (Exception e) {
                throw new CompletionException("Native parallel operation failed: " + operationType, e);
            }
        }, workStealingPool);
    }

    /**
     * Execute multiple tasks in parallel and collect results.
     *
     * @param tasks List of tasks to execute
     * @param <T> Task result type
     * @return CompletableFuture with list of results
     */
    public <T> CompletableFuture<List<T>> executeAll(Collection<Callable<T>> tasks) {
        ensureNotShutdown();

        List<CompletableFuture<T>> futures = tasks.stream()
            .map(this::submitCpuBound)
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

    /**
     * Execute tasks with custom batching and work distribution.
     *
     * @param tasks List of tasks
     * @param batchSize Size of each batch
     * @param batchProcessor Function to process each batch
     * @param <T> Input task type
     * @param <R> Result type
     * @return CompletableFuture with batch results
     */
    public <T, R> CompletableFuture<List<R>> executeInBatches(
            List<T> tasks, int batchSize,
            Function<List<T>, R> batchProcessor) {

        ensureNotShutdown();

        List<CompletableFuture<R>> batchFutures = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, tasks.size());
            List<T> batch = tasks.subList(i, endIndex);

            CompletableFuture<R> batchFuture = submitCpuBound(() -> batchProcessor.apply(batch));
            batchFutures.add(batchFuture);
        }

        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> batchFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

    /**
     * Get current executor statistics.
     *
     * @return Executor statistics
     */
    public ExecutorStats getStats() {
        return new ExecutorStats(
            totalTasksExecuted.get(),
            totalTasksFailed.get(),
            activeTasks.size(),
            workStealingPool.getActiveThreadCount(),
            ioBoundPool.getActiveCount(),
            workStealingPool.getQueuedTaskCount(),
            ioBoundPool.getQueue().size(),
            totalExecutionTime.get() / 1_000_000_000.0 // Convert to seconds
        );
    }

    /**
     * Shutdown the executor gracefully.
     */
    @Override
    public void close() {
        if (shutdown.getAndSet(true)) {
            return; // Already shutting down
        }

        LOGGER.info("Shutting down RustParallelExecutor");

        // Cancel all active tasks
        activeTasks.values().forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        activeTasks.clear();

        // Shutdown thread pools
        workStealingPool.shutdown();
        ioBoundPool.shutdown();

        try {
            // Wait for work-stealing pool to terminate
            if (!workStealingPool.awaitTermination(30, TimeUnit.SECONDS)) {
                workStealingPool.shutdownNow();
            }

            // Wait for I/O pool to terminate
            if (!ioBoundPool.awaitTermination(30, TimeUnit.SECONDS)) {
                ioBoundPool.shutdownNow();
            }

        } catch (InterruptedException e) {
            workStealingPool.shutdownNow();
            ioBoundPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close bridge
        try {
            bridge.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error closing bridge", e);
        }

        shutdownLatch.countDown();
        LOGGER.info("RustParallelExecutor shutdown complete");
    }

    /**
     * Wait for shutdown to complete.
     *
     * @param timeout Maximum time to wait
     * @param unit Time unit
     * @return true if shutdown completed, false if timed out
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return shutdownLatch.await(timeout, unit);
    }

    /**
     * Ensure executor is not shutdown.
     */
    private void ensureNotShutdown() {
        if (shutdown.get()) {
            throw new IllegalStateException("Executor is shutdown");
        }
    }

    /**
     * Executor statistics container.
     */
    public static class ExecutorStats {
        public final long totalTasksExecuted;
        public final long totalTasksFailed;
        public final int activeTasks;
        public final int workStealingActiveThreads;
        public final int ioBoundActiveThreads;
        public final long workStealingQueuedTasks;
        public final int ioBoundQueuedTasks;
        public final double totalExecutionTimeSeconds;

        public ExecutorStats(long totalTasksExecuted, long totalTasksFailed, int activeTasks,
                           int workStealingActiveThreads, int ioBoundActiveThreads,
                           long workStealingQueuedTasks, int ioBoundQueuedTasks,
                           double totalExecutionTimeSeconds) {
            this.totalTasksExecuted = totalTasksExecuted;
            this.totalTasksFailed = totalTasksFailed;
            this.activeTasks = activeTasks;
            this.workStealingActiveThreads = workStealingActiveThreads;
            this.ioBoundActiveThreads = ioBoundActiveThreads;
            this.workStealingQueuedTasks = workStealingQueuedTasks;
            this.ioBoundQueuedTasks = ioBoundQueuedTasks;
            this.totalExecutionTimeSeconds = totalExecutionTimeSeconds;
        }

        @Override
        public String toString() {
            return String.format(
                "ExecutorStats{tasks=%d/%d failed, active=%d, wsThreads=%d/%d queued, ioThreads=%d/%d queued, time=%.2fs}",
                totalTasksExecuted, totalTasksFailed, activeTasks,
                workStealingActiveThreads, workStealingQueuedTasks,
                ioBoundActiveThreads, ioBoundQueuedTasks, totalExecutionTimeSeconds
            );
        }
    }

    // Native method declarations
    private native byte[] nativeExecuteParallel(String operationType, byte[] inputData, int parallelismLevel);
}