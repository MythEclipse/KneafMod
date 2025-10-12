package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    @Override public BatchResult executeBatch(String batchName, List<UnifiedBridge.BridgeOperation> operations) throws BridgeException { throw new UnsupportedOperationException("Batch execution not implemented via JNI in this example."); }
    @Override public BridgeConfiguration getConfiguration() { return config; }
    @Override public void setConfiguration(BridgeConfiguration config) { this.config = Objects.requireNonNull(config); }
    @Override public BridgeMetrics getMetrics() { return metrics; }
    @Override public BridgeErrorHandler getErrorHandler() { return errorHandler; }
    @Override public boolean isValid() { return !executorService.isShutdown(); }
}
