package com.kneaf.core.performance.error;

/**
 * Centralized error messages for Rust performance monitoring system.
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
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return name() + ": " + message;
    }
}