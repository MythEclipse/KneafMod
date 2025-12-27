/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import com.kneaf.core.PerformanceManager;
import com.kneaf.core.RustNativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MixinHelper - Central utility class for all mixin operations.
 * Provides distance checking and native library management.
 * 
 * Moved to util package to avoid IllegalClassLoadError in Mixin transformer.
 */
public final class MixinHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinHelper.class);

    // Metrics tracking
    private static final AtomicLong processedTicks = new AtomicLong(0);
    private static final AtomicLong rustOptimizedTicks = new AtomicLong(0);
    private static final AtomicLong vanillaFallbackTicks = new AtomicLong(0);

    private static boolean initialized = false;
    private static boolean nativeAvailable = false;

    private MixinHelper() {
        // No instantiation
    }

    /**
     * Initialize MixinHelper. Called once on first use.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        nativeAvailable = RustNativeLoader.isLoaded();
        initialized = true;

        if (nativeAvailable) {
            LOGGER.info("✅ MixinHelper initialized with Rust native library support");
        } else {
            LOGGER.warn("⚠️ MixinHelper initialized WITHOUT Rust native library - using fallback");
        }
    }

    /**
     * Check if Rust native library is available.
     */
    public static boolean isNativeAvailable() {
        if (!initialized)
            initialize();
        return nativeAvailable;
    }

    /**
     * Check if AI optimization is enabled.
     */
    public static boolean isAIOptimizationEnabled() {
        return PerformanceManager.getInstance().isAiPathfindingOptimized();
    }

    /**
     * Clean up for removed entity.
     */
    public static void onEntityRemoved(int entityId) {
        // No-op - no tracking to clean up
    }

    /**
     * Get statistics for monitoring.
     */
    public static String getStatistics() {
        return String.format(
                "MixinStats{processed=%d, rust=%d, fallback=%d, ratio=%.1f%%}",
                processedTicks.get(),
                rustOptimizedTicks.get(),
                vanillaFallbackTicks.get(),
                processedTicks.get() > 0
                        ? (rustOptimizedTicks.get() * 100.0 / (rustOptimizedTicks.get() + vanillaFallbackTicks.get()))
                        : 0);
    }

    /**
     * Reset statistics (for testing).
     */
    public static void resetStatistics() {
        processedTicks.set(0);
        rustOptimizedTicks.set(0);
        vanillaFallbackTicks.set(0);
    }
}
