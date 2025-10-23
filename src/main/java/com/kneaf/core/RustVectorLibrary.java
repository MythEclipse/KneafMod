package com.kneaf.core;

/**
 * JNI wrapper class for dynamic exposure of Rust vector library functions.
 * Provides type-safe access to nalgebra, glam, and faer vector/matrix operations
 * implemented in the native Rust performance library.
 * 
 * NOTE: All native methods are now centralized in RustNativeLoader.
 * This class delegates to RustNativeLoader for all operations.
 */
public final class RustVectorLibrary {
    private static final String LIBRARY_NAME = "rustperf";
    private static boolean isLibraryLoaded = false;

    static {
        // Delegate library loading to RustNativeLoader
        isLibraryLoaded = RustNativeLoader.loadLibrary();
        if (isLibraryLoaded) {
            System.out.println("RustVectorLibrary: Successfully loaded via RustNativeLoader");
        } else {
            System.out.println("RustVectorLibrary: Failed to load via RustNativeLoader");
        }
    }

    /**
     * Multiplies two 4x4 matrices using nalgebra.
     *
     * @param a first matrix as float array of length 16 (row-major order)
     * @param b second matrix as float array of length 16 (row-major order)
     * @return result matrix as float array of length 16
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if matrices are null or not length 16
     * @throws RuntimeException if JNI call fails
     */
    public static float[] matrixMultiplyNalgebra(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 16 || b.length != 16) {
            throw new IllegalArgumentException("Matrices must be non-null float arrays of length 16");
        }
        try {
            return RustNativeLoader.nalgebra_matrix_mul(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in nalgebra_matrix_mul: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Adds two 3D vectors using nalgebra.
     *
     * @param a first vector as float array of length 3
     * @param b second vector as float array of length 3
     * @return result vector as float array of length 3
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if vectors are null or not length 3
     * @throws RuntimeException if JNI call fails
     */
    public static float[] vectorAddNalgebra(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 3 || b.length != 3) {
            throw new IllegalArgumentException("Vectors must be non-null float arrays of length 3");
        }
        try {
            return RustNativeLoader.nalgebra_vector_add(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in nalgebra_vector_add: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Computes dot product of two 3D vectors using glam.
     *
     * @param a first vector as float array of length 3
     * @param b second vector as float array of length 3
     * @return dot product as float
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if vectors are null or not length 3
     * @throws RuntimeException if JNI call fails
     */
    public static float vectorDotGlam(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 3 || b.length != 3) {
            throw new IllegalArgumentException("Vectors must be non-null float arrays of length 3");
        }
        try {
            return RustNativeLoader.glam_vector_dot(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in glam_vector_dot: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Computes cross product of two 3D vectors using glam.
     *
     * @param a first vector as float array of length 3
     * @param b second vector as float array of length 3
     * @return result vector as float array of length 3
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if vectors are null or not length 3
     * @throws RuntimeException if JNI call fails
     */
    public static float[] vectorCrossGlam(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 3 || b.length != 3) {
            throw new IllegalArgumentException("Vectors must be non-null float arrays of length 3");
        }
        try {
            return RustNativeLoader.glam_vector_cross(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in glam_vector_cross: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Multiplies two 4x4 matrices using glam.
     *
     * @param a first matrix as float array of length 16 (column-major order)
     * @param b second matrix as float array of length 16 (column-major order)
     * @return result matrix as float array of length 16
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if matrices are null or not length 16
     * @throws RuntimeException if JNI call fails
     */
    public static float[] matrixMultiplyGlam(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 16 || b.length != 16) {
            throw new IllegalArgumentException("Matrices must be non-null float arrays of length 16");
        }
        try {
            return RustNativeLoader.glam_matrix_mul(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in glam_matrix_mul: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Multiplies two 4x4 matrices using faer (returns double[] from RustNativeLoader, needs conversion).
     *
     * @param a first matrix as float array of length 16 (row-major order)
     * @param b second matrix as float array of length 16 (row-major order)
     * @return result matrix as float array of length 16
     * @throws IllegalStateException if native library is not loaded
     * @throws IllegalArgumentException if matrices are null or not length 16
     * @throws RuntimeException if JNI call fails
     */
    public static float[] matrixMultiplyFaer(float[] a, float[] b) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }
        if (a == null || b == null || a.length != 16 || b.length != 16) {
            throw new IllegalArgumentException("Matrices must be non-null float arrays of length 16");
        }
        try {
            // RustNativeLoader.faer_matrix_mul returns double[], convert to float[]
            double[] ad = new double[16];
            double[] bd = new double[16];
            for (int i = 0; i < 16; i++) {
                ad[i] = a[i];
                bd[i] = b[i];
            }
            double[] resultDouble = RustNativeLoader.faer_matrix_mul(ad, bd);
            float[] result = new float[16];
            for (int i = 0; i < 16; i++) {
                result[i] = (float) resultDouble[i];
            }
            return result;
        } catch (Exception e) {
            System.out.println("JNI call failed in faer_matrix_mul: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Tests all native vector library functions with known test data.
     * Verifies that Java can successfully call all Rust vector functions.
     *
     * @return true if all tests pass, false otherwise
     */
    public static boolean testAllFunctions() {
        if (!isLibraryLoaded) {
            System.out.println("Cannot test functions: native library not loaded");
            return false;
        }

        try {
            // Test matrices (identity matrices)
            float[] identityMatrix = {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            };

            // Test vectors
            float[] vecA = {1.0f, 2.0f, 3.0f};
            float[] vecB = {4.0f, 5.0f, 6.0f};

            // Test nalgebra matrix multiplication
            float[] result = matrixMultiplyNalgebra(identityMatrix, identityMatrix);
            if (result == null || result.length != 16) {
                System.out.println("nalgebra_matrix_mul test failed: invalid result length");
                return false;
            }

            // Test nalgebra vector addition
            result = vectorAddNalgebra(vecA, vecB);
            if (result == null || result.length != 3 ||
                result[0] != 5.0f || result[1] != 7.0f || result[2] != 9.0f) {
                System.out.println("nalgebra_vector_add test failed: expected [5.0, 7.0, 9.0], got " + java.util.Arrays.toString(result));
                return false;
            }

            // Test glam vector dot product
            float dotResult = vectorDotGlam(vecA, vecB);
            if (dotResult != 32.0f) { // 1*4 + 2*5 + 3*6 = 32
                System.out.println("glam_vector_dot test failed: expected 32.0, got " + dotResult);
                return false;
            }

            // Test glam vector cross product
            result = vectorCrossGlam(vecA, vecB);
            if (result == null || result.length != 3 ||
                result[0] != -3.0f || result[1] != 6.0f || result[2] != -3.0f) {
                System.out.println("glam_vector_cross test failed: expected [-3.0, 6.0, -3.0], got " + java.util.Arrays.toString(result));
                return false;
            }

            // Test glam matrix multiplication
            result = matrixMultiplyGlam(identityMatrix, identityMatrix);
            if (result == null || result.length != 16) {
                System.out.println("glam_matrix_mul test failed: invalid result length");
                return false;
            }

            // Test faer matrix multiplication
            result = matrixMultiplyFaer(identityMatrix, identityMatrix);
            if (result == null || result.length != 16) {
                System.out.println("faer_matrix_mul test failed: invalid result length");
                return false;
            }

            System.out.println("All Rust vector library functions tested successfully");
            return true;

        } catch (Exception e) {
            System.out.println("Test failed with exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the native library is loaded.
     *
     * @return true if library is loaded, false otherwise
     */
    public static boolean isLibraryLoaded() {
        return isLibraryLoaded;
    }
}