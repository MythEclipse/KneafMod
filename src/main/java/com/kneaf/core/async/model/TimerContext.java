package com.kneaf.core.async.model;

import com.kneaf.core.async.AsyncMetricsCollector;

/**
 * Timer context for auto-closable timing
 */
public class TimerContext implements AutoCloseable {
    private final AsyncMetricsCollector collector;
    private final String name;
    private final long startTime;

    public TimerContext(AsyncMetricsCollector collector, String name) {
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
