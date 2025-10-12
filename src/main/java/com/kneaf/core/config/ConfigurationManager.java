package com.kneaf.core.config;

import com.kneaf.core.config.chunkstorage.ChunkStorageConfig;
import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigSource;
import com.kneaf.core.config.core.ConfigurationUtils;
import com.kneaf.core.config.core.PropertiesFileConfigSource;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.performance.monitoring.PerformanceConfig;
import com.kneaf.core.config.resource.ResourceConfig;
import com.kneaf.core.config.swap.SwapConfig;
import com.kneaf.core.KneafCore;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized configuration manager that consolidates all configuration systems.
 * Provides unified access to performance, chunk storage, swap, and resource configurations.
 */
public class ConfigurationManager {
    private static volatile ConfigurationManager INSTANCE = null;
    private static final Object INSTANCE_LOCK = new Object();
    protected ConcurrentMap<Class<?>, Object> configurations = new ConcurrentHashMap<>();
    protected ConfigSource configSource;
    
    // Prevent static initialization issues by making all static fields non-initializing
    static {
      // Empty static block to prevent JVM from initializing class prematurely
    }

    /**
     * Private constructor to enforce singleton pattern.
     */
    private ConfigurationManager() {
        this.configSource = new PropertiesFileConfigSource("config/kneaf-core.properties");
        try {
            initializeConfigurations();
        } catch (ConfigurationException e) {
            KneafCore.LOGGER.warn("Failed to load initial configurations - using defaults", e);
            // Continue with empty configs instead of failing
        }
    }

    /**
     * Get the singleton instance of ConfigurationManager with lazy initialization.
     *
     * @return the singleton instance
     */
    public static ConfigurationManager getInstance() {
        if (INSTANCE == null) {
            synchronized (INSTANCE_LOCK) {
                if (INSTANCE == null) {
                    try {
                        INSTANCE = new ConfigurationManager();
                    } catch (Throwable t) {
                        System.err.println("Failed to create ConfigurationManager instance: " + t.getMessage());
                        // Create a new instance with empty state instead of failing
                        INSTANCE = new ConfigurationManager() {
                            {
                                try {
                                    this.configSource = new PropertiesFileConfigSource("config/kneaf-core.properties");
                                } catch (Exception e) {
                                    System.err.println("Failed to create config source: " + e.getMessage());
                                    // Use a simple empty properties object as fallback
                                    this.configSource = new ConfigSource() {
                                        @Override
                                        public Properties load() throws ConfigurationException {
                                            return new Properties();
                                        }
                                        
                                        @Override
                                        public String getName() {
                                            return "fallback";
                                        }
                                    };
                                }
                            }
                        };
                    }
                }
            }
        }
        return INSTANCE;
    }
    
    /** Get singleton instance with null safety */
    public static ConfigurationManager getSafeInstance() {
        try {
            return getInstance();
        } catch (Throwable t) {
            System.err.println("Failed to get ConfigurationManager instance: " + t.getMessage());
            // Return a new instance with empty configurations instead of failing
            return new ConfigurationManager() {
                {
                    try {
                        this.configSource = new PropertiesFileConfigSource("config/kneaf-core.properties");
                    } catch (Exception e) {
                        System.err.println("Failed to create config source: " + e.getMessage());
                        // Use a fallback config source if properties file loading fails
                        this.configSource = new ConfigSource() {
                            @Override
                            public Properties load() throws ConfigurationException {
                                return new Properties();
                            }
                            
                            @Override
                            public String getName() {
                                return "fallback";
                            }
                        };
                    }
                }
            };
        }
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
        } else if (configType == com.kneaf.core.config.performance.PerformanceConfiguration.class) {
            // Support consumers that expect the legacy PerformanceConfiguration loaded from properties
            return (T) com.kneaf.core.config.performance.PerformanceConfiguration.fromProperties(properties);
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
    private com.kneaf.core.performance.monitoring.PerformanceConfig createPerformanceConfig(Properties props) throws ConfigurationException {
        // Use the main PerformanceConfig implementation
        return com.kneaf.core.performance.monitoring.PerformanceConfig.load();
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
                .enabled(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enabled", true))
                .cacheCapacity(ConfigurationUtils.getIntProperty(props, "chunkstorage.cacheCapacity", 1000))
                .evictionPolicy(ConfigurationUtils.getProperty(props, "chunkstorage.evictionPolicy", "LRU"))
                .asyncThreadpoolSize(ConfigurationUtils.getIntProperty(props, "chunkstorage.asyncThreadpoolSize", 4))
                .enableAsyncOperations(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableAsyncOperations", true))
                .maintenanceIntervalMinutes(ConfigurationUtils.getLongProperty(props, "chunkstorage.maintenanceIntervalMinutes", 60))
                .enableBackups(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableBackups", true))
                .backupPath(ConfigurationUtils.getProperty(props, "chunkstorage.backupPath", "backups/chunkstorage"))
                .enableChecksums(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableChecksums", true))
                .enableCompression(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.enableCompression", false))
                .maxBackupFiles(ConfigurationUtils.getIntProperty(props, "chunkstorage.maxBackupFiles", 10))
                .backupRetentionDays(ConfigurationUtils.getLongProperty(props, "chunkstorage.backupRetentionDays", 7))
                .databaseType(ConfigurationUtils.getProperty(props, "chunkstorage.databaseType", "rust"))
                .useRustDatabase(ConfigurationUtils.getBooleanProperty(props, "chunkstorage.useRustDatabase", true))
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
                .enabled(ConfigurationUtils.getBooleanProperty(props, "swap.enabled", true))
                .memoryCheckIntervalMs(ConfigurationUtils.getLongProperty(props, "swap.memoryCheckIntervalMs", 5000))
                .maxConcurrentSwaps(ConfigurationUtils.getIntProperty(props, "swap.maxConcurrentSwaps", 10))
                .swapBatchSize(ConfigurationUtils.getIntProperty(props, "swap.swapBatchSize", 50))
                .swapTimeoutMs(ConfigurationUtils.getLongProperty(props, "swap.swapTimeoutMs", 30000))
                .enableAutomaticSwapping(ConfigurationUtils.getBooleanProperty(props, "swap.enableAutomaticSwapping", true))
                .criticalMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.criticalMemoryThreshold", ConfigurationConstants.DEFAULT_CRITICAL_MEMORY_THRESHOLD))
                .highMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.highMemoryThreshold", ConfigurationConstants.DEFAULT_HIGH_MEMORY_THRESHOLD))
                .elevatedMemoryThreshold(ConfigurationUtils.getDoubleProperty(props, "swap.elevatedMemoryThreshold", ConfigurationConstants.DEFAULT_ELEVATED_MEMORY_THRESHOLD))
                .minSwapChunkAgeMs(ConfigurationUtils.getIntProperty(props, "swap.minSwapChunkAgeMs", 60000))
                .enableSwapStatistics(ConfigurationUtils.getBooleanProperty(props, "swap.enableSwapStatistics", true))
                .enablePerformanceMonitoring(ConfigurationUtils.getBooleanProperty(props, "swap.enablePerformanceMonitoring", true))
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
                .maxMemoryUsage(ConfigurationUtils.getLongProperty(props, "resource.maxMemoryUsage", 1024L * 1024 * 1024)) // 1GB default
                .resourceCacheSize(ConfigurationUtils.getIntProperty(props, "resource.resourceCacheSize", 1000))
                .loadPriority(ResourceConfig.LoadPriority.valueOf(
                        ConfigurationUtils.getProperty(props, "resource.loadPriority", "MEDIUM").toUpperCase()))
                .build();
    }
}

