package com.kneaf.core.performance.logging;

import com.kneaf.core.performance.monitoring.PerformanceConfig;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for common performance logging patterns
 */
public final class PerformanceLoggerUtils {
    private static final Logger LOGGER = PerformanceConfig.getLogger();

    private PerformanceLoggerUtils() {
        // Prevent instantiation
    }

    /**
     * Log a slow tick warning with standard formatting
     * @param component Name of the component reporting the slow tick
     * @param tickMs Duration of the slow tick in milliseconds
     * @param thresholdMs Warning threshold in milliseconds
     */
    public static void logSlowTickWarning(String component, long tickMs, long thresholdMs) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "[PERF_WARNING] Slow {0} tick detected: {1}ms (threshold: {2}ms)",
                    new Object[]{component, tickMs, thresholdMs});
        }
    }

    /**
     * Log optimization summary with standard formatting
     * @param component Name of the component reporting optimization
     * @param operation Name of the optimized operation
     * @param itemsProcessed Number of items processed
     * @param durationMs Duration of the operation in milliseconds
     * @param improvement Percentage improvement (optional, may be null)
     */
    public static void logOptimizationSummary(String component, String operation, 
                                             int itemsProcessed, long durationMs, 
                                             Double improvement) {
        if (LOGGER.isLoggable(Level.INFO)) {
            StringBuilder message = new StringBuilder()
                    .append("[PERF_SUMMARY] ")
                    .append(component)
                    .append(" optimization summary - ")
                    .append(operation)
                    .append(": processed ")
                    .append(itemsProcessed)
                    .append(" items in ")
                    .append(durationMs)
                    .append("ms");

            if (improvement != null) {
                message.append(" (").append(improvement).append("% improvement)");
            }

            LOGGER.log(Level.INFO, message.toString());
        }
    }

    /**
     * Log threshold crossing alert with standard formatting
     * @param component Name of the component reporting the threshold crossing
     * @param metricName Name of the metric that crossed threshold
     * @param value Current value of the metric
     * @param threshold Threshold value
     */
    public static void logThresholdAlert(String component, String metricName, 
                                         double value, double threshold) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "[PERF_ALERT] {0} threshold crossed: {1} = {2} (exceeded {3})",
                    new Object[]{component, metricName, value, threshold});
        }
    }

    /**
     * Log memory pressure warning with standard formatting
     * @param component Name of the component reporting memory pressure
     * @param pressureLevel Current memory pressure level (0-100)
     * @param warningThreshold Warning threshold (0-100)
     */
    public static void logMemoryPressureWarning(String component, int pressureLevel, int warningThreshold) {
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "[MEM_WARNING] {0} memory pressure: {1}% (warning threshold: {2}%)",
                    new Object[]{component, pressureLevel, warningThreshold});
        }
    }

    /**
     * Check if logging is enabled for a specific level
     * @param level Log level to check
     * @return true if logging is enabled for the specified level
     */
    public static boolean isLoggingEnabled(Level level) {
        return LOGGER.isLoggable(level);
    }
}