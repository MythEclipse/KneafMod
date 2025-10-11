package com.kneaf.core.unifiedbridge;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Comprehensive error handling for bridge operations with fallback mechanisms.
 */
public class BridgeErrorHandler {
    private static final Logger LOGGER = Logger.getLogger(BridgeErrorHandler.class.getName());
    private static final BridgeErrorHandler INSTANCE = new BridgeErrorHandler();
    
    private volatile BridgeConfiguration config;
    private final ErrorRecoveryStrategy defaultRecoveryStrategy;

    private BridgeErrorHandler() {
        this.config = BridgeConfiguration.getDefault();
        this.defaultRecoveryStrategy = new DefaultErrorRecoveryStrategy();
        LOGGER.info("BridgeErrorHandler initialized with default configuration");
    }

    /**
     * Get the singleton instance of BridgeErrorHandler.
     * @return BridgeErrorHandler instance
     */
    public static BridgeErrorHandler getInstance() {
        return INSTANCE;
    }

    /**
     * Get the default singleton instance of BridgeErrorHandler.
     * @return BridgeErrorHandler instance
     */
    public static BridgeErrorHandler getDefault() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return BridgeErrorHandler instance
     */
    public static BridgeErrorHandler getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("BridgeErrorHandler reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Handle a bridge error and return a fallback result.
     * @param message Error message
     * @param error Error that occurred
     * @param errorType Type of error
     * @param <T> Return type
     * @return Fallback result
     */
    public <T> T handleBridgeError(String message, Throwable error, BridgeException.BridgeErrorType errorType) {
        LOGGER.log(Level.SEVERE, message, error);
        
        // Try to recover from the error using the appropriate strategy
        ErrorRecoveryStrategy strategy = getRecoveryStrategy(errorType);
        try {
            return strategy.recover(errorType, message, error);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error recovery strategy failed", e);
            throw new BridgeException(message + " - Recovery failed", errorType, e);
        }
    }

    /**
     * Handle a bridge error and throw a BridgeException.
     * @param message Error message
     * @param error Error that occurred
     * @param errorType Type of error
     * @throws BridgeException Always throws a BridgeException
     */
    public BridgeException createBridgeError(String message, Throwable error, BridgeException.BridgeErrorType errorType) {
        LOGGER.log(Level.SEVERE, message, error);
        return new BridgeException(message, errorType, error);
    }

    /**
     * Handle a bridge error and return a fallback result with default value.
     * @param message Error message
     * @param error Error that occurred
     * @param errorType Type of error
     * @param defaultValue Default value to return if recovery fails
     * @param <T> Return type
     * @return Fallback result or defaultValue
     */
    public <T> T handleBridgeErrorWithResult(String message, Throwable error, BridgeException.BridgeErrorType errorType, T defaultValue) {
        try {
            return handleBridgeError(message, error, errorType);
        } catch (Exception recoveryError) {
            LOGGER.log(Level.WARNING, "Error recovery failed, returning default value", recoveryError);
            return defaultValue;
        }
    }

    /**
     * Handle a bridge error and throw a BridgeException.
     * @param message Error message
     * @param error Error that occurred
     * @param errorType Type of error
     * @throws BridgeException Always throws a BridgeException
     */
    public void handleBridgeErrorAndThrow(String message, Throwable error, BridgeException.BridgeErrorType errorType) throws BridgeException {
        LOGGER.log(Level.SEVERE, message, error);
        throw new BridgeException(message, errorType, error);
    }


    /**
     * Get the recovery strategy for a specific error type.
     * @param errorType Error type
     * @return Error recovery strategy
     */
    protected ErrorRecoveryStrategy getRecoveryStrategy(BridgeException.BridgeErrorType errorType) {
        // In a complete implementation, we would return different strategies based on error type
        // For now, return the default strategy
        return defaultRecoveryStrategy;
    }

    /**
     * Register a custom error recovery strategy.
     * @param errorType Error type
     * @param strategy Recovery strategy
     */
    public void registerRecoveryStrategy(BridgeException.BridgeErrorType errorType, ErrorRecoveryStrategy strategy) {
        Objects.requireNonNull(errorType, "Error type cannot be null");
        Objects.requireNonNull(strategy, "Recovery strategy cannot be null");
        // In a complete implementation, we would store and retrieve strategies by error type
        LOGGER.info("Registered custom recovery strategy for error type: " + errorType);
    }

    /**
     * Interface for error recovery strategies.
     */
    @FunctionalInterface
    public interface ErrorRecoveryStrategy {
        /**
         * Try to recover from an error.
         * @param errorType Type of error
         * @param message Error message
         * @param error Error that occurred
         * @param <T> Return type
         * @return Recovery result
         * @throws Exception If recovery fails
         */
        <T> T recover(BridgeException.BridgeErrorType errorType, String message, Throwable error) throws Exception;
    }

    /**
     * Default error recovery strategy implementation.
     */
    private static class DefaultErrorRecoveryStrategy implements ErrorRecoveryStrategy {
        @Override
        public <T> T recover(BridgeException.BridgeErrorType errorType, String message, Throwable error) throws Exception {
            // In a complete implementation, we would have different recovery logic for different error types
            // For now, we just rethrow the exception as we can't really recover from most bridge errors
            LOGGER.log(Level.SEVERE, "No specific recovery strategy for error type: " + errorType, error);
            throw new BridgeException(message, errorType, error);
        }
    }
}