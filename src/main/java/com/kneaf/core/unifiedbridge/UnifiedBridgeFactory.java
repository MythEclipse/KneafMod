package com.kneaf.core.unifiedbridge;

import com.kneaf.core.KneafCore;
import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.performance.PerformanceConfiguration;
import com.kneaf.core.exceptions.InitializationException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory class for creating appropriate UnifiedBridge instances based on configuration
 * and runtime environment. Implements factory pattern for UnifiedBridge creation.
 */
public final class UnifiedBridgeFactory {
    private static final Map<String, UnifiedBridge> BRIDGE_CACHE = new ConcurrentHashMap<>();
    private static ConfigurationManager configManager;
    private static PerformanceConfiguration performanceConfig;

    private UnifiedBridgeFactory() {
        // Private constructor to prevent instantiation
    }

    /**
     * Initialize the factory with configuration
     */
    public static synchronized void initialize() {
        if (configManager == null) {
            try {
                configManager = ConfigurationManager.getInstance();
                performanceConfig = configManager.getConfiguration(PerformanceConfiguration.class);
            } catch (Exception e) {
                KneafCore.LOGGER.error("Failed to initialize UnifiedBridgeFactory", e);
                throw new RuntimeException("Failed to initialize UnifiedBridgeFactory", e);
            }
        }
    }

    /**
     * Get or create a UnifiedBridge instance for the given bridge type
     * @param bridgeType Type of bridge to create (e.g., "rust-performance-bridge", "java-fallback-bridge")
     * @return Appropriate UnifiedBridge instance
     */
    public static UnifiedBridge getBridgeInstance(String bridgeType) {
        return getBridgeInstance(bridgeType, null);
    }

    /**
     * Get or create a UnifiedBridge instance for the given bridge type with custom configuration
     * @param bridgeType Type of bridge to create
     * @param customConfig Optional custom configuration
     * @return Appropriate UnifiedBridge instance
     */
    public static UnifiedBridge getBridgeInstance(String bridgeType, BridgeConfiguration customConfig) {
        ensureInitialized();

        // Check cache first for existing instances
        String cacheKey = bridgeType + ":" + (customConfig != null ? customConfig.hashCode() : 0);
        UnifiedBridge cachedBridge = BRIDGE_CACHE.get(cacheKey);
        if (cachedBridge != null) {
            return cachedBridge;
        }

        // Create new bridge instance based on type
        UnifiedBridge newBridge;
        BridgeConfiguration config = customConfig != null ? customConfig : createDefaultConfiguration();

        try {
            switch (bridgeType) {
                case "rust-performance-bridge":
                    newBridge = createRustPerformanceBridge(config);
                    break;
                case "java-fallback-bridge":
                    newBridge = createJavaFallbackBridge(config);
                    break;
                case "async-bridge":
                    newBridge = createAsyncBridge(config);
                    break;
                case "zero-copy-bridge":
                    newBridge = createZeroCopyBridge(config);
                    break;
                default:
                    KneafCore.LOGGER.warn("Unknown bridge type: {}, using fallback", bridgeType);
                    newBridge = createJavaFallbackBridge(config);
                    break;
            }

            // Cache the new bridge instance
            BRIDGE_CACHE.put(cacheKey, newBridge);
            return newBridge;

        } catch (Exception e) {
            KneafCore.LOGGER.error("Failed to create UnifiedBridge instance for type: {}", bridgeType, e);
            // Fall back to Java implementation if native bridge creation fails
            return createJavaFallbackBridge(createDefaultConfiguration());
        }
    }

    /**
     * Create a Rust performance bridge instance
     * @param config Bridge configuration
     * @return Rust performance bridge instance
     * @throws Exception If bridge creation fails
     */
    private static UnifiedBridge createRustPerformanceBridge(BridgeConfiguration config) throws Exception {
        // Check if native library is available first
        if (isNativeLibraryAvailable()) {
            try {
                return new UnifiedBridgeImpl(config);
            } catch (UnsatisfiedLinkError | Exception e) {
                KneafCore.LOGGER.warn("Rust performance bridge failed, falling back to Java implementation", e);
                return createJavaFallbackBridge(config);
            }
        } else {
            KneafCore.LOGGER.info("Native library not available, using Java fallback bridge");
            return createJavaFallbackBridge(config);
        }
    }

    /**
     * Create a Java fallback bridge instance
     * @param config Bridge configuration
     * @return Java fallback bridge instance
     */
    private static UnifiedBridge createJavaFallbackBridge(BridgeConfiguration config) {
        // Use AsynchronousBridge as fallback since it implements UnifiedBridge
        return new AsynchronousBridge(config);
    }

    /**
     * Create an async bridge instance
     * @param config Bridge configuration
     * @return Async bridge instance
     */
    private static UnifiedBridge createAsyncBridge(BridgeConfiguration config) {
        return new AsynchronousBridge(config);
    }

    /**
     * Create a zero-copy bridge instance
     * @param config Bridge configuration
     * @return Zero-copy bridge instance
     */
    private static UnifiedBridge createZeroCopyBridge(BridgeConfiguration config) {
        return new ZeroCopyBridge(config);
    }

    /**
     * Create default bridge configuration
     * @return Default bridge configuration
     */
    private static BridgeConfiguration createDefaultConfiguration() {
        BridgeConfiguration.Builder builder = BridgeConfiguration.builder();
        
        // Use performance configuration if available
        if (performanceConfig != null) {
            builder.defaultWorkerConcurrency(performanceConfig.getThreadpoolSize())
                   .enableZeroCopy(performanceConfig.isEnableZeroCopy())
                   .enableBufferPooling(performanceConfig.isEnableBufferPooling())
                   .bufferThresholdForDirectAllocation(performanceConfig.getBufferThresholdForDirectAllocation());
        } else {
            // Use sensible defaults
            builder.defaultWorkerConcurrency(4)
                   .enableZeroCopy(true)
                   .enableBufferPooling(true)
                   .bufferThresholdForDirectAllocation(4096);
        }
        
        return builder.build();
    }

    /**
     * Check if native library is available
     * @return true if native library is available, false otherwise
     */
    private static boolean isNativeLibraryAvailable() {
        try {
            // Try to load native library (will throw if not available)
            System.loadLibrary("rustperf");
            return true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            KneafCore.LOGGER.debug("Native library not available", e);
            return false;
        }
    }

    /**
     * Ensure factory is initialized
     */
    private static void ensureInitialized() {
        if (configManager == null) {
            initialize();
        }
    }

    /**
     * Clear the bridge cache (for testing purposes only)
     */
    public static void clearCache() {
        BRIDGE_CACHE.clear();
    }
}