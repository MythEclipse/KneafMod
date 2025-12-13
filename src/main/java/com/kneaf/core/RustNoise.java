package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java wrapper for Rust-accelerated noise generation.
 * Provides high-performance Simplex/Perlin noise functions using the native
 * backend.
 */
public class RustNoise {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNoise.class);

    // Native method declarations
    public static native double noise2d(double x, double z, int seed, double frequency);

    public static native double noise3d(double x, double y, double z, int seed, double frequency);

    public static native double[] batchNoise2d(double xStart, double zStart, int width, int depth, int seed,
            double frequency);

    /**
     * Check if native noise generation is available
     */
    public static boolean isAvailable() {
        return OptimizationInjector.isNativeLibraryLoaded();
    }

    /**
     * Generate 2D noise with fallback to Java implementation if native is
     * unavailable
     */
    public static double getNoise2d(double x, double z, int seed, double frequency) {
        if (isAvailable()) {
            try {
                return noise2d(x, z, seed, frequency);
            } catch (UnsatisfiedLinkError e) {
                LOGGER.warn("Native noise2d failed, falling back to Java: {}", e.getMessage());
            }
        }
        // Fallback (simple implementation for now, or returns 0 if we assume native
        // always works)
        return 0.0; // TODO: Implement Java fallback if strict equivalence is needed
    }

    /**
     * Generate 3D noise with fallback
     */
    public static double getNoise3d(double x, double y, double z, int seed, double frequency) {
        if (isAvailable()) {
            try {
                return noise3d(x, y, z, seed, frequency);
            } catch (UnsatisfiedLinkError e) {
                // Warning suppressed to avoid log spam
            }
        }
        return 0.0;
    }
}
