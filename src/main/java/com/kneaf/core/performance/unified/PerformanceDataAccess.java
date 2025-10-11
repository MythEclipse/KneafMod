package com.kneaf.core.performance.unified;

import java.util.List;
import java.util.Map;
import com.kneaf.core.performance.core.MobProcessResult;

/**
 * Clean API interface for accessing performance data across the system.
 * Provides a unified view of performance metrics regardless of implementation.
 */
public interface PerformanceDataAccess {
    /**
     * Get current TPS (Ticks Per Second).
     * @return current TPS value
     */
    double getCurrentTPS();

    /**
     * Get rolling average TPS over configured window.
     * @return rolling average TPS
     */
    double getAverageTPS();

    /**
     * Get last tick duration in milliseconds.
     * @return last tick duration ms
     */
    long getLastTickDurationMs();

    /**
     * Get total JNI calls recorded.
     * @return total JNI calls
     */
    long getTotalJniCalls();

    /**
     * Get total JNI call duration in milliseconds.
     * @return total JNI call duration ms
     */
    long getTotalJniCallDurationMs();

    /**
     * Get maximum JNI call duration in milliseconds.
     * @return max JNI call duration ms
     */
    long getMaxJniCallDurationMs();

    /**
     * Get JNI call type metrics.
     * @return map of call types to counts
     */
    Map<String, Long> getJniCallTypeMetrics();

    /**
     * Get total lock waits recorded.
     * @return total lock waits
     */
    long getTotalLockWaits();

    /**
     * Get total lock wait time in milliseconds.
     * @return total lock wait time ms
     */
    long getTotalLockWaitTimeMs();

    /**
     * Get maximum lock wait time in milliseconds.
     * @return max lock wait time ms
     */
    long getMaxLockWaitTimeMs();

    /**
     * Get current lock contention count.
     * @return current lock contention
     */
    int getCurrentLockContention();

    /**
     * Get lock wait type metrics.
     * @return map of lock types to wait times
     */
    Map<String, Long> getLockWaitTypeMetrics();

    /**
     * Get total heap bytes.
     * @return total heap bytes
     */
    long getTotalHeapBytes();

    /**
     * Get used heap bytes.
     * @return used heap bytes
     */
    long getUsedHeapBytes();

    /**
     * Get free heap bytes.
     * @return free heap bytes
     */
    long getFreeHeapBytes();

    /**
     * Get peak heap usage bytes.
     * @return peak heap usage bytes
     */
    long getPeakHeapUsageBytes();

    /**
     * Get GC count.
     * @return GC count
     */
    long getGcCount();

    /**
     * Get total GC time in milliseconds.
     * @return total GC time ms
     */
    long getGcTimeMs();

    /**
     * Get total allocations.
     * @return total allocations
     */
    long getTotalAllocations();

    /**
     * Get total allocation bytes.
     * @return total allocation bytes
     */
    long getTotalAllocationBytes();

    /**
     * Get maximum allocation size in bytes.
     * @return max allocation size bytes
     */
    long getMaxAllocationSizeBytes();

    /**
     * Get total deallocations.
     * @return total deallocations
     */
    long getTotalDeallocations();

    /**
     * Get average allocation latency in nanoseconds.
     * @return average allocation latency ns
     */
    double getAverageAllocationLatencyNs();

    /**
     * Get allocation type metrics.
     * @return map of allocation types to counts
     */
    Map<String, Long> getAllocationTypeMetrics();

    /**
     * Get allocation pressure events count.
     * @return allocation pressure events
     */
    long getAllocationPressureEvents();

    /**
     * Get high allocation latency events count.
     * @return high allocation latency events
     */
    long getHighAllocationLatencyEvents();

    /**
     * Get total lock contention events.
     * @return total contention events
     */
    long getTotalContentionEvents();

    /**
     * Get total contention wait time in milliseconds.
     * @return total contention wait time ms
     */
    long getTotalContentionWaitTimeMs();

    /**
     * Get maximum queue length during contention.
     * @return max queue length
     */
    int getMaxQueueLength();

    /**
     * Get lock contention type metrics.
     * @return map of contention types to counts
     */
    Map<String, Integer> getLockContentionTypeMetrics();

    /**
     * Get call graph metrics.
     * @return call graph metrics
     */
    Map<String, Object> getCallGraphMetrics();

    /**
     * Get threshold alerts.
     * @return list of threshold alerts
     */
    List<String> getThresholdAlerts();

    /**
     * Get entities to tick from Rust performance system.
     * @param entities entity data list
     * @param players player data list
     * @return list of entity IDs to tick
     */
    List<Long> getEntitiesToTick(List<?> entities, List<?> players);

    /**
     * Get block entities to tick from Rust performance system.
     * @param blockEntities block entity data list
     * @return list of block entity IDs to tick
     */
    List<Long> getBlockEntitiesToTick(List<?> blockEntities);

    /**
     * Process mob AI through Rust performance system.
     * @param mobs mob data list
     * @return mob process result
     */
    MobProcessResult processMobAI(List<?> mobs);

    /**
     * Clear all threshold alerts.
     */
    void clearThresholdAlerts();

    /**
     * Add threshold alert.
     * @param alert alert message
     */
    void addThresholdAlert(String alert);

    /**
     * Record JNI call with duration.
     * @param callType type of JNI call
     * @param durationMs duration in milliseconds
     */
    void recordJniCall(String callType, long durationMs);

    /**
     * Record simple JNI call without specific type.
     */
    void recordJniCall();

    /**
     * Record lock wait event.
     * @param lockName name of lock
     * @param durationMs duration in milliseconds
     */
    void recordLockWait(String lockName, long durationMs);

    /**
     * Record lock contention event.
     * @param lockName name of lock
     * @param queueLength queue length
     * @param durationMs duration in milliseconds
     */
    void recordLockContention(String lockName, int queueLength, long durationMs);

    /**
     * Record lock resolved event.
     */
    void recordLockResolved();

    /**
     * Record memory usage.
     * @param totalBytes total heap bytes
     * @param usedBytes used heap bytes
     * @param freeBytes free heap bytes
     */
    void recordMemoryUsage(long totalBytes, long usedBytes, long freeBytes);

    /**
     * Record GC event.
     * @param durationMs duration in milliseconds
     */
    void recordGcEvent(long durationMs);

    /**
     * Check if performance monitoring is enabled.
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Set performance monitoring enabled state.
     * @param enabled enabled state
     */
    void setEnabled(boolean enabled);

    /**
     * Get current monitoring configuration.
     * @return monitoring configuration
     */
    MonitoringConfig getMonitoringConfig();

    /**
     * Update monitoring configuration.
     * @param config new monitoring configuration
     */
    void updateMonitoringConfig(MonitoringConfig config);

    /**
     * Get current monitoring level.
     * @return monitoring level
     */
    MonitoringLevel getMonitoringLevel();

    /**
     * Set monitoring level.
     * @param level monitoring level
     */
    void setMonitoringLevel(MonitoringLevel level);

    /**
     * Get performance metrics as a formatted string.
     * @return formatted metrics string
     */
    String getFormattedMetrics();

    /**
     * Get performance status as a formatted string.
     * @return formatted status string
     */
    String getFormattedStatus();

    /**
     * Reset all performance counters.
     */
    void resetCounters();

    /**
     * Rotate performance log.
     */
    void rotateLog();

    /**
     * Log a performance line.
     * @param line line to log
     */
    void logLine(String line);

    /**
     * Get performance metrics as JSON string.
     * @return JSON string of metrics
     */
    String getMetricsAsJson();

    /**
     * Get performance status as JSON string.
     * @return JSON string of status
     */
    String getStatusAsJson();
}