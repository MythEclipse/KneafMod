package com.kneaf.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for RustVectorLibrary native functions.
 * Tests all vector/matrix operations exposed through JNI bindings.
 */
public class RustVectorLibraryTest {

    private static final float[] IDENTITY_MATRIX = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    private static final float[] TEST_VECTOR_A = {1.0f, 2.0f, 3.0f};
    private static final float[] TEST_VECTOR_B = {4.0f, 5.0f, 6.0f};
    
    private static final double[] DOUBLE_VECTOR_A = {1.0, 2.0, 3.0};
    private static final double[] DOUBLE_VECTOR_B = {4.0, 5.0, 6.0};
    private static final double SCALAR_TEST = 2.5;

    @BeforeAll
    public static void setUp() {
        // Enable test mode to skip native library loading issues during testing
        OptimizationInjector.enableTestMode(true);
        
        // Wait for library to initialize
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    public static void tearDown() {
        // Reset metrics after tests
        OptimizationInjector.resetMetrics();
    }

    @Test
    public void testLibraryLoading() {
        System.out.println("Testing native library loading...");
        assertTrue(RustVectorLibrary.isLibraryLoaded(), "Native library should be loaded for tests");
        System.out.println("✓ Native library loaded successfully");
    }

    @Test
    public void testNalgebraMatrixMultiplication() {
        System.out.println("Testing nalgebra matrix multiplication...");
        
        float[] result = RustVectorLibrary.matrixMultiplyNalgebra(IDENTITY_MATRIX, IDENTITY_MATRIX);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(16, result.length, "Result should be 16 elements (4x4 matrix)");
        
        // Identity matrix * identity matrix should equal identity matrix
        assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity matrix");
        System.out.println("✓ Nalgebra matrix multiplication passed");
    }

    @Test
    public void testNalgebraVectorAddition() {
        System.out.println("Testing nalgebra vector addition...");
        
        float[] result = RustVectorLibrary.vectorAddNalgebra(TEST_VECTOR_A, TEST_VECTOR_B);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.length, "Result should be 3 elements (3D vector)");
        
        // 1+4=5, 2+5=7, 3+6=9
        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, result, 1e-6f, "Vector addition result should be correct");
        System.out.println("✓ Nalgebra vector addition passed");
    }

    @Test
    public void testGlamVectorDotProduct() {
        System.out.println("Testing glam vector dot product...");
        
        float result = RustVectorLibrary.vectorDotGlam(TEST_VECTOR_A, TEST_VECTOR_B);
        
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0f, result, 1e-6f, "Dot product result should be 32.0");
        System.out.println("✓ Glam vector dot product passed");
    }

    @Test
    public void testGlamVectorCrossProduct() {
        System.out.println("Testing glam vector cross product...");
        
        float[] result = RustVectorLibrary.vectorCrossGlam(TEST_VECTOR_A, TEST_VECTOR_B);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.length, "Result should be 3 elements (3D vector)");
        
        // Cross product: (2*6 - 3*5, 3*4 - 1*6, 1*5 - 2*4) = (12-15, 12-6, 5-8) = (-3, 6, -3)
        assertArrayEquals(new float[]{-3.0f, 6.0f, -3.0f}, result, 1e-6f, "Cross product result should be correct");
        System.out.println("✓ Glam vector cross product passed");
    }

    @Test
    public void testGlamMatrixMultiplication() {
        System.out.println("Testing glam matrix multiplication...");
        
        float[] result = RustVectorLibrary.matrixMultiplyGlam(IDENTITY_MATRIX, IDENTITY_MATRIX);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(16, result.length, "Result should be 16 elements (4x4 matrix)");
        
        // Identity matrix * identity matrix should equal identity matrix
        assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity matrix");
        System.out.println("✓ Glam matrix multiplication passed");
    }

    @Test
    public void testFaerMatrixMultiplication() {
        System.out.println("Testing faer matrix multiplication...");
        
        float[] result = RustVectorLibrary.matrixMultiplyFaer(IDENTITY_MATRIX, IDENTITY_MATRIX);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(16, result.length, "Result should be 16 elements (4x4 matrix)");
        
        // Identity matrix * identity matrix should equal identity matrix
        assertArrayEquals(IDENTITY_MATRIX, result, 1e-6f, "Matrix multiplication result should be identity matrix");
        System.out.println("✓ Faer matrix multiplication passed");
    }

    @Test
    public void testRustperfVectorMultiply() {
        System.out.println("Testing rustperf vector multiply...");
        
        double[] result = OptimizationInjector.rustperf_vector_multiply(
            DOUBLE_VECTOR_A[0], DOUBLE_VECTOR_A[1], DOUBLE_VECTOR_A[2], SCALAR_TEST
        );
        
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.length, "Result should be 3 elements (3D vector)");
        
        // 1*2.5=2.5, 2*2.5=5.0, 3*2.5=7.5
        assertArrayEquals(new double[]{2.5, 5.0, 7.5}, result, 1e-6, "Vector multiply result should be correct");
        System.out.println("✓ Rustperf vector multiply passed");
    }

    @Test
    public void testRustperfVectorAdd() {
        System.out.println("Testing rustperf vector add...");
        
        double[] result = OptimizationInjector.rustperf_vector_add(
            DOUBLE_VECTOR_A[0], DOUBLE_VECTOR_A[1], DOUBLE_VECTOR_A[2],
            DOUBLE_VECTOR_B[0], DOUBLE_VECTOR_B[1], DOUBLE_VECTOR_B[2]
        );
        
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.length, "Result should be 3 elements (3D vector)");
        
        // 1+4=5, 2+5=7, 3+6=9
        assertArrayEquals(new double[]{5.0, 7.0, 9.0}, result, 1e-6, "Vector add result should be correct");
        System.out.println("✓ Rustperf vector add passed");
    }

    @Test
    public void testRustperfVectorDamp() {
        System.out.println("Testing rustperf vector damp...");
        
        double dampingFactor = 0.95;
        double[] result = OptimizationInjector.rustperf_vector_damp(
            DOUBLE_VECTOR_A[0], DOUBLE_VECTOR_A[1], DOUBLE_VECTOR_A[2], dampingFactor
        );
        
        assertNotNull(result, "Result should not be null");
        assertEquals(3, result.length, "Result should be 3 elements (3D vector)");
        
        // 1*0.95=0.95, 2*0.95=1.9, 3*0.95=2.85
        assertArrayEquals(new double[]{0.95, 1.9, 2.85}, result, 1e-6, "Vector damp result should be correct");
        System.out.println("✓ Rustperf vector damp passed");
    }

    @Test
    public void testAllFunctionsIntegration() {
        System.out.println("Testing all Rust vector library functions integration...");
        
        boolean allPass = RustVectorLibrary.testAllFunctions();
        
        assertTrue(allPass, "All Rust vector library functions should pass integration test");
        System.out.println("✓ All Rust vector library functions integration test passed");
    }

    @Test
    public void testErrorConditions() {
        System.out.println("Testing error conditions...");
        
        // Test null input
        assertThrows(IllegalArgumentException.class, 
            () -> RustVectorLibrary.vectorAddNalgebra(null, TEST_VECTOR_B));
        
        // Test wrong length
        assertThrows(IllegalArgumentException.class, 
            () -> RustVectorLibrary.vectorAddNalgebra(new float[]{1.0f, 2.0f}, TEST_VECTOR_B));
        
        System.out.println("✓ Error conditions testing passed");
    }

    private static void printArray(float[] array) {
        System.out.println(Arrays.toString(array));
    }

    private static void printArray(double[] array) {
        System.out.println(Arrays.toString(array));
    }
}