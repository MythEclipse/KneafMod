/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

/**
 * Centralized TPS tracking utility used by various mixins.
 * This is separate from mixin classes to allow static method access.
 */
public final class TPSTracker {

    private TPSTracker() {
        // Utility class
    }

    // TPS tracking state
    private static volatile double currentTPS = 20.0;

    // Counters
    private static long totalTickTime = 0;
    private static long tickCount = 0;

    /**
     * Get current estimated TPS.
     */
    public static double getCurrentTPS() {
        return currentTPS;
    }

    /**
     * Record a tick duration and update TPS calculation.
     * Should be called at the end of each server tick.
     * 
     * @param tickMs duration of the tick in milliseconds
     */
    public static void recordTick(long tickMs) {
        tickCount++;
        totalTickTime += tickMs;

        // Calculate rolling TPS every 20 ticks
        if (tickCount % 20 == 0) {
            double avgMs = totalTickTime / 20.0;
            currentTPS = avgMs > 0 ? Math.min(20.0, 1000.0 / avgMs) : 20.0;
            totalTickTime = 0;
        }
    }

    /**
     * Record a tick duration (double precision).
     * 
     * @param tickMs duration of the tick in milliseconds
     */
    public static void recordTick(double tickMs) {
        recordTick((long) tickMs);
    }

    /**
     * Get statistics string.
     */
    public static String getStatistics() {
        return String.format("TPSTracker{tps=%.1f}", currentTPS);
    }
}
