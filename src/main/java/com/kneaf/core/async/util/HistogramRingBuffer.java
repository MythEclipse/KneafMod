package com.kneaf.core.async.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free ring buffer for histogram values
 */
public class HistogramRingBuffer {
    private final long[] values;
    private final AtomicLong writeIndex = new AtomicLong(0);
    private final int mask;

    public HistogramRingBuffer(int size) {
        this.values = new long[size];
        this.mask = size - 1;
    }

    public boolean offer(long value) {
        long index = writeIndex.getAndIncrement();
        values[(int) (index & mask)] = value;
        return true;
    }

    public long[] snapshot() {
        long currentIndex = writeIndex.get();
        int size = (int) Math.min(currentIndex, values.length);
        long[] snapshot = new long[size];

        for (int i = 0; i < size; i++) {
            snapshot[i] = values[i];
        }

        return snapshot;
    }

    public void clear() {
        writeIndex.set(0);
    }
}
