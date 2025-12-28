/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Rust native optimizations for mixin acceleration.
 * Provides SAFE fallbacks for all operations.
 */
package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RustOptimizations - Legacy Bridge for Native Rust acceleration.
 * 
 * Redirects calls to RustNativeLoader or executes Java fallbacks.
 * Ensures NO UnsatisfiedLinkError crashes the game.
 */
public class RustOptimizations {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/RustOptimizations");
    private static volatile boolean nativeAvailable = false;
    private static boolean initialized = false;

    static {
        initialize();
    }

    public static synchronized void initialize() {
        if (initialized)
            return;
        initialized = true;

        try {
            if (RustNativeLoader.isLibraryLoaded()) {
                nativeAvailable = true;
                // LOGGER.info("✅ RustOptimizations bridge initialized");
            } else {
                LOGGER.warn("Native library not loaded - RustOptimizations using Java fallbacks");
            }
        } catch (Throwable e) {
            nativeAvailable = false;
        }
    }

    public static boolean isAvailable() {
        return nativeAvailable;
    }

    // ========================================================================
    // Noise Generation
    // ========================================================================

    public static float[] batchNoiseGenerate3D(long seed, float[] coords, int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.batchNoiseGenerate3D(seed, coords, count);
            } catch (Throwable e) {
            }
        }
        return batchNoiseGenerate3DFallback(seed, coords, count);
    }

    public static float[] batchNoiseGenerate3DFallback(long seed, float[] coords, int count) {
        // Return null to indicate failure, allowing Mixin to use its own fallback or
        // vanilla logic.
        return null;
    }

    public static float[] batchNoise3D(long seed, float[] coords, int count) {
        return batchNoiseGenerate3D(seed, coords, count);
    }

    // ========================================================================
    // Light Propagation
    // ========================================================================

    public static int[] batchLightPropagate(int[] lightLevels, byte[] blockOpacity, int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.batchLightPropagate(lightLevels, blockOpacity, count);
            } catch (Throwable e) {
            }
        }
        return batchLightFallback(lightLevels, blockOpacity, count);
    }

    public static int[] batchLight(int[] lightLevels, byte[] blockOpacity, int count) {
        return batchLightPropagate(lightLevels, blockOpacity, count);
    }

    private static int[] batchLightFallback(int[] lightLevels, byte[] blockOpacity, int count) {
        return null;
    }

    // ========================================================================
    // AABB Intersection
    // ========================================================================

    public static int[] batchAABBIntersection(double[] boxes, double[] testBox, int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.batchAABBIntersection(boxes, testBox, count);
            } catch (Throwable e) {
            }
        }
        return batchAABBFallback(boxes, testBox, count);
    }

    public static int[] batchAABB(double[] boxes, double[] testBox, int count) {
        return batchAABBIntersection(boxes, testBox, count);
    }

    private static int[] batchAABBFallback(double[] boxes, double[] testBox, int count) {
        return null;
    }

    // ========================================================================
    // Biome Hashing (Used by BiomeSourceMixin)
    // ========================================================================

    public static long[] batchSpatialHash(double[] positions, double cellSize, int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.batchSpatialHash(positions, cellSize, count);
            } catch (Throwable e) {
            }
        }
        // Fallback or empty if not implemented
        return null;
    }

    public static long[] batchBiomeHash(int[] x, int[] y, int[] z, int count) {
        // Just use fallback, BiomeSourceMixin handles its own logic mostly
        long[] results = new long[count];
        for (int i = 0; i < count; i++) {
            results[i] = ((long) x[i] & 0x3FFFFF) | (((long) y[i] & 0xFFF) << 22)
                    | (((long) z[i] & 0x3FFFFF) << 34);
        }
        return results;
    }

    public static long[] batchHash(int[] x, int[] y, int[] z, int count) {
        return batchBiomeHash(x, y, z, count);
    }

    // ========================================================================
    // Explosion Ray Casting
    // ========================================================================

    public static float[] parallelRayCast(double[] origin, double[] rayDirs, int rayCount, float radius) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.castRays(origin, rayDirs, rayCount, radius);
            } catch (Throwable e) {
            }
        }
        return castRaysFallback(origin, rayDirs, rayCount, radius);
    }

    public static float[] castRays(double[] origin, double[] rayDirs, int rayCount, float radius) {
        return parallelRayCast(origin, rayDirs, rayCount, radius);
    }

    private static float[] castRaysFallback(double[] origin, double[] rayDirs, int rayCount, float radius) {
        return null;
    }

    // ========================================================================
    // Heightmap / Entity
    // ========================================================================

    public static int[] batchHeightmapUpdate(int[] currentHeights, int[] blockTypes, int columnCount, int maxHeight) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.updateHeightmap(currentHeights, blockTypes, columnCount, maxHeight);
            } catch (Throwable e) {
            }
        }
        return updateHeightmapFallback(currentHeights, blockTypes, columnCount, maxHeight);
    }

    public static int[] updateHeightmap(int[] currentHeights, int[] blockTypes, int columnCount, int maxHeight) {
        return batchHeightmapUpdate(currentHeights, blockTypes, columnCount, maxHeight);
    }

    private static int[] updateHeightmapFallback(int[] currentHeights, int[] blockTypes, int columnCount,
            int maxHeight) {
        // Return null to let vanilla handle it (or mixin loop)
        return null;
    }

    // ========================================================================
    // Fluid Flow (Previously caused trouble)
    // ========================================================================

    public static byte[] simulateFluidFlow(byte[] fluidLevels, byte[] solidBlocks, int width, int height, int depth) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.simulateFluidFlow(fluidLevels, solidBlocks, width, height, depth);
            } catch (Throwable e) {
                // Fallback
            }
        }
        return fluidFlowFallback(fluidLevels, solidBlocks, width, height, depth);
    }

    private static byte[] fluidFlowFallback(byte[] fluidLevels, byte[] solidBlocks, int width, int height, int depth) {
        return null;
    }

    // ========================================================================
    // Other Helpers
    // ========================================================================

    public static double[] batchEntityDistanceSq(double[] entityPositions, double refX, double refY, double refZ,
            int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.batchEntityDistanceSq(entityPositions, refX, refY, refZ, count);
            } catch (Throwable e) {
            }
        }

        double[] results = new double[count];
        for (int i = 0; i < count; i++) {
            double dx = entityPositions[i * 3] - refX;
            double dy = entityPositions[i * 3 + 1] - refY;
            double dz = entityPositions[i * 3 + 2] - refZ;
            results[i] = dx * dx + dy * dy + dz * dz;
        }
        return results;
    }

    /**
     * Compute tick rates for entities based on distance to player.
     * Returns: tick divisors (1 = every tick, 2 = every 2 ticks, etc.)
     */
    public static int[] computeEntityTickRates(double[] distancesSq, double nearThresholdSq,
            double farThresholdSq, int count) {
        if (nativeAvailable) {
            try {
                return RustNativeLoader.computeEntityTickRates(distancesSq, nearThresholdSq, farThresholdSq, count);
            } catch (Throwable e) {
            }
        }

        // Java fallback
        int[] rates = new int[count];
        double veryFarSq = farThresholdSq * 4.0;

        for (int i = 0; i < count; i++) {
            double d = distancesSq[i];
            if (d <= nearThresholdSq) {
                rates[i] = 1;
            } else if (d <= farThresholdSq) {
                rates[i] = 2;
            } else if (d <= veryFarSq) {
                rates[i] = 4;
            } else {
                rates[i] = 8;
            }
        }
        return rates;
    }

}
