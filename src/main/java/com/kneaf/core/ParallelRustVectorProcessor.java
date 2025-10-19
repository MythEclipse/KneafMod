package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.util.HashMap;

/**
 * Parallel processor for Rust vector operations using Fork/Join framework.
 * Provides batch processing, zero-copy array sharing, and thread-safe operation queue.
 * Integrated with ZeroCopyBufferManager for enhanced memory management.
 */
public class ParallelRustVectorProcessor {
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MIN_PARALLEL_THRESHOLD = 10;
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();
    
    private final ForkJoinPool forkJoinPool;
    private final ExecutorService batchExecutor;
    private final OperationQueue operationQueue;
    private final AtomicLong operationCounter;
    private final Lock memoryLock;
    private final ZeroCopyBufferManager zeroCopyManager;
    
    // Native batch processing methods
    private static native float[] batchNalgebraMatrixMul(float[][] matricesA, float[][] matricesB, int count);
    private static native float[] batchNalgebraVectorAdd(float[][] vectorsA, float[][] vectorsB, int count);
    private static native float[] batchGlamVectorDot(float[][] vectorsA, float[][] vectorsB, int count);
    private static native float[] batchGlamVectorCross(float[][] vectorsA, float[][] vectorsB, int count);
    private static native float[] batchGlamMatrixMul(float[][] matricesA, float[][] matricesB, int count);
    private static native float[] batchFaerMatrixMul(float[][] matricesA, float[][] matricesB, int count);
    
    // Zero-copy native methods using direct ByteBuffers
    private static native ByteBuffer nalgebraMatrixMulDirect(ByteBuffer a, ByteBuffer b, ByteBuffer result);
    private static native ByteBuffer nalgebraVectorAddDirect(ByteBuffer a, ByteBuffer b, ByteBuffer result);
    private static native float glamVectorDotDirect(ByteBuffer a, ByteBuffer b);
    private static native ByteBuffer glamVectorCrossDirect(ByteBuffer a, ByteBuffer b, ByteBuffer result);
    private static native ByteBuffer glamMatrixMulDirect(ByteBuffer a, ByteBuffer b, ByteBuffer result);
    private static native ByteBuffer faerMatrixMulDirect(ByteBuffer a, ByteBuffer b, ByteBuffer result);
    
    // Safe memory management native methods
    private static native void releaseNativeBuffer(long pointer);
    private static native long allocateNativeBuffer(int size);
    private static native void copyToNativeBuffer(long pointer, float[] data, int offset, int length);
    private static native void copyFromNativeBuffer(long pointer, float[] result, int offset, int length);
    
    public ParallelRustVectorProcessor() {
        this.forkJoinPool = new ForkJoinPool(MAX_THREADS);
        this.batchExecutor = Executors.newFixedThreadPool(MAX_THREADS);
        this.operationQueue = new OperationQueue();
        this.operationCounter = new AtomicLong(0);
        this.memoryLock = new ReentrantLock();
        this.zeroCopyManager = ZeroCopyBufferManager.getInstance();
    }
    
    /**
     * Thread-safe operation queue for managing parallel tasks
     */
    private static class OperationQueue {
        private final ConcurrentLinkedQueue<VectorOperation> pendingOperations;
        private final ConcurrentHashMap<Long, CompletableFuture<VectorOperationResult>> activeFutures;
        private final AtomicLong operationIdGenerator;
        
        public OperationQueue() {
            this.pendingOperations = new ConcurrentLinkedQueue<>();
            this.activeFutures = new ConcurrentHashMap<>();
            this.operationIdGenerator = new AtomicLong(0);
        }
        
        public long submitOperation(VectorOperation operation) {
            long operationId = operationIdGenerator.incrementAndGet();
            operation.setOperationId(operationId);
            pendingOperations.offer(operation);
            return operationId;
        }
        
        public VectorOperation pollOperation() {
            return pendingOperations.poll();
        }
        
        public void completeOperation(long operationId, VectorOperationResult result) {
            CompletableFuture<VectorOperationResult> future = activeFutures.remove(operationId);
            if (future != null) {
                future.complete(result);
            }
        }
        
        public CompletableFuture<VectorOperationResult> getFuture(long operationId) {
            CompletableFuture<VectorOperationResult> future = new CompletableFuture<>();
            activeFutures.put(operationId, future);
            return future;
        }
        
        public int getPendingOperationCount() {
            return pendingOperations.size();
        }
    }
    
    /**
     * Base class for vector operations
     */
    public static abstract class VectorOperation {
        protected long operationId;
        protected final long timestamp;
        
        public VectorOperation() {
            this.timestamp = System.nanoTime();
        }
        
        public void setOperationId(long operationId) {
            this.operationId = operationId;
        }
        
        public long getOperationId() {
            return operationId;
        }
        
        public abstract VectorOperationResult execute();
        public abstract int getEstimatedWorkload();
    }
    
    /**
     * Result of vector operation
     */
    public static class VectorOperationResult {
        public final long operationId;
        public final Object result;
        public final long executionTimeNs;
        public final Exception error;
        
        public VectorOperationResult(long operationId, Object result, long executionTimeNs) {
            this.operationId = operationId;
            this.result = result;
            this.executionTimeNs = executionTimeNs;
            this.error = null;
        }
        
        public VectorOperationResult(long operationId, Exception error) {
            this.operationId = operationId;
            this.result = null;
            this.executionTimeNs = 0;
            this.error = error;
        }
    }
    
    /**
     * Fork/Join task for parallel matrix multiplication
     */
    private static class MatrixMulTask extends RecursiveTask<float[]> {
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
            switch (operationType) {
                case "nalgebra":
                    return RustVectorLibrary.matrixMultiplyNalgebra(matrixA, matrixB);
                case "glam":
                    return RustVectorLibrary.matrixMultiplyGlam(matrixA, matrixB);
                case "faer":
                    return RustVectorLibrary.matrixMultiplyFaer(matrixA, matrixB);
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + operationType);
            }
        }
        
        private float[] combineResults(float[] left, float[] right) {
            // For matrix operations, results are identical, so return one
            return left;
        }
    }
    
    /**
     * Fork/Join task for parallel vector operations
     */
    private static class VectorOperationTask extends RecursiveTask<Object> {
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
            switch (operationType) {
                case "vectorAdd":
                    return RustVectorLibrary.vectorAddNalgebra(vectorA, vectorB);
                case "vectorDot":
                    return RustVectorLibrary.vectorDotGlam(vectorA, vectorB);
                case "vectorCross":
                    return RustVectorLibrary.vectorCrossGlam(vectorA, vectorB);
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + operationType);
            }
        }
        
        private Object combineResults(Object left, Object right) {
            // For vector operations, results are combined based on operation type
            if (operationType.equals("vectorDot")) {
                return (Float)left + (Float)right;
            }
            return left; // For other operations, return first result
        }
    }
    
    /**
     * Batch processing for matrix operations
     */
    public CompletableFuture<List<float[]>> batchMatrixMultiply(List<float[]> matricesA, List<float[]> matricesB, String operationType) {
        if (matricesA.size() != matricesB.size()) {
            throw new IllegalArgumentException("Matrix lists must have same size");
        }
        
        return CompletableFuture.supplyAsync(() -> {
            int batchSize = Math.min(DEFAULT_BATCH_SIZE, matricesA.size());
            List<float[]> results = new ArrayList<>(matricesA.size());
            
            for (int i = 0; i < matricesA.size(); i += batchSize) {
                int end = Math.min(i + batchSize, matricesA.size());
                
                // Convert to batch format for native processing
                float[][] batchA = new float[end - i][16];
                float[][] batchB = new float[end - i][16];
                
                for (int j = i; j < end; j++) {
                    batchA[j - i] = matricesA.get(j);
                    batchB[j - i] = matricesB.get(j);
                }
                
                // Process batch using native batch methods
                float[] batchResults = processBatch(batchA, batchB, operationType);
                
                // Convert results back to individual matrices
                for (int j = 0; j < end - i; j++) {
                    float[] result = new float[16];
                    System.arraycopy(batchResults, j * 16, result, 0, 16);
                    results.add(result);
                }
            }
            
            return results;
        }, batchExecutor);
    }
    
    /**
     * Zero-copy matrix multiplication using direct ByteBuffers
     */
    public float[] matrixMultiplyZeroCopy(float[] matrixA, float[] matrixB, String operationType) {
        memoryLock.lock();
        try {
            // Allocate direct ByteBuffers
            ByteBuffer bufferA = ByteBuffer.allocateDirect(matrixA.length * 4).order(ByteOrder.nativeOrder());
            ByteBuffer bufferB = ByteBuffer.allocateDirect(matrixB.length * 4).order(ByteOrder.nativeOrder());
            ByteBuffer bufferResult = ByteBuffer.allocateDirect(matrixA.length * 4).order(ByteOrder.nativeOrder());
            
            // Copy data to direct buffers
            bufferA.asFloatBuffer().put(matrixA);
            bufferB.asFloatBuffer().put(matrixB);
            
            // Perform operation using direct buffers
            ByteBuffer resultBuffer;
            switch (operationType) {
                case "nalgebra":
                    resultBuffer = nalgebraMatrixMulDirect(bufferA, bufferB, bufferResult);
                    break;
                case "glam":
                    resultBuffer = glamMatrixMulDirect(bufferA, bufferB, bufferResult);
                    break;
                case "faer":
                    resultBuffer = faerMatrixMulDirect(bufferA, bufferB, bufferResult);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown operation type: " + operationType);
            }
            
            // Extract result
            float[] result = new float[matrixA.length];
            resultBuffer.asFloatBuffer().get(result);
            
            return result;
        } finally {
            memoryLock.unlock();
        }
    }
    
    /**
     * Parallel matrix multiplication using Fork/Join framework
     */
    public CompletableFuture<float[]> parallelMatrixMultiply(float[] matrixA, float[] matrixB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            MatrixMulTask task = new MatrixMulTask(matrixA, matrixB, 0, 1, operationType);
            return forkJoinPool.invoke(task);
        });
    }
    
    /**
     * Parallel vector operation using Fork/Join framework
     */
    public CompletableFuture<Object> parallelVectorOperation(float[] vectorA, float[] vectorB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            VectorOperationTask task = new VectorOperationTask(vectorA, vectorB, 0, 1, operationType);
            return forkJoinPool.invoke(task);
        });
    }
    
    /**
     * Safe memory management for native operations
     */
    public float[] safeNativeOperation(float[] data, String operationType) {
        memoryLock.lock();
        try {
            // Allocate native memory
            long pointer = allocateNativeBuffer(data.length * 4);
            
            try {
                // Copy data to native memory
                copyToNativeBuffer(pointer, data, 0, data.length);
                
                // Perform operation (would need corresponding native method)
                // This is a placeholder - actual implementation would call native method
                
                // Copy result back
                float[] result = new float[data.length];
                copyFromNativeBuffer(pointer, result, 0, data.length);
                
                return result;
            } finally {
                // Always release native memory
                releaseNativeBuffer(pointer);
            }
        } finally {
            memoryLock.unlock();
        }
    }
    
    /**
     * Batch vector addition operations
     */
    public CompletableFuture<List<float[]>> batchVectorOperation(List<float[]> vectorsA, List<float[]> vectorsB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            List<float[]> results = new ArrayList<>(vectorsA.size());
            
            for (int i = 0; i < vectorsA.size(); i++) {
                float[] result;
                switch (operationType) {
                    case "vectorAdd":
                        result = RustVectorLibrary.vectorAddNalgebra(vectorsA.get(i), vectorsB.get(i));
                        break;
                    case "vectorCross":
                        result = RustVectorLibrary.vectorCrossGlam(vectorsA.get(i), vectorsB.get(i));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown vector operation type: " + operationType);
                }
                results.add(result);
            }
            
            return results;
        }, batchExecutor);
    }

    /**
     * Batch vector dot operations
     */
    public CompletableFuture<List<Float>> batchVectorDotOperation(List<float[]> vectorsA, List<float[]> vectorsB) {
        return CompletableFuture.supplyAsync(() -> {
            List<Float> results = new ArrayList<>(vectorsA.size());
            
            for (int i = 0; i < vectorsA.size(); i++) {
                float result = RustVectorLibrary.vectorDotGlam(vectorsA.get(i), vectorsB.get(i));
                results.add(result);
            }
            
            return results;
        }, batchExecutor);
    }

    /**
     * Batch vector cross operations
     */
    public CompletableFuture<List<float[]>> batchVectorCrossOperation(List<float[]> vectorsA, List<float[]> vectorsB) {
        return CompletableFuture.supplyAsync(() -> {
            List<float[]> results = new ArrayList<>(vectorsA.size());
            
            for (int i = 0; i < vectorsA.size(); i++) {
                float[] result = RustVectorLibrary.vectorCrossGlam(vectorsA.get(i), vectorsB.get(i));
                results.add(result);
            }
            
            return results;
        }, batchExecutor);
    }

    /**
     * Process batch of operations using native batch methods
     */
    private float[] processBatch(float[][] batchA, float[][] batchB, String operationType) {
        switch (operationType) {
            case "nalgebra":
                return batchNalgebraMatrixMul(batchA, batchB, batchA.length);
            case "glam":
                return batchGlamMatrixMul(batchA, batchB, batchA.length);
            case "faer":
                return batchFaerMatrixMul(batchA, batchB, batchA.length);
            default:
                throw new IllegalArgumentException("Unknown operation type: " + operationType);
        }
    }
    
    /**
     * Submit operation to thread-safe queue
     */
    public CompletableFuture<VectorOperationResult> submitOperation(VectorOperation operation) {
        long operationId = operationQueue.submitOperation(operation);
        CompletableFuture<VectorOperationResult> future = operationQueue.getFuture(operationId);
        
        // Process operation asynchronously
        CompletableFuture.runAsync(() -> {
            long startTime = System.nanoTime();
            try {
                VectorOperationResult result = operation.execute();
                long executionTime = System.nanoTime() - startTime;
                operationQueue.completeOperation(operationId, 
                    new VectorOperationResult(operationId, result.result, executionTime));
            } catch (Exception e) {
                operationQueue.completeOperation(operationId, 
                    new VectorOperationResult(operationId, e));
            }
        });
        
        return future;
    }
    
    /**
     * Get queue statistics
     */
    public QueueStatistics getQueueStatistics() {
        return new QueueStatistics(
            operationQueue.getPendingOperationCount(),
            operationCounter.get(),
            forkJoinPool.getActiveThreadCount(),
            forkJoinPool.getQueuedTaskCount()
        );
    }
    
    /**
     * Queue statistics data class
     */
    public static class QueueStatistics {
        public final int pendingOperations;
        public final long totalOperations;
        public final int activeThreads;
        public final long queuedTasks;
        
        public QueueStatistics(int pendingOperations, long totalOperations, int activeThreads, long queuedTasks) {
            this.pendingOperations = pendingOperations;
            this.totalOperations = totalOperations;
            this.activeThreads = activeThreads;
            this.queuedTasks = queuedTasks;
        }
    }
    
    /**
     * Zero-copy matrix multiplication using enhanced buffer management
     */
    public CompletableFuture<float[]> matrixMultiplyZeroCopyEnhanced(float[] matrixA, float[] matrixB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // Allocate zero-copy buffer for input data
                int dataSize = (matrixA.length + matrixB.length) * 4; // float size
                ZeroCopyBufferManager.ManagedDirectBuffer sharedBuffer =
                    zeroCopyManager.allocateBuffer(dataSize, "ParallelRustVectorProcessor");
                
                // Copy data to shared buffer
                ByteBuffer buffer = sharedBuffer.getBuffer();
                buffer.asFloatBuffer().put(matrixA);
                buffer.asFloatBuffer().put(matrixB);
                
                // Perform zero-copy operation
                ZeroCopyBufferManager.ZeroCopyOperation operation = new ZeroCopyBufferManager.ZeroCopyOperation() {
                    @Override
                    public Object execute() throws Exception {
                        // Call native zero-copy method
                        ByteBuffer resultBuffer;
                        switch (operationType) {
                            case "nalgebra":
                                resultBuffer = nalgebraMatrixMulDirect(buffer, buffer, ByteBuffer.allocateDirect(64));
                                break;
                            case "glam":
                                resultBuffer = glamMatrixMulDirect(buffer, buffer, ByteBuffer.allocateDirect(64));
                                break;
                            case "faer":
                                resultBuffer = faerMatrixMulDirect(buffer, buffer, ByteBuffer.allocateDirect(64));
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown operation type: " + operationType);
                        }
                        
                        // Extract result
                        float[] result = new float[16];
                        resultBuffer.asFloatBuffer().get(result);
                        return result;
                    }
                    
                    @Override
                    public String getType() {
                        return "matrix_multiply_" + operationType;
                    }
                    
                    @Override
                    public long getBytesTransferred() {
                        return dataSize;
                    }
                    
                    @Override
                    public ZeroCopyBufferManager.ManagedDirectBuffer getSourceBuffer() {
                        return sharedBuffer;
                    }
                };
                
                ZeroCopyBufferManager.ZeroCopyOperationResult result =
                    zeroCopyManager.performZeroCopyOperation(operation);
                
                // Release buffer
                zeroCopyManager.releaseBuffer(sharedBuffer, "ParallelRustVectorProcessor");
                
                long duration = System.nanoTime() - startTime;
                
                // Record performance metrics
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "matrix_multiply_zero_copy", duration,
                    Map.of("operation_type", operationType, "bytes_transferred", dataSize)
                );
                
                if (result.success && result.result instanceof float[]) {
                    return (float[]) result.result;
                } else {
                    throw new RuntimeException("Zero-copy operation failed", result.error);
                }
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "matrix_multiply_zero_copy", "operation_type", operationType)
                );
                throw new RuntimeException("Enhanced zero-copy matrix multiplication failed", e);
            }
        });
    }
    
    /**
     * Batch processing with zero-copy optimization
     */
    public CompletableFuture<List<float[]>> batchMatrixMultiplyZeroCopy(
            List<float[]> matricesA, List<float[]> matricesB, String operationType) {
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                int totalMatrices = matricesA.size();
                int totalDataSize = totalMatrices * 16 * 2 * 4; // 16 floats per matrix, 2 matrices, 4 bytes per float
                
                // Allocate large shared buffer for batch processing
                ZeroCopyBufferManager.ManagedDirectBuffer sharedBuffer =
                    zeroCopyManager.allocateBuffer(totalDataSize, "ParallelRustVectorProcessor");
                
                // Copy all matrices to shared buffer
                ByteBuffer buffer = sharedBuffer.getBuffer();
                FloatBuffer floatBuffer = buffer.asFloatBuffer();
                
                for (int i = 0; i < totalMatrices; i++) {
                    floatBuffer.put(matricesA.get(i));
                    floatBuffer.put(matricesB.get(i));
                }
                
                // Process batch using zero-copy operations
                List<float[]> results = new ArrayList<>();
                
                for (int i = 0; i < totalMatrices; i++) {
                    final int offset = i * 128; // Calculate offset for each matrix pair
                    
                    ZeroCopyBufferManager.ZeroCopyOperation operation = new ZeroCopyBufferManager.ZeroCopyOperation() {
                        @Override
                        public Object execute() throws Exception {
                            // Perform matrix multiplication using existing native methods
                            ByteBuffer matrixA = buffer.duplicate().position(offset).limit(offset + 64);
                            ByteBuffer matrixB = buffer.duplicate().position(offset + 64).limit(offset + 128);
                            ByteBuffer result = ByteBuffer.allocateDirect(64);
                            
                            ByteBuffer resultBuffer;
                            switch (operationType) {
                                case "nalgebra":
                                    resultBuffer = nalgebraMatrixMulDirect(matrixA, matrixB, result);
                                    break;
                                case "glam":
                                    resultBuffer = glamMatrixMulDirect(matrixA, matrixB, result);
                                    break;
                                case "faer":
                                    resultBuffer = faerMatrixMulDirect(matrixA, matrixB, result);
                                    break;
                                default:
                                    throw new IllegalArgumentException("Unknown operation type: " + operationType);
                            }
                            
                            float[] resultArray = new float[16];
                            resultBuffer.asFloatBuffer().get(resultArray);
                            return resultArray;
                        }
                        
                        @Override
                        public String getType() {
                            return "batch_matrix_multiply_" + operationType;
                        }
                        
                        @Override
                        public long getBytesTransferred() {
                            return 128; // 2 matrices * 16 floats * 4 bytes
                        }
                        
                        @Override
                        public ZeroCopyBufferManager.ManagedDirectBuffer getSourceBuffer() {
                            return sharedBuffer;
                        }
                    };
                    
                    ZeroCopyBufferManager.ZeroCopyOperationResult result =
                        zeroCopyManager.performZeroCopyOperation(operation);
                    
                    if (result.success && result.result instanceof float[]) {
                        results.add((float[]) result.result);
                    } else {
                        throw new RuntimeException("Batch zero-copy operation failed", result.error);
                    }
                }
                
                // Release shared buffer
                zeroCopyManager.releaseBuffer(sharedBuffer, "ParallelRustVectorProcessor");
                
                long duration = System.nanoTime() - startTime;
                
                // Record performance metrics
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "batch_matrix_multiply_zero_copy", duration,
                    Map.of("operation_type", operationType, "matrix_count", totalMatrices, "bytes_transferred", totalDataSize)
                );
                
                return results;
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "batch_matrix_multiply_zero_copy", "operation_type", operationType)
                );
                throw new RuntimeException("Enhanced batch matrix multiplication failed", e);
            }
        });
    }
    
    /**
     * Get zero-copy buffer manager
     */
    public ZeroCopyBufferManager getZeroCopyManager() {
        return zeroCopyManager;
    }
    
    /**
     * Shutdown processor and release resources
     */
    public void shutdown() {
        forkJoinPool.shutdown();
        batchExecutor.shutdown();
        
        try {
            if (!forkJoinPool.awaitTermination(5, TimeUnit.SECONDS)) {
                forkJoinPool.shutdownNow();
            }
            if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            forkJoinPool.shutdownNow();
            batchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown zero-copy manager
        if (zeroCopyManager != null) {
            zeroCopyManager.shutdown();
        }
    }
}