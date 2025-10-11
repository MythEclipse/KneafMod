package com.kneaf.core.config;

import com.kneaf.core.config.chunkstorage.ChunkStorageConfig;
import com.kneaf.core.config.core.ConfigSource;
import com.kneaf.core.config.core.PropertiesFileConfigSource;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.config.performance.PerformanceConfig;
import com.kneaf.core.config.resource.ResourceConfig;
import com.kneaf.core.config.swap.SwapConfig;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized configuration manager that consolidates all configuration systems.
 * Provides unified access to performance, chunk storage, swap, and resource configurations.
 */
public final class ConfigurationManager {
    private static final ConfigurationManager INSTANCE = new ConfigurationManager();
    private final ConcurrentMap<Class<?>, Object> configurations = new ConcurrentHashMap<>();
    private final ConfigSource configSource;

    /**
     * Private constructor to enforce singleton pattern.
     */
    private ConfigurationManager() {
        this.configSource = new PropertiesFileConfigSource("config/kneaf-core.properties");
        try {
            initializeConfigurations();
        } catch (ConfigurationException e) {
            throw new RuntimeException("Failed to initialize configurations", e);
        }
    }

    /**
     * Get the singleton instance of ConfigurationManager.
     *
     * @return the singleton instance
     */
    public static ConfigurationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get configuration for the specified type.
     *
     * @param configType the class of the configuration to get
     * @param <T>        the type of the configuration
     * @return the configuration instance
     * @throws ConfigurationException if configuration loading fails
     */
    public <T> T getConfiguration(Class<T> configType) throws ConfigurationException {
        return (T) configurations.computeIfAbsent(configType, key -> {
            try {
                return createConfiguration(key);
            } catch (ConfigurationException e) {
                throw new RuntimeException("Failed to create configuration for " + key.getName(), e);
            }
        });
    }
    
    /**
     * Create a configuration instance for the given type.
     *
     * @param configType the class of the configuration to create
     * @return the created configuration instance
     * @throws ConfigurationException if configuration creation fails
     */
    private <T> T createConfiguration(Class<T> configType) throws ConfigurationException {
        Properties properties = configSource.load();
        
        if (configType == PerformanceConfig.class) {
            return (T) createPerformanceConfig(properties);
        } else if (configType == ChunkStorageConfig.class) {
            return (T) createChunkStorageConfig(properties);
        } else if (configType == SwapConfig.class) {
            return (T) createSwapConfig(properties);
        } else if (configType == ResourceConfig.class) {
            return (T) createResourceConfig(properties);
        } else {
            throw new IllegalArgumentException("Unknown configuration type: " + configType.getName());
        }
    }

    /**
     * Reload all configurations from the source.
     *
     * @throws ConfigurationException if reloading fails
     */
    public void reload() throws ConfigurationException {
        configurations.clear();
        initializeConfigurations();
    }

    /**
     * Initialize all configurations from the config source.
     *
     * @throws ConfigurationException if initialization fails
     */
    private void initializeConfigurations() throws ConfigurationException {
        Properties properties = configSource.load();
        
        // Initialize all configuration types
        configurations.put(PerformanceConfig.class, createPerformanceConfig(properties));
        configurations.put(ChunkStorageConfig.class, createChunkStorageConfig(properties));
        configurations.put(SwapConfig.class, createSwapConfig(properties));
        configurations.put(ResourceConfig.class, createResourceConfig(properties));
    }

    /**
     * Create a PerformanceConfig from properties.
     *
     * @param props the properties to use
     * @return the created PerformanceConfig
     * @throws ConfigurationException if creation fails
     */
    private PerformanceConfig createPerformanceConfig(Properties props) throws ConfigurationException {
        return PerformanceConfig.builder()
                .enabled(getBooleanProperty(props, "performance.enabled", true))
                .threadpoolSize(getIntProperty(props, "performance.threadpoolSize", 4))
                .logIntervalTicks(getIntProperty(props, "performance.logIntervalTicks", 100))
                .scanIntervalTicks(getIntProperty(props, "performance.scanIntervalTicks", 1))
                .tpsThresholdForAsync(getDoubleProperty(props, "performance.tpsThresholdForAsync", 19.0))
                .maxEntitiesToCollect(getIntProperty(props, "performance.maxEntitiesToCollect", 20000))
                .entityDistanceCutoff(getDoubleProperty(props, "performance.entityDistanceCutoff", 256.0))
                .maxLogBytes(getLongProperty(props, "performance.maxLogBytes", 10L * 1024 * 1024))
                .adaptiveThreadPool(getBooleanProperty(props, "performance.adaptiveThreadPool", false))
                .maxThreadpoolSize(getIntProperty(props, "performance.maxThreadpoolSize", 
                        Math.max(1, Runtime.getRuntime().availableProcessors() - 1)))
                .excludedEntityTypes(getStringArrayProperty(props, "performance.excludedEntityTypes", new String[0]))
                .networkExecutorpoolSize(getIntProperty(props, "performance.networkExecutorpoolSize",
                        Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
                .profilingEnabled(getBooleanProperty(props, "performance.profilingEnabled", true))
                .slowTickThresholdMs(getLongProperty(props, "performance.slowTickThresholdMs", 50L))
                .profilingSampleRate(getIntProperty(props, "performance.profilingSampleRate", 100))
                .minThreadpoolSize(getIntProperty(props, "performance.minThreadpoolSize", 2))
                .dynamicThreadScaling(getBooleanProperty(props, "performance.dynamicThreadScaling", true))
                .threadScaleUpThreshold(getDoubleProperty(props, "performance.threadScaleUpThreshold", 0.8))
                .threadScaleDownThreshold(getDoubleProperty(props, "performance.threadScaleDownThreshold", 0.3))
                .threadScaleUpDelayTicks(getIntProperty(props, "performance.threadScaleUpDelayTicks", 100))
                .threadScaleDownDelayTicks(getIntProperty(props, "performance.threadScaleDownDelayTicks", 200))
                .workStealingEnabled(getBooleanProperty(props, "performance.workStealingEnabled", true))
                .workStealingQueueSize(getIntProperty(props, "performance.workStealingQueueSize", 100))
                .cpuAwareThreadSizing(getBooleanProperty(props, "performance.cpuAwareThreadSizing", false))
                .cpuLoadThreshold(getDoubleProperty(props, "performance.cpuLoadThreshold", 0.7))
                .threadPoolKeepAliveSeconds(getIntProperty(props, "performance.threadPoolKeepAliveSeconds", 60))
                .distanceCalculationInterval(getIntProperty(props, "performance.distanceCalculationInterval", 1))
                .distanceApproximationEnabled(getBooleanProperty(props, "performance.distanceApproximationEnabled", true))
                .distanceCacheSize(getIntProperty(props, "performance.distanceCacheSize", 100))
                .itemProcessingIntervalMultiplier(getIntProperty(props, "performance.itemProcessingIntervalMultiplier", 1))
                .spatialGridUpdateInterval(getIntProperty(props, "performance.spatialGridUpdateInterval", 1))
                .incrementalSpatialUpdates(getBooleanProperty(props, "performance.incrementalSpatialUpdates", true))
                .build();
    }

    /**
     * Create a ChunkStorageConfig from properties.
     *
     * @param props the properties to use
     * @return the created ChunkStorageConfig
     * @throws ConfigurationException if creation fails
     */
    private ChunkStorageConfig createChunkStorageConfig(Properties props) throws ConfigurationException {
        return ChunkStorageConfig.builder()
                .enabled(getBooleanProperty(props, "chunkstorage.enabled", true))
                .cacheCapacity(getIntProperty(props, "chunkstorage.cacheCapacity", 1000))
                .evictionPolicy(getProperty(props, "chunkstorage.evictionPolicy", "LRU"))
                .asyncThreadpoolSize(getIntProperty(props, "chunkstorage.asyncThreadpoolSize", 4))
                .enableAsyncOperations(getBooleanProperty(props, "chunkstorage.enableAsyncOperations", true))
                .maintenanceIntervalMinutes(getLongProperty(props, "chunkstorage.maintenanceIntervalMinutes", 60))
                .enableBackups(getBooleanProperty(props, "chunkstorage.enableBackups", true))
                .backupPath(getProperty(props, "chunkstorage.backupPath", "backups/chunkstorage"))
                .enableChecksums(getBooleanProperty(props, "chunkstorage.enableChecksums", true))
                .enableCompression(getBooleanProperty(props, "chunkstorage.enableCompression", false))
                .maxBackupFiles(getIntProperty(props, "chunkstorage.maxBackupFiles", 10))
                .backupRetentionDays(getLongProperty(props, "chunkstorage.backupRetentionDays", 7))
                .databaseType(getProperty(props, "chunkstorage.databaseType", "rust"))
                .useRustDatabase(getBooleanProperty(props, "chunkstorage.useRustDatabase", true))
                .build();
    }

    /**
     * Create a SwapConfig from properties.
     *
     * @param props the properties to use
     * @return the created SwapConfig
     * @throws ConfigurationException if creation fails
     */
    private SwapConfig createSwapConfig(Properties props) throws ConfigurationException {
        return SwapConfig.builder()
                .enabled(getBooleanProperty(props, "swap.enabled", true))
                .memoryCheckIntervalMs(getLongProperty(props, "swap.memoryCheckIntervalMs", 5000))
                .maxConcurrentSwaps(getIntProperty(props, "swap.maxConcurrentSwaps", 10))
                .swapBatchSize(getIntProperty(props, "swap.swapBatchSize", 50))
                .swapTimeoutMs(getLongProperty(props, "swap.swapTimeoutMs", 30000))
                .enableAutomaticSwapping(getBooleanProperty(props, "swap.enableAutomaticSwapping", true))
                .criticalMemoryThreshold(getDoubleProperty(props, "swap.criticalMemoryThreshold", 0.95))
                .highMemoryThreshold(getDoubleProperty(props, "swap.highMemoryThreshold", 0.85))
                .elevatedMemoryThreshold(getDoubleProperty(props, "swap.elevatedMemoryThreshold", 0.75))
                .minSwapChunkAgeMs(getIntProperty(props, "swap.minSwapChunkAgeMs", 60000))
                .enableSwapStatistics(getBooleanProperty(props, "swap.enableSwapStatistics", true))
                .enablePerformanceMonitoring(getBooleanProperty(props, "swap.enablePerformanceMonitoring", true))
                .build();
    }

    /**
     * Create a ResourceConfig from properties.
     *
     * @param props the properties to use
     * @return the created ResourceConfig
     * @throws ConfigurationException if creation fails
     */
    private ResourceConfig createResourceConfig(Properties props) throws ConfigurationException {
        return ResourceConfig.builder()
                .maxMemoryUsage(getLongProperty(props, "resource.maxMemoryUsage", 1024L * 1024 * 1024)) // 1GB default
                .resourceCacheSize(getIntProperty(props, "resource.resourceCacheSize", 1000))
                .loadPriority(ResourceConfig.LoadPriority.valueOf(
                        getProperty(props, "resource.loadPriority", "MEDIUM").toUpperCase()))
                .build();
    }

    /**
     * Get a boolean property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    /**
     * Get an integer property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get a long property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get a double property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private double getDoubleProperty(Properties props, String key, double defaultValue) {
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get a string property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private String getProperty(Properties props, String key, String defaultValue) {
        String value = props.getProperty(key);
        return value != null ? value.trim() : defaultValue;
    }

    /**
     * Get a string array property from properties with a default value.
     *
     * @param props      the properties to get from
     * @param key        the key to get
     * @param defaultValue the default value if the key is not present
     * @return the property value
     */
    private String[] getStringArrayProperty(Properties props, String key, String[] defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue != null ? defaultValue.clone() : new String[0];
        }
        return value.split("\\s*,\\s*");
    }
}
