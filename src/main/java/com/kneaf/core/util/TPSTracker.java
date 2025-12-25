/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import java.util.concurrent.atomic.AtomicLong;

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
    private static volatile int throttleLevel = 0; // 0=none, 1=light, 2=medium, 3=heavy

    // Counters
    private static final AtomicLong entitiesThrottled = new AtomicLong(0);
    private static long totalTickTime = 0;
    private static long tickCount = 0;

    // TPS thresholds for throttling
    private static final double TPS_HEAVY_THROTTLE = 12.0;
    private static final double TPS_MEDIUM_THROTTLE = 15.0;
    private static final double TPS_LIGHT_THROTTLE = 18.0;

    /**
     * Get current estimated TPS.
     */
    public static double getCurrentTPS() {
        return currentTPS;
    }

    /**
     * Get current throttle level (0-3).
     */
    public static int getThrottleLevel() {
        return throttleLevel;
    }

    /**
     * Get count of entities throttled.
     */
    public static long getEntitiesThrottled() {
        return entitiesThrottled.get();
    }

    /**
     * Increment the throttled entity counter.
     */
    public static void incrementThrottled() {
        entitiesThrottled.incrementAndGet();
    }

    /**
     * Reset the throttled counter.
     */
    public static void resetThrottledCount() {
        entitiesThrottled.set(0);
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

        updateThrottleLevel();
    }

    /**
     * Update throttle level based on current TPS.
     */
    private static void updateThrottleLevel() {
        if (currentTPS < TPS_HEAVY_THROTTLE) {
            throttleLevel = 3;
        } else if (currentTPS < TPS_MEDIUM_THROTTLE) {
            throttleLevel = 2;
        } else if (currentTPS < TPS_LIGHT_THROTTLE) {
            throttleLevel = 1;
        } else {
            throttleLevel = 0;
        }
    }

    /**
     * Get statistics string.
     */
    public static String getStatistics() {
        return String.format(
                "TPSTracker{tps=%.1f, throttleLevel=%d, entitiesThrottled=%d}",
                currentTPS,
                throttleLevel,
                entitiesThrottled.get());
    }
}
