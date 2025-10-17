package com.kneaf.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

public class RustVectorLibraryTest {

    private float[] identityMatrix;
    private float[] vecA;
    private float[] vecB;

    @BeforeEach
    void setUp() {
        identityMatrix = new float[]{
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
        vecA = new float[]{1.0f, 2.0f, 3.0f};
        vecB = new float[]{4.0f, 5.0f, 6.0f};
    }

    @Test
    void testIsLibraryLoaded() {
        assertTrue(RustVectorLibrary.isLibraryLoaded(), "Library should be loaded");
    }

    @Test
    void testMatrixMultiplyNalgebraHappyPath() {
        float[] result = RustVectorLibrary.matrixMultiplyNalgebra(identityMatrix, identityMatrix);
        assertArrayEquals(identityMatrix, result, 0.0001f, "Identity matrix multiplied by itself should be identity");
    }

    @Test
    void testMatrixMultiplyNalgebraNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyNalgebra(null, identityMatrix),
            "Should throw IllegalArgumentException for null matrix A");
    }

    @Test
    void testMatrixMultiplyNalgebraNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyNalgebra(identityMatrix, null),
            "Should throw IllegalArgumentException for null matrix B");
    }

    @Test
    void testMatrixMultiplyNalgebraWrongLengthA() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyNalgebra(wrongLength, identityMatrix),
            "Should throw IllegalArgumentException for wrong length matrix A");
    }

    @Test
    void testMatrixMultiplyNalgebraWrongLengthB() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyNalgebra(identityMatrix, wrongLength),
            "Should throw IllegalArgumentException for wrong length matrix B");
    }

    @Test
    void testVectorAddNalgebraHappyPath() {
        float[] expected = new float[]{5.0f, 7.0f, 9.0f};
        float[] result = RustVectorLibrary.vectorAddNalgebra(vecA, vecB);
        assertArrayEquals(expected, result, 0.0001f, "Vector addition should produce correct result");
    }

    @Test
    void testVectorAddNalgebraNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorAddNalgebra(null, vecB),
            "Should throw IllegalArgumentException for null vector A");
    }

    @Test
    void testVectorAddNalgebraNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorAddNalgebra(vecA, null),
            "Should throw IllegalArgumentException for null vector B");
    }

    @Test
    void testVectorAddNalgebraWrongLengthA() {
        float[] wrongLength = new float[]{1.0f, 2.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorAddNalgebra(wrongLength, vecB),
            "Should throw IllegalArgumentException for wrong length vector A");
    }

    @Test
    void testVectorAddNalgebraWrongLengthB() {
        float[] wrongLength = new float[]{4.0f, 5.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorAddNalgebra(vecA, wrongLength),
            "Should throw IllegalArgumentException for wrong length vector B");
    }

    @Test
    void testVectorDotGlamHappyPath() {
        float expected = 32.0f; // 1*4 + 2*5 + 3*6
        float result = RustVectorLibrary.vectorDotGlam(vecA, vecB);
        assertEquals(expected, result, 0.0001f, "Dot product should be correct");
    }

    @Test
    void testVectorDotGlamNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorDotGlam(null, vecB),
            "Should throw IllegalArgumentException for null vector A");
    }

    @Test
    void testVectorDotGlamNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorDotGlam(vecA, null),
            "Should throw IllegalArgumentException for null vector B");
    }

    @Test
    void testVectorDotGlamWrongLengthA() {
        float[] wrongLength = new float[]{1.0f, 2.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorDotGlam(wrongLength, vecB),
            "Should throw IllegalArgumentException for wrong length vector A");
    }

    @Test
    void testVectorDotGlamWrongLengthB() {
        float[] wrongLength = new float[]{4.0f, 5.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorDotGlam(vecA, wrongLength),
            "Should throw IllegalArgumentException for wrong length vector B");
    }

    @Test
    void testVectorCrossGlamHappyPath() {
        float[] expected = new float[]{-3.0f, 6.0f, -3.0f};
        float[] result = RustVectorLibrary.vectorCrossGlam(vecA, vecB);
        assertArrayEquals(expected, result, 0.0001f, "Cross product should be correct");
    }

    @Test
    void testVectorCrossGlamNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorCrossGlam(null, vecB),
            "Should throw IllegalArgumentException for null vector A");
    }

    @Test
    void testVectorCrossGlamNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorCrossGlam(vecA, null),
            "Should throw IllegalArgumentException for null vector B");
    }

    @Test
    void testVectorCrossGlamWrongLengthA() {
        float[] wrongLength = new float[]{1.0f, 2.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorCrossGlam(wrongLength, vecB),
            "Should throw IllegalArgumentException for wrong length vector A");
    }

    @Test
    void testVectorCrossGlamWrongLengthB() {
        float[] wrongLength = new float[]{4.0f, 5.0f};
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.vectorCrossGlam(vecA, wrongLength),
            "Should throw IllegalArgumentException for wrong length vector B");
    }

    @Test
    void testMatrixMultiplyGlamHappyPath() {
        float[] result = RustVectorLibrary.matrixMultiplyGlam(identityMatrix, identityMatrix);
        assertArrayEquals(identityMatrix, result, 0.0001f, "Identity matrix multiplied by itself should be identity");
    }

    @Test
    void testMatrixMultiplyGlamNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyGlam(null, identityMatrix),
            "Should throw IllegalArgumentException for null matrix A");
    }

    @Test
    void testMatrixMultiplyGlamNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyGlam(identityMatrix, null),
            "Should throw IllegalArgumentException for null matrix B");
    }

    @Test
    void testMatrixMultiplyGlamWrongLengthA() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyGlam(wrongLength, identityMatrix),
            "Should throw IllegalArgumentException for wrong length matrix A");
    }

    @Test
    void testMatrixMultiplyGlamWrongLengthB() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyGlam(identityMatrix, wrongLength),
            "Should throw IllegalArgumentException for wrong length matrix B");
    }

    @Test
    void testMatrixMultiplyFaerHappyPath() {
        float[] result = RustVectorLibrary.matrixMultiplyFaer(identityMatrix, identityMatrix);
        assertArrayEquals(identityMatrix, result, 0.0001f, "Identity matrix multiplied by itself should be identity");
    }

    @Test
    void testMatrixMultiplyFaerNullA() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyFaer(null, identityMatrix),
            "Should throw IllegalArgumentException for null matrix A");
    }

    @Test
    void testMatrixMultiplyFaerNullB() {
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyFaer(identityMatrix, null),
            "Should throw IllegalArgumentException for null matrix B");
    }

    @Test
    void testMatrixMultiplyFaerWrongLengthA() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyFaer(wrongLength, identityMatrix),
            "Should throw IllegalArgumentException for wrong length matrix A");
    }

    @Test
    void testMatrixMultiplyFaerWrongLengthB() {
        float[] wrongLength = new float[15];
        assertThrows(IllegalArgumentException.class, () -> RustVectorLibrary.matrixMultiplyFaer(identityMatrix, wrongLength),
            "Should throw IllegalArgumentException for wrong length matrix B");
    }
}