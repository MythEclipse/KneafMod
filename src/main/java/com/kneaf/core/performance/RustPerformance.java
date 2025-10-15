package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full JNI integration with Rust performance monitoring library.
 * Provides high-performance statistics collection, optimization controls,
 * and enhanced batch processing capabilities.
 */
public class RustPerformance {
    private static final Logger LOGGER = Logger.getLogger(RustPerformance.class.getName());

    // Native library loading state
    private static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Performance monitoring state
    private static final AtomicLong monitoringStartTime = new AtomicLong(0);
    private static final AtomicLong totalTicksProcessed = new AtomicLong(0);
    
    // Enhanced batch processing state
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> asyncOperations = new ConcurrentHashMap<>();
    private static final Semaphore batchProcessingSemaphore = new Semaphore(10, true); // Limit concurrent batches
    
    // Configuration constants
    public static final int DEFAULT_MIN_BATCH_SIZE = 50;
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;
    public static final long DEFAULT_ADAPTIVE_TIMEOUT_MS = 1;
    public static final int DEFAULT_WORKER_THREADS = 8;

    static {
        try {
            loadNativeLibrary();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load Rust performance native library", e);
        }
    }

    /**
     * Load the native Rust performance library with enhanced error handling.
     */
    private static void loadNativeLibrary() {
        if (nativeLibraryLoaded.get()) {
            LOGGER.info("Native library already loaded, skipping");
            return;
        }

        try {
            // Try to load from system path first
            LOGGER.info("Attempting to load library from system path");
            System.loadLibrary("rustperf");
            nativeLibraryLoaded.set(true);
            LOGGER.info("Rust performance native library loaded successfully from system path");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warning("System library load failed, trying classpath: " + e.getMessage());

            // Try loading from classpath resources with enhanced path resolution
            try {
                String osName = System.getProperty("os.name").toLowerCase();
                String osArch = System.getProperty("os.arch").toLowerCase();

                String libFileName;
                if (osName.contains("win")) {
                    libFileName = "rustperf.dll";
                } else if (osName.contains("mac")) {
                    libFileName = "librustperf.dylib";
                } else {
                    libFileName = "librustperf.so";
                }

                LOGGER.info("Attempting to load library: " + libFileName + " for OS: " + osName + " Arch: " + osArch);

                // Candidate locations with enhanced coverage
                String[] candidatePaths = new String[] {
                    "/natives/" + libFileName,
                    "/natives/" + osArch + "/" + libFileName,
                    "build/generated/resources/natives/" + osArch + "/" + libFileName,
                    "build/resources/main/natives/" + osArch + "/" + libFileName,
                    "src/main/resources/natives/" + libFileName,
                    "rust/target/release/" + libFileName,
                    "target/release/" + libFileName,
                    "lib/" + libFileName
                };

                boolean loaded = false;
                for (String candidate : candidatePaths) {
                    if (Thread.currentThread().isInterrupted()) {
                        LOGGER.warning("Library loading interrupted");
                        break;
                    }

                    LOGGER.info("Trying to load library from: " + candidate);
                    if (candidate.startsWith("/")) {
                        java.io.InputStream is = RustPerformance.class.getResourceAsStream(candidate);
                        if (is != null) {
                            java.nio.file.Path tempLib = java.nio.file.Files.createTempFile("kneaf_performance", getLibExtension());
                            java.nio.file.Files.copy(is, tempLib, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            System.load(tempLib.toAbsolutePath().toString());
                            tempLib.toFile().deleteOnExit();
                            nativeLibraryLoaded.set(true);
                            LOGGER.info("Rust performance native library loaded from classpath: " + candidate);
                            loaded = true;
                            break;
                        } else {
                            LOGGER.info("Library not found in classpath: " + candidate);
                        }
                    } else {
                        java.nio.file.Path path = java.nio.file.Paths.get(candidate);
                        if (java.nio.file.Files.exists(path)) {
                            System.load(path.toAbsolutePath().toString());
                            nativeLibraryLoaded.set(true);
                            LOGGER.info("Rust performance native library loaded from file: " + path.toAbsolutePath());
                            loaded = true;
                            break;
                        } else {
                            LOGGER.info("Library file not found: " + path.toAbsolutePath());
                        }
                    }
                }

                if (!loaded) {
                    throw new RuntimeException("Native library not found in candidate locations: " + Arrays.toString(candidatePaths));
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Failed to load Rust performance native library from classpath", ex);
                throw new RuntimeException("Cannot load Rust performance native library", ex);
            }
        }
    }

    private static String getLibExtension() {
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
     * Initialize the Rust performance monitoring system with enhanced configuration.
     */
    public static void initialize() {
        LOGGER.info("Initializing Rust performance monitoring system");
        if (!nativeLibraryLoaded.get()) {
            throw new IllegalStateException("Native library not loaded");
        }

        if (initialized.getAndSet(true)) {
            LOGGER.info("Rust performance monitoring already initialized, skipping");
            return;
        }

        try {
            LOGGER.info("Calling nativeInitialize()");
            boolean success = nativeInitialize();
            LOGGER.info("nativeInitialize() returned: " + success);
            if (!success) {
                initialized.set(false);
                throw new RuntimeException("Rust performance initialization failed");
            }

            monitoringStartTime.set(System.currentTimeMillis());
            LOGGER.info("Rust performance monitoring initialized successfully");
        } catch (Exception e) {
            initialized.set(false);
            LOGGER.log(Level.SEVERE, "Failed to initialize Rust performance monitoring", e);
            throw new RuntimeException("Rust performance initialization failed", e);
        }
    }

    /**
     * Initialize ultra-high performance mode with aggressive optimizations.
     */
    public static void initializeUltraPerformance() {
        if (!initialized.get()) {
            initialize();
        }

        try {
            boolean success = nativeInitializeUltraPerformance();
            if (!success) {
                throw new RuntimeException("Ultra performance initialization failed");
            }
            LOGGER.info("Ultra performance mode initialized");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize ultra performance mode", e);
            throw new RuntimeException("Ultra performance initialization failed", e);
        }
    }

    /**
     * Get current TPS (Ticks Per Second) with enhanced accuracy.
     */
    public static double getCurrentTPS() {
        if (!initialized.get()) {
            return 0.0;
        }

        try {
            double tps = nativeGetCurrentTPS();
            totalTicksProcessed.incrementAndGet();
            return tps;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get current TPS", e);
            return 0.0;
        }
    }

    /**
     * Get CPU usage statistics with detailed breakdown.
     */
    public static String getCpuStats() {
        if (!initialized.get()) {
            return "CPU: Not initialized";
        }

        try {
            return nativeGetCpuStats();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get CPU stats", e);
            return "CPU: Error retrieving stats";
        }
    }

    /**
     * Get memory usage statistics with detailed breakdown.
     */
    public static String getMemoryStats() {
        if (!initialized.get()) {
            return "Memory: Not initialized";
        }

        try {
            return nativeGetMemoryStats();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get memory stats", e);
            return "Memory: Error retrieving stats";
        }
    }

    /**
     * Get total entities processed since startup.
     */
    public static long getTotalEntitiesProcessed() {
        if (!initialized.get()) {
            return 0L;
        }

        try {
            return nativeGetTotalEntitiesProcessed();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total entities processed", e);
            return 0L;
        }
    }

    /**
     * Get total mobs processed since startup.
     */
    public static long getTotalMobsProcessed() {
        if (!initialized.get()) {
            return 0L;
        }

        try {
            return nativeGetTotalMobsProcessed();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total mobs processed", e);
            return 0L;
        }
    }

    /**
     * Get total blocks processed since startup.
     */
    public static long getTotalBlocksProcessed() {
        if (!initialized.get()) {
            return 0L;
        }

        try {
            return nativeGetTotalBlocksProcessed();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total blocks processed", e);
            return 0L;
        }
    }

    /**
     * Get total entities merged since startup.
     */
    public static long getTotalMerged() {
        if (!initialized.get()) {
            return 0L;
        }

        try {
            return nativeGetTotalMerged();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total merged", e);
            return 0L;
        }
    }

    /**
     * Get total entities despawned since startup.
     */
    public static long getTotalDespawned() {
        if (!initialized.get()) {
            return 0L;
        }

        try {
            return nativeGetTotalDespawned();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get total despawned", e);
            return 0L;
        }
    }

    /**
     * Log startup information with performance optimizations.
     */
    public static void logStartupInfo(String optimizationsActive, String cpuInfo, String configApplied) {
        LOGGER.info("logStartupInfo called with: optimizationsActive=" + optimizationsActive + ", cpuInfo=" + cpuInfo + ", configApplied=" + configApplied);
        if (!initialized.get()) {
            LOGGER.warning("Attempted to log startup info before initialization");
            return;
        }

        try {
            LOGGER.info("Calling nativeLogStartupInfo()");
            nativeLogStartupInfo(optimizationsActive, cpuInfo, configApplied);
            LOGGER.info("Performance startup info logged: " + optimizationsActive);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to log startup info", e);
        }
    }

    /**
     * Get performance monitoring uptime in milliseconds.
     */
    public static long getUptimeMs() {
        if (monitoringStartTime.get() == 0) {
            return 0;
        }
        return System.currentTimeMillis() - monitoringStartTime.get();
    }

    /**
     * Get total ticks processed since startup.
     */
    public static long getTotalTicksProcessed() {
        return totalTicksProcessed.get();
    }

    /**
     * Check if the performance monitoring is initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Reset performance counters (for testing).
     */
    public static void resetCounters() {
        if (!initialized.get()) {
            return;
        }

        try {
            nativeResetCounters();
            totalTicksProcessed.set(0);
            LOGGER.info("Performance counters reset");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to reset counters", e);
        }
    }

    /**
     * Optimize memory usage through native Rust optimizations.
     * This triggers garbage collection hints and memory defragmentation in Rust.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeMemory() {
        if (!initialized.get()) {
            LOGGER.warning("Cannot optimize memory: Rust performance system not initialized");
            return false;
        }

        try {
            boolean result = nativeOptimizeMemory();
            if (result) {
                LOGGER.info("Native memory optimization completed successfully");
            } else {
                LOGGER.warning("Native memory optimization failed or not supported");
            }
            return result;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during native memory optimization", e);
            return false;
        }
    }

    /**
     * Optimize chunk processing and loading through native Rust optimizations.
     * This includes chunk caching optimizations and processing pipeline improvements.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeChunks() {
        if (!initialized.get()) {
            LOGGER.warning("Cannot optimize chunks: Rust performance system not initialized");
            return false;
        }

        try {
            boolean result = nativeOptimizeChunks();
            if (result) {
                LOGGER.info("Native chunk optimization completed successfully");
            } else {
                LOGGER.warning("Native chunk optimization failed or not supported");
            }
            return result;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during native chunk optimization", e);
            return false;
        }
    }

    /**
     * Shutdown the performance monitoring system.
     */
    public static void shutdown() {
        if (!initialized.getAndSet(false)) {
            return;
        }

        try {
            nativeShutdown();
            LOGGER.info("Rust performance monitoring shutdown");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during performance monitoring shutdown", e);
        }
    }

    /**
     * Initialize enhanced batch processor with custom configuration.
     *
     * @param minBatchSize          Minimum batch size
     * @param maxBatchSize          Maximum batch size
     * @param adaptiveTimeoutMs     Adaptive batch timeout in milliseconds
     * @param maxPendingBatches     Maximum pending batches
     * @param workerThreads         Number of worker threads
     * @param enableAdaptiveSizing  Enable adaptive batch sizing
     * @return true if initialization was successful
     */
    public static native boolean nativeInitEnhancedBatchProcessor(
            int minBatchSize,
            int maxBatchSize,
            long adaptiveTimeoutMs,
            int maxPendingBatches,
            int workerThreads,
            boolean enableAdaptiveSizing
    );

    /**
     * Get enhanced batch processor metrics as JSON string.
     *
     * @return JSON string containing batch processor metrics
     */
    public static native String nativeGetEnhancedBatchMetrics();

    /**
     * Submit zero-copy batched operations directly from Java ByteBuffer.
     *
     * @param operationType Operation type identifier
     * @param buffer        Direct ByteBuffer containing operations
     * @param bufferSize    Size of the buffer in bytes
     * @return JSON string containing operation result
     */
    public static native String nativeSubmitZeroCopyBatchedOperations(
            byte operationType,
            ByteBuffer buffer,
            int bufferSize
    );

    /**
     * Submit async batched operations with priority support.
     *
     * @param operationType Operation type identifier
     * @param operations    Byte array containing serialized operations
     * @param priorities    Byte array containing priorities for each operation
     * @return Operation ID for polling results
     */
    public static native long nativeSubmitAsyncBatchedOperations(
            byte operationType,
            byte[] operations,
            byte[] priorities
    );

    /**
     * Poll async batch operation results.
     *
     * @param operationId Operation ID to poll
     * @return Byte array containing operation results
     */
    public static native byte[] nativePollAsyncBatchResult(long operationId);

    /**
     * Enhanced batch processing with zero-copy optimization.
     * Processes a batch of operations using direct memory access.
     *
     * @param operationType Type of operation to perform
     * @param operations    List of operations to process
     * @param priorities    Priorities for each operation
     * @return CompletableFuture containing the batch processing results
     */
    public static CompletableFuture<byte[]> processEnhancedBatch(
            byte operationType,
            List<byte[]> operations,
            List<Byte> priorities
    ) {
        if (!initialized.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Rust performance system not initialized")
            );
        }

        if (operations == null || operations.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Operations list cannot be null or empty")
            );
        }

        if (priorities == null || priorities.size() != operations.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Priorities must match operations count")
            );
        }

        try {
            // Acquire semaphore to limit concurrent batch processing
            if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Batch processing semaphore timeout")
                );
            }

            // Prepare operations data
            int totalSize = 0;
            for (byte[] operation : operations) {
                totalSize += operation.length + 4; // 4 bytes for length prefix
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(4 + totalSize); // 4 bytes for count prefix
            buffer.putInt(operations.size());

            for (int i = 0; i < operations.size(); i++) {
                byte[] operation = operations.get(i);
                buffer.putInt(operation.length);
                buffer.put(operation);
                
                // Store priority for later use
                Byte priority = priorities.get(i);
                if (priority == null) {
                    buffer.put((byte) 0); // Default priority
                } else {
                    buffer.put(priority);
                }
            }

            buffer.flip();

            // Submit to native processor
            long operationId = nativeSubmitAsyncBatchedOperations(
                    operationType,
                    buffer.array(),
                    buffer.array()
            );

            // Create and return CompletableFuture for async result
            CompletableFuture<byte[]> future = new CompletableFuture<>();
            asyncOperations.put(operationId, future);

            // Schedule result polling
            scheduleAsyncResultPolling(operationId, future);

            return future;
        } catch (Exception e) {
            batchProcessingSemaphore.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Schedule periodic polling for async batch operation results.
     *
     * @param operationId Operation ID to poll
     * @param future      CompletableFuture to complete with results
     */
    private static void scheduleAsyncResultPolling(long operationId, CompletableFuture<byte[]> future) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) {
                scheduler.shutdown();
                return;
            }

            try {
                byte[] result = nativePollAsyncBatchResult(operationId);
                if (result != null && result.length > 0) {
                    future.complete(result);
                    scheduler.shutdown();
                    asyncOperations.remove(operationId);
                    batchProcessingSemaphore.release();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to poll async batch result for operation " + operationId, e);
                future.completeExceptionally(e);
                scheduler.shutdown();
                asyncOperations.remove(operationId);
                batchProcessingSemaphore.release();
            }
        }, 0, 100, TimeUnit.MILLISECONDS); // Poll every 100ms
    }

    /**
     * Process zero-copy operations directly from Java ByteBuffer.
     *
     * @param operationType Type of operation to perform
     * @param buffer        Direct ByteBuffer containing operations
     * @return JSON string containing operation result
     */
    public static String processZeroCopyOperations(byte operationType, ByteBuffer buffer) {
        if (!initialized.get()) {
            throw new IllegalStateException("Rust performance system not initialized");
        }

        if (buffer == null || !buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be a direct ByteBuffer");
        }

        try {
            // Acquire semaphore to limit concurrent zero-copy operations
            if (!batchProcessingSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Zero-copy operation semaphore timeout");
            }

            String result = nativeSubmitZeroCopyBatchedOperations(
                    operationType,
                    buffer,
                    buffer.capacity()
            );

            batchProcessingSemaphore.release();
            return result;
        } catch (Exception e) {
            batchProcessingSemaphore.release();
            throw new RuntimeException("Failed to process zero-copy operations", e);
        }
    }

    /**
     * Get comprehensive performance metrics as JSON string.
     *
     * @return JSON string containing all performance metrics
     */
    public static String getComprehensiveMetrics() {
        if (!initialized.get()) {
            return "{\"error\":\"System not initialized\"}";
        }

        try {
            long uptime = getUptimeMs();
            double tps = getCurrentTPS();
            long totalTicks = getTotalTicksProcessed();
            long totalEntities = getTotalEntitiesProcessed();
            long totalMobs = getTotalMobsProcessed();
            long totalBlocks = getTotalBlocksProcessed();
            long totalMerged = getTotalMerged();
            long totalDespawned = getTotalDespawned();
            String cpuStats = getCpuStats();
            String memoryStats = getMemoryStats();
            String batchMetrics = nativeGetEnhancedBatchMetrics();

            return String.format("{\"uptimeMs\":%d,\"tps\":%.2f,\"totalTicks\":%d,\"totalEntities\":%d,\"totalMobs\":%d,\"totalBlocks\":%d,\"totalMerged\":%d,\"totalDespawned\":%d,\"cpuStats\":\"%s\",\"memoryStats\":\"%s\",\"batchMetrics\":%s}",
                    uptime, tps, totalTicks, totalEntities, totalMobs, totalBlocks, totalMerged, totalDespawned,
                    cpuStats.replace("\"", "\\\""), memoryStats.replace("\"", "\\\""), batchMetrics);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get comprehensive metrics", e);
            return "{\"error\":\"Failed to retrieve metrics\"}";
        }
    }

    // Native method declarations - updated to match Rust implementation
    private static native boolean nativeInitialize();
    private static native boolean nativeInitializeUltraPerformance();
    private static native double nativeGetCurrentTPS();
    private static native String nativeGetCpuStats();
    private static native String nativeGetMemoryStats();
    private static native long nativeGetTotalEntitiesProcessed();
    private static native long nativeGetTotalMobsProcessed();
    private static native long nativeGetTotalBlocksProcessed();
    private static native long nativeGetTotalMerged();
    private static native long nativeGetTotalDespawned();
    private static native void nativeLogStartupInfo(String optimizationsActive, String cpuInfo, String configApplied);
    private static native void nativeResetCounters();
    private static native void nativeShutdown();
    private static native boolean nativeOptimizeMemory();
    private static native boolean nativeOptimizeChunks();
}