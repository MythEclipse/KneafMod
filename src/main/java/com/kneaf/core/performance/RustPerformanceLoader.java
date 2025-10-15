package com.kneaf.core.performance;

import com.kneaf.core.performance.bridge.NativeLibraryLoader;
import com.kneaf.core.performance.error.RustPerformanceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Factory class for creating RustPerformanceLoader instances with different configurations.
 * Implements factory pattern for flexible loader creation.
 */
public class RustPerformanceLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(RustPerformanceLoader.class);
    
    // Native library loading state
    private static final AtomicBoolean nativeLibraryLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Performance monitoring state
    private static final AtomicLong monitoringStartTime = new AtomicLong(0);
    
    // Factory method to create standard loader
    public static RustPerformanceLoader createStandardLoader() {
        return new RustPerformanceLoader();
    }
    
    // Factory method to create ultra performance loader
    public static RustPerformanceLoader createUltraPerformanceLoader() {
        RustPerformanceLoader loader = new RustPerformanceLoader();
        loader.initializeUltraPerformanceInstance();
        return loader;
    }
    
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
            LOGGER.error(RustPerformanceError.LIBRARY_LOAD_FAILED.getMessage());
            throw new RuntimeException(RustPerformanceError.LIBRARY_LOAD_FAILED.getMessage());
        }
    }

    /**
     * Instance method: Initialize the Rust performance monitoring system.
     * Preferred method for new code - use factory methods to create instances.
     */
    public void initializeInstance() {
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
            LOGGER.error(RustPerformanceError.INITIALIZATION_FAILED.getMessage(), e);
            throw new RuntimeException(RustPerformanceError.INITIALIZATION_FAILED.getMessage(), e);
        }
    }

    /**
     * Instance method: Initialize ultra-high performance mode.
     * Preferred method for new code - use factory methods to create instances.
     */
    public void initializeUltraPerformanceInstance() {
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
            LOGGER.error(RustPerformanceError.ULTIMATE_INIT_FAILED.getMessage(), e);
            throw new RuntimeException(RustPerformanceError.ULTIMATE_INIT_FAILED.getMessage(), e);
        }
    }

    /**
     * Instance method: Shutdown the performance monitoring system.
     * Preferred method for new code - use factory methods to create instances.
     */
    public void shutdownInstance() {
        if (!initialized.get()) {
            return;
        }
        
        try {
            nativeShutdown();
            initialized.set(false);
            LOGGER.info("Rust performance monitoring shutdown");
        } catch (Exception e) {
            LOGGER.warn(RustPerformanceError.SHUTDOWN_ERROR.getMessage(), e);
        }
    }

    /**
     * Backward compatibility: Static initialize method (creates standard loader instance)
     * @deprecated Use factory methods with instance initialization for new code.
     */
    @Deprecated
    public static void initialize() {
        createStandardLoader().initializeInstance();
    }

    /**
     * Backward compatibility: Static initializeUltraPerformance method
     * @deprecated Use factory methods with instance initialization for new code.
     */
    @Deprecated
    public static void initializeUltraPerformance() {
        // Already initialized by factory method
    }

    /**
     * Backward compatibility: Static shutdown method
     * @deprecated Use instance.shutdownInstance() for new code.
     */
    @Deprecated
    public static void shutdown() {
        // Note: This is a simplification - in real usage, should track specific loader instances
        // For backward compatibility, we'll use the static state check
        if (isInitialized()) {
            try {
                nativeShutdown();
                initialized.set(false);
                LOGGER.info("Rust performance monitoring shutdown (static)");
            } catch (Exception e) {
                LOGGER.warn(RustPerformanceError.SHUTDOWN_ERROR.getMessage(), e);
            }
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