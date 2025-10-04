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
    
    // Batch processing constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveBatchSize(double,double)} instead. */
    @Deprecated
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** @deprecated Use {@link #getAdaptiveBatchTimeoutMs(double,double)} instead. Value in ms. */
    @Deprecated
    public static final long DEFAULT_BATCH_TIMEOUT_MS = 50;

    /** @deprecated Use {@link #getAdaptiveBatchProcessorSleepMs(double)} instead. */
    @Deprecated
    public static final int BATCH_PROCESSOR_SLEEP_MS = 5;
    // Deprecated static timeout removed in favor of adaptive per-request timeouts
    // public static final int BATCH_REQUEST_TIMEOUT_SECONDS = 5;
    
    // Connection pool constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveMaxConnections(double)} instead. */
    @Deprecated
    public static final int MAX_CONNECTIONS = 10;

    /** @deprecated Use {@link #getAdaptiveInitialConnections(double)} instead. */
    @Deprecated
    public static final int INITIAL_CONNECTIONS = 5;
    
    // Native library constants
    public static final String NATIVE_LIBRARY_NAME = "rustperf";
    
    // Performance thresholds (fallbacks)
    /** @deprecated Use live values from PerformanceManager and adaptive getters. */
    @Deprecated
    public static final double DEFAULT_TPS_THRESHOLD = 19.0;

    /** @deprecated Use live values from PerformanceManager and adaptive getters. */
    @Deprecated
    public static final double MIN_TPS_THRESHOLD = 10.0;

    /** @deprecated Use live values from PerformanceManager and adaptive getters. */
    @Deprecated
    public static final double MAX_TPS_THRESHOLD = 20.0;
    
    // Distance calculation constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveDistanceCalculationInterval(double)} and manager values. */
    @Deprecated
    public static final double DEFAULT_DISTANCE_CUTOFF = 256.0;

    /** @deprecated Use {@link #getAdaptiveDistanceCalculationInterval(double)} instead. */
    @Deprecated
    public static final int DISTANCE_CALCULATION_INTERVAL = 10;
    
    // Spatial grid constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveCellSize(double)} instead. */
    @Deprecated
    public static final double DEFAULT_CELL_SIZE = 32.0;

    /** @deprecated Use adaptive spatial grid update thresholds if needed. */
    @Deprecated
    public static final double SPATIAL_GRID_UPDATE_THRESHOLD = 1.0;
    
    // Buffer pooling constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveBufferPoolSize(double)} instead. */
    @Deprecated
    public static final int BUFFER_POOL_SIZE = 50;

    /** @deprecated Use {@link #getAdaptiveMaxBufferSize(double)} instead. Value in bytes. */
    @Deprecated
    public static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB

    /** @deprecated Use {@link #getAdaptiveBufferUseThreshold(double)} instead. Value in bytes. */
    @Deprecated
    public static final int BUFFER_USE_THRESHOLD = 8192; // 8KB

    public static int getAdaptiveDistanceCalculationInterval(double tps) {
        if (tps < 15.0) return 20;
        if (tps < 18.0) return 12;
        return 10;
    }

    public static int getAdaptiveBufferUseThreshold(double tps) {
        if (tps < 15.0) return 16384; // only use direct buffers for bigger payloads under stress
        if (tps < 18.0) return 12288;
        return BUFFER_USE_THRESHOLD;
    }

    public static int getAdaptiveChunkGenerationTimeoutMs(double tps) {
        if (tps < 15.0) return 15000; // shorter timeouts when under stress to avoid long blocking
        if (tps < 18.0) return 25000;
        return CHUNK_GENERATION_TIMEOUT_MS;
    }
    
    // Thread pool constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveCoreThreads(double)} and {@link #getAdaptiveMaxThreads(double)} instead. */
    @Deprecated
    public static final int CORE_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    /** @deprecated Use {@link #getAdaptiveMaxThreads(double)} instead. */
    @Deprecated
    public static final int MAX_THREADS = Math.max(8, CORE_THREADS * 2);

    /** @deprecated Use {@link #getAdaptiveThreadKeepAliveSeconds(double)} instead. Value in seconds. */
    @Deprecated
    public static final long THREAD_KEEP_ALIVE_TIME = 60L;

    /** @deprecated Use {@link #getAdaptiveQueueCapacity(double)} instead. */
    @Deprecated
    public static final int QUEUE_CAPACITY = 1000;

    /**
     * Adaptive getter for max connections in pools
     */
    public static int getAdaptiveMaxConnections(double tps) {
        if (tps < 15.0) return Math.max(2, MAX_CONNECTIONS / 2);
        if (tps < 18.0) return Math.max(4, (int) (MAX_CONNECTIONS * 0.75));
        return MAX_CONNECTIONS;
    }

    /**
     * Adaptive getter for initial/starting connections
     */
    public static int getAdaptiveInitialConnections(double tps) {
        if (tps < 15.0) return Math.max(1, INITIAL_CONNECTIONS / 2);
        if (tps < 18.0) return Math.max(2, (int) (INITIAL_CONNECTIONS * 0.75));
        return INITIAL_CONNECTIONS;
    }

    /**
     * Adaptive core thread base. Keeps a safe minimum based on CPU but can scale with TPS.
     */
    public static int getAdaptiveCoreThreads(double tps) {
        int base = Math.max(4, Runtime.getRuntime().availableProcessors());
        if (tps < 15.0) return Math.max(2, base / 2);
        if (tps < 18.0) return Math.max(3, (int) (base * 0.75));
        return base;
    }

    /**
     * Adaptive keep-alive time (seconds) used for thread pools
     */
    public static long getAdaptiveThreadKeepAliveSeconds(double tps) {
        if (tps < 15.0) return Math.max(10L, THREAD_KEEP_ALIVE_TIME / 2);
        if (tps < 18.0) return Math.max(30L, THREAD_KEEP_ALIVE_TIME * 3 / 4);
        return THREAD_KEEP_ALIVE_TIME;
    }

    // Dynamic getters - allow adaptation based on TPS / tick delay
    public static int getAdaptiveBatchSize(double tps, double tickDelayMs) {
        // Scale down batch size when TPS is low or tick delay high
        double tpsFactor = Math.max(0.25, tps / 20.0);
        double delayFactor = tickDelayMs > 50.0 ? Math.max(0.5, 50.0 / tickDelayMs) : 1.0;
        return Math.max(10, (int) (DEFAULT_BATCH_SIZE * tpsFactor * delayFactor));
    }

    public static long getAdaptiveBatchTimeoutMs(double tps, double tickDelayMs) {
        if (tps < 15.0) return Math.max(20, DEFAULT_BATCH_TIMEOUT_MS * 2);
        if (tps < 18.0) return Math.max(10, DEFAULT_BATCH_TIMEOUT_MS);
        return DEFAULT_BATCH_TIMEOUT_MS;
    }

    public static int getAdaptiveBatchProcessorSleepMs(double tps) {
        if (tps < 15.0) return Math.max(1, BATCH_PROCESSOR_SLEEP_MS / 2);
        if (tps < 18.0) return BATCH_PROCESSOR_SLEEP_MS;
        return Math.max(1, BATCH_PROCESSOR_SLEEP_MS * 2);
    }

    public static double getAdaptiveCellSize(double tps) {
        // When TPS drops, increase cell size to reduce partitions
        if (tps < 15.0) return DEFAULT_CELL_SIZE * 2.0;
        if (tps < 18.0) return DEFAULT_CELL_SIZE * 1.5;
        return DEFAULT_CELL_SIZE;
    }

    public static int getAdaptiveQueueCapacity(double tps) {
        // Reduce queue capacity slightly under heavy load to backpressure
        if (tps < 15.0) return Math.max(100, QUEUE_CAPACITY / 2);
        if (tps < 18.0) return Math.max(200, QUEUE_CAPACITY * 3 / 4);
        return QUEUE_CAPACITY;
    }

    public static int getAdaptivePredictiveRadius(double tps) {
        if (tps < 15.0) return 2;
        if (tps < 18.0) return 3;
        return 4;
    }

    public static int getAdaptiveMaxPredictiveChunksPerTick(double tps) {
        if (tps < 15.0) return 2;
        if (tps < 18.0) return 4;
        return 8;
    }
    
    // Logging constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveLogIntervalTicks(double)} instead. */
    @Deprecated
    public static final int DEFAULT_LOG_INTERVAL_TICKS = 100;

    /** @deprecated Use {@link #getAdaptiveMaxLogBytes(double)} instead. */
    @Deprecated
    public static final long DEFAULT_MAX_LOG_BYTES = 10L * 1024 * 1024; // 10MB
    
    public static int getAdaptiveLogIntervalTicks(double tps) {
        if (tps < 15.0) return Math.max(20, DEFAULT_LOG_INTERVAL_TICKS * 2);
        if (tps < 18.0) return Math.max(50, DEFAULT_LOG_INTERVAL_TICKS);
        return DEFAULT_LOG_INTERVAL_TICKS;
    }

    public static long getAdaptiveMaxLogBytes(double tps) {
        if (tps < 15.0) return Math.max(2L * 1024 * 1024, DEFAULT_MAX_LOG_BYTES / 2); // 2MB min
        if (tps < 18.0) return Math.max(5L * 1024 * 1024, DEFAULT_MAX_LOG_BYTES * 3 / 4);
        return DEFAULT_MAX_LOG_BYTES;
    }
    
    // Profiling constants (fallbacks)
    /** @deprecated Use {@link #getAdaptiveTargetTickTimeMs(double)} and config-driven values. */
    @Deprecated
    public static final long DEFAULT_SLOW_TICK_THRESHOLD_MS = 50L;

    /** @deprecated Use {@link #getAdaptiveProfilingSampleRate(double)} instead. */
    @Deprecated
    public static final int DEFAULT_PROFILING_SAMPLE_RATE = 100;

    public static int getAdaptiveProfilingSampleRate(double tps) {
        if (tps < 15.0) return Math.max(10, DEFAULT_PROFILING_SAMPLE_RATE / 2);
        if (tps < 18.0) return DEFAULT_PROFILING_SAMPLE_RATE;
        return DEFAULT_PROFILING_SAMPLE_RATE;
    }
    
    // Chunk generation constants (fallback)
    /** @deprecated Use {@link #getAdaptiveChunkGenerationTimeoutMs(double)} instead. Value in ms. */
    @Deprecated
    public static final int CHUNK_GENERATION_TIMEOUT_MS = 30000;

    // Buffer and pool adaptive getters
    public static int getAdaptiveBufferPoolSize(double tps) {
        if (tps < 15.0) return Math.max(8, BUFFER_POOL_SIZE / 2);
        if (tps < 18.0) return Math.max(16, BUFFER_POOL_SIZE * 3 / 4);
        return BUFFER_POOL_SIZE;
    }

    public static int getAdaptiveMaxPoolPerBucket(double tps) {
        if (tps < 15.0) return Math.max(8, 10);
        if (tps < 18.0) return Math.max(16, 20);
        return 20; // default
    }

    public static int getAdaptiveMaxBucketSize(double tps) {
        if (tps < 15.0) return 20; // 1MB
        return 24; // 16MB
    }

    public static int getAdaptivePrefetchCount(double tps) {
        if (tps < 15.0) return 2;
        if (tps < 18.0) return 3;
        return 5;
    }

    /**
     * Maximum buffer size we attempt to pool/allow for direct buffers.
     * When server is under heavy load (low TPS) reduce the maximum to
     * avoid large pooled allocations.
     */
    public static int getAdaptiveMaxBufferSize(double tps) {
        if (tps < 15.0) return 512 * 1024; // 512KB under heavy load
        if (tps < 18.0) return 768 * 1024; // 768KB moderate
        return MAX_BUFFER_SIZE;
    }

    public static int getAdaptiveMaxThreads(double tps) {
        if (tps < 15.0) return Math.max(4, MAX_THREADS / 2);
        if (tps < 18.0) return Math.max(6, (int)(MAX_THREADS * 0.75));
        return MAX_THREADS;
    }

    /* High-level gameplay/adaptive limits */
    public static int getAdaptiveMaxEntities(double tps, double tickDelayMs) {
        double tpsFactor = Math.max(0.5, Math.min(1.5, tps / 20.0));
        double delayFactor = 1.0;
        if (tickDelayMs > DEFAULT_SLOW_TICK_THRESHOLD_MS) {
            delayFactor = Math.max(0.5, DEFAULT_SLOW_TICK_THRESHOLD_MS / tickDelayMs);
        }
        return Math.max(10, (int) (200 * tpsFactor * delayFactor));
    }

    public static int getAdaptiveMaxItems(double tps) {
        double tpsFactor = Math.max(0.5, Math.min(1.5, tps / 20.0));
        return Math.max(10, (int) (300 * tpsFactor));
    }

    public static int getAdaptiveMaxMobs(double tps) {
        double tpsFactor = Math.max(0.4, Math.min(1.2, tps / 20.0));
        return Math.max(5, (int) (150 * tpsFactor));
    }

    public static int getAdaptiveMaxBlocks(double tps) {
        double tpsFactor = Math.max(0.6, Math.min(1.3, tps / 20.0));
        return Math.max(10, (int) (500 * tpsFactor));
    }

    public static double getAdaptiveTargetTickTimeMs(double tps) {
        return DEFAULT_SLOW_TICK_THRESHOLD_MS * (20.0 / Math.max(0.1, tps));
    }

    public static int getAdaptiveOptimizationThreshold(double tps) {
        double factor = tps >= 20.0 ? 1.0 : Math.max(0.4, tps / 20.0);
        int base = 25;
        return (int) Math.max(1, base * factor);
    }

    /** Memory usage threshold (%) above which GC+cleanup is considered */
    public static double getAdaptiveMemoryUsageThreshold(double tps) {
        if (tps < 15.0) return 75.0; // be more aggressive under low TPS
        if (tps < 18.0) return 80.0;
        return 85.0;
    }

    /* Chunk prediction tuning */
    public static int getAdaptivePlayerMovementHistorySize(double tps) {
        if (tps < 15.0) return 5;
        if (tps < 18.0) return 8;
        return 10;
    }

    public static double getAdaptiveMinVelocityThreshold(double tps) {
        if (tps < 15.0) return 0.2;
        return 0.1;
    }

    public static long getAdaptivePredictionValidityMs(double tps) {
        if (tps < 15.0) return 3000L;
        if (tps < 18.0) return 5000L;
        return 7000L;
    }

    /** Buffer pool cleanup interval (ms) - how often to sweep pools. */
    public static long getAdaptiveBufferCleanupIntervalMs(double tps) {
        if (tps < 15.0) return 30000L; // more frequent under stress
        if (tps < 18.0) return 45000L;
        return 60000L; // default 60s
    }

    /** Enhanced pool adaptive-check interval (ms) used to throttle pressure updates. */
    public static long getAdaptiveEnhancedCheckIntervalMs(double tps) {
        if (tps < 15.0) return 2000L;
        if (tps < 18.0) return 3500L;
        return 5000L;
    }

    /** Adaptive interval for executor management checks (ms). */
    public static long getAdaptiveExecutorCheckIntervalMs(double tps) {
        if (tps < 15.0) return 2000L;
        if (tps < 18.0) return 4000L;
        return 5000L;
    }

    /* Network-related adaptive getters */
    public static int getAdaptiveNetworkBatchScheduleMs(double tps) {
        // Schedule delay for half-full batch processing
        if (tps < 15.0) return 25;
        if (tps < 18.0) return 15;
        return 10;
    }

    public static double getAdaptiveBatchFlushFraction(double tps) {
        // Fraction of batch size to trigger a flush; when TPS is low flush sooner (smaller fraction)
        if (tps < 15.0) return 0.25;
        if (tps < 18.0) return 0.4;
        return 0.5;
    }

    public static int getAdaptivePacketCompressionThreshold(double tps) {
        // Packet size (bytes) above which compression is considered
        if (tps < 15.0) return 1500; // under stress compress larger packets only
        if (tps < 18.0) return 1200;
        return 1000;
    }

    public static int getAdaptiveRateLimitBaseDelayMs(double tps) {
        if (tps < 15.0) return 5;
        if (tps < 18.0) return 3;
        return 2;
    }

    public static int getAdaptiveRateLimitExtraForSize(int size, double tps) {
        double factor = Math.max(0.5, Math.min(2.0, 20.0 / Math.max(0.1, tps)));
        if (size > 5000) return (int) Math.round(10 * factor);
        if (size > 1000) return (int) Math.round(5 * factor);
        return 0;
    }
}