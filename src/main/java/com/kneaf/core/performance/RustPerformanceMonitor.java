package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors Rust performance metrics and provides statistics.
 */
public class RustPerformanceMonitor {
    // Performance monitoring state
    private static final AtomicLong totalTicksProcessed = new AtomicLong(0);
    
    /**
     * Get current TPS (Ticks Per Second) with enhanced accuracy.
     */
    public static double getCurrentTPS() {
        return RustPerformanceBase.safeNativeDoubleCall(
            () -> {
                double tps = nativeGetCurrentTPS();
                totalTicksProcessed.incrementAndGet();
                return tps;
            },
            RustPerformanceError.TPS_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get CPU usage statistics with detailed breakdown.
     */
    public static String getCpuStats() {
        return RustPerformanceBase.safeNativeStringCall(
            RustPerformanceMonitor::nativeGetCpuStats,
            RustPerformanceError.CPU_STATS_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get memory usage statistics with detailed breakdown.
     */
    public static String getMemoryStats() {
        return RustPerformanceBase.safeNativeStringCall(
            RustPerformanceMonitor::nativeGetMemoryStats,
            RustPerformanceError.MEMORY_STATS_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total entities processed since startup.
     */
    public static long getTotalEntitiesProcessed() {
        return RustPerformanceBase.safeNativeLongCall(
            RustPerformanceMonitor::nativeGetTotalEntitiesProcessed,
            RustPerformanceError.ENTITY_COUNT_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total mobs processed since startup.
     */
    public static long getTotalMobsProcessed() {
        return RustPerformanceBase.safeNativeLongCall(
            RustPerformanceMonitor::nativeGetTotalMobsProcessed,
            RustPerformanceError.MOB_COUNT_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total blocks processed since startup.
     */
    public static long getTotalBlocksProcessed() {
        return RustPerformanceBase.safeNativeLongCall(
            RustPerformanceMonitor::nativeGetTotalBlocksProcessed,
            RustPerformanceError.BLOCK_COUNT_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total entities merged since startup.
     */
    public static long getTotalMerged() {
        return RustPerformanceBase.safeNativeLongCall(
            RustPerformanceMonitor::nativeGetTotalMerged,
            RustPerformanceError.ENTITY_COUNT_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total entities despawned since startup.
     */
    public static long getTotalDespawned() {
        return RustPerformanceBase.safeNativeLongCall(
            RustPerformanceMonitor::nativeGetTotalDespawned,
            RustPerformanceError.ENTITY_COUNT_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Log startup information with performance optimizations.
     */
    public static void logStartupInfo(String optimizationsActive, String cpuInfo, String configApplied) {
        RustPerformanceBase.safeNativeVoidCall(
            () -> nativeLogStartupInfo(optimizationsActive, cpuInfo, configApplied),
            RustPerformanceError.LOG_STARTUP_ERROR,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Get total ticks processed since startup.
     */
    public static long getTotalTicksProcessed() {
        return totalTicksProcessed.get();
    }
    
    /**
     * Reset performance counters (for testing).
     */
    public static void resetCounters() {
        RustPerformanceBase.safeNativeVoidCall(
            RustPerformanceMonitor::nativeResetCounters,
            RustPerformanceError.COUNTER_RESET_ERROR,
            RustPerformanceLoader::isInitialized
        );
        totalTicksProcessed.set(0);
    }
    
    /**
     * Optimize memory usage through native Rust optimizations.
     * This triggers garbage collection hints and memory defragmentation in Rust.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeMemory() {
        return RustPerformanceBase.safeNativeBooleanCall(
            RustPerformanceMonitor::nativeOptimizeMemory,
            RustPerformanceError.MEMORY_OPTIMIZATION_FAILED,
            RustPerformanceLoader::isInitialized
        );
    }
    
    /**
     * Optimize chunk processing and loading through native Rust optimizations.
     * This includes chunk caching optimizations and processing pipeline improvements.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeChunks() {
        return RustPerformanceBase.safeNativeBooleanCall(
            RustPerformanceMonitor::nativeOptimizeChunks,
            RustPerformanceError.CHUNK_OPTIMIZATION_FAILED,
            RustPerformanceLoader::isInitialized
        );
    }
    
    // Native method declarations
    private static native double nativeGetCurrentTPS();
    private static native String nativeGetCpuStats();
    private static native String nativeGetMemoryStats();
    private static native long nativeGetTotalEntitiesProcessed();
    private static native long nativeGetTotalMobsProcessed();
    private static native long nativeGetTotalBlocksProcessed();
    private static native long nativeGetTotalMerged();
    private static native long nativeGetTotalDespawned();
    private static native void nativeLogStartupInfo(String optimizationsActive, String cpuInfo, String configApplied);
    private static native void nativeResetCounters();
    private static native boolean nativeOptimizeMemory();
    private static native boolean nativeOptimizeChunks();
}