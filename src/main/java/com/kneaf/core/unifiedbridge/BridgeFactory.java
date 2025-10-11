package com.kneaf.core.unifiedbridge;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Factory class for creating different types of UnifiedBridge implementations.
 * Implements the factory pattern for consistent bridge creation across the codebase.
 */
public final class BridgeFactory {
    private static final Logger LOGGER = Logger.getLogger(BridgeFactory.class.getName());
    private static final BridgeFactory INSTANCE = new BridgeFactory();

    private BridgeFactory() {
        LOGGER.info("BridgeFactory initialized");
    }

    /**
     * Get the singleton instance of BridgeFactory.
     * @return BridgeFactory instance
     */
    public static BridgeFactory getInstance() {
        return INSTANCE;
    }

    /**
     * Create a new bridge instance based on the specified type.
     * @param bridgeType Type of bridge to create
     * @param config Bridge configuration
     * @return Created bridge instance
     * @throws IllegalArgumentException If bridge type is unknown
     */
    public UnifiedBridge createBridge(BridgeType bridgeType, BridgeConfiguration config) {
        Objects.requireNonNull(bridgeType, "Bridge type cannot be null");
        Objects.requireNonNull(config, "Bridge configuration cannot be null");

        LOGGER.fine(() -> "Creating bridge of type: " + bridgeType.name());

        // Only ASYNCHRONOUS is actually used in production
        return switch (bridgeType) {
            case ASYNCHRONOUS -> new AsynchronousBridge(config);
            case SYNCHRONOUS -> new SynchronousBridge(config);  // Keep for compatibility
            case BATCH, ZERO_COPY -> {
                LOGGER.warning("Bridge type " + bridgeType + " is deprecated, using ASYNCHRONOUS instead");
                yield new AsynchronousBridge(config);
            }
            default -> throw new IllegalArgumentException("Unknown bridge type: " + bridgeType);
        };
    }

    /**
     * Create a new bridge instance with default configuration.
     * @param bridgeType Type of bridge to create
     * @return Created bridge instance with default configuration
     * @throws IllegalArgumentException If bridge type is unknown
     */
    public UnifiedBridge createBridge(BridgeType bridgeType) {
        return createBridge(bridgeType, BridgeConfiguration.getDefault());
    }

    /**
     * Create a unified bridge instance with the specified name.
     * @param bridgeName Name of the bridge
     * @return Created bridge instance with default configuration
     */
    public static UnifiedBridge createUnifiedBridge(String bridgeName) {
        return getInstance().createBridge(BridgeType.ASYNCHRONOUS, BridgeConfiguration.getDefault());
    }

    /**
     * Enum representing the different types of bridges that can be created.
     */
    public enum BridgeType {
        /** Synchronous bridge for immediate operations */
        SYNCHRONOUS,
        
        /** Asynchronous bridge for non-blocking operations */
        ASYNCHRONOUS,
        
        /** Batch bridge for processing multiple operations at once */
        BATCH,
        
        /** Zero-copy bridge for efficient memory operations */
        ZERO_COPY
    }
}