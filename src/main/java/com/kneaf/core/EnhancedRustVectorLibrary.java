package com.kneaf.core;

import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Enhanced RustVectorLibrary with parallel processing capabilities and zero-copy integration.
 * Provides batch operations, zero-copy processing, and thread-safe execution.
 */
public final class EnhancedRustVectorLibrary {
    private static final String LIBRARY_NAME = "rustperf";
    private static volatile ParallelRustVectorProcessor parallelProcessor;
    private static final Object processorLock = new Object();
    private static volatile boolean isLibraryLoaded = false;
    private static volatile ZeroCopyDataTransfer zeroCopyTransfer;

    static {
        try {
            System.out.println("EnhancedRustVectorLibrary: Loading native library...");
            String libPath = System.getProperty("user.dir") + "/src/main/resources/natives/rustperf.dll";
            System.load(libPath);
            isLibraryLoaded = true;
            System.out.println("EnhancedRustVectorLibrary: Successfully loaded native library '" + LIBRARY_NAME + "'");
        } catch (Throwable e) {
            System.out.println("EnhancedRustVectorLibrary: Failed to load native library: " + e.getMessage());
            isLibraryLoaded = false;
        }
    }

    /**
     * Get the parallel processor instance (lazy initialization)
     */
    public static ParallelRustVectorProcessor getParallelProcessor() {
        if (parallelProcessor == null) {
            synchronized (processorLock) {
                if (parallelProcessor == null) {
                    parallelProcessor = new ParallelRustVectorProcessor();
                }
            }
        }
        return parallelProcessor;
    }

    /**
     * Batch matrix multiplication using parallel processing
     */
    public static CompletableFuture<List<float[]>> batchMatrixMultiplyNalgebra(
            List<float[]> matricesA, List<float[]> matricesB) {
        return getParallelProcessor().batchMatrixMultiply(matricesA, matricesB, "nalgebra");
    }

    public static CompletableFuture<List<float[]>> batchMatrixMultiplyGlam(
            List<float[]> matricesA, List<float[]> matricesB) {
        return getParallelProcessor().batchMatrixMultiply(matricesA, matricesB, "glam");
    }

    public static CompletableFuture<List<float[]>> batchMatrixMultiplyFaer(
            List<float[]> matricesA, List<float[]> matricesB) {
        return getParallelProcessor().batchMatrixMultiply(matricesA, matricesB, "faer");
    }

    /**
     * Batch vector operations using parallel processing
     */
    public static CompletableFuture<List<float[]>> batchVectorAddNalgebra(
            List<float[]> vectorsA, List<float[]> vectorsB) {
        return getParallelProcessor().batchVectorOperation(vectorsA, vectorsB, "vectorAdd");
    }

    public static CompletableFuture<List<Float>> batchVectorDotGlam(
            List<float[]> vectorsA, List<float[]> vectorsB) {
        return getParallelProcessor().batchVectorDotOperation(vectorsA, vectorsB);
    }

    public static CompletableFuture<List<float[]>> batchVectorCrossGlam(
            List<float[]> vectorsA, List<float[]> vectorsB) {
        return getParallelProcessor().batchVectorCrossOperation(vectorsA, vectorsB);
    }

    /**
     * Zero-copy matrix operations
     */
    public static float[] matrixMultiplyZeroCopy(float[] matrixA, float[] matrixB, String operationType) {
        return getParallelProcessor().matrixMultiplyZeroCopy(matrixA, matrixB, operationType);
    }

    /**
     * Parallel single operations using Fork/Join framework
     */
    public static CompletableFuture<float[]> parallelMatrixMultiply(float[] matrixA, float[] matrixB, String operationType) {
        return getParallelProcessor().parallelMatrixMultiply(matrixA, matrixB, operationType);
    }

    public static CompletableFuture<float[]> parallelVectorAdd(float[] vectorA, float[] vectorB, String operationType) {
        return getParallelProcessor().parallelVectorOperation(vectorA, vectorB, "vectorAdd")
                .thenApply(result -> (float[]) result);
    }

    public static CompletableFuture<Float> parallelVectorDot(float[] vectorA, float[] vectorB, String operationType) {
        return getParallelProcessor().parallelVectorOperation(vectorA, vectorB, "vectorDot")
                .thenApply(result -> (Float) result);
    }

    public static CompletableFuture<float[]> parallelVectorCross(float[] vectorA, float[] vectorB, String operationType) {
        return getParallelProcessor().parallelVectorOperation(vectorA, vectorB, "vectorCross")
                .thenApply(result -> (float[]) result);
    }

    /**
     * Safe memory management operations
     */
    public static float[] safeMatrixMultiply(float[] matrixA, float[] matrixB, String operationType) {
        return getParallelProcessor().safeNativeOperation(matrixA, operationType);
    }

    /**
     * Thread-safe operation queue
     */
    public static CompletableFuture<ParallelRustVectorProcessor.VectorOperationResult> submitOperation(
            ParallelRustVectorProcessor.VectorOperation operation) {
        return getParallelProcessor().submitOperation(operation);
    }

    /**
     * High-level batch processing with automatic optimization
     */
    public static BatchProcessingResult processBatch(BatchProcessingRequest request) {
        if (!isLibraryLoaded) {
            throw new IllegalStateException("Native library not loaded");
        }

        BatchProcessingResult result = new BatchProcessingResult();
        result.startTime = System.currentTimeMillis();

        try {
            // Determine optimal batch size and processing strategy
            int optimalBatchSize = determineOptimalBatchSize(request.operations.size());
            int numBatches = (int) Math.ceil(request.operations.size() / (double) optimalBatchSize);

            List<CompletableFuture<BatchOperationResult>> futures = new ArrayList<>();

            for (int batchIndex = 0; batchIndex < numBatches; batchIndex++) {
                int start = batchIndex * optimalBatchSize;
                int end = Math.min(start + optimalBatchSize, request.operations.size());
                
                List<BatchOperation> batchOperations = request.operations.subList(start, end);
                
                CompletableFuture<BatchOperationResult> batchFuture = processBatchAsync(batchOperations);
                futures.add(batchFuture);
            }

            // Wait for all batches to complete
            List<BatchOperationResult> batchResults = new ArrayList<>();
            for (CompletableFuture<BatchOperationResult> future : futures) {
                try {
                    batchResults.add(future.get());
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException("Batch processing failed", e);
                }
            }

            // Combine results
            result.results = batchResults;
            result.successfulOperations = batchResults.stream()
                    .mapToInt(batch -> batch.successfulOperations)
                    .sum();
            result.failedOperations = batchResults.stream()
                    .mapToInt(batch -> batch.failedOperations)
                    .sum();

        } catch (Exception e) {
            result.error = e;
        }

        result.endTime = System.currentTimeMillis();
        result.totalTimeMs = result.endTime - result.startTime;

        return result;
    }

    /**
     * Process batch operations asynchronously
     */
    private static CompletableFuture<BatchOperationResult> processBatchAsync(List<BatchOperation> operations) {
        return CompletableFuture.supplyAsync(() -> {
            BatchOperationResult result = new BatchOperationResult();
            result.operations = new ArrayList<>();

            for (BatchOperation operation : operations) {
                try {
                    BatchOperationResultItem item = processSingleOperation(operation);
                    result.operations.add(item);
                    if (item.success) {
                        result.successfulOperations++;
                    } else {
                        result.failedOperations++;
                    }
                } catch (Exception e) {
                    BatchOperationResultItem errorItem = new BatchOperationResultItem();
                    errorItem.operation = operation;
                    errorItem.success = false;
                    errorItem.error = e;
                    result.operations.add(errorItem);
                    result.failedOperations++;
                }
            }

            return result;
        });
    }

    /**
     * Process single operation based on type
     */
    private static BatchOperationResultItem processSingleOperation(BatchOperation operation) {
        BatchOperationResultItem result = new BatchOperationResultItem();
        result.operation = operation;
        result.startTime = System.nanoTime();

        try {
            switch (operation.type) {
                case "matrix_mul_nalgebra":
                    result.result = RustVectorLibrary.matrixMultiplyNalgebra(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                case "matrix_mul_glam":
                    result.result = RustVectorLibrary.matrixMultiplyGlam(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                case "matrix_mul_faer":
                    result.result = RustVectorLibrary.matrixMultiplyFaer(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                case "vector_add_nalgebra":
                    result.result = RustVectorLibrary.vectorAddNalgebra(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                case "vector_dot_glam":
                    result.result = RustVectorLibrary.vectorDotGlam(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                case "vector_cross_glam":
                    result.result = RustVectorLibrary.vectorCrossGlam(
                            (float[]) operation.inputA, (float[]) operation.inputB);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + operation.type);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.error = e;
        }

        result.endTime = System.nanoTime();
        result.executionTimeNs = result.endTime - result.startTime;

        return result;
    }

    /**
     * Determine optimal batch size based on system capabilities and operation count
     */
    private static int determineOptimalBatchSize(int totalOperations) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int baseBatchSize = Math.max(10, 100 / availableProcessors);
        
        // Scale batch size based on total operations
        if (totalOperations < 100) {
            return Math.min(baseBatchSize, totalOperations);
        } else if (totalOperations < 1000) {
            return Math.min(baseBatchSize * 2, totalOperations);
        } else {
            return Math.min(baseBatchSize * 5, totalOperations);
        }
    }

    /**
     * Get queue statistics for monitoring
     */
    public static ParallelRustVectorProcessor.QueueStatistics getQueueStatistics() {
        return getParallelProcessor().getQueueStatistics();
    }
    
    /**
     * Get zero-copy data transfer instance
     */
    public static ZeroCopyDataTransfer getZeroCopyTransfer() {
        if (zeroCopyTransfer == null) {
            synchronized (processorLock) {
                if (zeroCopyTransfer == null) {
                    zeroCopyTransfer = ZeroCopyDataTransfer.getInstance();
                    zeroCopyTransfer.initializeComponentIntegration();
                }
            }
        }
        return zeroCopyTransfer;
    }
    
    /**
     * Enhanced matrix multiplication with zero-copy optimization
     */
    public static CompletableFuture<float[]> matrixMultiplyZeroCopyEnhanced(float[] matrixA, float[] matrixB, String operationType) {
        return getParallelProcessor().matrixMultiplyZeroCopyEnhanced(matrixA, matrixB, operationType);
    }
    
    /**
     * Enhanced batch matrix multiplication with zero-copy optimization
     */
    public static CompletableFuture<List<float[]>> batchMatrixMultiplyZeroCopyEnhanced(
            List<float[]> matricesA, List<float[]> matricesB, String operationType) {
        return getParallelProcessor().batchMatrixMultiplyZeroCopy(matricesA, matricesB, operationType);
    }
    
    /**
     * Vector addition with zero-copy optimization using data transfer system
     */
    public static CompletableFuture<float[]> vectorAddZeroCopy(float[] vectorA, float[] vectorB, String operationType) {
        return getZeroCopyTransfer().transferVectorData(
            "JavaComponent", "RustComponent", operationType, vectorA, vectorB, "vector_add")
            .thenApply(result -> {
                if (result.success && result.result instanceof float[]) {
                    return (float[]) result.result;
                } else {
                    throw new RuntimeException("Vector addition failed", result.error);
                }
            });
    }
    
    /**
     * Vector dot product with zero-copy optimization using data transfer system
     */
    public static CompletableFuture<Float> vectorDotZeroCopy(float[] vectorA, float[] vectorB, String operationType) {
        return getZeroCopyTransfer().transferVectorData(
            "JavaComponent", "RustComponent", operationType, vectorA, vectorB, "vector_dot")
            .thenApply(result -> {
                if (result.success && result.result instanceof Float) {
                    return (Float) result.result;
                } else {
                    throw new RuntimeException("Vector dot product failed", result.error);
                }
            });
    }
    
    /**
     * Vector cross product with zero-copy optimization using data transfer system
     */
    public static CompletableFuture<float[]> vectorCrossZeroCopy(float[] vectorA, float[] vectorB, String operationType) {
        return getZeroCopyTransfer().transferVectorData(
            "JavaComponent", "RustComponent", operationType, vectorA, vectorB, "vector_cross")
            .thenApply(result -> {
                if (result.success && result.result instanceof float[]) {
                    return (float[]) result.result;
                } else {
                    throw new RuntimeException("Vector cross product failed", result.error);
                }
            });
    }
    
    /**
     * Create shared buffer for cross-component data sharing
     */
    public static ZeroCopyBufferManager.SharedBuffer createSharedBuffer(int size, String creatorComponent, String sharedName) {
        return getZeroCopyTransfer().createSharedDataBuffer(creatorComponent, sharedName, size);
    }
    
    /**
     * Get zero-copy buffer statistics
     */
    public static ZeroCopyBufferManager.BufferStatistics getZeroCopyBufferStatistics() {
        return ZeroCopyBufferManager.getInstance().getStatistics();
    }
    
    /**
     * Get zero-copy transfer statistics
     */
    public static ZeroCopyDataTransfer.TransferStatistics getZeroCopyTransferStatistics() {
        return getZeroCopyTransfer().getStatistics();
    }
    
    /**
     * Get zero-copy data transfer instance (alternative method)
     */
    public static ZeroCopyDataTransfer getZeroCopyDataTransfer() {
        return getZeroCopyTransfer();
    }

    /**
     * Shutdown parallel processor and release resources
     */
    public static void shutdown() {
        if (parallelProcessor != null) {
            synchronized (processorLock) {
                if (parallelProcessor != null) {
                    parallelProcessor.shutdown();
                    parallelProcessor = null;
                }
            }
        }
    }

    /**
     * Check if native library is loaded
     */
    public static boolean isLibraryLoaded() {
        return isLibraryLoaded;
    }

    /**
     * Batch processing request
     */
    public static class BatchProcessingRequest {
        public List<BatchOperation> operations;
        public boolean useParallelProcessing = true;
        public boolean useZeroCopy = false;
        public int maxConcurrency = Runtime.getRuntime().availableProcessors();
        
        public BatchProcessingRequest() {
            this.operations = new ArrayList<>();
        }
        
        public BatchProcessingRequest addOperation(String type, Object inputA, Object inputB) {
            BatchOperation operation = new BatchOperation();
            operation.type = type;
            operation.inputA = inputA;
            operation.inputB = inputB;
            operations.add(operation);
            return this;
        }
    }

    /**
     * Batch processing result
     */
    public static class BatchProcessingResult {
        public long startTime;
        public long endTime;
        public long totalTimeMs;
        public int successfulOperations;
        public int failedOperations;
        public List<BatchOperationResult> results;
        public Exception error;
        
        public double getSuccessRate() {
            int total = successfulOperations + failedOperations;
            return total > 0 ? (double) successfulOperations / total : 0.0;
        }
    }

    /**
     * Single batch operation
     */
    public static class BatchOperation {
        public String type;
        public Object inputA;
        public Object inputB;
    }

    /**
     * Batch operation result
     */
    public static class BatchOperationResult {
        public List<BatchOperationResultItem> operations;
        public int successfulOperations;
        public int failedOperations;
    }

    /**
     * Single operation result item
     */
    public static class BatchOperationResultItem {
        public BatchOperation operation;
        public Object result;
        public boolean success;
        public Exception error;
        public long startTime;
        public long endTime;
        public long executionTimeNs;
        
        public double getExecutionTimeMs() {
            return executionTimeNs / 1_000_000.0;
        }
    }
}