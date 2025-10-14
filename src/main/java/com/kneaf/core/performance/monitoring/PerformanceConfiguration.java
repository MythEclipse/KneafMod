package com.kneaf.core.performance.monitoring;

import com.kneaf.core.performance.execution.UnifiedExecutorManager;
import com.kneaf.core.performance.unified.PerformanceManager;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration class for performance monitoring system.
 * Provides access to performance-related configuration and metrics.
 */
public class PerformanceConfiguration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PerformanceConfig CONFIG = PerformanceConfig.load();
    private static final ThreadPoolManager threadPoolManager = new ThreadPoolManager();

    private PerformanceConfiguration() {
        // Private constructor to prevent instantiation
    }

    // Enhanced thread pool size caching for performance
    private static final AtomicInteger CACHED_THREAD_POOL_SIZE = new AtomicInteger(-1);
    private static final AtomicLong CACHE_TIMESTAMP = new AtomicLong(0);
    private static final long CACHE_TTL_MS = 5000; // 5 second cache TTL

    // Thread pool size validation constants
    private static final int MIN_VALID_THREAD_SIZE = 0;
    private static final int MAX_VALID_THREAD_SIZE = 10000; // Reasonable upper bound

    /**
     * Get the current thread pool size for monitoring purposes.
     * This method provides comprehensive runtime thread pool size calculation
     * with validation, error handling, and integration with multiple executor systems.
     *
     * @return the current thread pool size, validated and cached for performance
     */
    public static int getCurrentThreadPoolSize() {
        // Check cache first for performance
        long now = System.currentTimeMillis();
        int cachedSize = CACHED_THREAD_POOL_SIZE.get();
        if (cachedSize >= 0 && (now - CACHE_TIMESTAMP.get()) < CACHE_TTL_MS) {
            return cachedSize;
        }

        int totalThreadPoolSize = 0;
        boolean hasValidData = false;

        try {
            // Primary executor from ThreadPoolManager
            ThreadPoolExecutor primaryExecutor = threadPoolManager.getExecutor();
            if (primaryExecutor != null && !primaryExecutor.isShutdown()) {
                int primarySize = primaryExecutor.getPoolSize();
                if (isValidThreadPoolSize(primarySize)) {
                    totalThreadPoolSize += primarySize;
                    hasValidData = true;
                    LOGGER.debug("Primary executor pool size: {}", primarySize);
                } else {
                    LOGGER.warn("Invalid primary executor pool size: {}", primarySize);
                }
            } else {
                LOGGER.warn("Primary thread pool executor is not available or shutdown");
            }

            // Unified executor system integration via reflection for advanced monitoring
            try {
                UnifiedExecutorManager unifiedManager = UnifiedExecutorManager.getInstance();
                if (unifiedManager != null) {
                    // Use reflection to access private executors for monitoring
                    java.lang.reflect.Field primaryExecutorField = UnifiedExecutorManager.class.getDeclaredField("primaryExecutor");
                    primaryExecutorField.setAccessible(true);
                    ThreadPoolExecutor unifiedPrimary = (ThreadPoolExecutor) primaryExecutorField.get(unifiedManager);

                    if (unifiedPrimary != null && !unifiedPrimary.isShutdown()) {
                        int unifiedPrimarySize = unifiedPrimary.getPoolSize();
                        if (isValidThreadPoolSize(unifiedPrimarySize)) {
                            totalThreadPoolSize += unifiedPrimarySize;
                            hasValidData = true;
                            LOGGER.debug("Unified primary executor pool size: {}", unifiedPrimarySize);
                        }
                    }

                    // Access IO executor
                    java.lang.reflect.Field ioExecutorField = UnifiedExecutorManager.class.getDeclaredField("ioExecutor");
                    ioExecutorField.setAccessible(true);
                    ThreadPoolExecutor ioExecutor = (ThreadPoolExecutor) ioExecutorField.get(unifiedManager);

                    if (ioExecutor != null && !ioExecutor.isShutdown()) {
                        int ioSize = ioExecutor.getPoolSize();
                        if (isValidThreadPoolSize(ioSize)) {
                            totalThreadPoolSize += ioSize;
                            hasValidData = true;
                            LOGGER.debug("Unified IO executor pool size: {}", ioSize);
                        }
                    }

                    // Access ForkJoin pool
                    java.lang.reflect.Field forkJoinField = UnifiedExecutorManager.class.getDeclaredField("forkJoinPool");
                    forkJoinField.setAccessible(true);
                    java.util.concurrent.ForkJoinPool forkJoinPool = (java.util.concurrent.ForkJoinPool) forkJoinField.get(unifiedManager);

                    if (forkJoinPool != null && !forkJoinPool.isShutdown()) {
                        int forkJoinSize = forkJoinPool.getPoolSize();
                        if (isValidThreadPoolSize(forkJoinSize)) {
                            totalThreadPoolSize += forkJoinSize;
                            hasValidData = true;
                            LOGGER.debug("ForkJoin pool size: {}", forkJoinSize);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to access unified executor manager via reflection: {}", e.getMessage());
                // Continue with primary executor only - this is not critical
            }

            // Validate total size
            if (!isValidThreadPoolSize(totalThreadPoolSize)) {
                LOGGER.error("Calculated total thread pool size is invalid: {}", totalThreadPoolSize);
                totalThreadPoolSize = CONFIG.getMinThreadpoolSize();
            }

            // Ensure size is within configured bounds
            int minSize = CONFIG.getMinThreadpoolSize();
            int maxSize = CONFIG.getMaxThreadpoolSize();
            if (totalThreadPoolSize < minSize) {
                LOGGER.warn("Thread pool size {} below minimum {}, using minimum", totalThreadPoolSize, minSize);
                totalThreadPoolSize = minSize;
            } else if (totalThreadPoolSize > maxSize) {
                LOGGER.warn("Thread pool size {} above maximum {}, using maximum", totalThreadPoolSize, maxSize);
                totalThreadPoolSize = maxSize;
            }

            // Log to performance metrics if available
            try {
                PerformanceManager perfManager = PerformanceManager.getInstance();
                if (perfManager != null && perfManager.isEnabled()) {
                    PerformanceMetricsLogger.logLine(String.format(
                        "PERF: thread_pool_size total=%d primary=%d unified=%d",
                        totalThreadPoolSize,
                        primaryExecutor != null ? primaryExecutor.getPoolSize() : 0,
                        totalThreadPoolSize - (primaryExecutor != null ? primaryExecutor.getPoolSize() : 0)
                    ));
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to log thread pool metrics: {}", e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.error("Critical error getting thread pool size, using fallback", e);
            totalThreadPoolSize = CONFIG.getMinThreadpoolSize();
            hasValidData = false;
        }

        // Fallback if no valid data
        if (!hasValidData) {
            LOGGER.warn("No valid thread pool data available, using configuration minimum");
            totalThreadPoolSize = CONFIG.getMinThreadpoolSize();
        }

        // Cache the result
        CACHED_THREAD_POOL_SIZE.set(totalThreadPoolSize);
        CACHE_TIMESTAMP.set(now);

        LOGGER.debug("Calculated total thread pool size: {}", totalThreadPoolSize);
        return totalThreadPoolSize;
    }

    /**
     * Validate if a thread pool size value is within acceptable bounds.
     *
     * @param size the size to validate
     * @return true if valid, false otherwise
     */
    private static boolean isValidThreadPoolSize(int size) {
        return size >= MIN_VALID_THREAD_SIZE && size <= MAX_VALID_THREAD_SIZE;
    }

    /**
     * Get the maximum thread pool size from configuration.
     *
     * @return the maximum thread pool size
     */
    public static int getMaxThreadPoolSize() {
        return CONFIG.getMaxThreadpoolSize();
    }

    /**
     * Get the minimum thread pool size from configuration.
     *
     * @return the minimum thread pool size
     */
    public static int getMinThreadPoolSize() {
        return CONFIG.getMinThreadpoolSize();
    }

    /**
     * Get the thread pool keep alive time in seconds.
     *
     * @return the thread pool keep alive time in seconds
     */
    public static int getThreadPoolKeepAliveSeconds() {
        return CONFIG.getThreadPoolKeepAliveSeconds();
    }

    /**
     * Check if CPU-aware thread sizing is enabled.
     *
     * @return true if CPU-aware thread sizing is enabled, false otherwise
     */
    public static boolean isCpuAwareThreadSizing() {
        return CONFIG.isCpuAwareThreadSizing();
    }

    /**
     * Check if adaptive thread pool is enabled.
     *
     * @return true if adaptive thread pool is enabled, false otherwise
     */
    public static boolean isAdaptiveThreadPool() {
        return CONFIG.isAdaptiveThreadPool();
    }

    /**
     * Check if work stealing is enabled.
     *
     * @return true if work stealing is enabled, false otherwise
     */
    public static boolean isWorkStealingEnabled() {
        return CONFIG.isWorkStealingEnabled();
    }

    /**
     * Get the work stealing queue size.
     *
     * @return the work stealing queue size
     */
    public static int getWorkStealingQueueSize() {
        return CONFIG.getWorkStealingQueueSize();
    }

    /**
     * Get the CPU load threshold.
     *
     * @return the CPU load threshold
     */
    public static double getCpuLoadThreshold() {
        return CONFIG.getCpuLoadThreshold();
    }
}