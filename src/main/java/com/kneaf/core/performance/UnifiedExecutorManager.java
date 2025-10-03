package com.kneaf.core.performance;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified executor management system to reduce thread pool contention
 * Implements adaptive thread pooling, work stealing, and load balancing
 */
public final class UnifiedExecutorManager {
    private static final UnifiedExecutorManager INSTANCE = new UnifiedExecutorManager();
    
    // Configuration constants
    private static final int CORE_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int MAX_THREADS = Math.max(8, CORE_THREADS * 2);
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final TimeUnit KEEP_ALIVE_UNIT = TimeUnit.SECONDS;
    private static final int QUEUE_CAPACITY = 1000;
    private static final int ADAPTIVE_CHECK_INTERVAL_MS = 5000;
    
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
    private final AtomicInteger currentCoreThreads = new AtomicInteger(CORE_THREADS);
    private final AtomicInteger currentMaxThreads = new AtomicInteger(MAX_THREADS);
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
        private final long taskId;
        private final String taskType;
        private final long startTime;
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
        // Initialize priority queues
        this.highPriorityQueue = new PriorityBlockingQueue<>();
        this.normalQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.backgroundQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY * 2);
        
        // Create fallback executor for rejected tasks
        this.forkJoinPool = new ForkJoinPool(
            CORE_THREADS,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> System.err.println("ForkJoinPool exception in thread " + t.getName() + ": " + e.getMessage()),
            true
        );
        
        // Create IO-optimized executor
        this.ioExecutor = new ThreadPoolExecutor(
            CORE_THREADS / 2,
            MAX_THREADS,
            KEEP_ALIVE_TIME,
            KEEP_ALIVE_UNIT,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            new EnhancedThreadFactory("UnifiedExecutor-IO-", true),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Create primary executor with enhanced queue
        this.primaryExecutor = new ThreadPoolExecutor(
            CORE_THREADS,
            MAX_THREADS,
            KEEP_ALIVE_TIME,
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
    public <T> CompletableFuture<T> submitParallelTask(java.util.function.Supplier<T> task, String taskType) {
        long taskId = Thread.currentThread().getId();
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
        long taskId = Thread.currentThread().getId();
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
        // Adaptive thread pool sizing
        scheduler.scheduleAtFixedRate(() -> {
            try {
                performAdaptiveThreadSizing();
            } catch (Exception e) {
                System.err.println("Error in adaptive thread sizing: " + e.getMessage());
            }
        }, ADAPTIVE_CHECK_INTERVAL_MS, ADAPTIVE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // Performance monitoring
        scheduler.scheduleAtFixedRate(() -> {
            try {
                logPerformanceMetrics();
            } catch (Exception e) {
                System.err.println("Error in performance monitoring: " + e.getMessage());
            }
        }, 30000, 30000, TimeUnit.MILLISECONDS); // Every 30 seconds
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
            double queueLoadFactor = (double) queueSize / QUEUE_CAPACITY;
            
            // Adaptive scaling based on load
            if (loadFactor > HIGH_LOAD_THRESHOLD || queueLoadFactor > 0.7) {
                // Scale up
                int newCoreSize = Math.min(currentCoreThreads.get() + THREAD_SCALING_FACTOR, MAX_THREADS);
                int newMaxSize = Math.min(currentMaxThreads.get() + THREAD_SCALING_FACTOR, MAX_THREADS * 2);
                
                if (newCoreSize > currentCoreThreads.get() || newMaxSize > currentMaxThreads.get()) {
                    primaryExecutor.setCorePoolSize(newCoreSize);
                    primaryExecutor.setMaximumPoolSize(newMaxSize);
                    currentCoreThreads.set(newCoreSize);
                    currentMaxThreads.set(newMaxSize);
                    adaptiveResizes.incrementAndGet();
                }
            } else if (loadFactor < LOW_LOAD_THRESHOLD && queueLoadFactor < 0.2) {
                // Scale down
                int newCoreSize = Math.max(CORE_THREADS, currentCoreThreads.get() - THREAD_SCALING_FACTOR);
                int newMaxSize = Math.max(MAX_THREADS, currentMaxThreads.get() - THREAD_SCALING_FACTOR);
                
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