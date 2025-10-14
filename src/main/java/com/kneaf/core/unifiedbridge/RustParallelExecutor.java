package com.kneaf.core.unifiedbridge;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Rust-inspired parallel execution system with thread pooling and safe error handling.
 * Implements patterns similar to Rust's std::thread and crossbeam utilities.
 */
public final class RustParallelExecutor {
    private static final RustParallelExecutor INSTANCE = new RustParallelExecutor();
    
    // Thread pool configuration
    private final int corePoolSize;
    private final int maxPoolSize;
    private final long keepAliveTime;
    private final BlockingQueue<Runnable> workQueue;
    private final ThreadPoolExecutor executor;
    
    // Internal state
    private final Map<Long, Future<?>> activeFutures = new ConcurrentHashMap<>();
    private static final AtomicLong nextTaskId = new AtomicLong(1);

    private RustParallelExecutor() {
        this.corePoolSize = Runtime.getRuntime().availableProcessors() - 1;
        this.maxPoolSize = Runtime.getRuntime().availableProcessors() * 2;
        this.keepAliveTime = 60L;
        this.workQueue = new LinkedBlockingQueue<>(100);
        
        this.executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            workQueue,
            new ThreadFactoryBuilder()
                .setNameFormat("rust-parallel-executor-%d")
                .setDaemon(true)
                .build()
        );
        
        // Configure executor for better performance
        executor.allowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Get the singleton instance of RustParallelExecutor.
     * @return Singleton instance
     */
    public static RustParallelExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * Spawn a new task for parallel execution (similar to Rust's std::thread::spawn).
     * @param task The task to execute
     * @param <T> The return type of the task
     * @return TaskHandle for managing the spawned task
     */
    public <T> TaskHandle<T> spawn(Callable<T> task) {
        return spawn(task, false);
    }

    /**
     * Spawn a new task for parallel execution with optional unowned data.
     * @param task The task to execute
     * @param unownedData true if task uses unowned (externally managed) data, false otherwise
     * @param <T> The return type of the task
     * @return TaskHandle for managing the spawned task
     */
    public <T> TaskHandle<T> spawn(Callable<T> task, boolean unownedData) {
        Objects.requireNonNull(task, "Task cannot be null");
        
        long taskId = nextTaskId.getAndIncrement();
        Future<T> future = executor.submit(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new ExecutionException("Task execution failed", e);
            }
        });
        
        activeFutures.put(taskId, future);
        
        return new TaskHandle<>(taskId, future);
    }

    /**
     * Execute multiple tasks in parallel and wait for all results (similar to Rust's join!).
     * @param tasks The tasks to execute in parallel
     * @param <T> The return type of the tasks
     * @return RustResult containing either a list of results or an error
     */
    public <T> RustResult<List<T>, ExecutionException> joinAll(List<Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "Tasks cannot be null");
        if (tasks.isEmpty()) {
            return RustResult.Factory.ok(Collections.emptyList());
        }

        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>(tasks.size());
            
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    return RustResult.Factory.err(new ExecutionException("Task failed", e));
                }
            }
            
            return RustResult.Factory.ok(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RustResult.Factory.err(new ExecutionException("JoinAll interrupted", e));
        }
    }

    /**
     * Execute multiple tasks in parallel with timeout and wait for all results.
     * @param tasks The tasks to execute in parallel
     * @param timeout The maximum time to wait
     * @param unit The time unit for the timeout
     * @param <T> The return type of the tasks
     * @return RustResult containing either a list of results or an error
     */
    public <T> RustResult<List<T>, ExecutionException> joinAllWithTimeout(
        List<Callable<T>> tasks, long timeout, TimeUnit unit) {
        
        Objects.requireNonNull(tasks, "Tasks cannot be null");
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        if (tasks.isEmpty()) {
            return RustResult.Factory.ok(Collections.emptyList());
        }

        try {
            List<Future<T>> futures = executor.invokeAll(tasks, timeout, unit);
            List<T> results = new ArrayList<>(tasks.size());
            
            for (Future<T> future : futures) {
                if (future.isDone()) {
                    try {
                        results.add(future.get());
                    } catch (Exception e) {
                        return RustResult.Factory.err(new ExecutionException("Task failed", e));
                    }
                } else {
                    future.cancel(true); // Cancel unfinished tasks
                }
            }
            
            return RustResult.Factory.ok(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RustResult.Factory.err(new ExecutionException("JoinAll interrupted", e));
        }
    }

    /**
     * Map a collection of items using parallel execution.
     * @param items The items to process
     * @param mapper The function to apply to each item
     * @param <T> The input type
     * @param <U> The output type
     * @return RustResult containing either the mapped results or an error
     */
    public <T, U> RustResult<List<U>, ExecutionException> parallelMap(
        Collection<T> items, Function<T, U> mapper) {
        
        Objects.requireNonNull(items, "Items cannot be null");
        Objects.requireNonNull(mapper, "Mapper cannot be null");
        
        List<Callable<U>> tasks = items.stream()
            .map(item -> (Callable<U>) () -> mapper.apply(item))
            .collect(Collectors.toList());
        
        return joinAll(tasks);
    }

    /**
     * Wait for a specific task to complete.
     * @param taskHandle The handle to the task
     * @param <T> The return type of the task
     * @return RustResult containing either the task result or an error
     */
    public <T> RustResult<T, ExecutionException> wait(TaskHandle<T> taskHandle) {
        Objects.requireNonNull(taskHandle, "Task handle cannot be null");
        
        Future<T> future = (Future<T>) activeFutures.remove(taskHandle.getTaskId());
        if (future == null) {
            return RustResult.Factory.err(new ExecutionException(new Exception("No active task with ID: " + taskHandle.getTaskId())));
        }
        
        try {
            T result = future.get();
            return RustResult.Factory.ok(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RustResult.Factory.err(new ExecutionException("Task wait interrupted", e));
        } catch (ExecutionException e) {
            return RustResult.Factory.err(e);
        }
    }

    /**
     * Wait for a specific task to complete with timeout.
     * @param taskHandle The handle to the task
     * @param timeout The maximum time to wait
     * @param unit The time unit for the timeout
     * @param <T> The return type of the task
     * @return RustResult containing either the task result or an error
     */
    public <T> RustResult<T, ExecutionException> waitWithTimeout(
        TaskHandle<T> taskHandle, long timeout, TimeUnit unit) {
        
        Objects.requireNonNull(taskHandle, "Task handle cannot be null");
        Objects.requireNonNull(unit, "Time unit cannot be null");
        
        Future<T> future = (Future<T>) activeFutures.remove(taskHandle.getTaskId());
        if (future == null) {
            return RustResult.Factory.err(new ExecutionException(new Exception("No active task with ID: " + taskHandle.getTaskId())));
        }
        
        try {
            T result = future.get(timeout, unit);
            return RustResult.Factory.ok(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return RustResult.Factory.err(new ExecutionException("Task timeout", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RustResult.Factory.err(new ExecutionException("Task wait interrupted", e));
        } catch (ExecutionException e) {
            return RustResult.Factory.err(e);
        }
    }

    /**
     * Cancel a running task.
     * @param taskHandle The handle to the task
     * @param mayInterruptIfRunning true if the thread executing this task should be interrupted
     * @return RustResult containing either success or an error
     */
    public RustResult<Void, ExecutionException> cancel(TaskHandle<?> taskHandle, boolean mayInterruptIfRunning) {
        Objects.requireNonNull(taskHandle, "Task handle cannot be null");
        
        Future<?> future = activeFutures.remove(taskHandle.getTaskId());
        if (future == null) {
            return RustResult.Factory.err(new ExecutionException(new Exception("No active task with ID: " + taskHandle.getTaskId())));
        }
        
        boolean cancelled = future.cancel(mayInterruptIfRunning);
        return cancelled 
            ? RustResult.Factory.ok(null)
            : RustResult.Factory.err(new ExecutionException(new Exception("Failed to cancel task with ID: " + taskHandle.getTaskId())));
    }

    /**
     * Get executor statistics.
     * @return ExecutorStatistics containing current executor state
     */
    public ExecutorStatistics getStatistics() {
        return new ExecutorStatistics(
            executor.getActiveCount(),
            executor.getCompletedTaskCount(),
            executor.getTaskCount(),
            executor.getPoolSize(),
            executor.getLargestPoolSize(),
            workQueue.size()
        );
    }

    /**
     * Shutdown the executor gracefully.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    /**
     * Handle for managing a spawned task.
     * @param <T> The return type of the task
     */
    public static final class TaskHandle<T> {
        private final long taskId;
        private final Future<T> future;

        TaskHandle(long taskId, Future<T> future) {
            this.taskId = taskId;
            this.future = future;
        }

        /**
         * Get the unique task ID.
         * @return Task ID
         */
        public long getTaskId() {
            return taskId;
        }

        /**
         * Check if the task is done.
         * @return true if task is completed, false otherwise
         */
        public boolean isDone() {
            return future.isDone();
        }

        /**
         * Check if the task was cancelled.
         * @return true if task was cancelled, false otherwise
         */
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }

    /**
     * Record containing executor statistics.
     */
    public static final class ExecutorStatistics {
        private final int activeThreads;
        private final long completedTasks;
        private final long totalTasks;
        private final int currentPoolSize;
        private final int largestPoolSize;
        private final int queueSize;

        ExecutorStatistics(int activeThreads, long completedTasks, long totalTasks,
                          int currentPoolSize, int largestPoolSize, int queueSize) {
            this.activeThreads = activeThreads;
            this.completedTasks = completedTasks;
            this.totalTasks = totalTasks;
            this.currentPoolSize = currentPoolSize;
            this.largestPoolSize = largestPoolSize;
            this.queueSize = queueSize;
        }

        // Getters
        public int getActiveThreads() { return activeThreads; }
        public long getCompletedTasks() { return completedTasks; }
        public long getTotalTasks() { return totalTasks; }
        public int getCurrentPoolSize() { return currentPoolSize; }
        public int getLargestPoolSize() { return largestPoolSize; }
        public int getQueueSize() { return queueSize; }

        @Override
        public String toString() {
            return "ExecutorStatistics{" +
                    "activeThreads=" + activeThreads +
                    ", completedTasks=" + completedTasks +
                    ", totalTasks=" + totalTasks +
                    ", currentPoolSize=" + currentPoolSize +
                    ", largestPoolSize=" + largestPoolSize +
                    ", queueSize=" + queueSize +
                    '}';
        }
    }

    /**
     * Simple ThreadFactory builder for creating named daemon threads.
     */
    private static class ThreadFactoryBuilder {
        private String nameFormat;
        private boolean daemon;

        ThreadFactoryBuilder() {}

        ThreadFactoryBuilder setNameFormat(String nameFormat) {
            this.nameFormat = nameFormat;
            return this;
        }

        ThreadFactoryBuilder setDaemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        ThreadFactory build() {
            return runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(daemon);
                if (nameFormat != null) {
                    thread.setName(String.format(nameFormat, nextTaskId.getAndIncrement()));
                }
                return thread;
            };
        }
    }
}