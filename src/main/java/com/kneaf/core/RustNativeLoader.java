package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/**
 * Centralized JNI Native Library Loader for KneafMod Rust Performance Library
 * 
 * Responsibilities:
 * - Load rustperf.dll from classpath or filesystem
 * - Declare all native methods in one place
 * - Verify native methods are available
 * - Provide status reporting
 * 
 * 1. Vector & Matrix Operations - Pure mathematical operations
 * 2. OptimizationInjector Methods - Core optimizations + Hayabusa skills
 * 3. ParallelRustVectorProcessor Methods - Advanced parallel processing
 */
public class RustNativeLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNativeLoader.class);
    private static volatile boolean libraryLoaded = false; // volatile for double-checked locking
    private static String loadedLibraryPath = null;
    private static final Set<String> availableMethods = new HashSet<>();
    private static final Object LOAD_LOCK = new Object(); // Dedicated lock object

    // Prevent instantiation
    private RustNativeLoader() {
    }

    /**
     * Load the native library from various possible locations
     * Uses double-checked locking for optimal performance
     */
    public static boolean loadLibrary() {
        // Fast path - no lock required
        if (libraryLoaded) {
            return true;
        }

        // Slow path - synchronized only when needed
        synchronized (LOAD_LOCK) {
            // Double-check after acquiring lock
            if (libraryLoaded) {
                return true;
            }

            try {
                // Try method 1: Load from classpath
                if (loadFromClasspath()) {
                    libraryLoaded = true;
                    return true;
                }

                // Try method 2: Load from filesystem paths
                if (loadFromFilesystem()) {
                    libraryLoaded = true;
                    return true;
                }

                // Try method 3: System.loadLibrary (searches java.library.path)
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

    /**
     * Extract and load library from classpath resources
     */
    private static boolean loadFromClasspath() {
        String libName = System.mapLibraryName("rustperf");
        String[] resourcePaths = {
                "natives/" + libName,
                "/natives/" + libName,
                libName
        };

        for (String resourcePath : resourcePaths) {
            try {
                URL resourceUrl = RustNativeLoader.class.getClassLoader().getResource(resourcePath);
                if (resourceUrl != null) {
                    return extractAndLoad(resourceUrl, resourcePath);
                }
            } catch (Exception e) {
                LOGGER.trace("Failed to load from classpath: {}", resourcePath, e);
            }
        }

        return false;
    }

    /**
     * Extract resource to temp file and load it
     */
    private static boolean extractAndLoad(URL resourceUrl, String resourcePath) {
        try {
            // Create temp directory with timestamp to avoid caching issues
            String tempDirName = "kneafmod-natives-" + System.currentTimeMillis();
            Path tempDir = Files.createTempDirectory(tempDirName);
            Path tempFile = tempDir.resolve(System.mapLibraryName("rustperf"));

            // Extract DLL to temp file
            try (InputStream in = resourceUrl.openStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // Load the DLL
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

    /**
     * Try loading from known filesystem paths
     */
    private static boolean loadFromFilesystem() {
        String libName = System.mapLibraryName("rustperf");
        String[] paths = {
                libName,
                "run/" + libName,
                "rust/target/release/" + libName,
                "rust/target/debug/" + libName,
                "src/main/resources/natives/" + libName,
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

    /**
     * Try System.loadLibrary (searches java.library.path)
     */
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

    /**
     * Test if a specific native method is available
     */
    public static boolean testMethod(String methodName, Runnable testFunction) {
        try {
            testFunction.run();
            availableMethods.add(methodName);
            LOGGER.debug("✅ Native method available: {}", methodName);
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("❌ Native method NOT available: {} - {}", methodName, e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("❌ Error testing native method: {}", methodName, e);
            return false;
        }
    }

    /**
     * Get library loading status
     */
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    /**
     * Get path of loaded library
     */
    public static String getLoadedLibraryPath() {
        return loadedLibraryPath;
    }

    /**
     * Get set of available methods that passed verification
     */
    public static Set<String> getAvailableMethods() {
        return Collections.unmodifiableSet(availableMethods);
    }

    /**
     * Print status report
     */
    public static void printStatusReport() {
        LOGGER.info("=== Rust Native Library Status ===");
        LOGGER.info("Library Loaded: {}", libraryLoaded);
        LOGGER.info("Loaded From: {}", loadedLibraryPath != null ? loadedLibraryPath : "Not loaded");
        LOGGER.info("Available Methods: {}/{}", availableMethods.size(), "TBD");

        if (!availableMethods.isEmpty()) {
            LOGGER.info("Verified Methods:");
            availableMethods.stream().sorted().forEach(method -> LOGGER.info("  ✅ {}", method));
        }
    }

    // ========================================
    // CATEGORY 1: Vector & Matrix Operations
    // Pure mathematical vector/matrix operations
    // ========================================

    public static native float[] nalgebra_matrix_mul(float[] a, float[] b);

    public static native float[] nalgebra_vector_add(float[] a, float[] b);

    public static native float glam_vector_dot(float[] a, float[] b);

    public static native float[] glam_vector_cross(float[] a, float[] b);

    public static native float[] glam_matrix_mul(float[] a, float[] b);

    public static native double[] faer_matrix_mul(double[] a, double[] b);

    // RESTORED: Batch native methods
    public static native float[][] batchNalgebraMatrixMulNative(float[][] matricesA, float[][] matricesB, int count);

    // RESTORED: Java helper for batch alignment
    public static float[] batchNalgebraMatrixMul(float[][] matricesA, float[][] matricesB, int count) {
        float[][] result2D = batchNalgebraMatrixMulNative(matricesA, matricesB, count);
        return flatten2DArray(result2D);
    }
    
    private static float[] flatten2DArray(float[][] arr) {
         if (arr == null || arr.length == 0) return new float[0];
         int rows = arr.length;
         int cols = arr[0].length;
         float[] flat = new float[rows * cols];
         for (int i=0; i<rows; i++) {
             System.arraycopy(arr[i], 0, flat, i*cols, cols);
         }
         return flat;
    }

    // ========================================
    // CATEGORY 2: OptimizationInjector Methods
    // Core optimizations + Hayabusa ninja skills
    // ========================================

    // Basic vector operations - native declarations
    public static native double[] rustperf_vector_multiply(double x, double y, double z, double scalar);

    public static native double[] rustperf_vector_add(double x1, double y1, double z1, double x2, double y2, double z2);

    public static native double[] rustperf_vector_damp(double x, double y, double z, double damping);

    // Hayabusa ninja skill calculations - native declarations
    public static native double[] rustperf_hayabusa_phantom_shuriken(
            double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ,
            double speed);

    public static native double[][] rustperf_hayabusa_quad_shadow(
            double centerX, double centerY, double centerZ,
            double radius);

    public static native double rustperf_hayabusa_shadow_kill_damage(
            int passiveStacks, double baseDamage);

    public static native int rustperf_hayabusa_calculate_passive_stacks(
            int currentStacks, boolean successfulHit, int maxStacks);

    // ========================================
    // CATEGORY 2B: High-Performance Vector Utilities
    // Common game physics operations optimized in Rust
    // ========================================

    /**
     * Calculate distance between two 3D points using SIMD optimization
     * Much faster than Math.sqrt(dx*dx + dy*dy + dz*dz)
     */
    public static native double vectorDistance(double x1, double y1, double z1, double x2, double y2, double z2);

    /**
     * Normalize a 3D vector (make unit length) using SIMD
     * Returns [x/length, y/length, z/length]
     */
    public static native double[] vectorNormalize(double x, double y, double z);

    /**
     * Calculate vector length/magnitude using SIMD
     * Faster than Math.sqrt(x*x + y*y + z*z)
     */
    public static native double vectorLength(double x, double y, double z);

    /**
     * Linear interpolation between two vectors
     * Returns lerp(a, b, t) = a + (b - a) * t
     */
    public static native double[] vectorLerp(
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double t);

    /**
     * Batch distance calculation for multiple entities
     * Input: flat array [x1,y1,z1, x2,y2,z2, ...], centerX, centerY, centerZ
     * Output: array of distances from center
     */
    public static native double[] batchDistanceCalculation(float[] positions, int count, double centerX, double centerY,
            double centerZ);

    /**
     * Zero-copy batch distance calculation using native buffer handle
     * Input: native buffer handle, count, centerX, centerY, centerZ
     * Output: array of distances from center
     * Optimized for reduced data transfer overhead between Java and Rust
     */
    public static native double[] batchDistanceCalculationWithZeroCopy(long bufferHandle, int count, double centerX,
            double centerY, double centerZ);

    /**
     * Calculate circular position using trigonometric functions
     * Returns [x, z] coordinates at given angle and radius from center
     * Optimized for entity positioning in circular patterns
     */
    public static native double[] calculateCircularPosition(double centerX, double centerZ, double radius,
            double angle);

    // ========================================
    // CATEGORY 3: ParallelRustVectorProcessor Methods
    // Advanced parallel processing operations
    // ========================================

    /**
     * A* pathfinding using Rust parallel implementation.
     * Grid is a boolean array where true = obstacle.
     * Returns path as [x1, y1, x2, y2, ...] or null if no path.
     */
    /**
     * A* pathfinding using Rust parallel implementation.
     * Grid is a boolean array where true = obstacle.
     * Returns path as [x1, y1, x2, y2, ...] or null if no path.
     */
    public static native int[] rustperf_astar_pathfind(
            boolean[] grid, int width, int height,
            int startX, int startY, int goalX, int goalY);

    // RESTORED: Zero-copy spatial grid method used by EntityProcessingService
    public static native void rustperf_batch_spatial_grid_zero_copy(
            java.nio.ByteBuffer input, java.nio.ByteBuffer output, int count);

    /**
     * Batch entity physics processing.
     * Velocities format: [vx1, vy1, vz1, vx2, vy2, vz2, ...]
     * Returns processed velocities with damping applied.
     */
    public static native double[] rustperf_batch_entity_physics(
            double[] velocities, int entityCount, double damping);

    // Compatibility alias - keeping this one as it's a simple boolean check used
    // widely
    public static boolean isLoaded() {
        return isLibraryLoaded();
    }

    // Note: Java-side batch helpers removed in favor of direct VectorMath usage or
    // native batch calls.

    // ========================================
    // CATEGORY 5: Native Memory Management
    // ========================================

    public static native void releaseNativeBuffer(long pointer);

    public static native long allocateNativeBuffer(int size);

    public static native void copyToNativeBuffer(long pointer, float[] data, int offset, int length);

    public static native void copyFromNativeBuffer(long pointer, float[] result, int offset, int length);

    // ========================================
    // CATEGORY 6: Performance Monitoring
    // ========================================

    public static native String getRustPerformanceStats();
}
