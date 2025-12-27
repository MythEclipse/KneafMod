/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Rust native optimizations for mixin acceleration.
 * Provides SIMD-accelerated operations via JNI.
 */
package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RustOptimizations - Native Rust acceleration for mixin operations.
 * 
 * Provides high-performance SIMD-optimized implementations for:
 * - Noise generation (NoiseChunkGeneratorMixin)
 * - Light propagation (LightEngineMixin)
 * - AABB intersection (VoxelShapeCacheMixin)
 * - Biome hashing (BiomeSourceMixin)
 * - Explosion ray casting (ExplosionMixin)
 * - Heightmap updates (HeightmapMixin)
 */
public class RustOptimizations {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/RustOptimizations");
    private static boolean nativeAvailable = false;
    private static boolean initialized = false;

    static {
        initialize();
    }

    /**
     * Initialize and check native library availability.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            // Check if native library is loaded via RustNativeLoader
            if (RustNativeLoader.isLibraryLoaded()) {
                nativeAvailable = true;
                LOGGER.info("✅ RustOptimizations initialized - Native SIMD acceleration active!");
            } else {
                LOGGER.warn("Native library not loaded - RustOptimizations will use Java fallbacks");
            }
        } catch (Throwable e) {
            LOGGER.warn("Failed to initialize native optimizations: {}", e.getMessage());
            nativeAvailable = false;
        }
    }

    /**
     * Check if native optimizations are available.
     */
    public static boolean isAvailable() {
        return nativeAvailable;
    }

    // ========================================================================
    // Noise Generation
    // ========================================================================

    /**
     * Batch 3D Perlin noise generation using Rust SIMD.
     *
     * @param seed   World seed for noise generation
     * @param coords Flat array of coordinates [x0,y0,z0, x1,y1,z1, ...]
     * @param count  Number of coordinate triplets
     * @return Array of noise values, one per coordinate triplet
     */
    public static native float[] batchNoiseGenerate3D(long seed, float[] coords, int count);

    /**
     * Java fallback for batch noise generation.
     */
    public static float[] batchNoiseGenerate3DFallback(long seed, float[] coords, int count) {
        float[] results = new float[count];
        for (int i = 0; i < count; i++) {
            float x = coords[i * 3];
            float y = coords[i * 3 + 1];
            float z = coords[i * 3 + 2];
            results[i] = javaPerlinNoise3D(x, y, z, seed);
        }
        return results;
    }

    /**
     * Safe wrapper that falls back to Java if native unavailable.
     */
    public static float[] batchNoise3D(long seed, float[] coords, int count) {
        if (nativeAvailable) {
            try {
                return batchNoiseGenerate3D(seed, coords, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
                LOGGER.warn("Native noise generation failed, using fallback");
            }
        }
        return batchNoiseGenerate3DFallback(seed, coords, count);
    }

    // ========================================================================
    // Light Propagation
    // ========================================================================

    /**
     * Batch light propagation processing using Rust.
     *
     * @param lightLevels  Current light levels
     * @param blockOpacity Block opacity values (0-15)
     * @param count        Number of blocks to process
     * @return Updated light levels
     */
    public static native int[] batchLightPropagate(int[] lightLevels, byte[] blockOpacity, int count);

    /**
     * Safe wrapper with Java fallback.
     */
    public static int[] batchLight(int[] lightLevels, byte[] blockOpacity, int count) {
        if (nativeAvailable) {
            try {
                return batchLightPropagate(lightLevels, blockOpacity, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        int[] results = new int[count];
        for (int i = 0; i < count; i++) {
            int decay = Math.max(blockOpacity[i] & 0xFF, 1);
            results[i] = Math.max(0, Math.min(15, lightLevels[i] - decay));
        }
        return results;
    }

    // ========================================================================
    // AABB Intersection
    // ========================================================================

    /**
     * Batch AABB intersection test using Rust SIMD.
     *
     * @param boxes   Flat array of boxes [minX,minY,minZ,maxX,maxY,maxZ, ...]
     * @param testBox Single box to test against [minX,minY,minZ,maxX,maxY,maxZ]
     * @param count   Number of boxes to test
     * @return Array of intersection results (0 or 1)
     */
    public static native int[] batchAABBIntersection(double[] boxes, double[] testBox, int count);

    /**
     * Safe wrapper with Java fallback.
     */
    public static int[] batchAABB(double[] boxes, double[] testBox, int count) {
        if (nativeAvailable) {
            try {
                return batchAABBIntersection(boxes, testBox, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        int[] results = new int[count];
        for (int i = 0; i < count; i++) {
            int idx = i * 6;
            boolean intersects = boxes[idx] <= testBox[3] && boxes[idx + 3] >= testBox[0]
                    && boxes[idx + 1] <= testBox[4] && boxes[idx + 4] >= testBox[1]
                    && boxes[idx + 2] <= testBox[5] && boxes[idx + 5] >= testBox[2];
            results[i] = intersects ? 1 : 0;
        }
        return results;
    }

    // ========================================================================
    // Biome Hashing
    // ========================================================================

    /**
     * Batch biome position hash computation.
     *
     * @param x     X coordinates
     * @param y     Y coordinates
     * @param z     Z coordinates
     * @param count Number of positions
     * @return Array of hash values
     */
    public static native long[] batchBiomeHash(int[] x, int[] y, int[] z, int count);

    /**
     * Safe wrapper with Java fallback.
     */
    public static long[] batchHash(int[] x, int[] y, int[] z, int count) {
        if (nativeAvailable) {
            try {
                return batchBiomeHash(x, y, z, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback using same algorithm as Rust
        long[] results = new long[count];
        for (int i = 0; i < count; i++) {
            results[i] = ((long) x[i] & 0x3FFFFF) | (((long) y[i] & 0xFFF) << 22)
                    | (((long) z[i] & 0x3FFFFF) << 34);
        }
        return results;
    }

    // ========================================================================
    // Explosion Ray Casting
    // ========================================================================

    /**
     * Parallel ray casting for explosion block destruction.
     *
     * @param origin   Explosion center [x, y, z]
     * @param rayDirs  Ray directions [dx0,dy0,dz0, dx1,dy1,dz1, ...]
     * @param rayCount Number of rays
     * @param radius   Explosion radius
     * @return Intensity values for each ray
     */
    public static native float[] parallelRayCast(double[] origin, double[] rayDirs, int rayCount, float radius);

    /**
     * Safe wrapper with Java fallback.
     */
    public static float[] castRays(double[] origin, double[] rayDirs, int rayCount, float radius) {
        if (nativeAvailable) {
            try {
                return parallelRayCast(origin, rayDirs, rayCount, radius);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback - simple distance-based attenuation
        float[] results = new float[rayCount];
        for (int i = 0; i < rayCount; i++) {
            results[i] = 1.0f - (1.0f / radius);
        }
        return results;
    }

    // ========================================================================
    // Heightmap Updates
    // ========================================================================

    /**
     * Batch heightmap update computation.
     *
     * @param currentHeights Current height values per column
     * @param blockTypes     Block types (0 = air)
     * @param columnCount    Number of columns
     * @param maxHeight      Maximum world height
     * @return Updated heights
     */
    public static native int[] batchHeightmapUpdate(int[] currentHeights, int[] blockTypes,
            int columnCount, int maxHeight);

    /**
     * Safe wrapper with Java fallback.
     */
    public static int[] updateHeightmap(int[] currentHeights, int[] blockTypes,
            int columnCount, int maxHeight) {
        if (nativeAvailable) {
            try {
                return batchHeightmapUpdate(currentHeights, blockTypes, columnCount, maxHeight);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        int[] results = new int[columnCount];
        for (int col = 0; col < columnCount; col++) {
            int baseIdx = col * maxHeight;
            results[col] = 0;
            for (int y = maxHeight - 1; y >= 0; y--) {
                int idx = baseIdx + y;
                if (idx < blockTypes.length && blockTypes[idx] != 0) {
                    results[col] = y + 1;
                    break;
                }
            }
        }
        return results;
    }

    // ========================================================================
    // Entity Batch Processing
    // ========================================================================

    /**
     * Batch calculate squared distances from entities to a reference point.
     *
     * @param entityPositions [x0,y0,z0, x1,y1,z1, ...]
     * @param refX            Reference X
     * @param refY            Reference Y
     * @param refZ            Reference Z
     * @param count           Number of entities
     * @return Array of squared distances
     */
    public static native double[] batchEntityDistanceSq(double[] entityPositions,
            double refX, double refY, double refZ, int count);

    /**
     * Compute tick rates based on entity distances.
     *
     * @param distancesSq     Squared distances
     * @param nearThresholdSq Near threshold (full tick)
     * @param farThresholdSq  Far threshold (reduced tick)
     * @param count           Number of entities
     * @return Tick divisors (1, 2, 4, or 8)
     */
    public static native int[] computeEntityTickRates(double[] distancesSq,
            double nearThresholdSq, double farThresholdSq, int count);

    /**
     * Batch mob AI priority evaluation.
     *
     * @param hasTarget   1 if has target, 0 otherwise
     * @param distancesSq Squared distances
     * @param count       Number of mobs
     * @return AI priorities (0=skip, 1=low, 2=medium, 3=high)
     */
    public static native int[] batchMobAIPriority(int[] hasTarget, double[] distancesSq, int count);

    /**
     * Simulate fluid flow in a grid.
     *
     * @param fluidLevels Current fluid levels (0-8)
     * @param solidBlocks 1 if solid, 0 if passable
     * @param width       Grid width
     * @param height      Grid height
     * @param depth       Grid depth
     * @return Updated fluid levels
     */
    public static native byte[] simulateFluidFlow(byte[] fluidLevels, byte[] solidBlocks,
            int width, int height, int depth);

    /**
     * Compute spatial hashes for positions.
     *
     * @param positions [x0,y0,z0, x1,y1,z1, ...]
     * @param cellSize  Size of spatial hash cells
     * @param count     Number of positions
     * @return Array of hash values
     */
    public static native long[] batchSpatialHash(double[] positions, double cellSize, int count);

    // ========================================================================
    // Safe Wrappers for New Functions
    // ========================================================================

    /**
     * Safe wrapper for entity distance calculation.
     */
    public static double[] entityDistances(double[] positions, double refX, double refY, double refZ, int count) {
        if (nativeAvailable) {
            try {
                return batchEntityDistanceSq(positions, refX, refY, refZ, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        double[] results = new double[count];
        for (int i = 0; i < count; i++) {
            double dx = positions[i * 3] - refX;
            double dy = positions[i * 3 + 1] - refY;
            double dz = positions[i * 3 + 2] - refZ;
            results[i] = dx * dx + dy * dy + dz * dz;
        }
        return results;
    }

    /**
     * Safe wrapper for tick rate computation.
     */
    public static int[] tickRates(double[] distancesSq, double nearSq, double farSq, int count) {
        if (nativeAvailable) {
            try {
                return computeEntityTickRates(distancesSq, nearSq, farSq, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        int[] results = new int[count];
        for (int i = 0; i < count; i++) {
            double d = distancesSq[i];
            if (d <= nearSq)
                results[i] = 1;
            else if (d <= farSq)
                results[i] = 2;
            else if (d <= farSq * 4)
                results[i] = 4;
            else
                results[i] = 8;
        }
        return results;
    }

    /**
     * Safe wrapper for mob AI priority.
     */
    public static int[] mobAIPriorities(int[] hasTarget, double[] distancesSq, int count) {
        if (nativeAvailable) {
            try {
                return batchMobAIPriority(hasTarget, distancesSq, count);
            } catch (UnsatisfiedLinkError e) {
                nativeAvailable = false;
            }
        }
        // Java fallback
        int[] results = new int[count];
        double nearSq = 32.0 * 32.0;
        double farSq = 64.0 * 64.0;
        for (int i = 0; i < count; i++) {
            if (hasTarget[i] != 0)
                results[i] = 3;
            else if (distancesSq[i] <= nearSq)
                results[i] = 2;
            else if (distancesSq[i] <= farSq)
                results[i] = 1;
            else
                results[i] = 0;
        }
        return results;
    }

    // ========================================================================
    // Java Fallback Implementations
    // ========================================================================

    /**
     * Java Perlin noise fallback.
     */
    private static float javaPerlinNoise3D(float x, float y, float z, long seed) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);

        float fx = x - ix;
        float fy = y - iy;
        float fz = z - iz;

        float u = fx * fx * (3 - 2 * fx);
        float v = fy * fy * (3 - 2 * fy);
        float w = fz * fz * (3 - 2 * fz);

        float n000 = grad(hash(ix, iy, iz, seed), fx, fy, fz);
        float n100 = grad(hash(ix + 1, iy, iz, seed), fx - 1, fy, fz);
        float n010 = grad(hash(ix, iy + 1, iz, seed), fx, fy - 1, fz);
        float n110 = grad(hash(ix + 1, iy + 1, iz, seed), fx - 1, fy - 1, fz);
        float n001 = grad(hash(ix, iy, iz + 1, seed), fx, fy, fz - 1);
        float n101 = grad(hash(ix + 1, iy, iz + 1, seed), fx - 1, fy, fz - 1);
        float n011 = grad(hash(ix, iy + 1, iz + 1, seed), fx, fy - 1, fz - 1);
        float n111 = grad(hash(ix + 1, iy + 1, iz + 1, seed), fx - 1, fy - 1, fz - 1);

        float nx00 = n000 + u * (n100 - n000);
        float nx10 = n010 + u * (n110 - n010);
        float nx01 = n001 + u * (n101 - n001);
        float nx11 = n011 + u * (n111 - n011);

        float nxy0 = nx00 + v * (nx10 - nx00);
        float nxy1 = nx01 + v * (nx11 - nx01);

        return nxy0 + w * (nxy1 - nxy0);
    }

    private static long hash(int x, int y, int z, long seed) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xBF58476D1CE4E5B9L;
        h ^= z * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static float grad(long hash, float x, float y, float z) {
        int h = (int) (hash & 15);
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
