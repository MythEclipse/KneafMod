package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// The public UnifiedBridge interface is defined in `UnifiedBridge.java`.

// -------------------------------------------------------------------
// 2. KELAS IMPLEMENTASI UTAMA (DENGAN JNI)
// -------------------------------------------------------------------

/**
 * Implementasi jembatan asinkron yang menggunakan JNI untuk memanggil kode native.
 */
class AsynchronousBridge implements UnifiedBridge {
    private static final Logger LOGGER = Logger.getLogger(AsynchronousBridge.class.getName());

    static {
        try {
            if (!com.kneaf.core.performance.bridge.NativeLibraryLoader.loadNativeLibrary()) {
                LOGGER.warning("Native library 'rustperf' not available via NativeLibraryLoader; JNI features will be disabled.");
            } else {
                LOGGER.info("Native library 'rustperf' loaded successfully via NativeLibraryLoader.");
            }
        } catch (UnsatisfiedLinkError e) {
            // Don't hard-exit here; other code (NativeUnifiedBridge) will attempt to load the native library
            // and will log more details. Log a warning so the JVM can decide how to proceed.
            LOGGER.log(Level.WARNING, "Failed to load native library 'rustperf' via AsynchronousBridge: " + e.getMessage(), e);
        }
    }

    private BridgeConfiguration config;
    private final ExecutorService executorService;
    private final BridgeErrorHandler errorHandler;
    private final BridgeMetrics metrics;

    public AsynchronousBridge(BridgeConfiguration config) {
        this.config = Objects.requireNonNull(config);
        this.errorHandler = BridgeErrorHandler.getDefault();
        this.metrics = BridgeMetrics.getInstance(config);
        
        this.executorService = Executors.newFixedThreadPool(
                config.getDefaultWorkerConcurrency(),
                r -> new Thread(r, "AsyncBridge-Executor-" + r.hashCode())
        );
        
        initializeNative(config);
        LOGGER.info("AsynchronousBridge initialized and native layer configured.");
    }

    @Override
    public CompletableFuture<BridgeResult> executeAsync(String operationName, Object... parameters) {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeSync(operationName, parameters);
            } catch (BridgeException e) {
                LOGGER.log(Level.WARNING, "Asynchronous operation failed for '" + operationName + "'", e);
                return BridgeResultFactory.createFailure(operationName, e.getMessage());
            }
        }, executorService);
    }
    
    @Override
    public BridgeResult executeSync(String operationName, Object... parameters) throws BridgeException {
        Objects.requireNonNull(operationName, "Operation name cannot be null");
        long startTime = System.nanoTime();
        
        try {
            BridgeResult result = executeNative(operationName, parameters);
            metrics.recordOperation(operationName, startTime, System.nanoTime(), calculateBytesProcessed(parameters), result.isSuccess());
            return result;
        } catch (Exception e) {
            metrics.recordOperation(operationName, startTime, System.nanoTime(), calculateBytesProcessed(parameters), false);
            throw errorHandler.createBridgeError("Native operation failed: " + operationName,
                e, BridgeException.BridgeErrorType.NATIVE_CALL_FAILED);
        }
    }

    @Override
    public void shutdown() {
        LOGGER.info("AsynchronousBridge shutting down...");
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(config.getOperationTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warning("Executor service did not terminate gracefully, forcing shutdown.");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.warning("Interrupted while waiting for executor service to shut down.");
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        shutdownNative();
        LOGGER.info("AsynchronousBridge shutdown complete.");
    }

    // --- Metode Native (Implementasi di C++) ---
    private native void initializeNative(BridgeConfiguration config);
    private native BridgeResult executeNative(String operationName, Object[] parameters);
    private native void shutdownNative();

    // --- Metode lainnya ---
    private long calculateBytesProcessed(Object... parameters) {
        if (parameters == null || parameters.length == 0) return 0;
        long totalBytes = 0;
        for (Object param : parameters) {
            if (param instanceof byte[]) {
                totalBytes += ((byte[]) param).length;
            } else if (param instanceof ByteBuffer) {
                totalBytes += ((ByteBuffer) param).capacity();
            } else if (param instanceof String) {
                totalBytes += ((String) param).getBytes().length;
            }
        }
        return totalBytes;
    }

    @Override
    public BatchResult executeBatch(String batchName, List<UnifiedBridge.BridgeOperation> operations) throws BridgeException {
        Objects.requireNonNull(batchName, "Batch name cannot be null");
        Objects.requireNonNull(operations, "Operations list cannot be null");
        
        if (operations.isEmpty()) {
            return new BatchResult.Builder()
                .batchId(System.nanoTime())
                .startTimeNanos(System.nanoTime())
                .endTimeNanos(System.nanoTime())
                .totalTasks(0)
                .successfulTasks(0)
                .failedTasks(0)
                .totalBytesProcessed(0)
                .status("COMPLETED")
                .detailedStats(Map.of("batchName", batchName, "reason", "empty_batch"))
                .build();
        }

        long startTime = System.nanoTime();
        long batchId = System.nanoTime();
        
        try {
            // Initialize batch processing metrics
            metrics.recordOperation(batchName + ".batch.start", startTime, System.nanoTime(), 0, true);
            
            // Convert operations to native format for JNI processing
            byte[][] operationData = convertOperationsToNativeFormat(operations);
            
            // Execute batch via JNI with comprehensive error handling
            BatchResult result = executeNativeBatch(batchName, operationData, batchId, startTime);
            
            // Record completion metrics
            long endTime = System.nanoTime();
            metrics.recordBatchOperation(batchId, batchName, startTime, endTime, operations.size(),
                                       result.getSuccessfulTasks(), result.getFailedTasks(),
                                       result.getTotalBytesProcessed(), result.isSuccessful());
            
            // Update detailed statistics
            Map<String, Object> enhancedStats = new HashMap<>(result.getDetailedStats());
            enhancedStats.put("jniCrossings", operations.size());
            enhancedStats.put("zeroCopyUsed", config.isEnableZeroCopy());
            enhancedStats.put("compressionEnabled", false); // Not implemented yet
            enhancedStats.put("batchOptimizationLevel", determineOptimizationLevel(operations.size()));
            
            return new BatchResult.Builder()
                .batchId(batchId)
                .startTimeNanos(startTime)
                .endTimeNanos(endTime)
                .totalTasks(result.getTotalTasks())
                .successfulTasks(result.getSuccessfulTasks())
                .failedTasks(result.getFailedTasks())
                .totalBytesProcessed(result.getTotalBytesProcessed())
                .status(result.getStatus())
                .errorMessage(result.getErrorMessage())
                .detailedStats(enhancedStats)
                .build();
                
        } catch (Exception e) {
            long endTime = System.nanoTime();
            String errorMessage = "Batch execution failed: " + e.getMessage();
            
            // Record failure metrics
            metrics.recordBatchOperation(batchId, batchName, startTime, endTime, operations.size(),
                                       0, operations.size(), 0, false);
            
            // Create failure result with detailed error information
            Map<String, Object> errorStats = Map.of(
                "batchName", batchName,
                "errorType", e.getClass().getSimpleName(),
                "errorMessage", e.getMessage(),
                "operationsCount", operations.size(),
                "jniAvailable", isNativeLibraryAvailable()
            );
            
            BridgeException bridgeException = errorHandler.createBridgeError(errorMessage, e,
                BridgeException.BridgeErrorType.BATCH_PROCESSING_FAILED);
            bridgeException.addSuppressed(new RuntimeException("Batch error details: " + errorStats.toString()));
            throw bridgeException;
        }
    }

    /**
     * Convert Java operations to native format for JNI processing.
     * Supports zero-copy optimization for large batches.
     */
    private byte[][] convertOperationsToNativeFormat(List<UnifiedBridge.BridgeOperation> operations) {
        byte[][] operationData = new byte[operations.size()][];
        long totalBytes = 0;
        
        for (int i = 0; i < operations.size(); i++) {
            UnifiedBridge.BridgeOperation operation = operations.get(i);
            String operationName = operation.getOperationName();
            Object[] parameters = operation.getParameters();
            
            // Serialize operation to binary format
            byte[] serializedOp = serializeOperationToBinary(operationName, parameters);
            operationData[i] = serializedOp;
            totalBytes += serializedOp.length;
            
            // Apply zero-copy optimization for large operations
            if (config.isEnableZeroCopy() && serializedOp.length > 1024) {
                operationData[i] = applyZeroCopyOptimization(serializedOp);
            }
        }
        
        // Log optimization metrics
        if (totalBytes > 1024 * 1024) { // > 1MB total
            LOGGER.log(Level.INFO, "Large batch detected: {0} operations, {1} bytes total, zero-copy: {2}",
                       new Object[]{operations.size(), totalBytes, config.isEnableZeroCopy()});
        }
        
        return operationData;
    }

    /**
     * Serialize operation to compact binary format for JNI processing.
     */
    private byte[] serializeOperationToBinary(String operationName, Object[] parameters) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            
            // Write operation name
            dos.writeUTF(operationName);
            
            // Write parameter count
            dos.writeInt(parameters != null ? parameters.length : 0);
            
            // Write parameters
            if (parameters != null) {
                for (Object param : parameters) {
                    if (param instanceof byte[]) {
                        byte[] bytes = (byte[]) param;
                        dos.writeInt(bytes.length);
                        dos.write(bytes);
                    } else if (param instanceof ByteBuffer) {
                        ByteBuffer buffer = (ByteBuffer) param;
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        dos.writeInt(bytes.length);
                        dos.write(bytes);
                    } else if (param instanceof String) {
                        String str = (String) param;
                        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                        dos.writeInt(bytes.length);
                        dos.write(bytes);
                    } else {
                        // Fallback to string representation
                        String str = String.valueOf(param);
                        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                        dos.writeInt(bytes.length);
                        dos.write(bytes);
                    }
                }
            }
            
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize operation: " + e.getMessage(), e);
        }
    }

    /**
     * Apply zero-copy optimization for large operations.
     */
    private byte[] applyZeroCopyOptimization(byte[] data) {
        try {
            // Create direct ByteBuffer for zero-copy transfer
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(data.length);
            directBuffer.put(data);
            directBuffer.flip();
            
            // Use JNI to transfer direct buffer without copying
            return transferZeroCopyBuffer(directBuffer);
        } catch (Exception e) {
            LOGGER.warning("Zero-copy optimization failed, falling back to regular transfer: " + e.getMessage());
            return data;
        }
    }

    /**
     * Execute native batch processing via JNI with comprehensive error handling.
     */
    private native BatchResult executeNativeBatch(String batchName, byte[][] operations, long batchId, long startTime);

    /**
     * Transfer zero-copy buffer via JNI.
     */
    private native byte[] transferZeroCopyBuffer(ByteBuffer buffer);

    /**
     * Check if native library is available.
     */
    private native boolean isNativeLibraryAvailable();

    /**
     * Determine optimization level based on batch size.
     */
    private String determineOptimizationLevel(int operationCount) {
        if (operationCount >= 1000) return "MAXIMUM";
        if (operationCount >= 500) return "HIGH";
        if (operationCount >= 100) return "MEDIUM";
        if (operationCount >= 50) return "LOW";
        return "MINIMAL";
    }
    @Override public BridgeConfiguration getConfiguration() { return config; }
    @Override public void setConfiguration(BridgeConfiguration config) { this.config = Objects.requireNonNull(config); }
    @Override public BridgeMetrics getMetrics() { return metrics; }
    @Override public BridgeErrorHandler getErrorHandler() { return errorHandler; }
    @Override public boolean isValid() { return !executorService.isShutdown(); }
}
