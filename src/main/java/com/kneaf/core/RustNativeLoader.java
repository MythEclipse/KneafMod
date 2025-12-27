package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/**
 * Centralized JNI Native Library Loader for KneafMod Rust Performance Library
 */
public class RustNativeLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNativeLoader.class);
    private static volatile boolean libraryLoaded = false;
    private static String loadedLibraryPath = null;
    private static final Set<String> availableMethods = new HashSet<>();
    private static final Object LOAD_LOCK = new Object();

    private RustNativeLoader() {
    }

    public static boolean loadLibrary() {
        if (libraryLoaded)
            return true;
        synchronized (LOAD_LOCK) {
            if (libraryLoaded)
                return true;
            try {
                if (loadFromClasspath()) {
                    libraryLoaded = true;
                    return true;
                }
                if (loadFromFilesystem()) {
                    libraryLoaded = true;
                    return true;
                }
                if (loadFromSystemPath()) {
                    libraryLoaded = true;
                    return true;
                }
                LOGGER.error("❌ Failed to load native library from all attempted locations");
                return false;
            } catch (Exception e) {
                LOGGER.error("❌ Exception while loading native library", e);
                return false;
            }
        }
    }

    private static boolean loadFromClasspath() {
        String libName = System.mapLibraryName("rustperf");
        String[] resourcePaths = { "natives/" + libName, "/natives/" + libName, libName };
        for (String resourcePath : resourcePaths) {
            try {
                URL resourceUrl = RustNativeLoader.class.getClassLoader().getResource(resourcePath);
                if (resourceUrl != null)
                    return extractAndLoad(resourceUrl, resourcePath);
            } catch (Exception e) {
                LOGGER.trace("Failed to load from classpath: {}", resourcePath, e);
            }
        }
        return false;
    }

    private static boolean extractAndLoad(URL resourceUrl, String resourcePath) {
        try {
            String tempDirName = "kneafmod-natives-" + System.currentTimeMillis();
            Path tempDir = Files.createTempDirectory(tempDirName);
            Path tempFile = tempDir.resolve(System.mapLibraryName("rustperf"));
            try (InputStream in = resourceUrl.openStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            String absolutePath = tempFile.toAbsolutePath().toString();
            System.load(absolutePath);
            loadedLibraryPath = absolutePath;
            LOGGER.info("✅ SUCCESS: Loaded native library from classpath: {} -> {}", resourcePath, absolutePath);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Failed to extract and load from classpath", e);
            return false;
        }
    }

    private static boolean loadFromFilesystem() {
        String libName = System.mapLibraryName("rustperf");
        String[] paths = {
                libName, "run/" + libName, "rust/target/release/" + libName,
                "rust/target/debug/" + libName, "src/main/resources/natives/" + libName,
                "build/resources/main/natives/" + libName
        };
        for (String pathStr : paths) {
            try {
                File file = new File(pathStr);
                if (file.exists() && file.isFile()) {
                    String absolutePath = file.getAbsolutePath();
                    System.load(absolutePath);
                    loadedLibraryPath = absolutePath;
                    LOGGER.info("✅ SUCCESS: Loaded native library from filesystem: {}", absolutePath);
                    return true;
                }
            } catch (Exception e) {
                LOGGER.trace("Failed to load from path: {}", pathStr, e);
            }
        }
        return false;
    }

    private static boolean loadFromSystemPath() {
        try {
            System.loadLibrary("rustperf");
            loadedLibraryPath = "System library path (rustperf)";
            LOGGER.info("✅ SUCCESS: Loaded native library from system path");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.trace("Failed to load from system path", e);
            return false;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    // Alias for compatibility
    public static boolean isLoaded() {
        return isLibraryLoaded();
    }

    // ========================================
    // CATEGORY 1: Vector & Matrix Operations
    // ========================================

    public static native float[] nalgebra_matrix_mul(float[] a, float[] b);

    public static native float[] nalgebra_vector_add(float[] a, float[] b);

    public static native float glam_vector_dot(float[] a, float[] b);

    public static native float[] glam_vector_cross(float[] a, float[] b);

    public static native float[] glam_matrix_mul(float[] a, float[] b);

    public static native double[] faer_matrix_mul(double[] a, double[] b);

    public static native float[][] batchNalgebraMatrixMulNative(float[][] matricesA, float[][] matricesB, int count);

    public static float[] batchNalgebraMatrixMul(float[][] matricesA, float[][] matricesB, int count) {
        float[][] result2D = batchNalgebraMatrixMulNative(matricesA, matricesB, count);
        if (result2D == null)
            return new float[0];
        int rows = result2D.length;
        if (rows == 0)
            return new float[0];
        int cols = result2D[0].length;
        float[] flat = new float[rows * cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(result2D[i], 0, flat, i * cols, cols);
        }
        return flat;
    }

    // ========================================
    // CATEGORY 2: OptimizationInjector / Physics
    // ========================================

    public static native double[] rustperf_vector_multiply(double x, double y, double z, double scalar);

    public static native double[] rustperf_vector_add(double x1, double y1, double z1, double x2, double y2, double z2);

    public static native double[] rustperf_vector_damp(double x, double y, double z, double damping);

    // Hayabusa (Keeping declarations to avoid breakages in legacy code if present,
    // though suppressed)
    public static native double[] rustperf_hayabusa_phantom_shuriken(double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ, double speed);

    public static native double[][] rustperf_hayabusa_quad_shadow(double centerX, double centerY, double centerZ,
            double radius);

    public static native double rustperf_hayabusa_shadow_kill_damage(int passiveStacks, double baseDamage);

    public static native int rustperf_hayabusa_calculate_passive_stacks(int currentStacks, boolean successfulHit,
            int maxStacks);

    // ========================================
    // CATEGORY 3: High-Performance Utils
    // ========================================

    public static native double vectorDistance(double x1, double y1, double z1, double x2, double y2, double z2);

    public static native double[] vectorNormalize(double x, double y, double z);

    public static native double vectorLength(double x, double y, double z);

    public static native double[] vectorLerp(double x1, double y1, double z1, double x2, double y2, double z2,
            double t);

    public static native double[] batchDistanceCalculation(float[] positions, int count, double centerX, double centerY,
            double centerZ);

    public static native double[] batchDistanceCalculationWithZeroCopy(long bufferHandle, int count, double centerX,
            double centerY, double centerZ);

    public static native double[] calculateCircularPosition(double centerX, double centerZ, double radius,
            double angle); // Missing in previous step?

    public static native int[] computeEntityTickRates(double[] distancesSq, double nearThresholdSq,
            double farThresholdSq, int count);

    public static native int[] batchMobAIPriority(int[] hasTarget, double[] distancesSq, int count);

    // ========================================
    // CATEGORY 4: New/Restored Batch Methods
    // ========================================

    public static native long[] batchSpatialHash(double[] positions, double cellSize, int count);

    public static native int[] batchAABBIntersection(double[] boxes, double[] testBox, int count);

    public static native byte[] simulateFluidFlow(byte[] fluidLevels, byte[] solidBlocks, int width, int height,
            int depth);

    public static native int[] rustperf_astar_pathfind(boolean[] grid, int width, int height, int startX, int startY,
            int goalX, int goalY);

    public static native void rustperf_batch_spatial_grid_zero_copy(java.nio.ByteBuffer input,
            java.nio.ByteBuffer output, int count);

    public static native double[] rustperf_batch_entity_physics(double[] velocities, int entityCount, double damping);

    // ========================================
    // CATEGORY 5: Memory Management
    // ========================================

    public static native void releaseNativeBuffer(long pointer);

    public static native long allocateNativeBuffer(int size);

    public static native void copyToNativeBuffer(long pointer, float[] data, int offset, int length);

    public static native void copyFromNativeBuffer(long pointer, float[] result, int offset, int length);

    public static native String getRustPerformanceStats();

    // ========================================
    // CATEGORY 6: Missing Methods (Restored for RustOptimizations)
    // ========================================

    public static native float[] batchNoiseGenerate3D(long seed, float[] coords, int count);

    public static native float[] castRays(double[] origin, double[] rayDirs, int rayCount, float radius);

    public static native int[] batchLightPropagate(int[] lightLevels, byte[] blockOpacity, int count);

    public static native int[] updateHeightmap(int[] currentHeights, int[] blockTypes, int columnCount, int maxHeight);

    public static native double[] batchEntityDistanceSq(double[] entityPositions, double refX, double refY, double refZ,
            int count);
}
