package com.kneaf.core.performance.monitoring;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.ThreadPoolExecutor;

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

    /**
     * Get the current thread pool size for monitoring purposes.
     *
     * @return the current thread pool size
     */
    public static int getCurrentThreadPoolSize() {
        try {
            // In a real implementation, this would return the actual current thread pool size
            // For now, we'll return a simulated value based on configuration
            ThreadPoolExecutor executor = threadPoolManager.getExecutor();
            if (executor != null) {
                return executor.getPoolSize();
            } else {
                LOGGER.warn("Thread pool executor is not initialized, returning default size");
                return CONFIG.getMinThreadpoolSize();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to get current thread pool size", e);
            return CONFIG.getMinThreadpoolSize();
        }
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