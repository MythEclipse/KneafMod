package com.kneaf.core.chunkstorage.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Centralized exception handling for chunk storage operations.
 */
public final class ChunkStorageExceptionHandler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkStorageExceptionHandler.class);
    
    private ChunkStorageExceptionHandler() {
        // Private constructor to prevent instantiation
    }
    
    /**
     * Handle exceptions during chunk save operations.
     * 
     * @param chunkKey The chunk key
     * @param exception The exception that occurred
     * @param operation The operation being performed
     * @return Failed CompletableFuture with the exception
     */
    public static CompletableFuture<Void> handleSaveException(String chunkKey, Exception exception, String operation) {
        LOGGER.error("Failed to {} chunk {}", operation, chunkKey, exception);
        return CompletableFuture.failedFuture(exception);
    }
    
    /**
     * Handle exceptions during chunk load operations.
     * 
     * @param chunkKey The chunk key
     * @param exception The exception that occurred
     * @return Empty Optional as the operation failed
     */
    public static <T> java.util.Optional<T> handleLoadException(String chunkKey, Exception exception) {
        LOGGER.error("Failed to load chunk {}", chunkKey, exception);
        return java.util.Optional.empty();
    }
    
    /**
     * Handle exceptions during database operations.
     * 
     * @param operation The database operation
     * @param chunkKey The chunk key
     * @param exception The exception that occurred
     * @return false to indicate failure
     */
    public static boolean handleDatabaseException(String operation, String chunkKey, Exception exception) {
        LOGGER.error("Database {} failed for chunk {}", operation, chunkKey, exception);
        return false;
    }
    
    /**
     * Handle exceptions during swap operations.
     * 
     * @param operation The swap operation (swap-in/swap-out)
     * @param chunkKey The chunk key
     * @param exception The exception that occurred
     * @return false to indicate failure
     */
    public static boolean handleSwapException(String operation, String chunkKey, Exception exception) {
        LOGGER.error("Swap {} failed for chunk {}", operation, chunkKey, exception);
        return false;
    }
    
    /**
     * Handle exceptions during initialization.
     * 
     * @param component The component being initialized
     * @param exception The exception that occurred
     * @param fallbackMessage Fallback message if exception message is null
     */
    public static void handleInitializationException(String component, Exception exception, String fallbackMessage) {
        String message = exception.getMessage() != null ? exception.getMessage() : fallbackMessage;
        LOGGER.error("Failed to initialize {}: {}", component, message, exception);
    }
    
    /**
     * Handle exceptions during shutdown.
     * 
     * @param component The component being shutdown
     * @param exception The exception that occurred
     */
    public static void handleShutdownException(String component, Exception exception) {
        LOGGER.error("Error during shutdown of {}", component, exception);
    }
    
    /**
     * Handle exceptions during maintenance operations.
     * 
     * @param component The component performing maintenance
     * @param exception The exception that occurred
     */
    public static void handleMaintenanceException(String component, Exception exception) {
        LOGGER.error("Failed to perform maintenance for {}", component, exception);
    }
    
    /**
     * Handle exceptions during backup operations.
     * 
     * @param component The component performing backup
     * @param backupPath The backup path
     * @param exception The exception that occurred
     */
    public static void handleBackupException(String component, String backupPath, Exception exception) {
        LOGGER.error("Failed to create backup for {} at '{}'", component, backupPath, exception);
    }
    
    /**
     * Handle exceptions during health checks.
     * 
     * @param component The component being checked
     * @param exception The exception that occurred
     * @return false to indicate unhealthy state
     */
    public static boolean handleHealthCheckException(String component, Exception exception) {
        LOGGER.error("Health check failed for {}", component, exception);
        return false;
    }
    
    /**
     * Handle exceptions during statistics collection.
     * 
     * @param component The component collecting stats
     * @param exception The exception that occurred
     * @param defaultStats Default stats to return on failure
     * @return Statistics object (either collected or default)
     */
    public static <T> T handleStatsException(String component, Exception exception, T defaultStats) {
        LOGGER.error("Failed to get statistics for {}", component, exception);
        return defaultStats;
    }
    
    /**
     * Handle reflection-related exceptions.
     * 
     * @param operation The reflection operation
     * @param className The class name involved
     * @param exception The exception that occurred
     * @return null or false depending on context
     */
    public static <T> T handleReflectionException(String operation, String className, Exception exception) {
        LOGGER.debug("Reflection {} failed for class {}: {}", operation, className, exception.getMessage());
        return null;
    }
    
    /**
     * Log a warning message for non-critical issues.
     * 
     * @param component The component reporting the warning
     * @param message The warning message
     * @param params Optional parameters for the message
     */
    public static void logWarning(String component, String message, Object... params) {
        LOGGER.warn("[{}] {}", component, String.format(message, params));
    }
    
    /**
     * Log a debug message for troubleshooting.
     * 
     * @param component The component reporting the debug info
     * @param message The debug message
     * @param params Optional parameters for the message
     */
    public static void logDebug(String component, String message, Object... params) {
        LOGGER.debug("[{}] {}", component, String.format(message, params));
    }
    
    /**
     * Log an info message for normal operations.
     * 
     * @param component The component reporting the info
     * @param message The info message
     * @param params Optional parameters for the message
     */
    public static void logInfo(String component, String message, Object... params) {
        LOGGER.info("[{}] {}", component, String.format(message, params));
    }
}