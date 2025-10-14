package com.kneaf.core.unifiedbridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full implementation of unified bridge to Rust with JNI integration.
 * Handles native library loading, method binding, connection management, and error handling.
 */
public class UnifiedBridgeImpl implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(UnifiedBridgeImpl.class.getName());

    // Native library name
    private static final String LIB_NAME = "kneaf_core";
    private static final String LIB_PATH_ENV = "KNEAF_NATIVE_LIB_PATH";

    // Connection state
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong connectionId = new AtomicLong(0);

    // Configuration
    private final BridgeConfiguration config;

    // Native resources
    private volatile long nativeHandle = 0;
    private volatile boolean nativeLibraryLoaded = false;

    // Threading
    private final ExecutorService bridgeExecutor = Executors.newSingleThreadExecutor(
        r -> {
            Thread t = new Thread(r, "unified-bridge-executor");
            t.setDaemon(true);
            return t;
        }
    );

    // Health monitoring
    private final ScheduledExecutorService healthMonitor = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "bridge-health-monitor");
            t.setDaemon(true);
            return t;
        }
    );

    private final AtomicLong lastHealthCheck = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public UnifiedBridgeImpl(BridgeConfiguration config) throws BridgeException {
        this.config = config;
        initialize();
    }

    /**
     * Initialize the bridge with native library loading and connection establishment.
     */
    private void initialize() throws BridgeException {
        if (initialized.getAndSet(true)) {
            return; // Already initialized
        }

        try {
            loadNativeLibrary();
            establishConnection();
            startHealthMonitoring();
            LOGGER.info("UnifiedBridgeImpl initialized successfully");
        } catch (Exception e) {
            initialized.set(false);
            throw new BridgeException("Failed to initialize unified bridge", e);
        }
    }

    /**
     * Load the native library with multiple fallback strategies.
     */
    private void loadNativeLibrary() throws IOException {
        if (nativeLibraryLoaded) {
            return;
        }

        String libPath = System.getProperty(LIB_PATH_ENV);
        if (libPath != null) {
            loadFromPath(libPath);
            return;
        }

        // Try loading from classpath resources
        loadFromClasspath();

        // Try loading from system library path
        loadFromSystemPath();

        nativeLibraryLoaded = true;
        LOGGER.info("Native library loaded successfully");
    }

    private void loadFromPath(String libPath) throws IOException {
        Path path = Paths.get(libPath);
        if (Files.exists(path)) {
            System.load(path.toAbsolutePath().toString());
        } else {
            throw new IOException("Native library not found at: " + libPath);
        }
    }

    private void loadFromClasspath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String libFileName;
        if (osName.contains("win")) {
            libFileName = LIB_NAME + ".dll";
        } else if (osName.contains("mac")) {
            libFileName = "lib" + LIB_NAME + ".dylib";
        } else {
            libFileName = "lib" + LIB_NAME + ".so";
        }

        // Try loading from natives directory in classpath
        String resourcePath = "/natives/" + osArch + "/" + libFileName;
        try (var is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                Path tempLib = Files.createTempFile(LIB_NAME, getLibExtension());
                Files.copy(is, tempLib, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.load(tempLib.toAbsolutePath().toString());
                // Keep temp file for JVM lifetime
                tempLib.toFile().deleteOnExit();
                return;
            }
        }

        throw new IOException("Native library not found in classpath: " + resourcePath);
    }

    private void loadFromSystemPath() {
        System.loadLibrary(LIB_NAME);
    }

    private String getLibExtension() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return ".dll";
        } else if (osName.contains("mac")) {
            return ".dylib";
        } else {
            return ".so";
        }
    }

    /**
     * Establish connection to the Rust bridge.
     */
    private void establishConnection() throws BridgeException {
        CompletableFuture<Long> connectionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                long handle = nativeCreateBridge(config);
                if (handle == 0) {
                    throw new BridgeException("Native bridge creation returned null handle");
                }
                connectionId.set(handle);
                connected.set(true);
                return handle;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, bridgeExecutor);

        try {
            nativeHandle = connectionFuture.get(config.getOperationTimeoutMs(), TimeUnit.MILLISECONDS);
            LOGGER.info("Bridge connection established with handle: " + nativeHandle);
        } catch (Exception e) {
            connected.set(false);
            throw new BridgeException("Failed to establish bridge connection", e);
        }
    }

    /**
     * Start health monitoring for the bridge connection.
     */
    private void startHealthMonitoring() {
        healthMonitor.scheduleWithFixedDelay(() -> {
            try {
                if (connected.get()) {
                    boolean healthy = nativeHealthCheck(nativeHandle);
                    if (healthy) {
                        consecutiveFailures.set(0);
                        lastHealthCheck.set(System.currentTimeMillis());
                    } else {
                        int failures = consecutiveFailures.incrementAndGet();
                        LOGGER.warning("Bridge health check failed, consecutive failures: " + failures);

                        if (failures >= 3) {
                            LOGGER.severe("Bridge connection unhealthy, attempting reconnection...");
                            reconnect();
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Health check error", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Attempt to reconnect to the bridge.
     */
    private void reconnect() {
        try {
            connected.set(false);
            nativeDestroyBridge(nativeHandle);

            establishConnection();
            consecutiveFailures.set(0);
            LOGGER.info("Bridge reconnection successful");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Bridge reconnection failed", e);
            // Could implement exponential backoff here
        }
    }

    /**
     * Execute a bridge operation with proper error handling and retries.
     */
    public BridgeResult executeOperation(String operation, Object... args) {
        if (!connected.get()) {
            return BridgeResult.error("Bridge not connected");
        }

        int retries = 0;
        while (retries <= config.getMaxRetries()) {
            try {
                BridgeResult result = nativeExecuteOperation(nativeHandle, operation, args);
                if (result.isSuccess()) {
                    return result;
                }

                // Check if operation is retryable
                if (isRetryableError(result.getErrorMessage()) && retries < config.getMaxRetries()) {
                    retries++;
                    Thread.sleep(config.getRetryDelayMs() * retries);
                    continue;
                }

                return result;

            } catch (Exception e) {
                retries++;
                if (retries > config.getMaxRetries()) {
                    return BridgeResult.error("Operation failed after retries", e);
                }

                try {
                    Thread.sleep(config.getRetryDelayMs() * retries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return BridgeResult.error("Operation interrupted", ie);
                }
            }
        }

        return BridgeResult.error("Max retries exceeded");
    }

    /**
     * Check if an error is retryable.
     */
    private boolean isRetryableError(String errorMessage) {
        if (errorMessage == null) return false;

        String lowerError = errorMessage.toLowerCase();
        return lowerError.contains("timeout") ||
               lowerError.contains("connection") ||
               lowerError.contains("temporary") ||
               lowerError.contains("busy");
    }

    /**
     * Get bridge statistics.
     */
    public BridgeStatistics getStatistics() {
        if (!connected.get()) {
            return new BridgeStatistics(0, 0, 0, 0, false);
        }

        try {
            return nativeGetStatistics(nativeHandle);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get bridge statistics", e);
            return new BridgeStatistics(0, 0, 0, 0, false);
        }
    }

    /**
     * Shutdown the bridge gracefully.
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        LOGGER.info("Shutting down UnifiedBridgeImpl...");

        try {
            healthMonitor.shutdown();
            if (!healthMonitor.awaitTermination(5, TimeUnit.SECONDS)) {
                healthMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthMonitor.shutdownNow();
        }

        if (connected.getAndSet(false)) {
            try {
                nativeDestroyBridge(nativeHandle);
                nativeHandle = 0;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during bridge destruction", e);
            }
        }

        bridgeExecutor.shutdown();
        try {
            if (!bridgeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                bridgeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bridgeExecutor.shutdownNow();
        }

        initialized.set(false);
        LOGGER.info("UnifiedBridgeImpl shutdown complete");
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Check if the bridge is healthy and connected.
     */
    public boolean isHealthy() {
        return connected.get() && consecutiveFailures.get() == 0;
    }

    /**
     * Get the connection ID.
     */
    public long getConnectionId() {
        return connectionId.get();
    }

    // Native method declarations
    private native long nativeCreateBridge(BridgeConfiguration config);
    private native void nativeDestroyBridge(long handle);
    private native BridgeResult nativeExecuteOperation(long handle, String operation, Object[] args);
    private native boolean nativeHealthCheck(long handle);
    private native BridgeStatistics nativeGetStatistics(long handle);

    /**
     * Bridge statistics record.
     */
    public static class BridgeStatistics {
        private final long totalOperations;
        private final long successfulOperations;
        private final long failedOperations;
        private final long averageResponseTimeMs;
        private final boolean healthy;

        public BridgeStatistics(long totalOperations, long successfulOperations,
                              long failedOperations, long averageResponseTimeMs, boolean healthy) {
            this.totalOperations = totalOperations;
            this.successfulOperations = successfulOperations;
            this.failedOperations = failedOperations;
            this.averageResponseTimeMs = averageResponseTimeMs;
            this.healthy = healthy;
        }

        public long getTotalOperations() { return totalOperations; }
        public long getSuccessfulOperations() { return successfulOperations; }
        public long getFailedOperations() { return failedOperations; }
        public long getAverageResponseTimeMs() { return averageResponseTimeMs; }
        public boolean isHealthy() { return healthy; }
    }
}