package com.kneaf.core.performance;

import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full JNI integration with Rust performance monitoring library.
 * Provides high-performance statistics collection, optimization controls,
 * and enhanced batch processing capabilities.
 */
public class RustPerformance {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustPerformance.class.getName());

    // Performance monitoring state
    private static final AtomicLong totalTicksProcessed = new AtomicLong(0);
    
    // Enhanced batch processing state
    private static final ConcurrentHashMap<Long, CompletableFuture<byte[]>> asyncOperations = new ConcurrentHashMap<>();
    private static final Semaphore batchProcessingSemaphore = new Semaphore(10, true); // Limit concurrent batches

    static {
        try {
            loadNativeLibrary();
        } catch (Exception e) {
            LOGGER.error("Failed to load Rust performance native library", e);
        }
    }

    /**
     * Load the native Rust performance library with enhanced error handling.
     */
    private static void loadNativeLibrary() {
        if (RustPerformanceBase.nativeLibraryLoaded.get()) {
            LOGGER.info("Native library already loaded, skipping");
            return;
        }

        try {
            // Try to load from system path first
            LOGGER.info("Attempting to load library from system path");
            System.loadLibrary("rustperf");
            RustPerformanceBase.nativeLibraryLoaded.set(true);
            LOGGER.info("Rust performance native library loaded successfully from system path");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("System library load failed, trying classpath: " + e.getMessage());

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
                        LOGGER.warn("Library loading interrupted");
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
                            RustPerformanceBase.nativeLibraryLoaded.set(true);
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
                            RustPerformanceBase.nativeLibraryLoaded.set(true);
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
                LOGGER.error("Failed to load Rust performance native library from classpath", ex);
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
        if (!RustPerformanceBase.nativeLibraryLoaded.get()) {
            throw new IllegalStateException("Native library not loaded");
        }

        if (RustPerformanceBase.initialized.getAndSet(true)) {
            LOGGER.info("Rust performance monitoring already initialized, skipping");
            return;
        }

        try {
            LOGGER.info("Calling nativeInitialize()");
            boolean success = nativeInitialize();
            LOGGER.info("nativeInitialize() returned: " + success);
            if (!success) {
                RustPerformanceBase.initialized.set(false);
                throw new RuntimeException("Rust performance initialization failed");
            }

            RustPerformanceBase.monitoringStartTime.set(System.currentTimeMillis());
            LOGGER.info("Rust performance monitoring initialized successfully");
        } catch (Exception e) {
            RustPerformanceBase.initialized.set(false);
            LOGGER.error("Failed to initialize Rust performance monitoring", e);
            throw new RuntimeException("Rust performance initialization failed", e);
        }
    }

    /**
     * Initialize ultra-high performance mode with aggressive optimizations.
     */
    public static void initializeUltraPerformance() {
        if (!RustPerformanceBase.isInitialized()) {
            initialize();
        }

        try {
            boolean success = nativeInitializeUltraPerformance();
            if (!success) {
                throw new RuntimeException("Ultra performance initialization failed");
            }
            LOGGER.info("Ultra performance mode initialized");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize ultra performance mode", e);
            throw new RuntimeException("Ultra performance initialization failed", e);
        }
    }

    /**
     * Get current TPS (Ticks Per Second) with enhanced accuracy.
     */
    public static double getCurrentTPS() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0.0;
        }

        try {
            double tps = nativeGetCurrentTPS();
            totalTicksProcessed.incrementAndGet();
            return tps;
        } catch (Exception e) {
            LOGGER.warn("Failed to get current TPS", e);
            return 0.0;
        }
    }

    /**
     * Get CPU usage statistics with detailed breakdown.
     */
    public static String getCpuStats() {
        if (!RustPerformanceBase.isInitialized()) {
            return "CPU: Not initialized";
        }

        return RustPerformanceBase.safeNativeStringCall(
                RustPerformance::nativeGetCpuStats,
                RustPerformanceError.CPU_STATS_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get memory usage statistics with detailed breakdown.
     */
    public static String getMemoryStats() {
        if (!RustPerformanceBase.isInitialized()) {
            return "Memory: Not initialized";
        }

        return RustPerformanceBase.safeNativeStringCall(
                RustPerformance::nativeGetMemoryStats,
                RustPerformanceError.MEMORY_STATS_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get total entities processed since startup.
     */
    public static long getTotalEntitiesProcessed() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0L;
        }

        return RustPerformanceBase.safeNativeLongCall(
                RustPerformance::nativeGetTotalEntitiesProcessed,
                RustPerformanceError.ENTITY_COUNT_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get total mobs processed since startup.
     */
    public static long getTotalMobsProcessed() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0L;
        }

        return RustPerformanceBase.safeNativeLongCall(
                RustPerformance::nativeGetTotalMobsProcessed,
                RustPerformanceError.MOB_COUNT_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get total blocks processed since startup.
     */
    public static long getTotalBlocksProcessed() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0L;
        }

        return RustPerformanceBase.safeNativeLongCall(
                RustPerformance::nativeGetTotalBlocksProcessed,
                RustPerformanceError.BLOCK_COUNT_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get total entities merged since startup.
     */
    public static long getTotalMerged() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0L;
        }

        return RustPerformanceBase.safeNativeLongCall(
                RustPerformance::nativeGetTotalMerged,
                RustPerformanceError.MERGED_COUNT_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Get total entities despawned since startup.
     */
    public static long getTotalDespawned() {
        if (!RustPerformanceBase.isInitialized()) {
            return 0L;
        }

        return RustPerformanceBase.safeNativeLongCall(
                RustPerformance::nativeGetTotalDespawned,
                RustPerformanceError.DESPAWNED_COUNT_ERROR,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Log startup information with performance optimizations.
     */
    public static void logStartupInfo(String optimizationsActive, String cpuInfo, String configApplied) {
        LOGGER.info("logStartupInfo called with: optimizationsActive={}, cpuInfo={}, configApplied={}",
                optimizationsActive, cpuInfo, configApplied);
        if (!RustPerformanceBase.isInitialized()) {
            LOGGER.warn("Attempted to log startup info before initialization");
            return;
        }

        RustPerformanceBase.safeNativeVoidCall(
                () -> nativeLogStartupInfo(optimizationsActive, cpuInfo, configApplied),
                RustPerformanceError.LOG_STARTUP_ERROR,
                RustPerformanceBase::isInitialized
        );
        LOGGER.info("Performance startup info logged: {}", optimizationsActive);
    }

    /**
     * Get total ticks processed since startup.
     */
    public static long getTotalTicksProcessed() {
        return totalTicksProcessed.get();
    }

    /**
     * Reset performance counters (for testing).
     */
    public static void resetCounters() {
        if (!RustPerformanceBase.isInitialized()) {
            return;
        }

        RustPerformanceBase.safeNativeVoidCall(
                RustPerformance::nativeResetCounters,
                RustPerformanceError.COUNTER_RESET_ERROR,
                RustPerformanceBase::isInitialized
        );
        totalTicksProcessed.set(0);
        LOGGER.info("Performance counters reset");
    }

    /**
     * Optimize memory usage through native Rust optimizations.
     * This triggers garbage collection hints and memory defragmentation in Rust.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeMemory() {
        if (!RustPerformanceBase.isInitialized()) {
            LOGGER.warn("Cannot optimize memory: Rust performance system not initialized");
            return false;
        }

        return RustPerformanceBase.safeNativeBooleanCall(
                RustPerformance::nativeOptimizeMemory,
                RustPerformanceError.MEMORY_OPTIMIZATION_FAILED,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Optimize chunk processing and loading through native Rust optimizations.
     * This includes chunk caching optimizations and processing pipeline improvements.
     *
     * @return true if optimization was successful, false otherwise
     */
    public static boolean optimizeChunks() {
        if (!RustPerformanceBase.isInitialized()) {
            LOGGER.warn("Cannot optimize chunks: Rust performance system not initialized");
            return false;
        }

        return RustPerformanceBase.safeNativeBooleanCall(
                RustPerformance::nativeOptimizeChunks,
                RustPerformanceError.CHUNK_OPTIMIZATION_FAILED,
                RustPerformanceBase::isInitialized
        );
    }

    /**
     * Shutdown the performance monitoring system.
     */
    public static void shutdown() {
        if (!RustPerformanceBase.initialized.getAndSet(false)) {
            return;
        }

        RustPerformanceBase.safeNativeVoidCall(
                RustPerformance::nativeShutdown,
                RustPerformanceError.SHUTDOWN_ERROR,
                () -> RustPerformanceBase.initialized.get()
        );
        LOGGER.info("Rust performance monitoring shutdown");
    }

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
        if (!RustPerformanceBase.isInitialized()) {
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
                LOGGER.warn("Failed to poll async batch result for operation {}", operationId, e);
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
        if (!RustPerformanceBase.isInitialized()) {
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
        if (!RustPerformanceBase.isInitialized()) {
            return "{\"error\":\"System not initialized\"}";
        }

        try {
            long uptime = RustPerformanceBase.getUptimeMs();
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
            LOGGER.warn("Failed to get comprehensive metrics", e);
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
    private static native boolean nativeInitEnhancedBatchProcessor(
            int minBatchSize,
            int maxBatchSize,
            long adaptiveTimeoutMs,
            int maxPendingBatches,
            int workerThreads,
            boolean enableAdaptiveSizing
    );
    private static native String nativeGetEnhancedBatchMetrics();
    private static native String nativeSubmitZeroCopyBatchedOperations(
            byte operationType,
            ByteBuffer buffer,
            int bufferSize
    );
    private static native long nativeSubmitAsyncBatchedOperations(
            byte operationType,
            byte[] operations,
            byte[] priorities
    );
    private static native byte[] nativePollAsyncBatchResult(long operationId);
}