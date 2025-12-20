package com.kneaf.core.async.model;

/**
 * Statistics for metrics collector
 */
public class CollectorStatistics {
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
