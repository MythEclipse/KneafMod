package com.kneaf.core.async.model;

/**
 * Statistics for timer
 */
public class TimerStats {
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
