package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.performance.monitoring.PerformanceManager;
import com.kneaf.core.performance.bridge.NativeIntegrationManager;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
// cleaned: removed unused exception import
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles performance optimization logic including entity processing, memory management,
 * and adaptive optimization strategies.
 */
@SuppressWarnings({"unused"})
public class PerformanceOptimizer {
    
    private final PerformanceMonitor monitor;
    private final NativeIntegrationManager nativeManager;
    private final EntityProcessor entityProcessor;
    private final BatchProcessor batchProcessor;
    
    // Optimization configuration (base values). Actual values are computed dynamically
    private static final double BASE_TARGET_TICK_TIME_MS = 50.0;
    
    // Performance tracking
    private final AtomicLong totalOptimizationsApplied = new AtomicLong(0);
    private final AtomicLong totalEntitiesProcessed = new AtomicLong(0);
    private final AtomicLong totalItemsProcessed = new AtomicLong(0);
    private final AtomicLong totalMobsProcessed = new AtomicLong(0);
    private final AtomicLong totalBlocksProcessed = new AtomicLong(0);
    
    // Adaptive optimization
    private final Map<String, OptimizationStats> optimizationStats = new ConcurrentHashMap<>();
    private volatile OptimizationLevel currentOptimizationLevel = OptimizationLevel.NORMAL;
    private volatile long lastOptimizationLevelChange = System.currentTimeMillis();
    
    public PerformanceOptimizer(PerformanceMonitor monitor, 
                               NativeIntegrationManager nativeManager,
                               EntityProcessor entityProcessor,
                               BatchProcessor batchProcessor) {
        this.monitor = monitor;
        this.nativeManager = nativeManager;
        this.entityProcessor = entityProcessor;
        this.batchProcessor = batchProcessor;
        
    // Default optimization configuration base values are constants; values used at runtime
    // are computed from current TPS and tick delay via helper getters below.
    }
    
    // Dynamic getters - compute actual runtime values based on TPS and tick delay
    private int getMaxEntitiesPerTick() {
        double tps = PerformanceManager.getAverageTPS();
        double tickDelayMs = PerformanceManager.getLastTickDurationMs();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxEntities(tps, tickDelayMs);
    }

    private int getMaxItemsPerTick() {
        double tps = PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxItems(tps);
    }

    private int getMaxMobsPerTick() {
        double tps = PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxMobs(tps);
    }

    private int getMaxBlocksPerTick() {
        double tps = PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxBlocks(tps);
    }

    private double getTargetTickTimeMs() {
        double tps = PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveTargetTickTimeMs(tps);
    }

    private int getOptimizationThreshold() {
        double tps = PerformanceManager.getAverageTPS();
        return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveOptimizationThreshold(tps);
    }
    
    /**
     * Optimize entity processing based on current performance metrics.
     */
    public List<Long> optimizeEntities(List<EntityData> entities, List<PlayerData> players) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Apply adaptive optimization based on current load
            OptimizationLevel level = determineOptimizationLevel(entities.size(), players.size());
            
            // Limit entities based on optimization level
            List<EntityData> entitiesToProcess = applyEntityLimit(entities, level);
            
            // Process entities using appropriate method
            List<Long> result;
            if (shouldUseBatchProcessing(entitiesToProcess.size())) {
                // Pass the typed EntityInput expected by EntityProcessor when processing batches
                result = batchProcessor.submitBatchRequest(
                    PerformanceConstants.ENTITIES_KEY,
                    new com.kneaf.core.performance.core.EntityProcessor.EntityInput(entitiesToProcess, players));
            } else {
                result = entityProcessor.processEntities(entitiesToProcess, players);
            }
            
            // Update statistics
            totalEntitiesProcessed.addAndGet(entitiesToProcess.size());
            totalOptimizationsApplied.incrementAndGet();
            updateOptimizationStats("entities", entitiesToProcess.size(),
                                  result.size(),
                                  System.currentTimeMillis() - startTime);
            
            // Monitoring: detailed entity optimization logging removed to reduce server log spam
            
            return result;
            
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error optimizing entities", e);
            throw new RuntimeException(
                "Failed to optimize " + entities.size() + " entities", e);
        }
    }
    
    /**
     * Optimize villager processing with spatial awareness.
     */
    public List<Long> optimizeVillagers(List<VillagerData> villagers, int centerX, int centerZ, int radius) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Use spatial optimization for villagers
            List<VillagerData> villagersToProcess = applyVillagerSpatialFilter(villagers, centerX, centerZ, radius);
            
            // Process villagers using native integration if available
            List<Long> result;
            if (nativeManager.isNativeAvailable() && villagersToProcess.size() >= getOptimizationThreshold()) {
                result = processVillagersNative(villagersToProcess);
            } else {
                result = processVillagersDirect(villagersToProcess);
            }
            
            // Update statistics
            updateOptimizationStats("villagers", villagers.size(), result.size(),
                                  System.currentTimeMillis() - startTime);
            
            // Monitor villager optimization
            KneafCore.LOGGER.debug("Optimized {} villagers, {} processed", villagers.size(), result.size());
            
            return result;
            
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error optimizing villagers", e);
            // Fallback: return all villagers
            return villagers.stream().map(v -> (long) v.hashCode()).toList();
        }
    }
    
    /**
     * Optimize memory usage by cleaning up unused resources.
     */
    public void optimizeMemory() {
        long startTime = System.currentTimeMillis();
        
        try {
            // Get current memory usage
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            // Check if memory optimization is needed
            double memoryUsagePercent = (double) usedMemory / totalMemory * 100;
            
            double tps = PerformanceManager.getAverageTPS();
            double memoryThreshold = com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMemoryUsageThreshold(tps);
            if (memoryUsagePercent > memoryThreshold) {
                // Force garbage collection
                System.gc();
                
                // Clear internal caches if needed
                clearInternalCaches();
                
                KneafCore.LOGGER.info("Memory optimization applied. Usage was {}%", 
                                    String.format("%.1f", memoryUsagePercent));
            }
            
            // Monitor memory optimization
            KneafCore.LOGGER.debug("Memory optimization applied. Used: {} MB, Free: {} MB",
                                 usedMemory / (1024 * 1024), freeMemory / (1024 * 1024));
            
        } catch (Exception e) {
            KneafCore.LOGGER.error("Error during memory optimization", e);
        }
    }
    
    /**
     * Get optimization statistics.
     */
    public OptimizationStatistics getOptimizationStatistics() {
        Map<String, OptimizationStats> stats = new HashMap<>(optimizationStats);
        
        return new OptimizationStatistics(
            totalOptimizationsApplied.get(),
            totalEntitiesProcessed.get(),
            totalItemsProcessed.get(),
            totalMobsProcessed.get(),
            totalBlocksProcessed.get(),
            currentOptimizationLevel,
            stats
        );
    }
    
    /**
     * Reset optimization statistics.
     */
    public void resetStatistics() {
        totalOptimizationsApplied.set(0);
        totalEntitiesProcessed.set(0);
        totalItemsProcessed.set(0);
        totalMobsProcessed.set(0);
        totalBlocksProcessed.set(0);
        optimizationStats.clear();
    }
    
    /**
     * Determine optimization level based on current load and performance metrics.
     */
    private OptimizationLevel determineOptimizationLevel(int entityCount, int playerCount) {
        // Get current performance metrics (dynamic)
        double avgTickTime = getTargetTickTimeMs();
        double memoryUsage = getMemoryUsagePercent();
        
        // Calculate load factor
        double loadFactor = (entityCount + playerCount * 10) / 1000.0; // Weight players more heavily
        
        // Determine optimization level
        OptimizationLevel newLevel;
        if (avgTickTime > getTargetTickTimeMs() * 1.5 || memoryUsage > 85.0 || loadFactor > 2.0) {
            newLevel = OptimizationLevel.AGGRESSIVE;
        } else if (avgTickTime > getTargetTickTimeMs() * 1.2 || memoryUsage > 70.0 || loadFactor > 1.5) {
            newLevel = OptimizationLevel.HIGH;
        } else if (avgTickTime > getTargetTickTimeMs() * 1.1 || memoryUsage > 60.0 || loadFactor > 1.0) {
            newLevel = OptimizationLevel.MEDIUM;
        } else {
            newLevel = OptimizationLevel.NORMAL;
        }
        
        // Update current level if changed
        if (newLevel != currentOptimizationLevel) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastOptimizationLevelChange > 5000) { // 5 second cooldown
                currentOptimizationLevel = newLevel;
                lastOptimizationLevelChange = currentTime;
                
                KneafCore.LOGGER.info("Optimization level changed to {} (tickTime={}ms, memory={}%, load={})", 
                                    newLevel, String.format("%.1f", avgTickTime), 
                                    String.format("%.1f", memoryUsage), String.format("%.1f", loadFactor));
            }
        }
        
        return currentOptimizationLevel;
    }
    
    /**
     * Apply entity limit based on optimization level.
     */
    private List<EntityData> applyEntityLimit(List<EntityData> entities, OptimizationLevel level) {
        if (entities.size() <= getMaxEntitiesPerTick()) {
            return entities;
        }
        
        int limit = switch (level) {
            case AGGRESSIVE -> Math.min(getMaxEntitiesPerTick() / 2, 50);
            case HIGH -> Math.min(getMaxEntitiesPerTick() * 2/3, 100);
            case MEDIUM -> Math.min(getMaxEntitiesPerTick() * 3/4, 150);
            case NORMAL -> getMaxEntitiesPerTick();
        };
        
        // Prioritize entities closer to players or with higher priority
        return entities.stream()
            .sorted((a, b) -> Integer.compare(getEntityPriority(b), getEntityPriority(a)))
            .limit(limit)
            .toList();
    }
    
    /**
     * Apply spatial filter for villagers.
     */
    private List<VillagerData> applyVillagerSpatialFilter(List<VillagerData> villagers, 
                                                         int centerX, int centerZ, int radius) {
        if (villagers.size() <= getOptimizationThreshold()) {
            return villagers;
        }
        
        // Filter villagers within radius
        int radiusSquared = radius * radius;
        return villagers.stream()
            .filter(v -> {
                int[] position = new int[]{(int) v.hashCode(), (int) v.hashCode(), (int) v.hashCode()};
                int dx = position[0] - centerX;
                int dz = position[1] - centerZ;
                return (dx * dx + dz * dz) <= radiusSquared;
            })
        .limit(getMaxEntitiesPerTick())
            .toList();
    }
    
    /**
     * Check if batch processing should be used.
     */
    private boolean shouldUseBatchProcessing(int entityCount) {
        return entityCount >= getOptimizationThreshold() && nativeManager.isNativeAvailable();
    }
    
    /**
     * Process villagers using native integration.
     */
    private List<Long> processVillagersNative(List<VillagerData> villagers) {
        // Implementation would use native integration
        return villagers.stream().map(v -> (long) v.hashCode()).toList();
    }
    
    /**
     * Process villagers directly.
     */
    private List<Long> processVillagersDirect(List<VillagerData> villagers) {
        // Simple direct processing - return all villager IDs
        return villagers.stream().map(v -> (long) v.hashCode()).toList();
    }
    
    /**
     * Get entity priority for sorting.
     */
    private int getEntityPriority(EntityData entity) {
        // Higher priority for entities closer to players or with special properties
        int priority = 0;
        
        // Distance-based priority (closer = higher priority)
        // This is a simplified version - in real implementation would calculate actual distance
        
        // Type-based priority (simplified)
        priority += 10; // Base priority for all entities
        
        return priority;
    }
    
    /**
     * Get current memory usage percentage.
     */
    private double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        return (double) usedMemory / totalMemory * 100;
    }
    
    /**
     * Update optimization statistics.
     */
    private void updateOptimizationStats(String type, int inputCount, int optimizedCount, long processingTime) {
        OptimizationStats stats = optimizationStats.computeIfAbsent(type, k -> new OptimizationStats());
        stats.addSample(inputCount, optimizedCount, processingTime);
    }
    
    /**
     * Clear internal caches.
     */
    private void clearInternalCaches() {
        // Clear any internal caches or temporary data structures
        // This would be implemented based on specific caching needs
    }
    
    /**
     * Optimization level enumeration.
     */
    public enum OptimizationLevel {
        NORMAL(1.0),
        MEDIUM(1.5),
        HIGH(2.0),
        AGGRESSIVE(3.0);
        
        private final double multiplier;
        
        OptimizationLevel(double multiplier) {
            this.multiplier = multiplier;
        }
        
        public double getMultiplier() {
            return multiplier;
        }
    }
    
    /**
     * Optimization statistics for a specific type.
     */
    public static class OptimizationStats {
        private final AtomicInteger sampleCount = new AtomicInteger(0);
        private final AtomicInteger totalInput = new AtomicInteger(0);
    /**
     * Helper method to get villager ID (since we don't know the exact field structure)
     */
    private long getVillagerId(VillagerData villager) {
        // This is a placeholder - in real implementation would access the actual ID field
        // For now, we'll use hashCode as a simple ID
        return villager.hashCode();
    }
    
    /**
     * Helper method to get villager position (since we don't know the exact field structure)
     */
    private int[] getVillagerPosition(VillagerData villager) {
        // This is a placeholder - in real implementation would access the actual position fields
        // For now, we'll return default position
        return new int[]{0, 0};
    }
        private final AtomicInteger totalOptimized = new AtomicInteger(0);
        private final AtomicLong totalProcessingTime = new AtomicLong(0);
        
        public void addSample(int inputCount, int optimizedCount, long processingTime) {
            sampleCount.incrementAndGet();
            totalInput.addAndGet(inputCount);
            totalOptimized.addAndGet(optimizedCount);
            totalProcessingTime.addAndGet(processingTime);
        }
        
        public double getAverageOptimizationRate() {
            int input = totalInput.get();
            int optimized = totalOptimized.get();
            return input > 0 ? (double) optimized / input : 0.0;
        }
        
        public double getAverageProcessingTime() {
            int samples = sampleCount.get();
            return samples > 0 ? (double) totalProcessingTime.get() / samples : 0.0;
        }
        
        public int getSampleCount() {
            return sampleCount.get();
        }
    }
    
    /**
     * Overall optimization statistics.
     */
    public static class OptimizationStatistics {
        private final long totalOptimizations;
        private final long totalEntitiesProcessed;
        private final long totalItemsProcessed;
        private final long totalMobsProcessed;
        private final long totalBlocksProcessed;
        private final OptimizationLevel currentLevel;
        private final Map<String, OptimizationStats> typeStats;
        
        public OptimizationStatistics(long totalOptimizations,
                                    long totalEntitiesProcessed,
                                    long totalItemsProcessed,
                                    long totalMobsProcessed,
                                    long totalBlocksProcessed,
                                    OptimizationLevel currentLevel,
                                    Map<String, OptimizationStats> typeStats) {
            this.totalOptimizations = totalOptimizations;
            this.totalEntitiesProcessed = totalEntitiesProcessed;
            this.totalItemsProcessed = totalItemsProcessed;
            this.totalMobsProcessed = totalMobsProcessed;
            this.totalBlocksProcessed = totalBlocksProcessed;
            this.currentLevel = currentLevel;
            this.typeStats = new HashMap<>(typeStats);
        }
        
        public long getTotalOptimizations() { return totalOptimizations; }
        public long getTotalEntitiesProcessed() { return totalEntitiesProcessed; }
        public long getTotalItemsProcessed() { return totalItemsProcessed; }
        public long getTotalMobsProcessed() { return totalMobsProcessed; }
        public long getTotalBlocksProcessed() { return totalBlocksProcessed; }
        public OptimizationLevel getCurrentLevel() { return currentLevel; }
        public Map<String, OptimizationStats> getTypeStats() { return new HashMap<>(typeStats); }
    }
}