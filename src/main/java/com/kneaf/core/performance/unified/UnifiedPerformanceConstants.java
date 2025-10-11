package com.kneaf.core.performance.unified;

/**
 * Constants for unified performance monitoring system.
 */
public final class UnifiedPerformanceConstants {
    // Package private constructor to prevent instantiation
    private UnifiedPerformanceConstants() {}

    /** Default monitoring level */
    public static final MonitoringLevel DEFAULT_MONITORING_LEVEL = MonitoringLevel.NORMAL;
    
    /** Default log interval in ticks */
    public static final int DEFAULT_LOG_INTERVAL_TICKS = 100;
    
    /** Default TPS window size for rolling average */
    public static final int DEFAULT_TPS_WINDOW_SIZE = 8;
    
    /** Default TPS threshold for async operations */
    public static final double DEFAULT_TPS_THRESHOLD_ASYNC = 18.0;
    
    /** Default maximum entities to collect per tick */
    public static final int DEFAULT_MAX_ENTITIES_TO_COLLECT = 5000;
    
    /** Default entity distance cutoff in blocks */
    public static final double DEFAULT_ENTITY_DISTANCE_CUTOFF = 64.0;
    
    /** Default excluded entity types */
    public static final String[] DEFAULT_EXCLUDED_ENTITY_TYPES = {
        "minecraft:armor_stand",
        "minecraft:item_frame",
        "minecraft:painting",
        "minecraft:boat",
        "minecraft:chest_minecart"
    };
    
    /** Default JNI call threshold in milliseconds */
    public static final long DEFAULT_JNI_CALL_THRESHOLD_MS = 100;
    
    /** Default lock wait threshold in milliseconds */
    public static final long DEFAULT_LOCK_WAIT_THRESHOLD_MS = 50;
    
    /** Default memory usage threshold percentage */
    public static final double DEFAULT_MEMORY_USAGE_THRESHOLD_PCT = 90.0;
    
    /** Default GC duration threshold in milliseconds */
    public static final long DEFAULT_GC_DURATION_THRESHOLD_MS = 100;
    
    /** Default lock contention threshold (number of waiting threads) */
    public static final int DEFAULT_LOCK_CONTENTION_THRESHOLD = 5;
    
    /** Default high allocation latency threshold in milliseconds */
    public static final long DEFAULT_HIGH_ALLOCATION_LATENCY_THRESHOLD_MS = 10;
}