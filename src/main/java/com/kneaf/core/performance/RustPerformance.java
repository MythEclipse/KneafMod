package com.kneaf.core.performance;

import java.util.concurrent.atomic.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full JNI integration with Rust performance monitoring library.
 * Provides high-performance statistics collection and optimization controls.
 */
public class RustPerformance {
    private static final Logger LOGGER = Logger.getLogger(RustPerformance.class.getName());

    // Native library loading state
    private static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    // Performance monitoring state
    private static final AtomicLong monitoringStartTime = new AtomicLong(0);
    private static final AtomicLong totalTicksProcessed = new AtomicLong(0);

    static {
        try {
            loadNativeLibrary();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load Rust performance native library", e);
        }
    }

    /**
     * Load the native Rust performance library.
     */
    private static void loadNativeLibrary() {
        if (nativeLibraryLoaded.get()) {
            return;
        }

        try {
            // Try to load from system path first
            System.loadLibrary("kneaf_performance");
            nativeLibraryLoaded.set(true);
            LOGGER.info("Rust performance native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warning("System library load failed, trying classpath: " + e.getMessage());

            // Try loading from classpath resources
            try {
                String osName = System.getProperty("os.name").toLowerCase();
                String osArch = System.getProperty("os.arch").toLowerCase();

                String libFileName;
                if (osName.contains("win")) {
                    libFileName = "kneaf_performance.dll";
                } else if (osName.contains("mac")) {
                    libFileName = "libkneaf_performance.dylib";
                } else {
                    libFileName = "libkneaf_performance.so";
                }

                // Load from natives directory
                String resourcePath = "/natives/" + osArch + "/" + libFileName;
                java.io.InputStream is = RustPerformance.class.getResourceAsStream(resourcePath);
                if (is != null) {
                    java.nio.file.Path tempLib = java.nio.file.Files.createTempFile("kneaf_performance", getLibExtension());
                    java.nio.file.Files.copy(is, tempLib, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.load(tempLib.toAbsolutePath().toString());
                    tempLib.toFile().deleteOnExit();
                    nativeLibraryLoaded.set(true);
                    LOGGER.info("Rust performance native library loaded from classpath");
                } else {
                    throw new RuntimeException("Native library not found in classpath: " + resourcePath);
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
     * Initialize the Rust performance monitoring system.
     */
    public static void initialize() {
        if (!nativeLibraryLoaded.get()) {
            throw new IllegalStateException("Native library not loaded");
        }

        if (initialized.getAndSet(true)) {
            return; // Already initialized
        }

        try {
            boolean success = nativeInitialize();
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
     * Initialize ultra-high performance mode.
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
     * Get current TPS (Ticks Per Second).
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
     * Get CPU usage statistics.
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
     * Get memory usage statistics.
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
        if (!initialized.get()) {
            LOGGER.warning("Attempted to log startup info before initialization");
            return;
        }

        try {
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

    // Native method declarations
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