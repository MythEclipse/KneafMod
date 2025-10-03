package com.kneaf.core.config;

import com.kneaf.core.exceptions.KneafCoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final String DEFAULT_CONFIG_PATH = "config/kneaf-core.properties";
    
    private static volatile ConfigurationManager instance;
    private final ConcurrentMap<Class<?>, Object> configurations = new ConcurrentHashMap<>();
    private final Properties properties;
    
    private ConfigurationManager() {
        this.properties = loadProperties();
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
        Properties newProperties = loadProperties();
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
        return properties.getProperty(key, defaultValue);
    }
    
    /**
     * Get raw property value as boolean.
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    /**
     * Get raw property value as integer.
     */
    public int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid integer value for key '{}': {}", key, value);
            return defaultValue;
        }
    }
    
    /**
     * Get raw property value as long.
     */
    public long getLongProperty(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid long value for key '{}': {}", key, value);
            return defaultValue;
        }
    }
    
    /**
     * Get raw property value as double.
     */
    public double getDoubleProperty(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid double value for key '{}': {}", key, value);
            return defaultValue;
        }
    }
    
    /**
     * Get raw property value as string array.
     */
    public String[] getStringArrayProperty(String key, String[] defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue != null ? defaultValue.clone() : new String[0];
        }
        return value.split("\\s*,\\s*");
    }
    
    private Properties loadProperties() {
        Properties props = new Properties();
        Path path = Paths.get(DEFAULT_CONFIG_PATH);
        
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
                LOGGER.info("Loaded configuration from: {}", DEFAULT_CONFIG_PATH);
            } catch (IOException e) {
                LOGGER.warn("Failed to read configuration from {}, using defaults: {}", 
                           DEFAULT_CONFIG_PATH, e.getMessage());
            }
        } else {
            LOGGER.info("Configuration file not found at {}, using defaults", DEFAULT_CONFIG_PATH);
        }
        
        return props;
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
            .enabled(getBooleanProperty("performance.enabled", true))
            .threadPoolSize(getIntProperty("performance.threadPoolSize", 4))
            .logIntervalTicks(getIntProperty("performance.logIntervalTicks", 100))
            .scanIntervalTicks(getIntProperty("performance.scanIntervalTicks", 1))
            .tpsThresholdForAsync(getDoubleProperty("performance.tpsThresholdForAsync", 19.0))
            .maxEntitiesToCollect(getIntProperty("performance.maxEntitiesToCollect", 20000))
            .entityDistanceCutoff(getDoubleProperty("performance.entityDistanceCutoff", 256.0))
            .maxLogBytes(getLongProperty("performance.maxLogBytes", 10L * 1024 * 1024))
            .adaptiveThreadPool(getBooleanProperty("performance.adaptiveThreadPool", false))
            .maxThreadPoolSize(getIntProperty("performance.maxThreadPoolSize", 
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1)))
            .excludedEntityTypes(getStringArrayProperty("performance.excludedEntityTypes", new String[0]))
            .networkExecutorPoolSize(getIntProperty("performance.networkExecutorPoolSize", 
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
            .profilingEnabled(getBooleanProperty("performance.profilingEnabled", true))
            .slowTickThresholdMs(getLongProperty("performance.slowTickThresholdMs", 50L))
            .profilingSampleRate(getIntProperty("performance.profilingSampleRate", 100))
            .minThreadPoolSize(getIntProperty("performance.minThreadPoolSize", 2))
            .dynamicThreadScaling(getBooleanProperty("performance.dynamicThreadScaling", true))
            .threadScaleUpThreshold(getDoubleProperty("performance.threadScaleUpThreshold", 0.8))
            .threadScaleDownThreshold(getDoubleProperty("performance.threadScaleDownThreshold", 0.3))
            .threadScaleUpDelayTicks(getIntProperty("performance.threadScaleUpDelayTicks", 100))
            .threadScaleDownDelayTicks(getIntProperty("performance.threadScaleDownDelayTicks", 200))
            .workStealingEnabled(getBooleanProperty("performance.workStealingEnabled", true))
            .workStealingQueueSize(getIntProperty("performance.workStealingQueueSize", 100))
            .cpuAwareThreadSizing(getBooleanProperty("performance.cpuAwareThreadSizing", false))
            .cpuLoadThreshold(getDoubleProperty("performance.cpuLoadThreshold", 0.7))
            .threadPoolKeepAliveSeconds(getIntProperty("performance.threadPoolKeepAliveSeconds", 60))
            .distanceCalculationInterval(getIntProperty("performance.distanceCalculationInterval", 1))
            .distanceApproximationEnabled(getBooleanProperty("performance.distanceApproximationEnabled", true))
            .distanceCacheSize(getIntProperty("performance.distanceCacheSize", 100))
            .itemProcessingIntervalMultiplier(getIntProperty("performance.itemProcessingIntervalMultiplier", 1))
            .spatialGridUpdateInterval(getIntProperty("performance.spatialGridUpdateInterval", 1))
            .incrementalSpatialUpdates(getBooleanProperty("performance.incrementalSpatialUpdates", true))
            .build();
    }
    
    private ChunkStorageConfiguration createChunkStorageConfig(Properties props) {
        return ChunkStorageConfiguration.builder()
            .enabled(getBooleanProperty("chunkstorage.enabled", true))
            .cacheCapacity(getIntProperty("chunkstorage.cacheCapacity", 1000))
            .evictionPolicy(getProperty("chunkstorage.evictionPolicy", "LRU"))
            .asyncThreadPoolSize(getIntProperty("chunkstorage.asyncThreadPoolSize", 4))
            .enableAsyncOperations(getBooleanProperty("chunkstorage.enableAsyncOperations", true))
            .maintenanceIntervalMinutes(getLongProperty("chunkstorage.maintenanceIntervalMinutes", 60))
            .enableBackups(getBooleanProperty("chunkstorage.enableBackups", true))
            .backupPath(getProperty("chunkstorage.backupPath", "backups/chunkstorage"))
            .enableChecksums(getBooleanProperty("chunkstorage.enableChecksums", true))
            .enableCompression(getBooleanProperty("chunkstorage.enableCompression", false))
            .maxBackupFiles(getIntProperty("chunkstorage.maxBackupFiles", 10))
            .backupRetentionDays(getLongProperty("chunkstorage.backupRetentionDays", 7))
            .databaseType(getProperty("chunkstorage.databaseType", "rust"))
            .useRustDatabase(getBooleanProperty("chunkstorage.useRustDatabase", true))
            .build();
    }
    
    private SwapConfiguration createSwapConfig(Properties props) {
        return SwapConfiguration.builder()
            .enabled(getBooleanProperty("swap.enabled", true))
            .memoryCheckIntervalMs(getLongProperty("swap.memoryCheckIntervalMs", 5000))
            .maxConcurrentSwaps(getIntProperty("swap.maxConcurrentSwaps", 10))
            .swapBatchSize(getIntProperty("swap.swapBatchSize", 50))
            .swapTimeoutMs(getLongProperty("swap.swapTimeoutMs", 30000))
            .enableAutomaticSwapping(getBooleanProperty("swap.enableAutomaticSwapping", true))
            .criticalMemoryThreshold(getDoubleProperty("swap.criticalMemoryThreshold", 0.95))
            .highMemoryThreshold(getDoubleProperty("swap.highMemoryThreshold", 0.85))
            .elevatedMemoryThreshold(getDoubleProperty("swap.elevatedMemoryThreshold", 0.75))
            .minSwapChunkAgeMs(getIntProperty("swap.minSwapChunkAgeMs", 60000))
            .enableSwapStatistics(getBooleanProperty("swap.enableSwapStatistics", true))
            .enablePerformanceMonitoring(getBooleanProperty("swap.enablePerformanceMonitoring", true))
            .build();
    }
    
    private ResourceConfiguration createResourceConfig(Properties props) {
        return ResourceConfiguration.builder()
            .resourceCleanupIntervalSeconds(getIntProperty("resource.cleanupIntervalSeconds", 300))
            .resourceHealthCheckIntervalSeconds(getIntProperty("resource.healthCheckIntervalSeconds", 60))
            .maxResourceAgeMinutes(getIntProperty("resource.maxResourceAgeMinutes", 30))
            .resourcePoolEnabled(getBooleanProperty("resource.poolEnabled", true))
            .resourcePoolMaxSize(getIntProperty("resource.poolMaxSize", 100))
            .resourcePoolInitialSize(getIntProperty("resource.poolInitialSize", 10))
            .build();
    }
    
    private <T> Object createConfiguration(Class<T> configType) {
        throw new IllegalArgumentException("Unknown configuration type: " + configType.getSimpleName());
    }
}