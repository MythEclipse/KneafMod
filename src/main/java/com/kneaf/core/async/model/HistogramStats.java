package com.kneaf.core.async.model;

/**
 * Statistics for histogram
 */
public class HistogramStats {
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
