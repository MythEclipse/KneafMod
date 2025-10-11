package com.kneaf.core.performance.unified;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Thread-safe metrics collector for performance monitoring.
 * Handles collection and aggregation of various performance metrics.
 */
public class MetricsCollector {
    // JNI call metrics
    private final AtomicLong totalJniCalls = new AtomicLong(0);
    private final AtomicLong totalJniCallDurationMs = new AtomicLong(0);
    private final AtomicLong maxJniCallDurationMs = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> jniCallTypes = new ConcurrentHashMap<>();

    // Lock wait metrics
    private final AtomicLong totalLockWaits = new AtomicLong(0);
    private final AtomicLong totalLockWaitTimeMs = new AtomicLong(0);
    private final AtomicLong maxLockWaitTimeMs = new AtomicLong(0);
    private final AtomicInteger currentLockContention = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicLong> lockWaitTypes = new ConcurrentHashMap<>();

    // Memory metrics
    private final AtomicLong totalHeapBytes = new AtomicLong(0);
    private final AtomicLong usedHeapBytes = new AtomicLong(0);
    private final AtomicLong freeHeapBytes = new AtomicLong(0);
    private final AtomicLong peakHeapUsageBytes = new AtomicLong(0);
    private final AtomicLong gcCount = new AtomicLong(0);
    private final AtomicLong gcTimeMs = new AtomicLong(0);

    // Allocation metrics
    private final AtomicLong totalAllocations = new AtomicLong(0);
    private final AtomicLong totalAllocationBytes = new AtomicLong(0);
    private final AtomicLong maxAllocationSizeBytes = new AtomicLong(0);
    private final AtomicLong totalDeallocations = new AtomicLong(0);
    private final AtomicLong allocationLatencyNanos = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> allocationTypes = new ConcurrentHashMap<>();
    private final AtomicLong allocationPressureEvents = new AtomicLong(0);
    private final AtomicLong highAllocationLatencyEvents = new AtomicLong(0);

    // Lock contention metrics
    private final AtomicLong lockContentionEvents = new AtomicLong(0);
    private final AtomicLong totalContentionWaitTimeMs = new AtomicLong(0);
    private final AtomicInteger maxQueueLength = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> lockContentionTypes = new ConcurrentHashMap<>();

    // Threshold alerts
    private final List<String> thresholdAlerts = Collections.synchronizedList(new ArrayList<>());

    private final Object lock = new Object();

    /**
     * Record a JNI call with duration tracking.
     * @param callType type of JNI call
     * @param durationMs duration in milliseconds
     */
    public void recordJniCall(String callType, long durationMs) {
        totalJniCalls.incrementAndGet();
        totalJniCallDurationMs.addAndGet(durationMs);
        
        long currentMax = maxJniCallDurationMs.get();
        if (durationMs > currentMax) {
            maxJniCallDurationMs.compareAndSet(currentMax, durationMs);
        }
        
        jniCallTypes.computeIfAbsent(callType, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Record a simple JNI call without specific type.
     */
    public void recordJniCall() {
        recordJniCall("unspecified", 0);
    }

    /**
     * Record a lock wait event.
     * @param lockName name of the lock
     * @param durationMs duration in milliseconds
     */
    public void recordLockWait(String lockName, long durationMs) {
        totalLockWaits.incrementAndGet();
        totalLockWaitTimeMs.addAndGet(durationMs);
        
        long currentMax = maxLockWaitTimeMs.get();
        if (durationMs > currentMax) {
            maxLockWaitTimeMs.compareAndSet(currentMax, durationMs);
        }
        
        lockWaitTypes.computeIfAbsent(lockName, k -> new AtomicLong(0)).addAndGet(durationMs);
        currentLockContention.incrementAndGet();
    }

    /**
     * Record a lock contention event.
     * @param lockName name of the lock
     * @param queueLength queue length during contention
     * @param durationMs duration in milliseconds
     */
    public void recordLockContention(String lockName, int queueLength, long durationMs) {
        lockContentionEvents.incrementAndGet();
        totalContentionWaitTimeMs.addAndGet(durationMs);
        lockContentionTypes.computeIfAbsent(lockName, k -> new AtomicInteger(0)).addAndGet(queueLength);
        
        int currentMax = maxQueueLength.get();
        if (queueLength > currentMax) {
            maxQueueLength.compareAndSet(currentMax, queueLength);
        }
    }

    /**
     * Record lock resolution (decrement contention counter).
     */
    public void recordLockResolved() {
        currentLockContention.updateAndGet(count -> Math.max(0, count - 1));
    }

    /**
     * Record memory usage statistics.
     * @param totalBytes total heap bytes
     * @param usedBytes used heap bytes
     * @param freeBytes free heap bytes
     */
    public void recordMemoryUsage(long totalBytes, long usedBytes, long freeBytes) {
        totalHeapBytes.set(totalBytes);
        usedHeapBytes.set(usedBytes);
        freeHeapBytes.set(freeBytes);
        
        long currentPeak = peakHeapUsageBytes.get();
        if (usedBytes > currentPeak) {
            peakHeapUsageBytes.compareAndSet(currentPeak, usedBytes);
        }
    }

    /**
     * Record GC event.
     * @param durationMs duration in milliseconds
     */
    public void recordGcEvent(long durationMs) {
        gcCount.incrementAndGet();
        gcTimeMs.addAndGet(durationMs);
    }

    /**
     * Record allocation event.
     * @param sizeBytes size in bytes
     * @param latencyNanos latency in nanoseconds
     * @param allocationType type of allocation
     */
    public void recordAllocation(long sizeBytes, long latencyNanos, String allocationType) {
        totalAllocations.incrementAndGet();
        totalAllocationBytes.addAndGet(sizeBytes);
        
        long currentMax = maxAllocationSizeBytes.get();
        if (sizeBytes > currentMax) {
            maxAllocationSizeBytes.compareAndSet(currentMax, sizeBytes);
        }
        
        allocationLatencyNanos.addAndGet(latencyNanos);
        allocationTypes.computeIfAbsent(allocationType, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Record deallocation event.
     * @param allocationType type of allocation being deallocated
     */
    public void recordDeallocation(String allocationType) {
        totalDeallocations.incrementAndGet();
        allocationTypes.computeIfPresent(allocationType, (k, v) -> {
            if (v.get() > 0) {
                v.decrementAndGet();
            }
            return v;
        });
    }

    /**
     * Record allocation pressure event.
     */
    public void recordAllocationPressureEvent() {
        allocationPressureEvents.incrementAndGet();
    }

    /**
     * Record high allocation latency event.
     */
    public void recordHighAllocationLatencyEvent() {
        highAllocationLatencyEvents.incrementAndGet();
    }

    /**
     * Add a threshold alert.
     * @param alert alert message
     */
    public void addThresholdAlert(String alert) {
        if (alert == null || alert.isBlank()) return;
        synchronized (lock) {
            thresholdAlerts.add(alert);
        }
    }

    /**
     * Clear all threshold alerts.
     */
    public void clearThresholdAlerts() {
        synchronized (lock) {
            thresholdAlerts.clear();
        }
    }

    /**
     * Get JNI call metrics.
     * @return map of JNI call metrics
     */
    public Map<String, Object> getJniCallMetrics() {
        return Map.of(
            "totalCalls", totalJniCalls.get(),
            "totalDurationMs", totalJniCallDurationMs.get(),
            "maxDurationMs", maxJniCallDurationMs.get(),
            "callTypes", Collections.unmodifiableMap(jniCallTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())))
        );
    }

    /**
     * Get lock wait metrics.
     * @return map of lock wait metrics
     */
    public Map<String, Object> getLockWaitMetrics() {
        return Map.of(
            "totalWaits", totalLockWaits.get(),
            "totalWaitTimeMs", totalLockWaitTimeMs.get(),
            "maxWaitTimeMs", maxLockWaitTimeMs.get(),
            "currentContention", currentLockContention.get(),
            "lockWaitTypes", Collections.unmodifiableMap(lockWaitTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())))
        );
    }

    /**
     * Get memory metrics.
     * @return map of memory metrics
     */
    public Map<String, Object> getMemoryMetrics() {
        return Map.of(
            "totalHeapBytes", totalHeapBytes.get(),
            "usedHeapBytes", usedHeapBytes.get(),
            "freeHeapBytes", freeHeapBytes.get(),
            "peakHeapBytes", peakHeapUsageBytes.get(),
            "gcCount", gcCount.get(),
            "gcTimeMs", gcTimeMs.get()
        );
    }

    /**
     * Get allocation metrics.
     * @return map of allocation metrics
     */
    public Map<String, Object> getAllocationMetrics() {
        double avgLatency = totalAllocations.get() > 0 
            ? allocationLatencyNanos.get() / (double) totalAllocations.get() 
            : 0.0;
        
        return Map.of(
            "totalAllocations", totalAllocations.get(),
            "totalAllocationBytes", totalAllocationBytes.get(),
            "maxAllocationSizeBytes", maxAllocationSizeBytes.get(),
            "totalDeallocations", totalDeallocations.get(),
            "avgAllocationLatencyNs", avgLatency,
            "allocationTypes", Collections.unmodifiableMap(allocationTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()))),
            "allocationPressureEvents", allocationPressureEvents.get(),
            "highAllocationLatencyEvents", highAllocationLatencyEvents.get()
        );
    }

    /**
     * Get lock contention metrics.
     * @return map of lock contention metrics
     */
    public Map<String, Object> getLockContentionMetrics() {
        return Map.of(
            "totalContentionEvents", lockContentionEvents.get(),
            "totalContentionWaitTimeMs", totalContentionWaitTimeMs.get(),
            "maxQueueLength", maxQueueLength.get(),
            "lockContentionTypes", Collections.unmodifiableMap(lockContentionTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())))
        );
    }

    /**
     * Get threshold alerts.
     * @return unmodifiable list of threshold alerts
     */
    public List<String> getThresholdAlerts() {
        synchronized (lock) {
            return new ArrayList<>(thresholdAlerts);
        }
    }

    /**
     * Reset all metrics counters.
     */
    public void resetCounters() {
        totalJniCalls.set(0);
        totalJniCallDurationMs.set(0);
        maxJniCallDurationMs.set(0);
        jniCallTypes.clear();
        
        totalLockWaits.set(0);
        totalLockWaitTimeMs.set(0);
        maxLockWaitTimeMs.set(0);
        currentLockContention.set(0);
        lockWaitTypes.clear();
        
        totalHeapBytes.set(0);
        usedHeapBytes.set(0);
        freeHeapBytes.set(0);
        peakHeapUsageBytes.set(0);
        gcCount.set(0);
        gcTimeMs.set(0);
        
        totalAllocations.set(0);
        totalAllocationBytes.set(0);
        maxAllocationSizeBytes.set(0);
        totalDeallocations.set(0);
        allocationLatencyNanos.set(0);
        allocationTypes.clear();
        allocationPressureEvents.set(0);
        highAllocationLatencyEvents.set(0);
        
        lockContentionEvents.set(0);
        totalContentionWaitTimeMs.set(0);
        maxQueueLength.set(0);
        lockContentionTypes.clear();
        
        clearThresholdAlerts();
    }
}