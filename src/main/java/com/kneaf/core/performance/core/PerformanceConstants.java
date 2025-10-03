package com.kneaf.core.performance.core;

/**
 * Constants used throughout the performance system.
 */
public final class PerformanceConstants {
    
    private PerformanceConstants() {} // Prevent instantiation
    
    // Request type constants
    public static final String ENTITIES_KEY = "entities";
    public static final String ITEMS_KEY = "items";
    public static final String MOBS_KEY = "mobs";
    public static final String BLOCKS_KEY = "blocks";
    public static final String PLAYERS_KEY = "players";
    
    // JSON keys
    public static final String TICK_COUNT_KEY = "tickCount";
    
    // Error messages
    public static final String BINARY_FALLBACK_MESSAGE = "Binary protocol failed, falling back to JSON: {}";
    public static final String NATIVE_NOT_AVAILABLE_ERROR = "Rust native library is not available";
    
    // Batch processing constants
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final long DEFAULT_BATCH_TIMEOUT_MS = 50;
    public static final int BATCH_PROCESSOR_SLEEP_MS = 5;
    public static final int BATCH_REQUEST_TIMEOUT_SECONDS = 5;
    
    // Connection pool constants
    public static final int MAX_CONNECTIONS = 10;
    public static final int INITIAL_CONNECTIONS = 5;
    
    // Native library constants
    public static final String NATIVE_LIBRARY_NAME = "rustperf";
    
    // Performance thresholds
    public static final double DEFAULT_TPS_THRESHOLD = 19.0;
    public static final double MIN_TPS_THRESHOLD = 10.0;
    public static final double MAX_TPS_THRESHOLD = 20.0;
    
    // Distance calculation constants
    public static final double DEFAULT_DISTANCE_CUTOFF = 256.0;
    public static final int DISTANCE_CALCULATION_INTERVAL = 10;
    
    // Spatial grid constants
    public static final double DEFAULT_CELL_SIZE = 32.0;
    public static final double SPATIAL_GRID_UPDATE_THRESHOLD = 1.0;
    
    // Buffer pooling constants
    public static final int BUFFER_POOL_SIZE = 50;
    public static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB
    public static final int BUFFER_USE_THRESHOLD = 8192; // 8KB
    
    // Thread pool constants
    public static final int CORE_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    public static final int MAX_THREADS = Math.max(8, CORE_THREADS * 2);
    public static final long THREAD_KEEP_ALIVE_TIME = 60L;
    public static final int QUEUE_CAPACITY = 1000;
    
    // Logging constants
    public static final int DEFAULT_LOG_INTERVAL_TICKS = 100;
    public static final long DEFAULT_MAX_LOG_BYTES = 10L * 1024 * 1024; // 10MB
    
    // Profiling constants
    public static final long DEFAULT_SLOW_TICK_THRESHOLD_MS = 50L;
    public static final int DEFAULT_PROFILING_SAMPLE_RATE = 100;
    
    // Chunk generation constants
    public static final int CHUNK_GENERATION_TIMEOUT_MS = 30000;
}