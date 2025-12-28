/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.io;

import net.minecraft.world.level.ChunkPos;

/**
 * Immutable task for chunk prefetching with priority support.
 */
public final class PrefetchTask implements Comparable<PrefetchTask> {
    private final ChunkPos pos;
    private final int priority; // Higher = more important
    private final long scheduledTime;
    private volatile boolean cancelled;

    public PrefetchTask(ChunkPos pos, int priority) {
        this.pos = pos;
        this.priority = priority;
        this.scheduledTime = System.currentTimeMillis();
        this.cancelled = false;
    }

    public ChunkPos getPos() {
        return pos;
    }

    public int getPriority() {
        return priority;
    }

    public long getScheduledTime() {
        return scheduledTime;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Age of task in milliseconds.
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - scheduledTime;
    }

    /**
     * Compare by priority (higher first), then by age (older first).
     */
    @Override
    public int compareTo(PrefetchTask other) {
        // Higher priority first
        int priorityCompare = Integer.compare(other.priority, this.priority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        // Older tasks first (FIFO for same priority)
        return Long.compare(this.scheduledTime, other.scheduledTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof PrefetchTask))
            return false;
        PrefetchTask other = (PrefetchTask) obj;
        return pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }

    @Override
    public String toString() {
        return String.format("PrefetchTask{pos=%s, priority=%d, age=%dms, cancelled=%b}",
                pos, priority, getAgeMs(), cancelled);
    }
}
