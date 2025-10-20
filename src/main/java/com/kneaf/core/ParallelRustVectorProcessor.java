package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Cache-optimized parallel processor for Rust vector operations using Fork/Join framework.
 * Provides batch processing, zero-copy array sharing, and thread-safe operation queue.
 * Enhanced with CPU cache utilization optimizations and work-stealing scheduler.
 * Integrated with ZeroCopyBufferManager for enhanced memory management.
 */
public class ParallelRustVectorProcessor {
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MIN_PARALLEL_THRESHOLD = 10;
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();
    private static final int CACHE_LINE_SIZE = 64; // Typical cache line size in bytes
    private static final int BLOCK_SIZE = 8; // 8x8 blocks for cache-friendly processing
    
    private final ForkJoinPool forkJoinPool;
    private final ExecutorService batchExecutor;
    private final OperationQueue operationQueue;
    private final AtomicLong operationCounter;
    private final Lock memoryLock;
    private final ZeroCopyBufferManager zeroCopyManager;
    private final CacheOptimizedWorkStealingScheduler workStealingScheduler;
    private final ConcurrentHashMap<Integer, CacheAffinity> workerCacheAffinity;
    
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
    
    /**
     * Cache affinity tracking for spatial locality optimization
     */
    public static class CacheAffinity {
        private final List<Integer> preferredBlocks;
        private Integer lastAccessedBlock;
        private double cacheHitRate;
        private final AtomicInteger cacheHits;
        private final AtomicInteger totalAccesses;
        
        public CacheAffinity() {
            this.preferredBlocks = new ArrayList<>();
            this.lastAccessedBlock = null;
            this.cacheHitRate = 0.0;
            this.cacheHits = new AtomicInteger(0);
            this.totalAccesses = new AtomicInteger(0);
        }
        
        public void updatePreferredBlock(int block) {
            this.lastAccessedBlock = block;
            totalAccesses.incrementAndGet();
            
            if (!preferredBlocks.contains(block)) {
                preferredBlocks.add(block);
                if (preferredBlocks.size() > 8) {
                    preferredBlocks.remove(0); // Keep only recent blocks
                }
            } else {
                cacheHits.incrementAndGet();
            }
            
            updateCacheHitRate();
        }
        
        private void updateCacheHitRate() {
            int hits = cacheHits.get();
            int total = totalAccesses.get();
            if (total > 0) {
                cacheHitRate = (double) hits / total;
            }
        }
        
        public List<Integer> getPreferredBlocks() {
            return new ArrayList<>(preferredBlocks);
        }
        
        public double getCacheHitRate() {
            return cacheHitRate;
        }
        
        public Integer getLastAccessedBlock() {
            return lastAccessedBlock;
        }
    }
    
    /**
     * Block distribution for cache-aware work stealing
     */
    public static class BlockDistribution {
        private final ConcurrentHashMap<Integer, Integer> blockWorkload;
        private final ConcurrentHashMap<Integer, Integer> blockOwnership;
        private final AtomicInteger totalBlocks;
        
        public BlockDistribution() {
            this.blockWorkload = new ConcurrentHashMap<>();
            this.blockOwnership = new ConcurrentHashMap<>();
            this.totalBlocks = new AtomicInteger(0);
        }
        
        public void assignBlock(int block, int workerId) {
            blockOwnership.put(block, workerId);
            blockWorkload.put(block, 0);
            totalBlocks.incrementAndGet();
        }
        
        public void incrementBlockWorkload(int block) {
            blockWorkload.computeIfPresent(block, (k, v) -> v + 1);
        }
        
        public Integer getBlockOwner(int block) {
            return blockOwnership.get(block);
        }
        
        public double getLoadImbalance() {
            if (blockWorkload.isEmpty()) {
                return 0.0;
            }
            
            int maxLoad = blockWorkload.values().stream().max(Integer::compareTo).orElse(0);
            int minLoad = blockWorkload.values().stream().min(Integer::compareTo).orElse(0);
            double avgLoad = blockWorkload.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
            
            if (avgLoad > 0.0) {
                return (maxLoad - minLoad) / avgLoad;
            } else {
                return 0.0;
            }
        }
    }
    
    /**
     * Cache-optimized work-stealing scheduler
     */
    public static class CacheOptimizedWorkStealingScheduler {
        private final ForkJoinPool forkJoinPool;
        private final ConcurrentLinkedQueue<VectorOperation> globalQueue;
        private final ConcurrentLinkedQueue<VectorOperation>[] workerQueues;
        private final AtomicInteger successfulSteals;
        private final AtomicInteger stealAttempts;
        private final BlockDistribution blockDistribution;
        
        public CacheOptimizedWorkStealingScheduler(int numWorkers) {
            this.forkJoinPool = new ForkJoinPool(numWorkers);
            this.globalQueue = new ConcurrentLinkedQueue<>();
            this.workerQueues = new ConcurrentLinkedQueue[numWorkers];
            this.successfulSteals = new AtomicInteger(0);
            this.stealAttempts = new AtomicInteger(0);
            this.blockDistribution = new BlockDistribution();
            
            for (int i = 0; i < numWorkers; i++) {
                workerQueues[i] = new ConcurrentLinkedQueue<>();
            }
        }
        
        public void submit(VectorOperation operation) {
            globalQueue.offer(operation);
        }
        
        public void submitToWorker(int workerId, VectorOperation operation) {
            if (workerId >= 0 && workerId < workerQueues.length) {
                workerQueues[workerId].offer(operation);
            } else {
                globalQueue.offer(operation);
            }
        }
        
        public VectorOperation stealWithCacheAffinity(int workerId, List<Integer> preferredBlocks) {
            stealAttempts.incrementAndGet();
            
            // Try to steal from workers that own preferred blocks
            for (int block : preferredBlocks) {
                Integer targetWorker = blockDistribution.getBlockOwner(block);
                if (targetWorker != null && targetWorker != workerId) {
                    VectorOperation task = workerQueues[targetWorker].poll();
                    if (task != null) {
                        successfulSteals.incrementAndGet();
                        return task;
                    }
                }
            }
            
            // Fallback to regular stealing
            return steal(workerId);
        }
        
        public VectorOperation steal(int workerId) {
            stealAttempts.incrementAndGet();
            
            // Try other worker queues
            for (int i = 0; i < workerQueues.length; i++) {
                if (i == workerId) continue;
                
                VectorOperation task = workerQueues[i].poll();
                if (task != null) {
                    successfulSteals.incrementAndGet();
                    return task;
                }
            }
            
            // Try global queue as fallback
            return globalQueue.poll();
        }
        
        public VectorOperation getLocalTask(int workerId) {
            if (workerId >= 0 && workerId < workerQueues.length) {
                return workerQueues[workerId].poll();
            }
            return null;
        }
        
        public double getStealSuccessRate() {
            int attempts = stealAttempts.get();
            if (attempts == 0) return 0.0;
            return (double) successfulSteals.get() / attempts;
        }
        
        public BlockDistribution getBlockDistribution() {
            return blockDistribution;
        }
    }
    
    public ParallelRustVectorProcessor() {
        this.forkJoinPool = new ForkJoinPool(MAX_THREADS);
        this.batchExecutor = Executors.newFixedThreadPool(MAX_THREADS);
        this.operationQueue = new OperationQueue();
        this.operationCounter = new AtomicLong(0);
        this.memoryLock = new ReentrantLock();
        this.zeroCopyManager = ZeroCopyBufferManager.getInstance();
        this.workStealingScheduler = new CacheOptimizedWorkStealingScheduler(MAX_THREADS);
        this.workerCacheAffinity = new ConcurrentHashMap<>();
        
        // Initialize cache affinity for each worker
        for (int i = 0; i < MAX_THREADS; i++) {
            workerCacheAffinity.put(i, new CacheAffinity());
        }
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
     * Cache-optimized batch processing for matrix operations with spatial locality
     */
    public CompletableFuture<List<float[]>> batchMatrixMultiply(List<float[]> matricesA, List<float[]> matricesB, String operationType) {
        if (matricesA.size() != matricesB.size()) {
            throw new IllegalArgumentException("Matrix lists must have same size");
        }
        
        return CompletableFuture.supplyAsync(() -> {
            int batchSize = Math.min(DEFAULT_BATCH_SIZE, matricesA.size());
            List<float[]> results = new ArrayList<>(matricesA.size());
            
            // Process in cache-friendly blocks
            int numBlocks = (matricesA.size() + BLOCK_SIZE - 1) / BLOCK_SIZE;
            
            for (int block = 0; block < numBlocks; block++) {
                int startIdx = block * BLOCK_SIZE;
                int endIdx = Math.min(startIdx + BLOCK_SIZE, matricesA.size());
                
                // Convert to batch format for native processing
                float[][] blockA = new float[endIdx - startIdx][16];
                float[][] blockB = new float[endIdx - startIdx][16];
                
                for (int i = startIdx; i < endIdx; i++) {
                    blockA[i - startIdx] = matricesA.get(i);
                    blockB[i - startIdx] = matricesB.get(i);
                }
                
                // Process block using native batch methods
                float[] blockResults = processBatch(blockA, blockB, operationType);
                
                // Convert results back to individual matrices
                for (int i = 0; i < endIdx - startIdx; i++) {
                    float[] result = new float[16];
                    System.arraycopy(blockResults, i * 16, result, 0, 16);
                    results.add(result);
                }
            }
            
            return results;
        }, batchExecutor);
    }
    
    /**
     * Cache-optimized parallel matrix multiplication with blocking
     */
    public CompletableFuture<float[]> parallelMatrixMultiplyOptimized(float[] matrixA, float[] matrixB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // Use cache-optimized blocking approach
                int blockSize = BLOCK_SIZE;
                int numBlocks = (matrixA.length + blockSize - 1) / blockSize;
                
                List<Future<float[]>> futures = new ArrayList<>();
                
                for (int block = 0; block < numBlocks; block++) {
                    int startIdx = block * blockSize;
                    int endIdx = Math.min(startIdx + blockSize, matrixA.length);
                    
                    Future<float[]> future = forkJoinPool.submit(() -> {
                        float[] blockA = new float[endIdx - startIdx];
                        float[] blockB = new float[endIdx - startIdx];
                        
                        System.arraycopy(matrixA, startIdx, blockA, 0, endIdx - startIdx);
                        System.arraycopy(matrixB, startIdx, blockB, 0, endIdx - startIdx);
                        
                        // Process block with cache-friendly access
                        switch (operationType) {
                            case "nalgebra":
                                return RustVectorLibrary.matrixMultiplyNalgebra(blockA, blockB);
                            case "glam":
                                return RustVectorLibrary.matrixMultiplyGlam(blockA, blockB);
                            case "faer":
                                return RustVectorLibrary.matrixMultiplyFaer(blockA, blockB);
                            default:
                                throw new IllegalArgumentException("Unknown operation type: " + operationType);
                        }
                    });
                    
                    futures.add(future);
                }
                
                // Combine results
                float[] result = new float[matrixA.length];
                for (int block = 0; block < numBlocks; block++) {
                    int startIdx = block * blockSize;
                    int endIdx = Math.min(startIdx + blockSize, matrixA.length);
                    
                    float[] blockResult = futures.get(block).get();
                    System.arraycopy(blockResult, 0, result, startIdx, endIdx - startIdx);
                }
                
                long duration = System.nanoTime() - startTime;
                
                // Record performance metrics
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "parallel_matrix_multiply_optimized", duration,
                    Map.of("operation_type", operationType, "block_size", blockSize, "num_blocks", numBlocks)
                );
                
                return result;
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "parallel_matrix_multiply_optimized", "operation_type", operationType)
                );
                throw new RuntimeException("Cache-optimized parallel matrix multiplication failed", e);
            }
        });
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
     * Enhanced parallel matrix multiplication with cache-aware work stealing
     */
    public CompletableFuture<float[]> parallelMatrixMultiply(float[] matrixA, float[] matrixB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // Use cache-optimized approach by default
                return parallelMatrixMultiplyOptimized(matrixA, matrixB, operationType).get();
                
            } catch (Exception e) {
                // Fallback to traditional Fork/Join if cache-optimized fails
                try {
                    MatrixMulTask task = new MatrixMulTask(matrixA, matrixB, 0, 1, operationType);
                    float[] result = forkJoinPool.invoke(task);
                    
                    long duration = System.nanoTime() - startTime;
                    PerformanceMonitoringSystem.getInstance().recordEvent(
                        "ParallelRustVectorProcessor", "parallel_matrix_multiply_fallback", duration,
                        Map.of("operation_type", operationType)
                    );
                    
                    return result;
                } catch (Exception fallbackException) {
                    long duration = System.nanoTime() - startTime;
                    PerformanceMonitoringSystem.getInstance().recordError(
                        "ParallelRustVectorProcessor", fallbackException,
                        Map.of("operation", "parallel_matrix_multiply_fallback", "operation_type", operationType)
                    );
                    throw new RuntimeException("Parallel matrix multiplication failed", fallbackException);
                }
            }
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
     * Cache-optimized batch vector operations with spatial locality
     */
    public CompletableFuture<List<float[]>> batchVectorOperation(List<float[]> vectorsA, List<float[]> vectorsB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            List<float[]> results = new ArrayList<>(vectorsA.size());
            
            // Process in cache-friendly blocks
            int numBlocks = (vectorsA.size() + BLOCK_SIZE - 1) / BLOCK_SIZE;
            
            for (int block = 0; block < numBlocks; block++) {
                int startIdx = block * BLOCK_SIZE;
                int endIdx = Math.min(startIdx + BLOCK_SIZE, vectorsA.size());
                
                // Process block with cache-friendly access
                for (int i = startIdx; i < endIdx; i++) {
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
            }
            
            return results;
        }, batchExecutor);
    }
    
    /**
     * Cache-aware vector operations with work stealing
     */
    public CompletableFuture<List<float[]>> cacheOptimizedVectorOperation(List<float[]> vectorsA, List<float[]> vectorsB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                List<float[]> results = new ArrayList<>(vectorsA.size());
                
                // Create cache-aware tasks
                int numTasks = Math.min(vectorsA.size(), MAX_THREADS * 2);
                int taskSize = vectorsA.size() / numTasks;
                
                List<Future<List<float[]>>> futures = new ArrayList<>();
                
                for (int taskId = 0; taskId < numTasks; taskId++) {
                    int startIdx = taskId * taskSize;
                    int endIdx = (taskId == numTasks - 1) ? vectorsA.size() : (taskId + 1) * taskSize;
                    
                    Future<List<float[]>> future = forkJoinPool.submit(() -> {
                        List<float[]> taskResults = new ArrayList<>(endIdx - startIdx);
                        
                        // Process with cache-friendly access pattern
                        for (int i = startIdx; i < endIdx; i++) {
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
                            taskResults.add(result);
                        }
                        
                        return taskResults;
                    });
                    
                    futures.add(future);
                }
                
                // Collect results
                for (Future<List<float[]>> future : futures) {
                    results.addAll(future.get());
                }
                
                long duration = System.nanoTime() - startTime;
                
                // Record performance metrics
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "cache_optimized_vector_operation", duration,
                    Map.of("operation_type", operationType, "num_tasks", numTasks, "total_vectors", vectorsA.size())
                );
                
                return results;
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "cache_optimized_vector_operation", "operation_type", operationType)
                );
                throw new RuntimeException("Cache-optimized vector operation failed", e);
            }
        });
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
     * Cache-optimized zero-copy matrix multiplication with enhanced buffer management
     */
    public CompletableFuture<float[]> matrixMultiplyZeroCopyEnhanced(float[] matrixA, float[] matrixB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                // Use cache-aligned buffer size for better performance
                int dataSize = ((matrixA.length + matrixB.length) * 4 + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE * CACHE_LINE_SIZE;
                
                ZeroCopyBufferManager.ManagedDirectBuffer sharedBuffer =
                    zeroCopyManager.allocateBuffer(dataSize, "ParallelRustVectorProcessor");
                
                // Copy data to shared buffer with cache-friendly layout
                ByteBuffer buffer = sharedBuffer.getBuffer();
                FloatBuffer floatBuffer = buffer.asFloatBuffer();
                
                // Ensure cache-aligned access
                floatBuffer.put(matrixA);
                floatBuffer.put(matrixB);
                
                // Perform zero-copy operation with cache optimization
                ZeroCopyBufferManager.ZeroCopyOperation operation = new ZeroCopyBufferManager.ZeroCopyOperation() {
                    @Override
                    public Object execute() throws Exception {
                        // Use cache-aligned buffer for result
                        ByteBuffer resultBuffer = ByteBuffer.allocateDirect(
                            ((16 * 4 + CACHE_LINE_SIZE - 1) / CACHE_LINE_SIZE) * CACHE_LINE_SIZE
                        ).order(ByteOrder.nativeOrder());
                        
                        ByteBuffer operationResult;
                        switch (operationType) {
                            case "nalgebra":
                                operationResult = nalgebraMatrixMulDirect(buffer, buffer, resultBuffer);
                                break;
                            case "glam":
                                operationResult = glamMatrixMulDirect(buffer, buffer, resultBuffer);
                                break;
                            case "faer":
                                operationResult = faerMatrixMulDirect(buffer, buffer, resultBuffer);
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown operation type: " + operationType);
                        }
                        
                        // Extract result with cache-friendly access
                        float[] result = new float[16];
                        operationResult.asFloatBuffer().get(result);
                        return result;
                    }
                    
                    @Override
                    public String getType() {
                        return "cache_optimized_matrix_multiply_" + operationType;
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
                
                // Record performance metrics with cache optimization details
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "cache_optimized_matrix_multiply_zero_copy", duration,
                    Map.of(
                        "operation_type", operationType,
                        "bytes_transferred", dataSize,
                        "cache_line_size", CACHE_LINE_SIZE,
                        "buffer_alignment", "cache_aligned"
                    )
                );
                
                if (result.success && result.result instanceof float[]) {
                    return (float[]) result.result;
                } else {
                    throw new RuntimeException("Cache-optimized zero-copy operation failed", result.error);
                }
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "cache_optimized_matrix_multiply_zero_copy", "operation_type", operationType)
                );
                throw new RuntimeException("Cache-optimized zero-copy matrix multiplication failed", e);
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
     * Get cache-optimized work-stealing scheduler
     */
    public CacheOptimizedWorkStealingScheduler getWorkStealingScheduler() {
        return workStealingScheduler;
    }
    
    /**
     * Get cache affinity for specific worker
     */
    public CacheAffinity getWorkerCacheAffinity(int workerId) {
        return workerCacheAffinity.get(workerId);
    }
    
    /**
     * Get cache statistics for all workers
     */
    public Map<Integer, Double> getCacheHitRates() {
        Map<Integer, Double> hitRates = new HashMap<>();
        for (Map.Entry<Integer, CacheAffinity> entry : workerCacheAffinity.entrySet()) {
            hitRates.put(entry.getKey(), entry.getValue().getCacheHitRate());
        }
        return hitRates;
    }
    
    /**
     * Get work-stealing statistics
     */
    public Map<String, Object> getWorkStealingStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("successful_steals", workStealingScheduler.successfulSteals.get());
        stats.put("steal_attempts", workStealingScheduler.stealAttempts.get());
        stats.put("steal_success_rate", workStealingScheduler.getStealSuccessRate());
        stats.put("load_imbalance", workStealingScheduler.getBlockDistribution().getLoadImbalance());
        return stats;
    }
    
    /**
     * Cache-optimized batch processing with work stealing
     */
    public CompletableFuture<List<float[]>> cacheOptimizedBatchMatrixMultiply(List<float[]> matricesA, List<float[]> matricesB, String operationType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            
            try {
                if (matricesA.size() != matricesB.size()) {
                    throw new IllegalArgumentException("Matrix lists must have same size");
                }
                
                List<float[]> results = new ArrayList<>(matricesA.size());
                
                // Create cache-aware work distribution
                int numWorkers = MAX_THREADS;
                int matricesPerWorker = matricesA.size() / numWorkers;
                
                List<Future<List<float[]>>> futures = new ArrayList<>();
                
                for (int workerId = 0; workerId < numWorkers; workerId++) {
                    final int finalWorkerId = workerId;
                    int startIdx = workerId * matricesPerWorker;
                    int endIdx = (workerId == numWorkers - 1) ? matricesA.size() : (workerId + 1) * matricesPerWorker;
                    
                    Future<List<float[]>> future = forkJoinPool.submit(() -> {
                        List<float[]> workerResults = new ArrayList<>(endIdx - startIdx);
                        
                        // Process in cache-friendly blocks
                        for (int i = startIdx; i < endIdx; i++) {
                            // Use cache-optimized zero-copy approach
                            float[] result = matrixMultiplyZeroCopyEnhanced(matricesA.get(i), matricesB.get(i), operationType).get();
                            workerResults.add(result);
                            
                            // Update cache affinity
                            CacheAffinity affinity = workerCacheAffinity.get(finalWorkerId);
                            if (affinity != null) {
                                affinity.updatePreferredBlock(i / BLOCK_SIZE);
                            }
                        }
                        
                        return workerResults;
                    });
                    
                    futures.add(future);
                }
                
                // Collect results
                for (Future<List<float[]>> future : futures) {
                    results.addAll(future.get());
                }
                
                long duration = System.nanoTime() - startTime;
                
                // Record performance metrics
                PerformanceMonitoringSystem.getInstance().recordEvent(
                    "ParallelRustVectorProcessor", "cache_optimized_batch_matrix_multiply", duration,
                    Map.of(
                        "operation_type", operationType,
                        "total_matrices", matricesA.size(),
                        "num_workers", numWorkers,
                        "cache_line_size", CACHE_LINE_SIZE,
                        "block_size", BLOCK_SIZE
                    )
                );
                
                return results;
                
            } catch (Exception e) {
                long duration = System.nanoTime() - startTime;
                PerformanceMonitoringSystem.getInstance().recordError(
                    "ParallelRustVectorProcessor", e,
                    Map.of("operation", "cache_optimized_batch_matrix_multiply", "operation_type", operationType)
                );
                throw new RuntimeException("Cache-optimized batch matrix multiplication failed", e);
            }
        });
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
        
        // Log final cache statistics
        Map<Integer, Double> finalHitRates = getCacheHitRates();
        Map<String, Object> finalWorkStealingStats = getWorkStealingStatistics();
        
        System.out.println("Final cache hit rates: " + finalHitRates);
        System.out.println("Final work-stealing statistics: " + finalWorkStealingStats);
    }
}