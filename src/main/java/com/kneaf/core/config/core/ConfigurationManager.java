package com.kneaf.core.config.core;

import com.kneaf.core.config.performance.PerformanceConfiguration;
import com.kneaf.core.config.storage.ChunkStorageConfiguration;
import com.kneaf.core.config.storage.SwapConfiguration;
import com.kneaf.core.config.resource.ResourceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Centralized configuration manager that consolidates all configuration systems.
 * Provides unified access to performance, chunk storage, and swap configurations.
 */
public class ConfigurationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationManager.class);
    private static final String DEFAULT_CONFIG_PATH = ConfigurationConstants.DEFAULT_CONFIG_PATH;
    
    private static volatile ConfigurationManager instance;
    private final ConcurrentMap<Class<?>, Object> configurations = new ConcurrentHashMap<>();
    private final Properties properties;
    
    private ConfigurationManager() {
        this.properties = ConfigurationUtils.loadProperties(DEFAULT_CONFIG_PATH);
        initializeDefaults();
    }
    
    /**
     * Get the singleton instance of ConfigurationManager.
     */
    public static ConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (ConfigurationManager.class) {
                if (instance == null) {
                    instance = new ConfigurationManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get configuration for the specified type.
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfiguration(Class<T> configType) {
        return (T) configurations.computeIfAbsent(configType, this::createConfiguration);
    }
    
    /**
     * Register a custom configuration type.
     */
    public <T> void registerConfiguration(Class<T> configType, Function<Properties, T> factory) {
        configurations.put(configType, factory.apply(properties));
        LOGGER.info("Registered custom configuration type: {}", configType.getSimpleName());
    }
    
    /**
     * Reload all configurations from properties file.
     */
    public void reload() {
        Properties newProperties = ConfigurationUtils.loadProperties(DEFAULT_CONFIG_PATH);
        configurations.clear();
        properties.clear();
        properties.putAll(newProperties);
        initializeDefaults();
        LOGGER.info("Configuration reloaded from: {}", DEFAULT_CONFIG_PATH);
    }
    
    /**
     * Get raw property value.
     */
    public String getProperty(String key, String defaultValue) {
        return ConfigurationUtils.getProperty(properties, key, defaultValue);
    }
    
    /**
     * Get raw property value as boolean.
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        return ConfigurationUtils.getBooleanProperty(properties, key, defaultValue);
    }
    
    /**
     * Get raw property value as integer.
     */
    public int getIntProperty(String key, int defaultValue) {
        return ConfigurationUtils.getIntProperty(properties, key, defaultValue);
    }
    
    /**
     * Get raw property value as long.
     */
    public long getLongProperty(String key, long defaultValue) {
        return ConfigurationUtils.getLongProperty(properties, key, defaultValue);
    }
    
    /**
     * Get raw property value as double.
     */
    public double getDoubleProperty(String key, double defaultValue) {
        return ConfigurationUtils.getDoubleProperty(properties, key, defaultValue);
    }
    
    /**
     * Get raw property value as string array.
     */
    public String[] getStringArrayProperty(String key, String[] defaultValue) {
        return ConfigurationUtils.getStringArrayProperty(properties, key, defaultValue);
    }
    
    private void initializeDefaults() {
        // Register default configuration types
        registerConfiguration(PerformanceConfiguration.class, this::createPerformanceConfig);
        registerConfiguration(ChunkStorageConfiguration.class, this::createChunkStorageConfig);
        registerConfiguration(SwapConfiguration.class, this::createSwapConfig);
        registerConfiguration(ResourceConfiguration.class, this::createResourceConfig);
    }
    
    private PerformanceConfiguration createPerformanceConfig(Properties props) {
        return PerformanceConfiguration.builder()
            .enabled(ConfigurationUtils.getBooleanProperty(props, "performance.enabled", ConfigurationConstants.DEFAULT_ENABLED))
            .threadPoolSize(ConfigurationUtils.getIntProperty(props, "performance.threadPoolSize", ConfigurationConstants.DEFAULT_THREAD_POOL_SIZE))
            .logIntervalTicks(ConfigurationUtils.getIntProperty(props, "performance.logIntervalTicks", ConfigurationConstants.DEFAULT_LOG_INTERVAL_TICKS))
            .scanIntervalTicks(ConfigurationUtils.getIntProperty(props, "performance.scanIntervalTicks", ConfigurationConstants.DEFAULT_SCAN_INTERVAL_TICKS))
            .tpsThresholdForAsync(ConfigurationUtils.getDoubleProperty(props, "performance.tpsThresholdForAsync", ConfigurationConstants.DEFAULT_TPS_THRESHOLD))
            .maxEntitiesToCollect(ConfigurationUtils.getIntProperty(props, "performance.maxEntitiesToCollect", ConfigurationConstants.DEFAULT_MAX_ENTITIES_TO_COLLECT))
            .entityDistanceCutoff(ConfigurationUtils.getDoubleProperty(props, "performance.entityDistanceCutoff", ConfigurationConstants.DEFAULT_DISTANCE_CUTOFF))
            .maxLogBytes(ConfigurationUtils.getLongProperty(props, "performance.maxLogBytes", ConfigurationConstants.DEFAULT_MAX_LOG_BYTES))
            .adaptiveThreadPool(ConfigurationUtils.getBooleanProperty(props, "performance.adaptiveThreadPool", ConfigurationConstants.DEFAULT_ADAPTIVE_THREAD_POOL))
            .maxThreadPoolSize(ConfigurationUtils.getIntProperty(props, "performance.maxThreadPoolSize", ConfigurationConstants.DEFAULT_MAX_THREAD_POOL_SIZE))
            .excludedEntityTypes(ConfigurationUtils.getStringArrayProperty(props, "performance.excludedEntityTypes", new String[0]))
            .networkExecutorPoolSize(ConfigurationUtils.getIntProperty(props, "performance.networkExecutorPoolSize", ConfigurationConstants.DEFAULT_NETWORK_EXECUTOR_POOL_SIZE))
            .profilingEnabled(ConfigurationUtils.getBooleanProperty(props, "performance.profilingEnabled", ConfigurationConstants.DEFAULT_PROFILING_ENABLED))
            .slowTickThresholdMs(ConfigurationUtils.getLongProperty(props, "performance.slowTickThresholdMs", ConfigurationConstants.DEFAULT_SLOW_TICK_THRESHOLD_MS))
            .profilingSampleRate(ConfigurationUtils.getIntProperty(props, "performance.profilingSampleRate", ConfigurationConstants.DEFAULT_PROFILING_SAMPLE_RATE))
            .minThreadPoolSize(ConfigurationUtils.getIntProperty(props, "performance.minThreadPoolSize", ConfigurationConstants.DEFAULT_MIN_THREAD_POOL_SIZE))
            .dynamicThreadScaling(ConfigurationUtils.getBooleanProperty(props, "performance.dynamicThreadScaling", ConfigurationConstants.DEFAULT_DYNAMIC_THREAD_SCALING))
            .threadScaleUpThreshold(ConfigurationUtils.getDoubleProperty(props, "performance.threadScaleUpThreshold", ConfigurationConstants.DEFAULT_THREAD_SCALE_UP_THRESHOLD))
            .threadScaleDownThreshold(ConfigurationUtils.getDoubleProperty(props, "performance.threadScaleDownThreshold", ConfigurationConstants.DEFAULT_THREAD_SCALE_DOWN_THRESHOLD))
            .threadScaleUpDelayTicks(ConfigurationUtils.getIntProperty(props, "performance.threadScaleUpDelayTicks", ConfigurationConstants.DEFAULT_THREAD_SCALE_UP_DELAY_TICKS))
            .threadScaleDownDelayTicks(ConfigurationUtils.getIntProperty(props, "performance.threadScaleDownDelayTicks", ConfigurationConstants.DEFAULT_THREAD_SCALE_DOWN_DELAY_TICKS))
            .workStealingEnabled(ConfigurationUtils.getBooleanProperty(props, "performance.workStealingEnabled", ConfigurationConstants.DEFAULT_WORK_STEALING_ENABLED))
            .workStealingQueueSize(ConfigurationUtils.getIntProperty(props, "performance.workStealingQueueSize", ConfigurationConstants.DEFAULT_WORK_STEALING_QUEUE_SIZE))
            .cpuAwareThreadSizing(ConfigurationUtils.getBooleanProperty(props, "performance.cpuAwareThreadSizing", ConfigurationConstants.DEFAULT_CPU_AWARE_THREAD_SIZING))
            .cpuLoadThreshold(ConfigurationUtils.getDoubleProperty(props, "performance.cpuLoadThreshold", ConfigurationConstants.DEFAULT_CPU_LOAD_THRESHOLD))
            .threadPoolKeepAliveSeconds(ConfigurationUtils.getIntProperty(props, "performance.threadPoolKeepAliveSeconds", ConfigurationConstants.DEFAULT_THREAD_POOL_KEEP_ALIVE_SECONDS))
            .distanceCalculationInterval(ConfigurationUtils.getIntProperty(props, "performance.distanceCalculationInterval", ConfigurationConstants.DEFAULT_DISTANCE_CALCULATION_INTERVAL))
            .distanceApproximationEnabled(ConfigurationUtils.getBooleanProperty(props, "performance.distanceApproximationEnabled", ConfigurationConstants.DEFAULT_DISTANCE_APPROXIMATION_ENABLED))
            .distanceCacheSize(ConfigurationUtils.getIntProperty(props, "performance.distanceCacheSize", ConfigurationConstants.DEFAULT_DISTANCE_CACHE_SIZE))
            .itemProcessingIntervalMultiplier(ConfigurationUtils.getIntProperty(props, "performance.itemProcessingIntervalMultiplier", ConfigurationConstants.DEFAULT_ITEM_PROCESSING_INTERVAL_MULTIPLIER))
            .spatialGridUpdateInterval(ConfigurationUtils.getIntProperty(props, "performance.spatialGridUpdateInterval", ConfigurationConstants.DEFAULT_SPATIAL_GRID_UPDATE_INTERVAL))
            .incrementalSpatialUpdates(ConfigurationUtils.getBooleanProperty(props, "performance.incrementalSpatialUpdates", ConfigurationConstants.DEFAULT_INCREMENTAL_SPATIAL_UPDATES))
            .build();
    }
    
    private ChunkStorageConfiguration createChunkStorageConfig(Properties props) {
        return ChunkStorageConfiguration.builder()
            .enabled(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enabled", ConfigurationConstants.DEFAULT_ENABLED))
            .cacheCapacity(ConfigurationUtils.getIntProperty(props, "chunkstorage.cacheCapacity", ConfigurationConstants.DEFAULT_CACHE_CAPACITY))
            .evictionPolicy(ConfigurationUtils.getProperty(props, "chunkstorage.evictionPolicy", ConfigurationConstants.DEFAULT_EVICTION_POLICY))
            .asyncThreadPoolSize(ConfigurationUtils.getIntProperty(props, "chunkstorage.asyncThreadPoolSize", ConfigurationConstants.DEFAULT_ASYNC_THREAD_POOL_SIZE))
            .enableAsyncOperations(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableAsyncOperations", ConfigurationConstants.DEFAULT_ENABLE_ASYNC_OPERATIONS))
            .maintenanceIntervalMinutes(ConfigurationUtils.getLongProperty(props, "chunkstorage.maintenanceIntervalMinutes", ConfigurationConstants.DEFAULT_MAINTENANCE_INTERVAL_MINUTES))
            .enableBackups(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableBackups", ConfigurationConstants.DEFAULT_ENABLE_BACKUPS))
            .backupPath(ConfigurationUtils.getProperty(props, "chunkstorage.backupPath", ConfigurationConstants.DEFAULT_BACKUP_PATH))
            .enableChecksums(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableChecksums", ConfigurationConstants.DEFAULT_ENABLE_CHECKSUMS))
            .enableCompression(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableCompression", ConfigurationConstants.DEFAULT_ENABLE_COMPRESSION))
            .maxBackupFiles(ConfigurationUtils.getIntProperty(props, "chunkstorage.maxBackupFiles", ConfigurationConstants.DEFAULT_MAX_BACKUP_FILES))
            .backupRetentionDays(ConfigurationUtils.getLongProperty(props, "chunkstorage.backupRetentionDays", ConfigurationConstants.DEFAULT_BACKUP_RETENTION_DAYS))
            .databaseType(ConfigurationUtils.getProperty(props, "chunkstorage.databaseType", ConfigurationConstants.DEFAULT_DATABASE_TYPE))
            .useRustDatabase(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.useRustDatabase", ConfigurationConstants.DEFAULT_USE_RUST_DATABASE))
            .build();
    }
    
    private SwapConfiguration createSwapConfig(Properties props) {
        return SwapConfiguration.builder()
            .enabled(ConfigurationUtils.getBooleanProperty(props, "swap.enabled", ConfigurationConstants.DEFAULT_ENABLED))
            .memoryCheckIntervalMs(ConfigurationUtils.getLongProperty(props, "swap.memoryCheckIntervalMs", ConfigurationConstants.DEFAULT_SWAP_MEMORY_CHECK_INTERVAL_MS))
            .maxConcurrentSwaps(ConfigurationUtils.getIntProperty(props, "swap.maxConcurrentSwaps", ConfigurationConstants.DEFAULT_MAX_CONCURRENT_SWAPS))
            .swapBatchSize(ConfigurationUtils.getIntProperty(props, "swap.swapBatchSize", ConfigurationConstants.DEFAULT_SWAP_BATCH_SIZE))
            .swapTimeoutMs(ConfigurationUtils.getLongProperty(props, "swap.swapTimeoutMs", ConfigurationConstants.DEFAULT_SWAP_TIMEOUT_MS))
            .enableAutomaticSwapping(ConfigurationUtils.getBooleanProperty(props, "swap.enableAutomaticSwapping", ConfigurationConstants.DEFAULT_ENABLE_AUTOMATIC_SWAPPING))
            .criticalMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.criticalMemoryThreshold", ConfigurationConstants.DEFAULT_CRITICAL_MEMORY_THRESHOLD))
            .highMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.highMemoryThreshold", ConfigurationConstants.DEFAULT_HIGH_MEMORY_THRESHOLD))
            .elevatedMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.elevatedMemoryThreshold", ConfigurationConstants.DEFAULT_ELEVATED_MEMORY_THRESHOLD))
            .minSwapChunkAgeMs(ConfigurationUtils.getIntProperty(props, "swap.minSwapChunkAgeMs", ConfigurationConstants.DEFAULT_MIN_SWAP_CHUNK_AGE_MS))
            .enableSwapStatistics(ConfigurationUtils.getBooleanProperty(props, "swap.enableSwapStatistics", ConfigurationConstants.DEFAULT_ENABLE_SWAP_STATISTICS))
            .enablePerformanceMonitoring(ConfigurationUtils.getBooleanProperty(props, "swap.enablePerformanceMonitoring", ConfigurationConstants.DEFAULT_ENABLE_PERFORMANCE_MONITORING))
            .build();
    }
    
    private ResourceConfiguration createResourceConfig(Properties props) {
        return ResourceConfiguration.builder()
            .resourceCleanupIntervalSeconds(ConfigurationUtils.getIntProperty(props, "resource.cleanupIntervalSeconds", ConfigurationConstants.DEFAULT_RESOURCE_CLEANUP_INTERVAL_SECONDS))
            .resourceHealthCheckIntervalSeconds(ConfigurationUtils.getIntProperty(props, "resource.healthCheckIntervalSeconds", ConfigurationConstants.DEFAULT_RESOURCE_HEALTH_CHECK_INTERVAL_SECONDS))
            .maxResourceAgeMinutes(ConfigurationUtils.getIntProperty(props, "resource.maxResourceAgeMinutes", ConfigurationConstants.DEFAULT_MAX_RESOURCE_AGE_MINUTES))
            .resourcePoolEnabled(ConfigurationUtils.getBooleanProperty(props, "resource.poolEnabled", ConfigurationConstants.DEFAULT_RESOURCE_POOL_ENABLED))
            .resourcePoolMaxSize(ConfigurationUtils.getIntProperty(props, "resource.poolMaxSize", ConfigurationConstants.DEFAULT_RESOURCE_POOL_MAX_SIZE))
            .resourcePoolInitialSize(ConfigurationUtils.getIntProperty(props, "resource.poolInitialSize", ConfigurationConstants.DEFAULT_RESOURCE_POOL_INITIAL_SIZE))
            .build();
    }
    
    private <T> Object createConfiguration(Class<T> configType) {
        throw new IllegalArgumentException("Unknown configuration type: " + configType.getSimpleName());
    }
}