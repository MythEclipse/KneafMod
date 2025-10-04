package com.kneaf.core.performance.execution;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Unified executor management system to reduce thread pool contention
 * Implements adaptive thread pooling, work stealing, and load balancing
 */
public final class UnifiedExecutorManager {
    private static final UnifiedExecutorManager INSTANCE = new UnifiedExecutorManager();
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Configuration bases (will be computed per-instance using adaptive getters)
    private final int baseCoreThreads;
    private final int baseMaxThreads;
    private final long keepAliveTimeSeconds;
    private final TimeUnit KEEP_ALIVE_UNIT = TimeUnit.SECONDS;
    private final int baseQueueCapacity;
    // adaptive interval for executor management is computed at runtime
    
    // Performance thresholds
    private static final double HIGH_LOAD_THRESHOLD = 0.8;
    private static final double LOW_LOAD_THRESHOLD = 0.3;
    private static final int THREAD_SCALING_FACTOR = 2;
    
    // Enhanced thread pool with monitoring
    private final ThreadPoolExecutor primaryExecutor;
    private final ForkJoinPool forkJoinPool;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService ioExecutor;
    
    // Performance monitoring
    private final AtomicLong submittedTasks = new AtomicLong(0);
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong rejectedTasks = new AtomicLong(0);
    private final AtomicLong queueWaits = new AtomicLong(0);
    private final AtomicLong adaptiveResizes = new AtomicLong(0);
    
    // Adaptive configuration
    private final AtomicInteger currentCoreThreads;
    private final AtomicInteger currentMaxThreads;
    private final StampedLock configLock = new StampedLock();
    
    // Task tracking
    private final ConcurrentHashMap<String, TaskMetrics> taskMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TaskInfo> activeTasks = new ConcurrentHashMap<>();
    
    // Priority queues for different task types
    private final PriorityBlockingQueue<PriorityTask> highPriorityQueue;
    private final LinkedBlockingQueue<Runnable> normalQueue;
    private final LinkedBlockingQueue<Runnable> backgroundQueue;
    
    /**
     * Task priority enumeration
     */
    public enum TaskPriority {
        HIGH(1),      // Critical tasks - chunk loading, player actions
        NORMAL(2),    // Standard tasks - entity updates, block processing
        BACKGROUND(3); // Low priority - cleanup, statistics, logging
        
        private final int priority;
        
        TaskPriority(int priority) {
            this.priority = priority;
        }
        
        public int getPriority() {
            return priority;
        }
    }
    
    /**
     * Priority task wrapper
     */
    private static final class PriorityTask implements Comparable<PriorityTask> {
        private final Runnable task;
        private final TaskPriority priority;
        private final long sequence;
        private static final AtomicLong sequenceGenerator = new AtomicLong(0);
        
        PriorityTask(Runnable task, TaskPriority priority) {
            this.task = task;
            this.priority = priority;
            this.sequence = sequenceGenerator.incrementAndGet();
        }
        
        @Override
        public int compareTo(PriorityTask other) {
            int priorityCompare = Integer.compare(this.priority.getPriority(), other.priority.getPriority());
            return priorityCompare != 0 ? priorityCompare : Long.compare(this.sequence, other.sequence);
        }
        
        Runnable getTask() {
            return task;
        }
    }
    
    /**
     * Task metrics for monitoring
     */
    private static final class TaskMetrics {
        private final AtomicLong executionCount = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private final AtomicLong maxExecutionTime = new AtomicLong(0);
        private final AtomicLong minExecutionTime = new AtomicLong(Long.MAX_VALUE);
        
        void recordExecution(long duration) {
            executionCount.incrementAndGet();
            totalExecutionTime.addAndGet(duration);
            maxExecutionTime.updateAndGet(current -> Math.max(current, duration));
            minExecutionTime.updateAndGet(current -> Math.min(current, duration));
        }
        
        double getAverageExecutionTime() {
            long count = executionCount.get();
            return count > 0 ? (double) totalExecutionTime.get() / count : 0.0;
        }
        
        long getMaxExecutionTime() {
            return maxExecutionTime.get();
        }
        
        long getMinExecutionTime() {
            return minExecutionTime.get() == Long.MAX_VALUE ? 0 : minExecutionTime.get();
        }
    }
    
    /**
     * Task information for tracking
     */
    private static final class TaskInfo {
        @SuppressWarnings("unused")
        private final long taskId;
        @SuppressWarnings("unused")
        private final String taskType;
        private final long startTime;
        @SuppressWarnings("unused")
        private final TaskPriority priority;
        
        TaskInfo(long taskId, String taskType, long startTime, TaskPriority priority) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.startTime = startTime;
            this.priority = priority;
        }
    }
    
    /**
     * Enhanced thread factory with monitoring
     */
    private static final class EnhancedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final boolean isDaemon;
        
        EnhancedThreadFactory(String namePrefix, boolean isDaemon) {
            this.namePrefix = namePrefix;
            this.isDaemon = isDaemon;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(isDaemon);
            thread.setUncaughtExceptionHandler((t, e) -> {
                System.err.println("Uncaught exception in thread " + t.getName() + ": " + e.getMessage());
                e.printStackTrace();
            });
            return thread;
        }
    }
    
    /**
     * Custom rejection handler with fallback
     */
    private static final class EnhancedRejectedExecutionHandler implements RejectedExecutionHandler {
        private final ExecutorService fallbackExecutor;
        
        EnhancedRejectedExecutionHandler(ExecutorService fallbackExecutor) {
            this.fallbackExecutor = fallbackExecutor;
        }
        
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // Try to execute on fallback executor
            try {
                fallbackExecutor.execute(r);
            } catch (RejectedExecutionException e) {
                // Last resort: execute in caller thread
                r.run();
            }
        }
    }
    
    private UnifiedExecutorManager() {
        // Compute adaptive base sizes from PerformanceManager metrics
        double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
        this.baseCoreThreads = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveCoreThreads(tps);
        this.baseMaxThreads = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxThreads(tps);
        this.keepAliveTimeSeconds = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveThreadKeepAliveSeconds(tps);
        this.baseQueueCapacity = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveQueueCapacity(tps);

    // Initialize priority queues
    this.highPriorityQueue = new PriorityBlockingQueue<>();
    this.normalQueue = new LinkedBlockingQueue<>(baseQueueCapacity);
    this.backgroundQueue = new LinkedBlockingQueue<>(baseQueueCapacity * 2);

    // Initialize adaptive current thread counters
    this.currentCoreThreads = new AtomicInteger(baseCoreThreads);
    this.currentMaxThreads = new AtomicInteger(baseMaxThreads);
        
        // Create fallback executor for rejected tasks
        this.forkJoinPool = new ForkJoinPool(
            Math.max(1, baseCoreThreads),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> System.err.println("ForkJoinPool exception in thread " + t.getName() + ": " + e.getMessage()),
            true
        );
        
        // Create IO-optimized executor
        this.ioExecutor = new ThreadPoolExecutor(
            Math.max(1, baseCoreThreads / 2),
            Math.max(baseCoreThreads + 1, baseMaxThreads),
            keepAliveTimeSeconds,
            KEEP_ALIVE_UNIT,
            new LinkedBlockingQueue<>(baseQueueCapacity),
            new EnhancedThreadFactory("UnifiedExecutor-IO-", true),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Create primary executor with enhanced queue
        this.primaryExecutor = new ThreadPoolExecutor(
            baseCoreThreads,
            baseMaxThreads,
            keepAliveTimeSeconds,
            KEEP_ALIVE_UNIT,
            new EnhancedBlockingQueue(),
            new EnhancedThreadFactory("UnifiedExecutor-Primary-", true),
            new EnhancedRejectedExecutionHandler(forkJoinPool)
        );
        
        // Allow core threads to timeout
        this.primaryExecutor.allowCoreThreadTimeOut(true);
        
        // Create scheduler for adaptive management
        this.scheduler = Executors.newScheduledThreadPool(
            2,
            new EnhancedThreadFactory("UnifiedExecutor-Scheduler-", true)
        );
        
        // Start adaptive management
        startAdaptiveManagement();
    }
    
    /**
     * Get the singleton instance
     */
    public static UnifiedExecutorManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Submit high-priority task (chunk loading, player actions)
     */
    public CompletableFuture<Void> submitHighPriority(Runnable task, String taskType) {
        return submitTask(task, TaskPriority.HIGH, taskType);
    }
    
    /**
     * Submit normal priority task (entity updates, block processing)
     */
    public CompletableFuture<Void> submitNormalPriority(Runnable task, String taskType) {
        return submitTask(task, TaskPriority.NORMAL, taskType);
    }
    
    /**
     * Submit background task (cleanup, statistics, logging)
     */
    public CompletableFuture<Void> submitBackgroundTask(Runnable task, String taskType) {
        return submitTask(task, TaskPriority.BACKGROUND, taskType);
    }
    
    /**
     * Submit IO-intensive task
     */
    public CompletableFuture<Void> submitIOTask(Runnable task, String taskType) {
        return submitToExecutor(task, ioExecutor, TaskPriority.NORMAL, taskType);
    }
    
    /**
     * Submit parallel computation task
     */
    private static final java.util.concurrent.atomic.AtomicLong TASK_ID_GENERATOR = new java.util.concurrent.atomic.AtomicLong(1);

    public <T> CompletableFuture<T> submitParallelTask(java.util.function.Supplier<T> task, String taskType) {
        long taskId = TASK_ID_GENERATOR.getAndIncrement();
        TaskInfo taskInfo = new TaskInfo(taskId, taskType, System.currentTimeMillis(), TaskPriority.NORMAL);
        activeTasks.put(taskId, taskInfo);
        
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, forkJoinPool)
            .whenComplete((result, throwable) -> {
                activeTasks.remove(taskId);
                recordTaskCompletion(taskType, System.currentTimeMillis() - taskInfo.startTime);
            });
        
        submittedTasks.incrementAndGet();
        return future;
    }
    
    /**
     * Submit batch of tasks with priority
     */
    public CompletableFuture<Void> submitBatch(List<Runnable> tasks, TaskPriority priority, String taskType) {
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(submitTask(task, priority, taskType));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Core task submission method
     */
    private CompletableFuture<Void> submitTask(Runnable task, TaskPriority priority, String taskType) {
        return submitToExecutor(task, primaryExecutor, priority, taskType);
    }
    
    /**
     * Submit task to specific executor
     */
    private CompletableFuture<Void> submitToExecutor(Runnable task, ExecutorService executor, TaskPriority priority, String taskType) {
        long taskId = TASK_ID_GENERATOR.getAndIncrement();
        TaskInfo taskInfo = new TaskInfo(taskId, taskType, System.currentTimeMillis(), priority);
        activeTasks.put(taskId, taskInfo);
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Runnable wrappedTask = () -> {
            long startTime = System.currentTimeMillis();
            try {
                task.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
                System.err.println("Task execution failed: " + taskType + " - " + e.getMessage());
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                activeTasks.remove(taskId);
                recordTaskCompletion(taskType, duration);
                completedTasks.incrementAndGet();
            }
        };
        
        try {
            switch (priority) {
                case HIGH:
                    PriorityTask priorityTask = new PriorityTask(wrappedTask, priority);
                    highPriorityQueue.offer(priorityTask);
                    break;
                case NORMAL:
                    normalQueue.offer(wrappedTask);
                    break;
                case BACKGROUND:
                    backgroundQueue.offer(wrappedTask);
                    break;
            }
            
            submittedTasks.incrementAndGet();
        } catch (Exception e) {
            rejectedTasks.incrementAndGet();
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Enhanced blocking queue that handles priority tasks
     */
    private final class EnhancedBlockingQueue extends LinkedBlockingQueue<Runnable> {
        
        @Override
        public Runnable take() throws InterruptedException {
            // Priority: high -> normal -> background
            PriorityTask priorityTask = highPriorityQueue.poll();
            if (priorityTask != null) {
                queueWaits.incrementAndGet();
                return priorityTask.getTask();
            }
            
            Runnable normalTask = normalQueue.poll();
            if (normalTask != null) {
                return normalTask;
            }
            
            Runnable backgroundTask = backgroundQueue.poll();
            if (backgroundTask != null) {
                return backgroundTask;
            }
            
            // Wait for next task
            return super.take();
        }
        
        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            
            while (System.nanoTime() < deadline) {
                PriorityTask priorityTask = highPriorityQueue.poll();
                if (priorityTask != null) {
                    queueWaits.incrementAndGet();
                    return priorityTask.getTask();
                }
                
                Runnable normalTask = normalQueue.poll();
                if (normalTask != null) {
                    return normalTask;
                }
                
                Runnable backgroundTask = backgroundQueue.poll();
                if (backgroundTask != null) {
                    return backgroundTask;
                }
                
                // Small sleep to avoid busy waiting
                Thread.sleep(1);
            }
            
            return super.poll(timeout, unit);
        }
        
        @Override
        public int size() {
            return highPriorityQueue.size() + normalQueue.size() + backgroundQueue.size() + super.size();
        }
    }
    
    /**
     * Record task completion metrics
     */
    private void recordTaskCompletion(String taskType, long duration) {
        TaskMetrics metrics = taskMetrics.computeIfAbsent(taskType, k -> new TaskMetrics());
        metrics.recordExecution(duration);
    }
    
    /**
     * Start adaptive management
     */
    private void startAdaptiveManagement() {
        // Adaptive thread pool sizing (interval adapts to TPS)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performAdaptiveThreadSizing();
            } catch (Exception e) {
                System.err.println("Error in adaptive thread sizing: " + e.getMessage());
            }
        }, 0,  getAdaptiveExecutorInterval(), TimeUnit.MILLISECONDS);
        
        // Performance monitoring
        scheduler.scheduleAtFixedRate(() -> {
            try {
                logPerformanceMetrics();
            } catch (Exception e) {
                System.err.println("Error in performance monitoring: " + e.getMessage());
            }
        }, 30000, 30000, TimeUnit.MILLISECONDS); // Every 30 seconds
    }

    private long getAdaptiveExecutorInterval() {
        double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveExecutorCheckIntervalMs(tps);
    }
    
    /**
     * Perform adaptive thread pool sizing based on load
     */
    private void performAdaptiveThreadSizing() {
        long stamp = configLock.readLock();
        try {
            int activeCount = primaryExecutor.getActiveCount();
            int poolSize = primaryExecutor.getPoolSize();
            int queueSize = primaryExecutor.getQueue().size();
            
            if (poolSize == 0) return;
            
            double loadFactor = (double) activeCount / poolSize;
            double adaptiveQueueCapacity = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveQueueCapacity(com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
            double queueLoadFactor = adaptiveQueueCapacity > 0 ? (double) queueSize / adaptiveQueueCapacity : 0.0;
            
            // Adaptive scaling based on load or slow server ticks
            boolean slowTick = false;
            try {
                // Check last tick duration from PerformanceManager; if >50ms consider scaling up
                long lastTickMs = com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
                slowTick = lastTickMs > 50L;
            } catch (Throwable t) {
                // If PerformanceManager isn't available for some reason, ignore and proceed with other checks
                slowTick = false;
            }

            if (loadFactor > HIGH_LOAD_THRESHOLD || queueLoadFactor > 0.7 || slowTick) {
                // Scale up
                    int newCoreSize = Math.min(currentCoreThreads.get() + THREAD_SCALING_FACTOR, baseMaxThreads);
                    int newMaxSize = Math.min(currentMaxThreads.get() + THREAD_SCALING_FACTOR, baseMaxThreads * 2);
                
                if (newCoreSize > currentCoreThreads.get() || newMaxSize > currentMaxThreads.get()) {
                    if (slowTick) {
                        try {
                            long lastTickMs = com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
                            LOGGER.info("Scaling up threads due to slow tick: {} ms", lastTickMs);
                        } catch (Throwable t) {
                            // Ignore if can't get the value
                        }
                    }
                    primaryExecutor.setCorePoolSize(newCoreSize);
                    primaryExecutor.setMaximumPoolSize(newMaxSize);
                    currentCoreThreads.set(newCoreSize);
                    currentMaxThreads.set(newMaxSize);
                    adaptiveResizes.incrementAndGet();
                }
            } else if (loadFactor < LOW_LOAD_THRESHOLD && queueLoadFactor < 0.2) {
                // Scale down
                int newCoreSize = Math.max(baseCoreThreads, currentCoreThreads.get() - THREAD_SCALING_FACTOR);
                int newMaxSize = Math.max(baseMaxThreads, currentMaxThreads.get() - THREAD_SCALING_FACTOR);
                
                if (newCoreSize < currentCoreThreads.get() || newMaxSize < currentMaxThreads.get()) {
                    primaryExecutor.setCorePoolSize(newCoreSize);
                    primaryExecutor.setMaximumPoolSize(newMaxSize);
                    currentCoreThreads.set(newCoreSize);
                    currentMaxThreads.set(newMaxSize);
                    adaptiveResizes.incrementAndGet();
                }
            }
        } finally {
            configLock.unlockRead(stamp);
        }
    }
    
    /**
     * Log performance metrics
     */
    private void logPerformanceMetrics() {
        System.out.println("=== UnifiedExecutorManager Performance Metrics ===");
        System.out.println("Submitted Tasks: " + submittedTasks.get());
        System.out.println("Completed Tasks: " + completedTasks.get());
        System.out.println("Rejected Tasks: " + rejectedTasks.get());
        System.out.println("Queue Waits: " + queueWaits.get());
        System.out.println("Adaptive Resizes: " + adaptiveResizes.get());
        System.out.println("Active Threads: " + primaryExecutor.getActiveCount() + "/" + primaryExecutor.getPoolSize());
        System.out.println("Queue Size: " + primaryExecutor.getQueue().size());
        System.out.println("Current Config: Core=" + currentCoreThreads.get() + ", Max=" + currentMaxThreads.get());
        
        // Task type metrics
        System.out.println("Task Execution Metrics:");
        for (Map.Entry<String, TaskMetrics> entry : taskMetrics.entrySet()) {
            TaskMetrics metrics = entry.getValue();
            System.out.println("  " + entry.getKey() + ":");
            System.out.println("    Count: " + metrics.executionCount.get());
            System.out.println("    Avg: " + String.format("%.2f", metrics.getAverageExecutionTime()) + "ms");
            System.out.println("    Min: " + metrics.getMinExecutionTime() + "ms");
            System.out.println("    Max: " + metrics.getMaxExecutionTime() + "ms");
        }
        
        System.out.println("Active Tasks: " + activeTasks.size());
        System.out.println("=============================================");
    }
    
    /**
     * Get current performance statistics
     */
    public String getPerformanceStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("UnifiedExecutorManager Statistics:\n");
        stats.append("Submitted: ").append(submittedTasks.get()).append("\n");
        stats.append("Completed: ").append(completedTasks.get()).append("\n");
        stats.append("Rejected: ").append(rejectedTasks.get()).append("\n");
        stats.append("Active Threads: ").append(primaryExecutor.getActiveCount()).append("/").append(primaryExecutor.getPoolSize()).append("\n");
        stats.append("Queue Size: ").append(primaryExecutor.getQueue().size()).append("\n");
        stats.append("Current Config: Core=").append(currentCoreThreads.get()).append(", Max=").append(currentMaxThreads.get()).append("\n");
        return stats.toString();
    }
    
    /**
     * Shutdown all executors gracefully
     */
    public void shutdown() {
        System.out.println("Shutting down UnifiedExecutorManager...");
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        primaryExecutor.shutdown();
        forkJoinPool.shutdown();
        ioExecutor.shutdown();
        
        try {
            if (!primaryExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                primaryExecutor.shutdownNow();
            }
            if (!forkJoinPool.awaitTermination(10, TimeUnit.SECONDS)) {
                forkJoinPool.shutdownNow();
            }
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            primaryExecutor.shutdownNow();
            forkJoinPool.shutdownNow();
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("UnifiedExecutorManager shutdown complete.");
    }
}