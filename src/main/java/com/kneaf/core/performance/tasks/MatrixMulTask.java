package com.kneaf.core.performance.tasks;

import java.util.concurrent.RecursiveTask;
import com.kneaf.core.RustNativeLoader;
import com.kneaf.core.math.VectorMath;

/**
 * Fork/Join task for parallel matrix multiplication
 */
public class MatrixMulTask extends RecursiveTask<float[]> {
    private static final int MIN_PARALLEL_THRESHOLD = 10;

    private final float[] matrixA;
    private final float[] matrixB;
    private final int start;
    private final int end;
    private final String operationType;

    public MatrixMulTask(float[] matrixA, float[] matrixB, int start, int end, String operationType) {
        this.matrixA = matrixA;
        this.matrixB = matrixB;
        this.start = start;
        this.end = end;
        this.operationType = operationType;
    }

    @Override
    protected float[] compute() {
        if (end - start <= MIN_PARALLEL_THRESHOLD) {
            return computeDirectly();
        } else {
            int mid = start + (end - start) / 2;
            MatrixMulTask leftTask = new MatrixMulTask(matrixA, matrixB, start, mid, operationType);
            MatrixMulTask rightTask = new MatrixMulTask(matrixA, matrixB, mid, end, operationType);

            leftTask.fork();
            float[] rightResult = rightTask.compute();
            float[] leftResult = leftTask.join();

            return combineResults(leftResult, rightResult);
        }
    }

    private float[] computeDirectly() {
        if (!RustNativeLoader.isLibraryLoaded()) {
            // Robust Fallback: Use VectorMath (which has Java implementation)
            return VectorMath.matrixMultiply(matrixA, matrixB);
        }
        switch (operationType) {
            case "nalgebra":
                return RustNativeLoader.nalgebra_matrix_mul(matrixA, matrixB);
            case "glam":
                return RustNativeLoader.glam_matrix_mul(matrixA, matrixB);
            case "faer":
                // Handle double[] to float[] conversion
                double[] a = new double[16];
                double[] b = new double[16];
                for (int i = 0; i < 16; i++) {
                    a[i] = matrixA[i];
                    b[i] = matrixB[i];
                }
                double[] res = RustNativeLoader.faer_matrix_mul(a, b);
                float[] floatRes = new float[16];
                for (int i = 0; i < 16; i++)
                    floatRes[i] = (float) res[i];
                return floatRes;
            default:
                throw new IllegalArgumentException("Unknown operation type: " + operationType);
        }
    }

    private float[] combineResults(float[] left, float[] right) {
        // For matrix operations, results are identical, so return one
        return left;
    }
}
