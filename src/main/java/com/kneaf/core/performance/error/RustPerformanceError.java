package com.kneaf.core.performance.error;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Centralized error messages for Rust performance monitoring system.
 * Uses Java 17+ features for improved error handling and extensibility.
 */
public enum RustPerformanceError {
    LIBRARY_NOT_LOADED("Native library not loaded"),
    NOT_INITIALIZED("Rust performance system not initialized"),
    LIBRARY_LOAD_FAILED("Failed to load Rust performance native library"),
    INITIALIZATION_FAILED("Rust performance initialization failed"),
    ULTIMATE_INIT_FAILED("Ultra performance initialization failed"),
    INVALID_ARGUMENTS("Invalid arguments provided"),
    SEMAPHORE_TIMEOUT("Semaphore timeout"),
    NATIVE_CALL_FAILED("Native call failed"),
    MEMORY_OPTIMIZATION_FAILED("Memory optimization failed"),
    CHUNK_OPTIMIZATION_FAILED("Chunk optimization failed"),
    SHUTDOWN_ERROR("Error during performance monitoring shutdown"),
    COUNTER_RESET_ERROR("Error during counter reset"),
    CPU_STATS_ERROR("Error retrieving CPU stats"),
    MEMORY_STATS_ERROR("Error retrieving memory stats"),
    TPS_ERROR("Error retrieving TPS"),
    ENTITY_COUNT_ERROR("Error retrieving entity count"),
    MOB_COUNT_ERROR("Error retrieving mob count"),
    BLOCK_COUNT_ERROR("Error retrieving block count"),
    MERGED_COUNT_ERROR("Error retrieving merged count"),
    DESPAWNED_COUNT_ERROR("Error retrieving despawned count"),
    BATCH_PROCESSING_ERROR("Error during batch processing"),
    ZERO_COPY_ERROR("Error during zero-copy operation"),
    METRICS_ERROR("Error retrieving metrics"),
    LOG_STARTUP_ERROR("Error logging startup info");

    private final String message;

    RustPerformanceError(String message) {
        this.message = Objects.requireNonNull(message, "Error message cannot be null");
    }

    /**
     * Get the error message.
     *
     * @return The error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Create a runtime exception with this error.
     *
     * @return A new RuntimeException with this error message
     */
    public RuntimeException asRuntimeException() {
        return new RuntimeException(getMessage());
    }

    /**
     * Create a runtime exception with this error and a cause.
     *
     * @param cause The cause of the error
     * @return A new RuntimeException with this error message and cause
     */
    public RuntimeException asRuntimeException(Throwable cause) {
        return new RuntimeException(getMessage(), Objects.requireNonNull(cause, "Cause cannot be null"));
    }

    /**
     * Create an illegal state exception with this error.
     *
     * @return A new IllegalStateException with this error message
     */
    public IllegalStateException asIllegalStateException() {
        return new IllegalStateException(getMessage());
    }

    /**
     * Create an illegal state exception with this error and a cause.
     *
     * @param cause The cause of the error
     * @return A new IllegalStateException with this error message and cause
     */
    public IllegalStateException asIllegalStateException(Throwable cause) {
        return new IllegalStateException(getMessage(), Objects.requireNonNull(cause, "Cause cannot be null"));
    }

    /**
     * Get a formatted error message with additional context.
     *
     * @param args Format arguments
     * @return A formatted error message
     */
    public String format(Object... args) {
        return String.format(message, args);
    }

    @Override
    public String toString() {
        return name() + ": " + message;
    }

    /**
     * Get a RustPerformanceError by name (case-insensitive).
     *
     * @param name The name of the error
     * @return The RustPerformanceError with the given name, or null if not found
     */
    public static RustPerformanceError fromName(String name) {
        Objects.requireNonNull(name, "Name cannot be null");
        return switch (name.toUpperCase()) {
            case "LIBRARY_NOT_LOADED" -> LIBRARY_NOT_LOADED;
            case "NOT_INITIALIZED" -> NOT_INITIALIZED;
            case "LIBRARY_LOAD_FAILED" -> LIBRARY_LOAD_FAILED;
            case "INITIALIZATION_FAILED" -> INITIALIZATION_FAILED;
            case "ULTIMATE_INIT_FAILED" -> ULTIMATE_INIT_FAILED;
            case "INVALID_ARGUMENTS" -> INVALID_ARGUMENTS;
            case "SEMAPHORE_TIMEOUT" -> SEMAPHORE_TIMEOUT;
            case "NATIVE_CALL_FAILED" -> NATIVE_CALL_FAILED;
            case "MEMORY_OPTIMIZATION_FAILED" -> MEMORY_OPTIMIZATION_FAILED;
            case "CHUNK_OPTIMIZATION_FAILED" -> CHUNK_OPTIMIZATION_FAILED;
            case "SHUTDOWN_ERROR" -> SHUTDOWN_ERROR;
            case "COUNTER_RESET_ERROR" -> COUNTER_RESET_ERROR;
            case "CPU_STATS_ERROR" -> CPU_STATS_ERROR;
            case "MEMORY_STATS_ERROR" -> MEMORY_STATS_ERROR;
            case "TPS_ERROR" -> TPS_ERROR;
            case "ENTITY_COUNT_ERROR" -> ENTITY_COUNT_ERROR;
            case "MOB_COUNT_ERROR" -> MOB_COUNT_ERROR;
            case "BLOCK_COUNT_ERROR" -> BLOCK_COUNT_ERROR;
            case "MERGED_COUNT_ERROR" -> MERGED_COUNT_ERROR;
            case "DESPAWNED_COUNT_ERROR" -> DESPAWNED_COUNT_ERROR;
            case "BATCH_PROCESSING_ERROR" -> BATCH_PROCESSING_ERROR;
            case "ZERO_COPY_ERROR" -> ZERO_COPY_ERROR;
            case "METRICS_ERROR" -> METRICS_ERROR;
            case "LOG_STARTUP_ERROR" -> LOG_STARTUP_ERROR;
            default -> null;
        };
    }
}