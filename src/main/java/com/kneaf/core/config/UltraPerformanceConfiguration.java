package com.kneaf.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Ultra-performance configuration loader specifically designed for minimal latency optimization.
 * This configuration disables safety checks and enables aggressive optimizations for maximum performance.
 */
public class UltraPerformanceConfiguration {
    
    private static final String CONFIG_FILE = "config/kneaf-performance-ultra.properties";
    private static PerformanceConfiguration config;
    
    public static PerformanceConfiguration load() {
        if (config != null) {
            return config;
        }
        
        Properties props = new Properties();
        Path configPath = Paths.get(CONFIG_FILE);
        
        try {
            if (Files.exists(configPath)) {
                props.load(Files.newInputStream(configPath));
            }
        } catch (IOException e) {
            System.err.println("Failed to load ultra-performance configuration: " + e.getMessage());
            // Fallback to default ultra-performance settings
        }
        
        // Apply ultra-performance settings with aggressive optimizations
        config = PerformanceConfiguration.builder()
            .enabled(getBoolean(props, "enabled", true))
            .threadpoolSize(getInt(props, "threadpoolSize", Math.max(1, Runtime.getRuntime().availableProcessors())))
            .logIntervalTicks(getInt(props, "logIntervalTicks", 1000)) // Less frequent logging
            .scanIntervalTicks(getInt(props, "scanIntervalTicks", 1))
            .tpsThresholdForAsync(getDouble(props, "tpsThresholdForAsync", 17.0)) // Lower threshold for more aggressive async (from 19.0 to 17.0)
            .maxEntitiesToCollect(getInt(props, "maxEntitiesToCollect", 50000)) // Higher limit
            .entityDistanceCutoff(getDouble(props, "entityDistanceCutoff", 128.0)) // Smaller cutoff
            .maxLogBytes(getLong(props, "maxLogBytes", 5L * 1024 * 1024)) // Smaller log size
            .adaptiveThreadPool(getBoolean(props, "adaptiveThreadPool", true))
            .maxThreadpoolSize(getInt(props, "maxThreadpoolSize", Math.max(1, Runtime.getRuntime().availableProcessors() * 2)))
            .profilingEnabled(getBoolean(props, "profilingEnabled", false)) // Disable profiling
            .slowTickThresholdMs(getLong(props, "slowTickThresholdMs", 30L)) // Lower threshold
            .profilingSampleRate(getInt(props, "profilingSampleRate", 10)) // Minimal sampling
            
            // Advanced parallelism
            .minThreadpoolSize(getInt(props, "minThreadpoolSize", 1))
            .dynamicThreadScaling(getBoolean(props, "dynamicThreadScaling", true))
            .threadScaleUpThreshold(getDouble(props, "threadScaleUpThreshold", 0.9)) // Higher threshold
            .threadScaleDownThreshold(getDouble(props, "threadScaleDownThreshold", 0.2)) // Lower threshold
            .threadScaleUpDelayTicks(getInt(props, "threadScaleUpDelayTicks", 50)) // Faster scaling
            .threadScaleDownDelayTicks(getInt(props, "threadScaleDownDelayTicks", 100))
            .workStealingEnabled(getBoolean(props, "workStealingEnabled", true))
            .workStealingQueueSize(getInt(props, "workStealingQueueSize", 200)) // Larger queue
            .cpuAwareThreadSizing(getBoolean(props, "cpuAwareThreadSizing", true))
            .cpuLoadThreshold(getDouble(props, "cpuLoadThreshold", 0.8)) // Higher threshold
            .threadPoolKeepAliveSeconds(getInt(props, "threadPoolKeepAliveSeconds", 30)) // Shorter keep-alive
            
            // Distance optimizations
            .distanceCalculationInterval(getInt(props, "distanceCalculationInterval", 1))
            .distanceApproximationEnabled(getBoolean(props, "distanceApproximationEnabled", true))
            .distanceCacheSize(getInt(props, "distanceCacheSize", 500)) // Larger cache
            .itemProcessingIntervalMultiplier(getInt(props, "itemProcessingIntervalMultiplier", 1))
            .spatialGridUpdateInterval(getInt(props, "spatialGridUpdateInterval", 1))
            .incrementalSpatialUpdates(getBoolean(props, "incrementalSpatialUpdates", true))
            
            // Extreme performance
            .enableExtremeAvx512(getBoolean(props, "enableExtremeAvx512", true))
            .enableLockFreePooling(getBoolean(props, "enableLockFreePooling", true))
            .memoryPressureThreshold(getDouble(props, "memoryPressureThreshold", 0.95)) // Higher threshold
            .enableAggressivePreallocation(getBoolean(props, "enableAggressivePreallocation", true))
            .preallocationBufferSize(getInt(props, "preallocationBufferSize", 512)) // Standard buffer size
            .enableSafetyChecks(getBoolean(props, "enableSafetyChecks", false)) // Disable safety checks
            .enableMemoryLeakDetection(getBoolean(props, "enableMemoryLeakDetection", false)) // Disable leak detection
            .enablePerformanceMonitoring(getBoolean(props, "enablePerformanceMonitoring", false)) // Disable monitoring
            .enableErrorRecovery(getBoolean(props, "enableErrorRecovery", false)) // Disable error recovery
            .enableMinimalMonitoring(getBoolean(props, "enableMinimalMonitoring", true))
            .monitoringSampleRate(getInt(props, "monitoringSampleRate", 5)) // Very low sampling
            .enablePerformanceWarnings(getBoolean(props, "enablePerformanceWarnings", false)) // Disable warnings
            .enableFeatureFlags(getBoolean(props, "enableFeatureFlags", true))
            .enableAutoRollback(getBoolean(props, "enableAutoRollback", false)) // Disable auto-rollback
            .rollbackThreshold(getDouble(props, "rollbackThreshold", 50.0)) // Higher threshold
            .rollbackCheckInterval(getInt(props, "rollbackCheckInterval", 500)) // Less frequent checks
            
            // Ultra-performance settings
            .enableUltraPerformanceMode(true)
            .enableAggressiveOptimizations(true)
            .maxConcurrentOperations(getInt(props, "maxConcurrentOperations", 64))
            .batchSize(getInt(props, "batchSize", 128))
            .prefetchDistance(getInt(props, "prefetchDistance", 16))
            .cacheSizeMb(getInt(props, "cacheSizeMb", 1024))
            .enableSafetyChecksUltra(false)
            .enableBoundsCheckingUltra(false)
            .enableNullChecksUltra(false)
            .enableAggressiveInlining(true)
            .enableLoopUnrolling(true)
            .enableVectorization(true)
            .enableMemoryPoolingUltra(true)
            .poolSizeMbUltra(getInt(props, "poolSizeMbUltra", 512))
            .enableSimdUltra(true)
            .simdBatchSizeUltra(getInt(props, "simdBatchSizeUltra", 32))
            .enableBranchHints(true)
            .enableCachePrefetching(true)
            .build();
        
        return config;
    }
    
    private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    private static int getInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
    
    private static long getLong(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }
    
    private static double getDouble(Properties props, String key, double defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Double.parseDouble(value) : defaultValue;
    }
    
    public static PerformanceConfiguration getConfig() {
        return load();
    }
}