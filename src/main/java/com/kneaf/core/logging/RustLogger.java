package com.kneaf.core.logging;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Rust Logger integration for forwarding logs from Rust to Minecraft server logging system.
 * Provides bridge between Rust native code and Java logging infrastructure.
 */
public class RustLogger {
    private static final Logger LOGGER = LogManager.getLogger("KneafMod");
    
    /**
     * Log a message received from Rust native code.
     * @param level The log level (DEBUG, INFO, WARN, ERROR, TRACE)
     * @param message The message to log
     */
    public static void logFromRust(String level, String message) {
        if (level == null || message == null) {
            return;
        }
        
        // Format the message with prefix for consistency
        String formattedMessage = "[KneafMod] " + message;
        
        switch (level.toUpperCase()) {
            case "DEBUG":
                LOGGER.debug(formattedMessage);
                break;
            case "INFO":
                LOGGER.info(formattedMessage);
                break;
            case "WARN":
                LOGGER.warn(formattedMessage);
                break;
            case "ERROR":
                LOGGER.error(formattedMessage);
                break;
            case "TRACE":
                LOGGER.trace(formattedMessage);
                break;
            default:
                LOGGER.info(formattedMessage); // Default to INFO
                break;
        }
    }
    
    /**
     * Initialize native logging system.
     * Called from Rust during initialization.
     */
    public static void initNativeLogging() {
        LOGGER.info("[KneafMod] Rust logging system initialized");
    }
    
    /**
     * Log system status information including CPU capabilities and SIMD level.
     * @param cpuCapabilities Detected CPU capabilities (AVX2, AVX-512, etc.)
     * @param simdLevel Active SIMD level
     * @param fallbackRate Fallback rate percentage
     * @param opsPerCycle Operations per cycle metric
     */
    public static void logSystemStatus(String cpuCapabilities, String simdLevel, 
                                      double fallbackRate, double opsPerCycle) {
        LOGGER.info("[KneafMod] CPU Capabilities: {}", cpuCapabilities);
        LOGGER.info("[KneafMod] SIMD Level: {}", simdLevel);
        LOGGER.info("[KneafMod] Performance: {:.2f}% fallback rate, {:.2f} ops/cycle", 
                   fallbackRate * 100.0, opsPerCycle);
    }
    
    /**
     * Log memory pool status.
     * @param usagePercentage Memory usage percentage
     * @param hitRate Cache hit rate percentage
     * @param contention Current lock contention level
     */
    public static void logMemoryPoolStatus(double usagePercentage, double hitRate, int contention) {
        LOGGER.info("[KneafMod] Memory Pool: {:.1f}% usage, {:.1f}% hit rate, {} contention", 
                   usagePercentage, hitRate, contention);
    }
    
    /**
     * Log thread pool status.
     * @param activeThreads Number of active threads
     * @param queueSize Current queue size
     * @param utilization Thread pool utilization percentage
     */
    public static void logThreadPoolStatus(int activeThreads, int queueSize, double utilization) {
        LOGGER.info("[KneafMod] Thread Pool: {} active threads, {} queue size, {:.1f}% utilization", 
                   activeThreads, queueSize, utilization);
    }
    
    /**
     * Log performance metrics.
     * @param tps Current TPS (ticks per second)
     * @param latency Current latency in milliseconds
     * @param gcEvents Number of GC events
     */
    public static void logPerformanceMetrics(double tps, double latency, long gcEvents) {
        LOGGER.info("[KneafMod] Performance Metrics: {:.2f} TPS, {:.2f}ms latency, {} GC events", 
                   tps, latency, gcEvents);
    }
    
    /**
     * Log configuration status.
     * @param extremeMode Whether extreme performance mode is active
     * @param safetyChecks Whether safety checks are enabled
     * @param tpsThreshold TPS threshold for extreme mode
     */
    public static void logConfigurationStatus(boolean extremeMode, boolean safetyChecks, double tpsThreshold) {
        LOGGER.info("[KneafMod] Extreme Performance Mode: {} (TPS threshold: {:.1f})", 
                   extremeMode ? "ACTIVE" : "INACTIVE", tpsThreshold);
        LOGGER.info("[KneafMod] Safety Checks: {}", safetyChecks ? "ENABLED" : "DISABLED");
    }
    
    /**
     * Log startup information during mod initialization.
     * @param optimizationsActive List of active optimizations
     * @param cpuInfo CPU information string
     * @param configApplied Configuration that was applied
     */
    public static void logStartupInfo(String optimizationsActive, String cpuInfo, String configApplied) {
        LOGGER.info("[KneafMod] === KNEAF MOD STARTUP ===");
        LOGGER.info("[KneafMod] Active Optimizations: {}", optimizationsActive);
        LOGGER.info("[KneafMod] CPU Info: {}", cpuInfo);
        LOGGER.info("[KneafMod] Configuration Applied: {}", configApplied);
        LOGGER.info("[KneafMod] =========================");
    }
    
    /**
     * Log real-time status updates.
     * @param systemStatus Current system status summary
     * @param importantEvents Important events that occurred
     */
    public static void logRealTimeStatus(String systemStatus, String importantEvents) {
        if (importantEvents != null && !importantEvents.isEmpty()) {
            LOGGER.info("[KneafMod] System Status: {} | Events: {}", systemStatus, importantEvents);
        } else {
            LOGGER.info("[KneafMod] System Status: {}", systemStatus);
        }
    }
    
    /**
     * Log threshold-based events for important system events.
     * @param eventType Type of event (MEMORY_USAGE, JNI_CALL, LOCK_WAIT, etc.)
     * @param message Event message
     * @param thresholdValue Threshold value that was exceeded
     * @param actualValue Actual value that triggered the event
     */
    public static void logThresholdEvent(String eventType, String message, 
                                        double thresholdValue, double actualValue) {
        LOGGER.warn("[KneafMod] THRESHOLD EVENT [{}]: {} ({} > {})", 
                   eventType, message, actualValue, thresholdValue);
    }
}