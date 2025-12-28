package com.kneaf.core;

import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Centralized thread pool manager using Minecraft's built-in executors.
 * Delegates to Minecraft's Util.backgroundExecutor and Util.ioPool for better
 * integration.
 */
public final class WorkerThreadPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerThreadPool.class);
    private static volatile boolean initialized = false;

    // Use Minecraft's built-in executors instead of creating custom pools
    // Util.backgroundExecutor - multi-thread pool for background tasks
    // Util.ioPool - multi-thread pool for I/O operations

    private WorkerThreadPool() {
        // Prevent instantiation
    }

    /**
     * Initialize the worker thread pool (delegates to Minecraft's executors)
     */
    public static void initialize() {
        if (!initialized) {
            initialized = true;
            LOGGER.info("WorkerThreadPool initialized - using Minecraft's built-in executors");
            LOGGER.info("  - Compute/Background tasks: Util.backgroundExecutor()");
            LOGGER.info("  - I/O operations: Util.ioPool()");

            // Log initial worker status
            logWorkerStatus();
        }
    }

    /**
     * Get compute pool for CPU-intensive tasks (chunk generation, pathfinding,
     * etc.)
     * Uses Minecraft's Util.backgroundExecutor which is a ForkJoinPool
     */
    public static ForkJoinPool getComputePool() {
        ExecutorService executor = Util.backgroundExecutor();
        // Minecraft's backgroundExecutor is actually a ForkJoinPool
        if (executor instanceof ForkJoinPool) {
            return (ForkJoinPool) executor;
        }
        // Fallback: shouldn't happen in normal Minecraft
        LOGGER.warn("Util.backgroundExecutor() is not a ForkJoinPool! Using ForkJoin.commonPool()");
        return ForkJoinPool.commonPool();
    }

    /**
     * Get I/O pool for I/O operations (logging, file operations, etc.)
     * Uses Minecraft's Util.ioPool
     */
    public static ExecutorService getIOPool() {
        return Util.ioPool();
    }

    /**
     * Get scheduled pool for periodic tasks (monitoring, metrics, etc.)
     * Creates a lightweight ScheduledThreadPoolExecutor wrapping Minecraft's
     * executor
     */
    public static ScheduledExecutorService getScheduledPool() {
        // For scheduled tasks, we create a minimal wrapper that delegates to
        // backgroundExecutor
        return ScheduledThreadPoolHolder.INSTANCE;
    }

    /**
     * Get priority pool for high-priority tasks
     * Uses Minecraft's backgroundExecutor (same as compute pool)
     */
    public static ExecutorService getPriorityPool() {
        return Util.backgroundExecutor();
    }

    /**
     * Shutdown the worker thread pool
     * Note: We don't shutdown Minecraft's built-in executors as they're managed by
     * Minecraft
     */
    public static void shutdown() {
        if (initialized) {
            LOGGER.info("WorkerThreadPool shutdown - Minecraft's executors remain active");
            // Don't shutdown Minecraft's built-in executors
            // They're managed by Minecraft's lifecycle
            initialized = false;
        }
    }

    /**
     * Log current worker thread status for monitoring
     */
    public static void logWorkerStatus() {
        try {
            ForkJoinPool computePool = getComputePool();
            ExecutorService ioPool = getIOPool();

            // Log compute pool (ForkJoinPool) status
            LOGGER.info("=== Worker Thread Pool Status ===");
            LOGGER.info("Compute Pool (ForkJoin):");
            LOGGER.info("  - Parallelism: " + computePool.getParallelism());
            LOGGER.info("  - Active threads: " + computePool.getActiveThreadCount());
            LOGGER.info("  - Running threads: " + computePool.getRunningThreadCount());
            LOGGER.info("  - Queued tasks: " + computePool.getQueuedTaskCount());
            LOGGER.info("  - Pool size: " + computePool.getPoolSize());

            // Log I/O pool status
            if (ioPool instanceof java.util.concurrent.ThreadPoolExecutor) {
                java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) ioPool;
                LOGGER.info("I/O Pool (ThreadPoolExecutor):");
                LOGGER.info("  - Core size: " + tpe.getCorePoolSize());
                LOGGER.info("  - Max size: " + tpe.getMaximumPoolSize());
                LOGGER.info("  - Active threads: " + tpe.getActiveCount());
                LOGGER.info("  - Pool size: " + tpe.getPoolSize());
                LOGGER.info("  - Largest pool size: " + tpe.getLargestPoolSize());
                LOGGER.info("  - Task count: " + tpe.getTaskCount());
                LOGGER.info("  - Completed: " + tpe.getCompletedTaskCount());
            } else {
                LOGGER.info("I/O Pool: " + ioPool.getClass().getSimpleName());
            }

            LOGGER.info("Scheduled Pool: Wrapper (minimal overhead)");
            LOGGER.info("===================================");
        } catch (Exception e) {
            LOGGER.error("Error logging worker status", e);
        }
    }

    /**
     * Get worker thread statistics for monitoring
     */
    public static String getWorkerStats() {
        try {
            ForkJoinPool computePool = getComputePool();
            return String.format(
                    "Workers{compute=%d/%d active, I/O=Minecraft-managed}",
                    computePool.getActiveThreadCount(),
                    computePool.getParallelism());
        } catch (Exception e) {
            return "Workers{error: " + e.getMessage() + "}";
        }
    }

    /**
     * Get statistics about the worker thread pools
     */
    public static String getStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("WorkerThreadPool Statistics:\n");
        stats.append("  Using Minecraft's built-in executors:\n");
        stats.append("  - Compute Pool: Util.backgroundExecutor()\n");
        stats.append("  - I/O Pool: Util.ioPool()\n");
        stats.append("  - Scheduled Pool: Delegated wrapper\n");
        stats.append("  - Priority Pool: Util.backgroundExecutor()\n");
        stats.append("  Note: Pool statistics managed by Minecraft\n");
        return stats.toString();
    }

    /**
     * Lazy holder for ScheduledExecutorService
     * Uses Minecraft's backgroundExecutor for actual execution
     */
    private static class ScheduledThreadPoolHolder {
        private static final ScheduledExecutorService INSTANCE = new ScheduledThreadPoolExecutor(
                Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
                r -> {
                    Thread t = new Thread(r, "Kneaf-Scheduled");
                    t.setDaemon(true);
                    return t;
                }) {
            @Override
            public void execute(Runnable command) {
                // Delegate actual execution to Minecraft's backgroundExecutor
                Util.backgroundExecutor().execute(command);
            }
        };
    }
}
