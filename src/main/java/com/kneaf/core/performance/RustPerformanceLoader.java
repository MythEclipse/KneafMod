package com.kneaf.core.performance;

import com.kneaf.core.performance.bridge.NativeLibraryLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.kneaf.core.performance.error.RustPerformanceError;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles loading and initialization of the Rust performance native library.
 */
public class RustPerformanceLoader {
    private static final Logger LOGGER = Logger.getLogger(RustPerformanceLoader.class.getName());
    
    // Native library loading state
    private static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Performance monitoring state
    private static final AtomicLong monitoringStartTime = new AtomicLong(0);
    
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
    public static void loadNativeLibrary() {
        if (nativeLibraryLoaded.get()) {
            LOGGER.info("Native library already loaded, skipping");
            return;
        }
        
        // Use the existing NativeLibraryLoader for library loading
        boolean loaded = NativeLibraryLoader.loadNativeLibrary("rustperf");
        
        if (loaded) {
            nativeLibraryLoaded.set(true);
            LOGGER.info("Rust performance native library loaded successfully");
        } else {
            LOGGER.severe(RustPerformanceError.LIBRARY_LOAD_FAILED.getMessage());
            throw new RuntimeException(RustPerformanceError.LIBRARY_LOAD_FAILED.getMessage());
        }
    }
    
    /**
     * Initialize the Rust performance monitoring system with enhanced configuration.
     */
    public static void initialize() {
        LOGGER.info("Initializing Rust performance monitoring system");
        
        if (!nativeLibraryLoaded.get()) {
            throw new IllegalStateException(RustPerformanceError.LIBRARY_NOT_LOADED.getMessage());
        }
        
        if (initialized.get()) {
            LOGGER.info("Rust performance monitoring already initialized, skipping");
            return;
        }
        
        try {
            LOGGER.info("Calling nativeInitialize()");
            boolean success = nativeInitialize();
            LOGGER.info("nativeInitialize() returned: " + success);
            
            if (!success) {
                throw new RuntimeException(RustPerformanceError.INITIALIZATION_FAILED.getMessage());
            }
            
            monitoringStartTime.set(System.currentTimeMillis());
            initialized.set(true);
            LOGGER.info("Rust performance monitoring initialized successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, RustPerformanceError.INITIALIZATION_FAILED.getMessage(), e);
            throw new RuntimeException(RustPerformanceError.INITIALIZATION_FAILED.getMessage(), e);
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
                throw new RuntimeException(RustPerformanceError.ULTIMATE_INIT_FAILED.getMessage());
            }
            LOGGER.info("Ultra performance mode initialized");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, RustPerformanceError.ULTIMATE_INIT_FAILED.getMessage(), e);
            throw new RuntimeException(RustPerformanceError.ULTIMATE_INIT_FAILED.getMessage(), e);
        }
    }
    
    /**
     * Shutdown the performance monitoring system.
     */
    public static void shutdown() {
        if (!initialized.get()) {
            return;
        }
        
        try {
            nativeShutdown();
            initialized.set(false);
            LOGGER.info("Rust performance monitoring shutdown");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, RustPerformanceError.SHUTDOWN_ERROR.getMessage(), e);
        }
    }
    
    /**
     * Check if the performance monitoring is initialized.
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Get performance monitoring uptime in milliseconds.
     */
    public static long getUptimeMs() {
        long startTime = monitoringStartTime.get();
        if (startTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTime;
    }
    
    // Native method declarations
    private static native boolean nativeInitialize();
    private static native boolean nativeInitializeUltraPerformance();
    private static native void nativeShutdown();
}