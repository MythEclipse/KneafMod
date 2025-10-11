package com.kneaf.core.unifiedbridge;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.performance.monitoring.PerformanceConfig;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.core.MobProcessResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Adapter for migrating from legacy performance systems to the unified bridge system.
 * Provides backward compatibility while guiding migration to new unified interfaces.
 */
public final class BackwardCompatibilityAdapter {
    private static final UnifiedBridge UNIFIED_BRIDGE = BridgeFactory.getInstance().createBridge(BridgeFactory.BridgeType.ASYNCHRONOUS, BridgeConfiguration.getDefault());
    private static final ConfigurationManager CONFIG_MANAGER = ConfigurationManager.getInstance();
    private static PerformanceConfig CONFIG = loadConfiguration();

    private BackwardCompatibilityAdapter() {
        // Private constructor to prevent instantiation
    }

    /**
     * Initialize the backward compatibility adapter.
     */
    public static void initialize() {
        CONFIG = reloadConfiguration();
        UNIFIED_BRIDGE.setConfiguration(createBridgeConfiguration(CONFIG));
    }

    /**
     * Get entities to tick using unified bridge with backward compatibility.
     * @param entities List of entities to evaluate
     * @param players List of players to consider
     * @return List of entity IDs that should be ticked
     */
    public static List<Long> getEntitiesToTick(List<EntityData> entities, List<PlayerData> players) {
        try {
            // Try unified bridge first
            BridgeResult result = UNIFIED_BRIDGE.executeSync(
                "get_entities_to_tick",
                entities.stream().map(EntityData::getId).collect(Collectors.toList()),
                players.stream().map(PlayerData::getId).collect(Collectors.toList())
            );
            
            return extractLongListResult(result);
        } catch (BridgeException e) {
            // Fall back to legacy system with warning
            System.out.println("Using legacy fallback for getEntitiesToTick: " + e.getMessage());
            return RustPerformance.getEntitiesToTick(entities, players);
        }
    }

    /**
     * Get entities to tick asynchronously using unified bridge with backward compatibility.
     * @param entities List of entities to evaluate
     * @param players List of players to consider
     * @return CompletableFuture containing list of entity IDs that should be ticked
     */
    public static CompletableFuture<List<Long>> getEntitiesToTickAsync(List<EntityData> entities, List<PlayerData> players) {
        return CompletableFuture.supplyAsync(() -> getEntitiesToTick(entities, players));
    }

    /**
     * Get block entities to tick using unified bridge with backward compatibility.
     * @param blockEntities List of block entities to evaluate
     * @return List of block entity IDs that should be ticked
     */
    public static List<Long> getBlockEntitiesToTick(List<BlockEntityData> blockEntities) {
        try {
            // Try unified bridge first
            BridgeResult result = UNIFIED_BRIDGE.executeSync(
                "get_block_entities_to_tick",
                blockEntities.stream().map(BlockEntityData::getId).collect(Collectors.toList())
            );
            
            return extractLongListResult(result);
        } catch (BridgeException e) {
            // Fall back to legacy system with warning
            System.out.println("Using legacy fallback for getBlockEntitiesToTick: " + e.getMessage());
            return RustPerformance.getBlockEntitiesToTick(blockEntities);
        }
    }

    /**
     * Get block entities to tick asynchronously using unified bridge with backward compatibility.
     * @param blockEntities List of block entities to evaluate
     * @return CompletableFuture containing list of block entity IDs that should be ticked
     */
    public static CompletableFuture<List<Long>> getBlockEntitiesToTickAsync(List<BlockEntityData> blockEntities) {
        return CompletableFuture.supplyAsync(() -> getBlockEntitiesToTick(blockEntities));
    }

    /**
     * Process mob AI using unified bridge with backward compatibility.
     * @param mobs List of mobs to process
     * @return MobProcessResult containing optimization results
     */
    public static MobProcessResult processMobAI(List<MobData> mobs) {
        try {
            // Try unified bridge first
            BridgeResult result = UNIFIED_BRIDGE.executeSync(
                "process_mob_ai",
                mobs.stream().map(MobData::getId).collect(Collectors.toList())
            );
            
            return extractMobProcessResult(result);
        } catch (BridgeException e) {
            // Fall back to legacy system with warning
            System.out.println("Using legacy fallback for processMobAI: " + e.getMessage());
            return RustPerformance.processMobAI(mobs);
        }
    }

    /**
     * Record JNI call metrics using unified bridge with backward compatibility.
     * @param callType Type of JNI call
     * @param durationMs Duration in milliseconds
     */
    public static void recordJniCall(String callType, long durationMs) {
        try {
            // Try unified bridge first
            UNIFIED_BRIDGE.executeSync(
                "record_jni_call",
                callType,
                durationMs
            );
        } catch (BridgeException e) {
            // Fall back to legacy system with warning
            System.out.println("Using legacy fallback for recordJniCall: " + e.getMessage());
            RustPerformance.recordJniCallNative(callType, durationMs);
        }
    }

    /**
     * Get performance statistics using unified bridge with backward compatibility.
     * @return Map containing performance statistics
     */
    public static Map<String, Object> getPerformanceStatistics() {
        try {
            // Try unified bridge first
            BridgeResult result = UNIFIED_BRIDGE.executeSync("get_performance_statistics");
            
            Map<String, Object> stats = result.getMetadata();
            if (stats != null && !stats.isEmpty()) {
                return stats;
            }
            
            // If metadata doesn't contain stats, try to extract from result data
            return extractStatisticsFromResultData(result);
        } catch (BridgeException e) {
            // Fall back to legacy system with warning
            System.out.println("Using legacy fallback for getPerformanceStatistics: " + e.getMessage());
            return convertLegacyStatsToMap(RustPerformance.getPerformanceStatistics());
        }
    }

    /**
     * Reload configuration from ConfigurationManager.
     * @return PerformanceConfig instance
     */
    private static PerformanceConfig reloadConfiguration() {
        try {
            return CONFIG_MANAGER.getConfiguration(PerformanceConfig.class);
        } catch (Exception e) {
            System.err.println("Failed to reload configuration, using defaults: " + e.getMessage());
            return createDefaultConfiguration();
        }
    }

    /**
     * Load initial configuration from ConfigurationManager.
     * @return PerformanceConfig instance
     */
    private static PerformanceConfig loadConfiguration() {
        try {
            return CONFIG_MANAGER.getConfiguration(PerformanceConfig.class);
        } catch (Exception e) {
            System.err.println("Failed to load configuration, using defaults: " + e.getMessage());
            return createDefaultConfiguration();
        }
    }

    /**
     * Create default configuration when loading fails.
     * @return PerformanceConfig instance with default values
     */
    private static PerformanceConfig createDefaultConfiguration() {
        return new PerformanceConfig.Builder()
                .enabled(true)
                .threadpoolSize(4)
                .tpsThresholdForAsync(19.0)
                .maxEntitiesToCollect(1000)
                .profilingEnabled(false)
                .build();
    }

    /**
     * Create bridge configuration from performance configuration.
     * @param config Performance configuration
     * @return BridgeConfiguration instance
     */
    private static BridgeConfiguration createBridgeConfiguration(PerformanceConfig config) {
        return BridgeConfiguration.builder()
                .defaultWorkerConcurrency(config.getThreadpoolSize())
                .enableDebugLogging(config.isProfilingEnabled())
                .build();
    }

    /**
     * Extract Long list from BridgeResult.
     * @param result BridgeResult containing the data
     * @return List of Long values
     * @throws BridgeException If extraction fails
     */
    public static List<Long> extractLongListResult(BridgeResult result) throws BridgeException {
        if (result == null || !result.isSuccess()) {
            throw new BridgeException("Operation failed or returned null result", BridgeException.BridgeErrorType.GENERIC_ERROR);
        }
        
        byte[] resultData = result.getResultData();
        if (resultData == null || resultData.length == 0) {
            return List.of();
        }
        
        // In a real implementation, you would deserialize the byte[] into the appropriate format
        // This is a simplified example that assumes the result data is a list of longs in a simple format
        try {
            // For demonstration purposes, we'll just return an empty list
            // In a real implementation, you would use your serialization framework to deserialize
            return List.of();
        } catch (Exception e) {
            throw new BridgeException("Failed to extract Long list from result", BridgeException.BridgeErrorType.GENERIC_ERROR, e);
        }
    }

    /**
     * Extract MobProcessResult from BridgeResult.
     * @param result BridgeResult containing the data
     * @return MobProcessResult instance
     * @throws BridgeException If extraction fails
     */
    public static MobProcessResult extractMobProcessResult(BridgeResult result) throws BridgeException {
        if (result == null || !result.isSuccess()) {
            throw new BridgeException("Operation failed or returned null result", BridgeException.BridgeErrorType.GENERIC_ERROR);
        }
        
        Map<String, Object> metadata = result.getMetadata();
        if (metadata != null) {
            List<Long> disableList = extractLongListFromMetadata(metadata, "disableList", List.of());
            List<Long> simplifyList = extractLongListFromMetadata(metadata, "simplifyList", List.of());
            return new MobProcessResult(disableList, simplifyList);
        }
        
        // Fall back to result data deserialization
        try {
            // In a real implementation, you would deserialize the byte[] result data
            // For demonstration purposes, we'll return empty lists
            return new MobProcessResult(List.of(), List.of());
        } catch (Exception e) {
            throw new BridgeException("Failed to extract MobProcessResult from result", BridgeException.BridgeErrorType.GENERIC_ERROR, e);
        }
    }

    /**
     * Extract Long list from metadata.
     * @param metadata Metadata map
     * @param key Key to look up
     * @param defaultValue Default value if key not found or cannot be converted
     * @return List of Long values
     */
    private static List<Long> extractLongListFromMetadata(Map<String, Object> metadata, String key, List<Long> defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        
        try {
            if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                return list.stream()
                        .filter(item -> item instanceof Number)
                        .map(item -> ((Number) item).longValue())
                        .collect(Collectors.toList());
            } else if (value instanceof String) {
                // Try to parse as a single long
                return List.of(Long.parseLong((String) value));
            } else if (value instanceof Number) {
                return List.of(((Number) value).longValue());
            }
        } catch (Exception e) {
            System.err.println("Failed to extract Long list from metadata for key '" + key + "': " + e.getMessage());
        }
        
        return defaultValue;
    }

    /**
     * Extract statistics from result data.
     * @param result BridgeResult containing the data
     * @return Map containing statistics
     */
    private static Map<String, Object> extractStatisticsFromResultData(BridgeResult result) {
        if (result == null || result.getResultData() == null) {
            return Map.of();
        }
        
        // In a real implementation, you would deserialize the byte[] result data
        // For demonstration purposes, we'll return an empty map
        return Map.of();
    }

    /**
     * Convert legacy performance statistics to map.
     * @param stats Legacy performance statistics
     * @return Map containing the statistics
     */
    private static Map<String, Object> convertLegacyStatsToMap(RustPerformance.PerformanceStatistics stats) {
        return Map.of(
                "totalEntitiesProcessed", stats.getTotalEntitiesProcessed(),
                "totalItemsProcessed", stats.getTotalItemsProcessed(),
                "totalMobsProcessed", stats.getTotalMobsProcessed(),
                "totalBlocksProcessed", stats.getTotalBlocksProcessed(),
                "averageTickTime", stats.getAverageTickTime(),
                "nativeAvailable", stats.isNativeAvailable()
        );
    }

    /**
     * Update the adapter configuration when performance configuration changes.
     */
    public static void updateConfiguration() {
        CONFIG = reloadConfiguration();
        UNIFIED_BRIDGE.setConfiguration(createBridgeConfiguration(CONFIG));
    }
}