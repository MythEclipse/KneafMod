package com.kneaf.core.async;

import com.kneaf.core.performance.AtomicDouble;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

/**
 * Non-blocking metrics collector yang menggunakan lock-free data structures.
 * Semua operasi metrics dilakukan tanpa blocking thread utama.
 * 
 * Features:
 * - Lock-free counter updates menggunakan AtomicLong
 * - Lock-free gauge updates menggunakan AtomicDouble
 * - Async histogram updates menggunakan ring buffer
 * - Batch aggregation di background thread
 * - Zero allocation di hot path untuk counter/gauge
 */
public final class AsyncMetricsCollector {
    private static final int HISTOGRAM_RING_SIZE = 16384; // Must be power of 2
    private static final int TIMER_RING_SIZE = 16384;
    private static final int AGGREGATION_BATCH_SIZE = 128;
    
    // Lock-free metrics storage
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicDouble> gauges = new ConcurrentHashMap<>();
    
    // Ring buffers untuk histogram dan timer (lock-free)
    private final ConcurrentHashMap<String, HistogramRingBuffer> histograms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimerRingBuffer> timers = new ConcurrentHashMap<>();
    
    // Background aggregation
    private final ScheduledExecutorService aggregator = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "AsyncMetricsAggregator");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    );
    
    // Configuration
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger samplingRate = new AtomicInteger(100); // 100% by default
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    
    // Statistics
    private final AtomicLong totalMetricsRecorded = new AtomicLong(0);
    private final AtomicLong metricsDropped = new AtomicLong(0);
    
    /**
     * Lock-free ring buffer untuk histogram values
     */
    private static class HistogramRingBuffer {
        private final long[] values;
        private final AtomicLong writeIndex = new AtomicLong(0);
        private final int mask;
        
        HistogramRingBuffer(int size) {
            this.values = new long[size];
            this.mask = size - 1;
        }
        
        boolean offer(long value) {
            long index = writeIndex.getAndIncrement();
            values[(int) (index & mask)] = value;
            return true;
        }
        
        long[] snapshot() {
            long currentIndex = writeIndex.get();
            int size = (int) Math.min(currentIndex, values.length);
            long[] snapshot = new long[size];
            
            for (int i = 0; i < size; i++) {
                snapshot[i] = values[i];
            }
            
            return snapshot;
        }
        
        void clear() {
            writeIndex.set(0);
        }
    }
    
    /**
     * Lock-free ring buffer untuk timer values
     */
    private static class TimerRingBuffer {
        private final long[] values;
        private final AtomicLong writeIndex = new AtomicLong(0);
        private final int mask;
        
        TimerRingBuffer(int size) {
            this.values = new long[size];
            this.mask = size - 1;
        }
        
        boolean offer(long value) {
            long index = writeIndex.getAndIncrement();
            values[(int) (index & mask)] = value;
            return true;
        }
        
        long[] snapshot() {
            long currentIndex = writeIndex.get();
            int size = (int) Math.min(currentIndex, values.length);
            long[] snapshot = new long[size];
            
            for (int i = 0; i < size; i++) {
                snapshot[i] = values[i];
            }
            
            return snapshot;
        }
        
        void clear() {
            writeIndex.set(0);
        }
    }
    
    public AsyncMetricsCollector() {
        // Start background aggregation every 5 seconds
        aggregator.scheduleAtFixedRate(
            this::aggregateMetrics,
            5, 5, TimeUnit.SECONDS
        );
    }
    
    /**
     * Record counter (lock-free, zero allocation)
     */
    public void recordCounter(String name, long delta) {
        if (!enabled.get() || !shouldSample()) {
            return;
        }
        
        counters.computeIfAbsent(name, k -> new AtomicLong(0))
               .addAndGet(delta);
        totalMetricsRecorded.incrementAndGet();
    }
    
    /**
     * Increment counter (lock-free, zero allocation)
     */
    public void incrementCounter(String name) {
        recordCounter(name, 1);
    }
    
    /**
     * Record gauge (lock-free, zero allocation)
     */
    public void recordGauge(String name, double value) {
        if (!enabled.get() || !shouldSample()) {
            return;
        }
        
        gauges.computeIfAbsent(name, k -> new AtomicDouble(0.0))
              .set(value);
        totalMetricsRecorded.incrementAndGet();
    }
    
    /**
     * Record histogram value (lock-free)
     */
    public void recordHistogram(String name, long value) {
        if (!enabled.get() || !shouldSample()) {
            return;
        }
        
        HistogramRingBuffer buffer = histograms.computeIfAbsent(
            name, 
            k -> new HistogramRingBuffer(HISTOGRAM_RING_SIZE)
        );
        
        if (buffer.offer(value)) {
            totalMetricsRecorded.incrementAndGet();
        } else {
            metricsDropped.incrementAndGet();
        }
    }
    
    /**
     * Record timer value (lock-free)
     */
    public void recordTimer(String name, long durationNs) {
        if (!enabled.get() || !shouldSample()) {
            return;
        }
        
        TimerRingBuffer buffer = timers.computeIfAbsent(
            name,
            k -> new TimerRingBuffer(TIMER_RING_SIZE)
        );
        
        if (buffer.offer(durationNs)) {
            totalMetricsRecorded.incrementAndGet();
        } else {
            metricsDropped.incrementAndGet();
        }
    }
    
    /**
     * Measure dan record timer menggunakan AutoCloseable
     */
    public TimerContext startTimer(String name) {
        return new TimerContext(this, name);
    }
    
    public static class TimerContext implements AutoCloseable {
        private final AsyncMetricsCollector collector;
        private final String name;
        private final long startTime;
        
        TimerContext(AsyncMetricsCollector collector, String name) {
            this.collector = collector;
            this.name = name;
            this.startTime = System.nanoTime();
        }
        
        @Override
        public void close() {
            long duration = System.nanoTime() - startTime;
            collector.recordTimer(name, duration);
        }
    }
    
    /**
     * Get counter value
     */
    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }
    
    /**
     * Get gauge value
     */
    public double getGauge(String name) {
        AtomicDouble gauge = gauges.get(name);
        return gauge != null ? gauge.get() : 0.0;
    }
    
    /**
     * Get histogram statistics
     */
    public HistogramStats getHistogramStats(String name) {
        HistogramRingBuffer buffer = histograms.get(name);
        if (buffer == null) {
            return new HistogramStats(0, 0, 0, 0, 0, 0);
        }
        
        long[] values = buffer.snapshot();
        if (values.length == 0) {
            return new HistogramStats(0, 0, 0, 0, 0, 0);
        }
        
        Arrays.sort(values);
        
        long sum = 0;
        long min = values[0];
        long max = values[values.length - 1];
        
        for (long value : values) {
            sum += value;
        }
        
        long avg = sum / values.length;
        long p50 = values[values.length / 2];
        long p95 = values[(int) (values.length * 0.95)];
        
        return new HistogramStats(values.length, min, max, avg, p50, p95);
    }
    
    public static class HistogramStats {
        public final long count;
        public final long min;
        public final long max;
        public final long avg;
        public final long p50;
        public final long p95;
        
        public HistogramStats(long count, long min, long max, long avg, long p50, long p95) {
            this.count = count;
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
        }
    }
    
    /**
     * Get timer statistics (in nanoseconds)
     */
    public TimerStats getTimerStats(String name) {
        TimerRingBuffer buffer = timers.get(name);
        if (buffer == null) {
            return new TimerStats(0, 0, 0, 0, 0, 0);
        }
        
        long[] values = buffer.snapshot();
        if (values.length == 0) {
            return new TimerStats(0, 0, 0, 0, 0, 0);
        }
        
        Arrays.sort(values);
        
        long sum = 0;
        long min = values[0];
        long max = values[values.length - 1];
        
        for (long value : values) {
            sum += value;
        }
        
        long avg = sum / values.length;
        long p50 = values[values.length / 2];
        long p95 = values[(int) (values.length * 0.95)];
        
        return new TimerStats(values.length, min, max, avg, p50, p95);
    }
    
    public static class TimerStats {
        public final long count;
        public final long minNs;
        public final long maxNs;
        public final long avgNs;
        public final long p50Ns;
        public final long p95Ns;
        
        public TimerStats(long count, long min, long max, long avg, long p50, long p95) {
            this.count = count;
            this.minNs = min;
            this.maxNs = max;
            this.avgNs = avg;
            this.p50Ns = p50;
            this.p95Ns = p95;
        }
        
        public double getAvgMs() {
            return avgNs / 1_000_000.0;
        }
        
        public double getP95Ms() {
            return p95Ns / 1_000_000.0;
        }
    }
    
    /**
     * Background aggregation task
     */
    private void aggregateMetrics() {
        // This can be used untuk periodic cleanup, reporting, etc.
        // Currently just a placeholder
    }
    
    /**
     * Check apakah metric harus di-sample
     */
    private boolean shouldSample() {
        int rate = samplingRate.get();
        if (rate >= 100) {
            return true;
        }
        if (rate <= 0) {
            return false;
        }
        return random.nextInt(100) < rate;
    }
    
    /**
     * Set sampling rate (0-100)
     */
    public void setSamplingRate(int rate) {
        samplingRate.set(Math.max(0, Math.min(100, rate)));
    }
    
    /**
     * Enable/disable metrics collection
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }
    
    /**
     * Get statistics
     */
    public CollectorStatistics getStatistics() {
        return new CollectorStatistics(
            totalMetricsRecorded.get(),
            metricsDropped.get(),
            counters.size(),
            gauges.size(),
            histograms.size(),
            timers.size()
        );
    }
    
    public static class CollectorStatistics {
        public final long totalRecorded;
        public final long totalDropped;
        public final int counterCount;
        public final int gaugeCount;
        public final int histogramCount;
        public final int timerCount;
        
        public CollectorStatistics(long recorded, long dropped, int counters, 
                                 int gauges, int histograms, int timers) {
            this.totalRecorded = recorded;
            this.totalDropped = dropped;
            this.counterCount = counters;
            this.gaugeCount = gauges;
            this.histogramCount = histograms;
            this.timerCount = timers;
        }
    }
    
    /**
     * Shutdown async metrics collector
     */
    public void shutdown() {
        aggregator.shutdown();
        try {
            aggregator.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Clear all metrics
     */
    public void clear() {
        counters.clear();
        gauges.clear();
        histograms.values().forEach(HistogramRingBuffer::clear);
        timers.values().forEach(TimerRingBuffer::clear);
    }
}
