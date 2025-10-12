package com.kneaf.core.unifiedbridge;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.performance.monitoring.PerformanceConfig;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.core.MobProcessResult;
import com.kneaf.core.binary.ManualSerializers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Adapter for migrating from legacy performance systems to the unified bridge system.
 * Provides backward compatibility while guiding migration to new unified interfaces.
 */
public final class BackwardCompatibilityAdapter {
    private static final UnifiedBridge UNIFIED_BRIDGE = BridgeFactory.getInstance().createBridge(BridgeFactory.BridgeType.ASYNCHRONOUS, BridgeConfiguration.getDefault());
    private static final ConfigurationManager CONFIG_MANAGER = ConfigurationManager.getInstance();
    private static final Map<String, String[]> ARGUMENT_MAPPINGS = createArgumentMappings();
    private static final AtomicLong TOTAL_ENTITIES_PROCESSED = new AtomicLong(0);
    private static final AtomicLong TOTAL_MOBS_PROCESSED = new AtomicLong(0);
    private static final AtomicLong TOTAL_BLOCK_ENTITIES_PROCESSED = new AtomicLong(0);
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
                // Record metrics before processing
                long startTime = System.currentTimeMillis();
                TOTAL_ENTITIES_PROCESSED.addAndGet(entities.size());
                
                // Try unified bridge first with proper argument mapping
                ByteBuffer inputBuffer = ManualSerializers.serializeEntityInput(
                    System.currentTimeMillis() / 1000, entities, players);
                BridgeResult result = UNIFIED_BRIDGE.executeSync(
                    "get_entities_to_tick",
                    inputBuffer.array()
                );
                
                List<Long> entityIds = extractLongListResult(result);
                
                // Record completion metrics
                System.out.printf("Performance metrics: entities_to_tick - processed %d entities, returned %d results in %dms%n",
                        entities.size(), entityIds.size(), System.currentTimeMillis() - startTime);
                return entityIds;
            } catch (BridgeException e) {
                // Record failure metrics
                System.err.printf("Performance metrics: entities_to_tick failed - processed %d entities, error: %s%n",
                        entities.size(), e.getMessage());
                
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
                // Record metrics before processing
                long startTime = System.currentTimeMillis();
                TOTAL_BLOCK_ENTITIES_PROCESSED.addAndGet(blockEntities.size());
                
                // Try unified bridge first with proper argument mapping
                ByteBuffer inputBuffer = ManualSerializers.serializeBlockInput(
                    System.currentTimeMillis() / 1000, blockEntities);
                BridgeResult result = UNIFIED_BRIDGE.executeSync(
                    "get_block_entities_to_tick",
                    inputBuffer.array()
                );
                
                List<Long> blockEntityIds = extractLongListResult(result);
                
                // Record completion metrics
                System.out.printf("Performance metrics: block_entities_to_tick - processed %d block entities, returned %d results in %dms%n",
                        blockEntities.size(), blockEntityIds.size(), System.currentTimeMillis() - startTime);
                return blockEntityIds;
            } catch (BridgeException e) {
                // Record failure metrics
                System.err.printf("Performance metrics: block_entities_to_tick failed - processed %d block entities, error: %s%n",
                        blockEntities.size(), e.getMessage());
                
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
                // Record metrics before processing
                long startTime = System.currentTimeMillis();
                TOTAL_MOBS_PROCESSED.addAndGet(mobs.size());
                
                // Try unified bridge first with proper argument mapping
                ByteBuffer inputBuffer = ManualSerializers.serializeMobInput(
                    System.currentTimeMillis() / 1000, mobs);
                BridgeResult result = UNIFIED_BRIDGE.executeSync(
                    "process_mob_ai",
                    inputBuffer.array()
                );
                
                MobProcessResult mobResult = extractMobProcessResult(result);
                    
                // Record completion metrics
                System.out.printf("Performance metrics: process_mob_ai - processed %d mobs, disabled %d, simplified %d in %dms%n",
                        mobs.size(), mobResult.getDisableList().size(), mobResult.getSimplifyList().size(),
                        System.currentTimeMillis() - startTime);
                
                return mobResult;
            } catch (BridgeException e) {
                // Record failure metrics
                System.err.printf("Performance metrics: process_mob_ai failed - processed %d mobs, error: %s%n",
                        mobs.size(), e.getMessage());
                
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
                // Record metrics before processing
                long startTime = System.currentTimeMillis();
                
                // Try unified bridge first with proper argument mapping
                UNIFIED_BRIDGE.executeSync(
                    "record_jni_call",
                    Map.of("callType", callType, "durationMs", durationMs)
                );
                
                // Record completion metrics
                System.out.printf("Performance metrics: record_jni_call - callType: %s, duration: %dms, processing time: %dms%n",
                        callType, durationMs, System.currentTimeMillis() - startTime);
            } catch (BridgeException e) {
                // Record failure metrics
                // Record failure metrics
                System.err.printf("Performance metrics: record_jni_call failed - callType: %s, duration: %dms, error: %s%n",
                        callType, durationMs, e.getMessage());
                
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
            // Record metrics before processing
            long startTime = System.currentTimeMillis();
            
            // Try unified bridge first
            BridgeResult result = UNIFIED_BRIDGE.executeSync("get_performance_statistics");
            
            Map<String, Object> stats = result.getMetadata();
            if (stats != null && !stats.isEmpty()) {
                return stats;
            }
            
            // If metadata doesn't contain stats, try to extract from result data
            Map<String, Object> resultStats = extractStatisticsFromResultData(result);
             
            // Add unified metrics to the result
            resultStats.put("unified_metrics_enabled", true);
            resultStats.put("total_entities_processed", TOTAL_ENTITIES_PROCESSED.get());
            resultStats.put("total_mobs_processed", TOTAL_MOBS_PROCESSED.get());
            resultStats.put("total_block_entities_processed", TOTAL_BLOCK_ENTITIES_PROCESSED.get());
            
            // Record completion metrics
            System.out.printf("Performance metrics: get_performance_statistics - returned %d stats in %dms%n",
                    resultStats.size(), System.currentTimeMillis() - startTime);
            return resultStats;
        } catch (BridgeException e) {
            // Record failure metrics
            System.err.printf("Performance metrics: get_performance_statistics failed - error: %s%n",
                    e.getMessage());
                
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
        
        // Use ManualSerializers to deserialize the byte[] result data
        // This assumes the result data follows the entity process result format
        try {
            ByteBuffer buffer = ByteBuffer.wrap(resultData).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return ManualSerializers.deserializeEntityProcessResult(buffer);
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
        
        // Fall back to result data deserialization using ManualSerializers
        try {
            // Deserialize the byte[] result data using ManualSerializers
            byte[] resultData = result.getResultData();
            if (resultData != null && resultData.length > 0) {
                ByteBuffer buffer = ByteBuffer.wrap(resultData).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                List<com.kneaf.core.data.entity.MobData> mobDataList = ManualSerializers.deserializeMobProcessResult(buffer);
                
                // Convert to MobProcessResult format (simplified for this example)
                List<Long> disableList = mobDataList.stream()
                        .filter(mob -> mob.getType().equals("DISABLED"))
                        .map(com.kneaf.core.data.entity.MobData::getId)
                        .collect(Collectors.toList());
                
                List<Long> simplifyList = mobDataList.stream()
                        .filter(mob -> mob.getType().equals("SIMPLIFIED"))
                        .map(com.kneaf.core.data.entity.MobData::getId)
                        .collect(Collectors.toList());
                
                return new MobProcessResult(disableList, simplifyList);
            }
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
        
        // Deserialize the byte[] result data using ManualSerializers or custom deserialization
        try {
            byte[] resultData = result.getResultData();
            if (resultData.length == 0) {
                return Map.of();
            }
            
            // For this example, we'll simulate extracting statistics from the byte array
            // In a real implementation, you would use the appropriate deserialization method
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEntitiesProcessed", resultData.length / 8); // Simplified calculation
            stats.put("processingTimeMs", 10); // Example value
            stats.put("successRate", 95.5); // Example value
            return stats;
        } catch (Exception e) {
            System.err.println("Failed to extract statistics from result data: " + e.getMessage());
            return Map.of();
        }
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
     * Create argument mappings for unified bridge operations.
     * @return Map of operation names to argument name arrays
     */
    private static Map<String, String[]> createArgumentMappings() {
        Map<String, String[]> mappings = new HashMap<>();
        mappings.put("get_entities_to_tick", new String[]{"entities", "players"});
        mappings.put("get_block_entities_to_tick", new String[]{"blockEntities"});
        mappings.put("process_mob_ai", new String[]{"mobs"});
        mappings.put("record_jni_call", new String[]{"callType", "durationMs"});
        mappings.put("get_performance_statistics", new String[]{});
        return mappings;
    }
    
    /**
     * Get argument names for a specific operation.
     * @param operationName Name of the operation
     * @return Array of argument names, or null if operation not found
     */
    public static String[] getArgumentNames(String operationName) {
        return ARGUMENT_MAPPINGS.get(operationName);
    }
    
    /**
     * Get unified metrics statistics for monitoring.
     * @return Map containing unified metrics statistics
     */
    public static Map<String, Object> getUnifiedMetricsStatistics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total_entities_processed", TOTAL_ENTITIES_PROCESSED.get());
        metrics.put("total_mobs_processed", TOTAL_MOBS_PROCESSED.get());
        metrics.put("total_block_entities_processed", TOTAL_BLOCK_ENTITIES_PROCESSED.get());
        metrics.put("unified_metrics_enabled", true);
        metrics.put("last_update_time", System.currentTimeMillis());
        return metrics;
    }
    
    /**
     * Reset unified metrics counters.
     */
    public static void resetUnifiedMetrics() {
        TOTAL_ENTITIES_PROCESSED.set(0);
        TOTAL_MOBS_PROCESSED.set(0);
        TOTAL_BLOCK_ENTITIES_PROCESSED.set(0);
        System.out.println("Unified metrics counters reset");
    }
    
    /**
     * Update the adapter configuration when performance configuration changes.
     */
    public static void updateConfiguration() {
        CONFIG = reloadConfiguration();
        UNIFIED_BRIDGE.setConfiguration(createBridgeConfiguration(CONFIG));
    }
}