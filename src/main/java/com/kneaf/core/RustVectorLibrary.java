package com.kneaf.core;


/**
 * JNI wrapper class for dynamic exposure of Rust vector library functions.
 * Provides type-safe access to nalgebra, glam, and faer vector/matrix operations
 * implemented in the native Rust performance library.
 */
public final class RustVectorLibrary {
    private static final String LIBRARY_NAME = "rustperf";
    private static boolean isLibraryLoaded = false;

    static {
        try {
            System.out.println("RustVectorLibrary: java.library.path = " + System.getProperty("java.library.path"));
            System.out.println("RustVectorLibrary: user.dir = " + System.getProperty("user.dir"));
            // Load from absolute path for testing
            String libPath = System.getProperty("user.dir") + "/src/main/resources/natives/rustperf.dll";
            System.out.println("RustVectorLibrary: attempting to load from " + libPath);
            System.load(libPath);
            isLibraryLoaded = true;
            System.out.println("RustVectorLibrary: Successfully loaded native library '" + LIBRARY_NAME + "'");
        } catch (Throwable e) {
            System.out.println("RustVectorLibrary: Exception in static initializer: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            isLibraryLoaded = false;
        }
    }

    // Native method declarations - direct mappings to Rust extern "C" functions
    private static native float[] nalgebra_matrix_mul(float[] a, float[] b);
    private static native float[] nalgebra_vector_add(float[] a, float[] b);
    private static native float glam_vector_dot(float[] a, float[] b);
    private static native float[] glam_vector_cross(float[] a, float[] b);
    private static native float[] glam_matrix_mul(float[] a, float[] b);
    private static native float[] faer_matrix_mul(float[] a, float[] b);

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
            return nalgebra_matrix_mul(a, b);
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
            return nalgebra_vector_add(a, b);
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
            return glam_vector_dot(a, b);
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
            return glam_vector_cross(a, b);
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
            return glam_matrix_mul(a, b);
        } catch (Exception e) {
            System.out.println("JNI call failed in glam_matrix_mul: " + e.getMessage());
            throw new RuntimeException("JNI call failed", e);
        }
    }

    /**
     * Multiplies two 4x4 matrices using faer.
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
            return faer_matrix_mul(a, b);
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