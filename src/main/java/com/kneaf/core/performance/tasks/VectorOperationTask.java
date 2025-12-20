package com.kneaf.core.performance.tasks;

import java.util.concurrent.RecursiveTask;
import com.kneaf.core.RustNativeLoader;

/**
 * Fork/Join task for parallel vector operations
 */
public class VectorOperationTask extends RecursiveTask<Object> {
    private static final int MIN_PARALLEL_THRESHOLD = 10;

    private final float[] vectorA;
    private final float[] vectorB;
    private final int start;
    private final int end;
    private final String operationType;

    public VectorOperationTask(float[] vectorA, float[] vectorB, int start, int end, String operationType) {
        this.vectorA = vectorA;
        this.vectorB = vectorB;
        this.start = start;
        this.end = end;
        this.operationType = operationType;
    }

    @Override
    protected Object compute() {
        if (end - start <= MIN_PARALLEL_THRESHOLD) {
            return computeDirectly();
        } else {
            int mid = start + (end - start) / 2;
            VectorOperationTask leftTask = new VectorOperationTask(vectorA, vectorB, start, mid, operationType);
            VectorOperationTask rightTask = new VectorOperationTask(vectorA, vectorB, mid, end, operationType);

            leftTask.fork();
            Object rightResult = rightTask.compute();
            Object leftResult = leftTask.join();

            return combineResults(leftResult, rightResult);
        }
    }

    private Object computeDirectly() {
        if (!RustNativeLoader.isLibraryLoaded()) {
            throw new IllegalStateException("Rust native library not loaded");
        }
        switch (operationType) {
            case "vectorAdd":
                return RustNativeLoader.nalgebra_vector_add(vectorA, vectorB);
            case "vectorDot":
                return RustNativeLoader.glam_vector_dot(vectorA, vectorB);
            case "vectorCross":
                return RustNativeLoader.glam_vector_cross(vectorA, vectorB);
            default:
                throw new IllegalArgumentException("Unknown operation type: " + operationType);
        }
    }

    private Object combineResults(Object left, Object right) {
        // For vector operations, results are combined based on operation type
        if (operationType.equals("vectorDot")) {
            return (Float) left + (Float) right;
        }
        return left; // For other operations, return first result
    }
}
