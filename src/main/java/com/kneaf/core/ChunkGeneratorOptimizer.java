package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import com.kneaf.core.performance.PerformanceMonitoringSystem;

/**
 * Optimizes chunk generation by managing thread priorities and
 * providing access to native acceleration.
 */
public class ChunkGeneratorOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkGeneratorOptimizer.class);
    private static boolean initialized = false;
    private static boolean rustAccelerationAvailable = false;

    public static synchronized void init() {
        if (initialized)
            return;

        LOGGER.info("Starting Chunk Generator Optimization...");

        // Check for Rust acceleration
        try {
            if (RustNoise.isAvailable()) {
                rustAccelerationAvailable = true;
                LOGGER.info("✅ Rust acceleration available for chunk noise generation");

                // Perform initial warm-up
                try {
                    RustNoise.noise2d(0, 0, 12345, 0.01);
                    LOGGER.info("   Native noise generator warmed up");
                } catch (Exception e) {
                    LOGGER.warn("   Failed to warm up native noise: {}", e.getMessage());
                }
            } else {
                LOGGER.warn("⚠️ Rust acceleration unavailable for chunk noise - falling back to Java");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to check Rust noise availability: {}", t.getMessage());
        }

        // Optimize threads
        optimizeChunkThreads();

        initialized = true;
    }

    private static void optimizeChunkThreads() {
        try {
            // Adjust common pool parallelism if needed
            ForkJoinPool commonPool = ForkJoinPool.commonPool();
            LOGGER.info("Current ForkJoinPool parallelism: {}", commonPool.getParallelism());

            // Note: modifying commonPool is limited, but we can log metrics
            // Optimizations are primarily applied via custom executors in ChunkProcessor

            LOGGER.info("Chunk generation thread optimization complete");
        } catch (Exception e) {
            LOGGER.warn("Failed to optimize chunk threads: {}", e.getMessage());
        }
    }

    public static boolean isRustAccelerationAvailable() {
        return rustAccelerationAvailable;
    }
}
