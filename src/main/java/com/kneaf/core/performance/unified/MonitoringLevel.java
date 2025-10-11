package com.kneaf.core.performance.unified;

import java.util.Arrays;
import java.util.Optional;

/**
 * Enum representing different monitoring levels for performance tracking.
 */
public enum MonitoringLevel {
    /** No monitoring - minimal overhead */
    OFF(0, "Off", false, false, false, false),
    
    /** Basic monitoring - essential metrics only */
    BASIC(1, "Basic", true, false, false, false),
    
    /** Normal monitoring - standard metrics and alerts */
    NORMAL(2, "Normal", true, true, true, false),
    
    /** Detailed monitoring - comprehensive metrics and debugging */
    DETAILED(3, "Detailed", true, true, true, true),
    
    /** Aggressive monitoring - full instrumentation for performance analysis */
    AGGRESSIVE(4, "Aggressive", true, true, true, true);

    private final int level;
    private final String displayName;
    private final boolean enableMetrics;
    private final boolean enableAlerts;
    private final boolean enableProfiling;
    private final boolean enableDebugLogging;

    MonitoringLevel(int level, String displayName, boolean enableMetrics, boolean enableAlerts, 
                   boolean enableProfiling, boolean enableDebugLogging) {
        this.level = level;
        this.displayName = displayName;
        this.enableMetrics = enableMetrics;
        this.enableAlerts = enableAlerts;
        this.enableProfiling = enableProfiling;
        this.enableDebugLogging = enableDebugLogging;
    }

    /**
     * Get the numerical level value.
     * @return level value
     */
    public int getLevel() {
        return level;
    }

    /**
     * Get the display name for the monitoring level.
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if metrics collection is enabled at this level.
     * @return true if metrics are enabled
     */
    public boolean isMetricsEnabled() {
        return enableMetrics;
    }

    /**
     * Check if alerts are enabled at this level.
     * @return true if alerts are enabled
     */
    public boolean isAlertsEnabled() {
        return enableAlerts;
    }

    /**
     * Check if profiling is enabled at this level.
     * @return true if profiling is enabled
     */
    public boolean isProfilingEnabled() {
        return enableProfiling;
    }

    /**
     * Check if debug logging is enabled at this level.
     * @return true if debug logging is enabled
     */
    public boolean isDebugLoggingEnabled() {
        return enableDebugLogging;
    }

    /**
     * Get the monitoring level by its numerical value.
     * @param level the numerical level
     * @return Optional containing the MonitoringLevel or empty if not found
     */
    public static Optional<MonitoringLevel> fromLevel(int level) {
        return Arrays.stream(values())
                .filter(l -> l.level == level)
                .findFirst();
    }

    /**
     * Get the monitoring level by its display name (case-insensitive).
     * @param displayName the display name
     * @return Optional containing the MonitoringLevel or empty if not found
     */
    public static Optional<MonitoringLevel> fromDisplayName(String displayName) {
        return Arrays.stream(values())
                .filter(l -> l.displayName.equalsIgnoreCase(displayName))
                .findFirst();
    }

    /**
     * Get the highest level between two monitoring levels.
     * @param level1 first level
     * @param level2 second level
     * @return the higher level
     */
    public static MonitoringLevel max(MonitoringLevel level1, MonitoringLevel level2) {
        return level1.level > level2.level ? level1 : level2;
    }

    /**
     * Get the lowest level between two monitoring levels.
     * @param level1 first level
     * @param level2 second level
     * @return the lower level
     */
    public static MonitoringLevel min(MonitoringLevel level1, MonitoringLevel level2) {
        return level1.level < level2.level ? level1 : level2;
    }
}