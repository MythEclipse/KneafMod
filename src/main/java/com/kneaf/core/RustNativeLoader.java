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
 * JNI Method Categories:
 * 1. RustVectorLibrary Methods (6 methods) - Pure mathematical operations
 * 2. OptimizationInjector Methods (7 methods) - Core optimizations + Hayabusa skills
 * 3. ParallelRustVectorProcessor Methods (14+ methods) - Advanced parallel processing
 */
public class RustNativeLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustNativeLoader.class);
    private static volatile boolean libraryLoaded = false; // volatile for double-checked locking
    private static String loadedLibraryPath = null;
    private static final Set<String> availableMethods = new HashSet<>();
    private static final Object LOAD_LOCK = new Object(); // Dedicated lock object
    
    // Prevent instantiation
    private RustNativeLoader() {}
    
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
        String[] resourcePaths = {
            "natives/rustperf.dll",
            "/natives/rustperf.dll",
            "rustperf.dll"
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
            Path tempFile = tempDir.resolve("rustperf.dll");
            
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
        String[] paths = {
            "rustperf.dll",
            "run/rustperf.dll",
            "rust/target/release/rustperf.dll",
            "rust/target/debug/rustperf.dll",
            "src/main/resources/natives/rustperf.dll",
            "build/resources/main/natives/rustperf.dll"
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
            availableMethods.stream().sorted().forEach(method -> 
                LOGGER.info("  ✅ {}", method)
            );
        }
    }
    
    // ========================================
    // CATEGORY 1: RustVectorLibrary Methods
    // Pure mathematical vector/matrix operations
    // ========================================
    
    public static native float[] nalgebra_matrix_mul(float[] a, float[] b);
    public static native float[] nalgebra_vector_add(float[] a, float[] b);
    public static native float glam_vector_dot(float[] a, float[] b);
    public static native float[] glam_vector_cross(float[] a, float[] b);
    public static native float[] glam_matrix_mul(float[] a, float[] b);
    public static native double[] faer_matrix_mul(double[] a, double[] b);
    
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
        double speed
    );
    
    public static native double[][] rustperf_hayabusa_quad_shadow(
        double centerX, double centerY, double centerZ,
        double radius
    );
    
    public static native double rustperf_hayabusa_shadow_kill_damage(
        int passiveStacks, double baseDamage
    );
    
    public static native int rustperf_hayabusa_calculate_passive_stacks(
        int currentStacks, boolean successfulHit, int maxStacks
    );
    
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
        double t
    );
    
    /**
     * Batch distance calculation for multiple entities
     * Input: flat array [x1,y1,z1, x2,y2,z2, ...], centerX, centerY, centerZ
     * Output: array of distances from center
     */
    public static native double[] batchDistanceCalculation(float[] positions, int count, double centerX, double centerY, double centerZ);
    
    /**
     * Zero-copy batch distance calculation using native buffer handle
     * Input: native buffer handle, count, centerX, centerY, centerZ
     * Output: array of distances from center
     * Optimized for reduced data transfer overhead between Java and Rust
     */
    public static native double[] batchDistanceCalculationWithZeroCopy(long bufferHandle, int count, double centerX, double centerY, double centerZ);
    
    /**
     * Calculate circular position using trigonometric functions
     * Returns [x, z] coordinates at given angle and radius from center
     * Optimized for entity positioning in circular patterns
     */
    public static native double[] calculateCircularPosition(double centerX, double centerZ, double radius, double angle);
    
    // ========================================
    // CATEGORY 3: ParallelRustVectorProcessor Methods
    // Advanced parallel processing operations
    // ========================================
    
    // A* pathfinding - NOT IMPLEMENTED YET
    public static int[] parallelAStarPathfind(
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        byte[] blockData, int worldWidth, int worldHeight, int worldDepth
    ) {
        throw new UnsatisfiedLinkError("A* pathfinding not yet implemented for RustNativeLoader");
    }
    
    // Matrix operations - IMPLEMENTED
    public static native float[] parallelMatrixMultiplyBlock(float[] a, float[] b, int size);
    public static native float[] parallelStrassenMultiply(float[] a, float[] b, int n);
    
    // Batch operations - NOT IMPLEMENTED YET
    public static float[] batchMatrixMultiply(float[] matrices, int batchSize, int matrixSize) {
        throw new UnsatisfiedLinkError("Batch matrix multiply not yet implemented for RustNativeLoader");
    }
    
    // Arena memory operations - IMPLEMENTED
    public static native float[] arenaMatrixMultiply(float[] a, float[] b, int size);
    
    public static void resetMemoryArena() {
        throw new UnsatisfiedLinkError("Reset memory arena not yet implemented for RustNativeLoader");
    }
    
    // Runtime SIMD operations - IMPLEMENTED
    public static native float[] runtimeMatrixMultiply(float[] a, float[] b, int size);
    public static native float runtimeVectorDotProduct(float[] a, float[] b);
    public static native float[] runtimeVectorAdd(float[] a, float[] b);
    public static native float[] runtimeMatrix4x4Multiply(float[] a, float[] b);
    
    // Load balancer operations - NOT IMPLEMENTED YET
    public static long createLoadBalancer(int numWorkers) {
        throw new UnsatisfiedLinkError("Load balancer not yet implemented for RustNativeLoader");
    }
    
    public static long submitTask(long balancerPtr, int taskType, float[] data) {
        throw new UnsatisfiedLinkError("Load balancer not yet implemented for RustNativeLoader");
    }
    
    public static float[] getTaskResult(long taskPtr, int timeoutMs) {
        throw new UnsatisfiedLinkError("Load balancer not yet implemented for RustNativeLoader");
    }
    
    public static void destroyLoadBalancer(long balancerPtr) {
        throw new UnsatisfiedLinkError("Load balancer not yet implemented for RustNativeLoader");
    }
    
    // Performance monitoring - NOT IMPLEMENTED YET
    public static long createPerformanceMonitor() {
        throw new UnsatisfiedLinkError("Performance monitor not yet implemented for RustNativeLoader");
    }
    
    public static void recordMetric(long monitorPtr, String metricName, double value) {
        throw new UnsatisfiedLinkError("Performance monitor not yet implemented for RustNativeLoader");
    }
    
    public static double getMetricAverage(long monitorPtr, String metricName) {
        throw new UnsatisfiedLinkError("Performance monitor not yet implemented for RustNativeLoader");
    }
    
    public static void destroyPerformanceMonitor(long monitorPtr) {
        throw new UnsatisfiedLinkError("Performance monitor not yet implemented for RustNativeLoader");
    }
    
    // Entity system operations - NOT IMPLEMENTED YET
    public static long createEntityRegistry() {
        throw new UnsatisfiedLinkError("Entity registry not yet implemented for RustNativeLoader");
    }
    
    public static long registerEntity(long registryPtr, String entityType) {
        throw new UnsatisfiedLinkError("Entity registry not yet implemented for RustNativeLoader");
    }
    
    public static void updateEntity(long registryPtr, long entityId, float[] position, float[] velocity) {
        throw new UnsatisfiedLinkError("Entity registry not yet implemented for RustNativeLoader");
    }
    
    public static float[] queryEntities(long registryPtr, float[] center, float radius) {
        throw new UnsatisfiedLinkError("Entity registry not yet implemented for RustNativeLoader");
    }
    
    public static void destroyEntityRegistry(long registryPtr) {
        throw new UnsatisfiedLinkError("Entity registry not yet implemented for RustNativeLoader");
    }
    
    // ========================================
    // CATEGORY 4: ParallelRustVectorProcessor Batch Operations
    // Batch processing for parallel vector/matrix operations
    // Note: Rust returns float[][], but some callers expect flat float[]
    // ========================================
    
    // Native declarations returning float[][]
    private static native float[][] batchNalgebraMatrixMulNative(float[][] matricesA, float[][] matricesB, int count);
    private static native float[][] batchNalgebraVectorAddNative(float[][] vectorsA, float[][] vectorsB, int count);
    
    // Public wrappers that flatten results for compatibility
    public static float[] batchNalgebraMatrixMul(float[][] matricesA, float[][] matricesB, int count) {
        float[][] result2D = batchNalgebraMatrixMulNative(matricesA, matricesB, count);
        return flatten2DArray(result2D);
    }
    
    public static float[] batchNalgebraVectorAdd(float[][] vectorsA, float[][] vectorsB, int count) {
        float[][] result2D = batchNalgebraVectorAddNative(vectorsA, vectorsB, count);
        return flatten2DArray(result2D);
    }
    
    private static float[] flatten2DArray(float[][] array2D) {
        if (array2D == null) return null;
        int totalLength = 0;
        for (float[] row : array2D) {
            if (row != null) totalLength += row.length;
        }
        float[] flat = new float[totalLength];
        int offset = 0;
        for (float[] row : array2D) {
            if (row != null) {
                System.arraycopy(row, 0, flat, offset, row.length);
                offset += row.length;
            }
        }
        return flat;
    }
    
    // Batch vector operations with Java fallback implementations
    public static float[] batchGlamVectorDot(float[][] vectorsA, float[][] vectorsB, int count) {
        float[] results = new float[count];
        for (int i = 0; i < count; i++) {
            if (i < vectorsA.length && i < vectorsB.length) {
                float[] a = vectorsA[i];
                float[] b = vectorsB[i];
                float dot = 0.0f;
                int len = Math.min(a.length, b.length);
                for (int j = 0; j < len; j++) {
                    dot += a[j] * b[j];
                }
                results[i] = dot;
            }
        }
        return results;
    }
    
    public static float[] batchGlamVectorCross(float[][] vectorsA, float[][] vectorsB, int count) {
        // Cross product only for 3D vectors, returns flattened array
        float[] results = new float[count * 3];
        for (int i = 0; i < count; i++) {
            if (i < vectorsA.length && i < vectorsB.length && 
                vectorsA[i].length >= 3 && vectorsB[i].length >= 3) {
                float[] a = vectorsA[i];
                float[] b = vectorsB[i];
                results[i * 3] = a[1] * b[2] - a[2] * b[1];
                results[i * 3 + 1] = a[2] * b[0] - a[0] * b[2];
                results[i * 3 + 2] = a[0] * b[1] - a[1] * b[0];
            }
        }
        return results;
    }
    
    public static float[] batchGlamMatrixMul(float[][] matricesA, float[][] matricesB, int count) {
        // Simplified 4x4 matrix multiplication fallback
        float[] results = new float[count * 16]; // 4x4 matrices
        for (int i = 0; i < count; i++) {
            if (i < matricesA.length && i < matricesB.length && 
                matricesA[i].length >= 16 && matricesB[i].length >= 16) {
                float[] a = matricesA[i];
                float[] b = matricesB[i];
                // Matrix multiplication for 4x4 matrices
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        float sum = 0.0f;
                        for (int k = 0; k < 4; k++) {
                            sum += a[row * 4 + k] * b[k * 4 + col];
                        }
                        results[i * 16 + row * 4 + col] = sum;
                    }
                }
            }
        }
        return results;
    }
    
    public static float[] batchFaerMatrixMul(float[][] matricesA, float[][] matricesB, int count) {
        // Faer matrix multiplication uses same algorithm as Glam for Java fallback
        return batchGlamMatrixMul(matricesA, matricesB, count);
    }
    
    // ========================================
    // CATEGORY 5: Native Memory Management
    // Safe memory buffer operations for zero-copy transfers
    // ========================================
    
    public static native void releaseNativeBuffer(long pointer);
    public static native long allocateNativeBuffer(int size);
    public static native void copyToNativeBuffer(long pointer, float[] data, int offset, int length);
    public static native void copyFromNativeBuffer(long pointer, float[] result, int offset, int length);
    
    // ========================================
    // CATEGORY 6: Performance Monitoring
    // Rust-side performance statistics
    // ========================================
    
    public static native String getRustPerformanceStats();
}
