/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Profiling Monitor - Tracks detailed performance timing.
 */
package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ProfileMonitor - Advanced profiling for KneafMod.
 * Used by /kneaf profile command.
 */
public class ProfileMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ProfileMonitor");

    // State
    private static boolean active = false;
    private static long startTime = 0;

    // Performance Metrics (Name -> Nanos)
    private static final Map<String, AtomicLong> totalTimes = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> callCounts = new ConcurrentHashMap<>();

    /**
     * Start profiling session.
     */
    public static void start() {
        if (active)
            return;

        active = true;
        startTime = System.currentTimeMillis();
        totalTimes.clear();
        callCounts.clear();

        LOGGER.info("Profiling started...");
    }

    /**
     * Stop profiling and return report.
     */
    public static String stop() {
        if (!active)
            return "Profiling is not active.";

        active = false;
        long duration = System.currentTimeMillis() - startTime;

        StringBuilder report = new StringBuilder();
        report.append("=== Profiling Report (").append(duration / 1000.0).append("s) ===\n");

        totalTimes.forEach((key, value) -> {
            long calls = callCounts.getOrDefault(key, new AtomicLong(0)).get();
            double totalMs = value.get() / 1_000_000.0;
            double avgMs = calls > 0 ? totalMs / calls : 0;

            report.append(String.format("- %s: %d calls, %.2fms total (avg %.3fms)\n",
                    key, calls, totalMs, avgMs));
        });

        report.append("================================");
        return report.toString();
    }

    /**
     * Record execution time for a named operation.
     */
    public static void record(String name, long nanoTime) {
        if (!active)
            return;

        totalTimes.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(nanoTime);
        callCounts.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static boolean isActive() {
        return active;
    }
}
