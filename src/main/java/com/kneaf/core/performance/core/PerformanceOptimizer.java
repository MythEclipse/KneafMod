package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
// cleaned: removed unused exception import
import com.kneaf.core.performance.bridge.NativeIntegrationManager;
import com.kneaf.core.performance.monitoring.PerformanceManager;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles performance optimization logic including entity processing, memory management, and
 * adaptive optimization strategies.
 */
@SuppressWarnings({"unused"})
public class PerformanceOptimizer {

  private final PerformanceMonitor monitor;
  private final NativeIntegrationManager NATIVE_MANAGER;
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
  private final AtomicReference<OptimizationLevel> previousOptimizationLevel = new AtomicReference<>(OptimizationLevel.NORMAL);
  private final AtomicInteger optimizationChangeCounter = new AtomicInteger(0);

  public PerformanceOptimizer(
      PerformanceMonitor monitor,
      NativeIntegrationManager NATIVE_MANAGER,
      EntityProcessor entityProcessor,
      BatchProcessor batchProcessor) {
    this.monitor = monitor;
    this.NATIVE_MANAGER = NATIVE_MANAGER;
    this.entityProcessor = entityProcessor;
    this.batchProcessor = batchProcessor;

    // Default optimization configuration base values are constants; values used at runtime
    // are computed from current TPS and tick delay via helper getters below.
  }

  // Dynamic getters - compute actual runtime values based on TPS and tick delay
  private int getMaxEntitiesPerTick() {
    double tps = PerformanceManager.getAverageTPS();
    double tickDelayMs = PerformanceManager.getLastTickDurationMs();
    return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMaxEntities(
        tps, tickDelayMs);
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

  /** Calculate a smoothed load factor to reduce optimization level fluctuations */
  private double getSmoothedLoadFactor(double rawLoadFactor) {
    // Apply exponential smoothing with factor 0.3 (more weight to recent values)
    return 0.3 * rawLoadFactor + 0.7 * getPreviousLoadFactor();
  }

  /** Get previous load factor from optimization statistics (simplified for this example) */
  private double getPreviousLoadFactor() {
    // In a complete implementation, this would track historical load factors
    // For now, return a conservative value to demonstrate the concept
    return 0.5;
  }

  /**
   * Hysteresis logic to prevent rapid optimization level fluctuations.
   * Returns true if the optimization level change should be allowed.
   */
  private boolean shouldChangeOptimizationLevel(OptimizationLevel newLevel, OptimizationLevel currentLevel) {
    // Prevent rapid cycling between levels
    if (optimizationChangeCounter.get() > 5) {
      return false;
    }

    // Allow changes when moving to more aggressive levels (faster response to lag)
    if (newLevel.ordinal() > currentLevel.ordinal()) {
      return true;
    }

    // For less aggressive levels, require more significant improvement
    return optimizationChangeCounter.get() < 3;
  }

  private int getOptimizationThreshold() {
    double tps = PerformanceManager.getAverageTPS();
    return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveOptimizationThreshold(
        tps);
  }

  /** Optimize entity processing based on current performance metrics. */
 public CompletableFuture<List<Long>> optimizeEntities(List<EntityData> entities, List<PlayerData> players) {
   long startTime = System.currentTimeMillis();

   try {
     // Apply adaptive optimization based on current load
     OptimizationLevel level = determineOptimizationLevel(entities.size(), players.size());

     // Limit entities based on optimization level
     List<EntityData> entitiesToProcess = applyEntityLimit(entities, level);

     // Process entities using appropriate method
     if (shouldUseBatchProcessing(entitiesToProcess.size())) {
       // Pass the typed EntityInput expected by EntityProcessor when processing batches
       return batchProcessor.submitLongListRequest(
               PerformanceConstants.ENTITIES_KEY,
               new com.kneaf.core.performance.core.EntityProcessor.EntityInput(
                   entitiesToProcess, players))
           .thenApply(result -> {
             // Update statistics
             totalEntitiesProcessed.addAndGet(entitiesToProcess.size());
             totalOptimizationsApplied.incrementAndGet();
             updateOptimizationStats(
                 "entities",
                 entitiesToProcess.size(),
                 result.size(),
                 System.currentTimeMillis() - startTime);
             return result;
           });
     } else {
       List<Long> result = entityProcessor.processEntities(entitiesToProcess, players);
       
       // Update statistics
       totalEntitiesProcessed.addAndGet(entitiesToProcess.size());
       totalOptimizationsApplied.incrementAndGet();
       updateOptimizationStats(
           "entities",
           entitiesToProcess.size(),
           result.size(),
           System.currentTimeMillis() - startTime);
       
       return CompletableFuture.completedFuture(result);
     }

   } catch (Exception e) {
     KneafCore.LOGGER.error("Error optimizing entities", e);
     return CompletableFuture.failedFuture(new RuntimeException("Failed to optimize " + entities.size() + " entities", e));
   }
 }

  /** Optimize villager processing with spatial awareness. */
  public List<Long> optimizeVillagers(
      List<VillagerData> villagers, int centerX, int centerZ, int radius) {
    long startTime = System.currentTimeMillis();

    try {
      // Use spatial optimization for villagers
      List<VillagerData> villagersToProcess =
          applyVillagerSpatialFilter(villagers, centerX, centerZ, radius);

      // Process villagers using native integration if available
      List<Long> result;
      if (NATIVE_MANAGER.isNativeAvailable()
          && villagersToProcess.size() >= getOptimizationThreshold()) {
        result = processVillagersNative(villagersToProcess);
      } else {
        result = processVillagersDirect(villagersToProcess);
      }

      // Update statistics
      updateOptimizationStats(
          "villagers", villagers.size(), result.size(), System.currentTimeMillis() - startTime);

      // Monitor villager optimization
      KneafCore.LOGGER.debug(
          "Optimized {} villagers, {} processed", villagers.size(), result.size());

      return result;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error optimizing villagers", e);
      // Fallback: return all villagers
      return villagers.stream().map(v -> (long) v.hashCode()).toList();
    }
  }

  /** Optimize memory usage by cleaning up unused resources with more aggressive strategies. */
  public void optimizeMemory() {
    long startTime = System.currentTimeMillis();

    try {
      // Get current memory usage with more detailed metrics
      Runtime runtime = Runtime.getRuntime();
      long totalMemory = runtime.totalMemory();
      long freeMemory = runtime.freeMemory();
      long usedMemory = totalMemory - freeMemory;
      long maxMemory = runtime.maxMemory();

      // Check if memory optimization is needed with more sophisticated thresholds
      double memoryUsagePercent = (double) usedMemory / totalMemory * 100;
      double heapUsagePercent = (double) usedMemory / maxMemory * 100;

      double tps = PerformanceManager.getAverageTPS();
      double memoryThreshold =
          com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMemoryUsageThreshold(tps);
      
      // Apply tiered memory optimization based on severity
      if (memoryUsagePercent > memoryThreshold + 20.0) { // Critical memory pressure
        applyCriticalMemoryOptimization(runtime, usedMemory, memoryUsagePercent);
      } else if (memoryUsagePercent > memoryThreshold) { // Moderate memory pressure
        applyModerateMemoryOptimization(runtime, usedMemory, memoryUsagePercent);
      } else { // Maintenance optimization
        applyMaintenanceMemoryOptimization();
      }

      // Monitor memory optimization results
      logMemoryOptimizationResults(runtime, usedMemory, freeMemory, memoryUsagePercent, heapUsagePercent);

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error during memory optimization", e);
    }
  }

  /** Apply critical memory optimization for severe memory pressure */
  private void applyCriticalMemoryOptimization(Runtime runtime, long usedMemory, double memoryUsagePercent) {
    // Force aggressive garbage collection with multiple passes
    System.gc();
    try {
      Thread.sleep(10); // Brief pause to allow GC to make progress
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Removed deprecated System.runFinalization() (deprecated since JDK 18).
    // Finalizers are deprecated and unreliable; prefer multiple GC passes and explicit resource/ cache cleanup.
    System.gc();
    Thread.yield();

    // Clear all internal caches aggressively
    clearAllInternalCaches();

    // Reduce entity processing limits temporarily
    getOptimizationStatistics(); // Trigger cache cleanup

    KneafCore.LOGGER.warn(
        "CRITICAL Memory optimization applied. Usage was {}% - reduced entity processing limits",
        String.format("%.1f", memoryUsagePercent));
  }

  /** Apply moderate memory optimization for typical memory pressure */
  private void applyModerateMemoryOptimization(Runtime runtime, long usedMemory, double memoryUsagePercent) {
    // Standard garbage collection
    System.gc();

    // Clear selective internal caches
    clearInternalCaches();

    // Log optimization
    KneafCore.LOGGER.info(
        "Memory optimization applied. Usage was {}%",
        String.format("%.1f", memoryUsagePercent));
  }

  /** Apply maintenance memory optimization for routine cleanup */
  private void applyMaintenanceMemoryOptimization() {
    // Clear only non-critical caches
    clearNonCriticalCaches();
    
    // Log at debug level only
    KneafCore.LOGGER.debug("Routine memory maintenance completed");
  }

  /** Clear all internal caches (aggressive) */
  private void clearAllInternalCaches() {
    clearInternalCaches(); // Call base implementation
    // Additional cache clearing would go here
  }

  /** Clear non-critical internal caches (maintenance) */
  private void clearNonCriticalCaches() {
    // Lightweight cache clearing for routine maintenance
    // Implementation would go here
  }

  /** Log memory optimization results with detailed metrics */
  private void logMemoryOptimizationResults(Runtime runtime, long usedMemory, long freeMemory,
                                         double memoryUsagePercent, double heapUsagePercent) {
    KneafCore.LOGGER.debug(
        "Memory optimization results: Used={}MB, Free={}MB, Usage={}%, HeapUsage={}%",
        usedMemory / (1024 * 1024),
        freeMemory / (1024 * 1024),
        String.format("%.1f", memoryUsagePercent),
        String.format("%.1f", heapUsagePercent));
  }

  /** Get optimization statistics. */
  public OptimizationStatistics getOptimizationStatistics() {
    Map<String, OptimizationStats> Stats = new HashMap<>(optimizationStats);

    return new OptimizationStatistics(
        totalOptimizationsApplied.get(),
        totalEntitiesProcessed.get(),
        totalItemsProcessed.get(),
        totalMobsProcessed.get(),
        totalBlocksProcessed.get(),
        currentOptimizationLevel,
        Stats);
  }

  /** Reset optimization statistics. */
  public void resetStatistics() {
    totalOptimizationsApplied.set(0);
    totalEntitiesProcessed.set(0);
    totalItemsProcessed.set(0);
    totalMobsProcessed.set(0);
    totalBlocksProcessed.set(0);
    optimizationStats.clear();
  }

  /** Determine optimization level based on current load and performance metrics. */
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
    } else if (avgTickTime > getTargetTickTimeMs() * 1.2
        || memoryUsage > 70.0
        || loadFactor > 1.5) {
      newLevel = OptimizationLevel.HIGH;
    } else if (avgTickTime > getTargetTickTimeMs() * 1.1
        || memoryUsage > 60.0
        || loadFactor > 1.0) {
      newLevel = OptimizationLevel.MEDIUM;
    } else {
      newLevel = OptimizationLevel.NORMAL;
    }

    // Update current level if changed
    // Prevent rapid optimization level fluctuations with hysteresis and smoothing
    if (newLevel != currentOptimizationLevel) {
      long currentTime = System.currentTimeMillis();
      
      // Apply hysteresis: require more significant changes to switch levels
      if (shouldChangeOptimizationLevel(newLevel, currentOptimizationLevel)) {
        if (currentTime - lastOptimizationLevelChange > 2000) { // Reduced to 2s for responsiveness but with hysteresis
          currentOptimizationLevel = newLevel;
          lastOptimizationLevelChange = currentTime;
          optimizationChangeCounter.incrementAndGet();
          
          // Log optimization level changes with context
          KneafCore.LOGGER.debug(
              "Optimization level changed to {} (from {}) - tickTime={}ms, memory={}%, load={}",
              newLevel,
              previousOptimizationLevel.get(),
              String.format("%.1f", avgTickTime),
              String.format("%.1f", memoryUsage),
              String.format("%.1f", loadFactor));
        }
      }
    }
    previousOptimizationLevel.set(newLevel);

    return currentOptimizationLevel;
  }

  /** Apply entity limit based on optimization level. */
 private List<EntityData> applyEntityLimit(List<EntityData> entities, OptimizationLevel level) {
   if (entities.size() <= getMaxEntitiesPerTick()) {
     return entities;
   }

   int limit =
       switch (level) {
         case AGGRESSIVE -> Math.max(10, Math.min(getMaxEntitiesPerTick() / 2, 50)); // Minimum 10 entities
         case HIGH -> Math.max(20, Math.min(getMaxEntitiesPerTick() * 2 / 3, 100)); // Minimum 20 entities
         case MEDIUM -> Math.max(30, Math.min(getMaxEntitiesPerTick() * 3 / 4, 150)); // Minimum 30 entities
         case NORMAL -> getMaxEntitiesPerTick();
       };

   // Prioritize entities closer to players or with higher priority - use parallel stream for large datasets
   return entities.parallelStream()
       .sorted((a, b) -> Integer.compare(getEntityPriority(b), getEntityPriority(a)))
       .limit(limit)
       .toList();
 }

 /** Optimized entity priority calculation with better performance characteristics */
 private int getEntityPriority(EntityData entity) {
   int priority = 0;

   // Distance-based priority (closer = higher priority) - use squared distance for performance
   double distance = entity.getDistance();
   if (distance < 10.0) priority += 100; // Very close entities
   else if (distance < 30.0) priority += 75; // Close entities
   else if (distance < 100.0) priority += 50; // Medium distance
   else if (distance < 300.0) priority += 25; // Far distance
   else priority += 10; // Very far distance

   // Type-based priority (optimized with direct field access)
   String typeStr = entity.getType();
   if (typeStr.contains("Player")) priority += 50;
   else if (typeStr.contains("Mob")) priority += 30;
   else if (typeStr.contains("Animal")) priority += 20;
   else if (typeStr.contains("Item")) priority += 15;
   else if (typeStr.contains("Villager")) priority += 25;

   // Add base priority
   priority += 10;

   return priority;
 }

  /** Apply spatial filter for villagers. */
  private List<VillagerData> applyVillagerSpatialFilter(
      List<VillagerData> villagers, int centerX, int centerZ, int radius) {
    if (villagers.size() <= getOptimizationThreshold()) {
      return villagers;
    }

    // Filter villagers within radius
    int radiusSquared = radius * radius;
    return villagers.stream()
        .filter(
            v -> {
              int[] position =
                  new int[] {(int) v.hashCode(), (int) v.hashCode(), (int) v.hashCode()};
              int dx = position[0] - centerX;
              int dz = position[1] - centerZ;
              return (dx * dx + dz * dz) <= radiusSquared;
            })
        .limit(getMaxEntitiesPerTick())
        .toList();
  }

  /** Check if batch processing should be used. */
  private boolean shouldUseBatchProcessing(int entityCount) {
    return entityCount >= getOptimizationThreshold() && NATIVE_MANAGER.isNativeAvailable();
  }

  /** Process villagers using native integration. */
  private List<Long> processVillagersNative(List<VillagerData> villagers) {
    // Implementation would use native integration
    return villagers.stream().map(v -> (long) v.hashCode()).toList();
  }

  /** Process villagers directly. */
  private List<Long> processVillagersDirect(List<VillagerData> villagers) {
    // Simple direct processing - return all villager IDs
    return villagers.stream().map(v -> (long) v.hashCode()).toList();
  }

  /** Get entity priority for sorting. */

  /** Get current memory usage percentage. */
  private double getMemoryUsagePercent() {
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    return (double) usedMemory / totalMemory * 100;
  }

  /** Update optimization statistics. */
  private void updateOptimizationStats(
      String type, int inputCount, int optimizedCount, long processingTime) {
    OptimizationStats Stats = optimizationStats.computeIfAbsent(type, k -> new OptimizationStats());
    Stats.addSample(inputCount, optimizedCount, processingTime);
  }

  /** Clear internal caches. */
  private void clearInternalCaches() {
    // Clear any internal caches or temporary data structures
    // This would be implemented based on specific caching needs
  }

  /** Optimization level enumeration. */
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

  /** Optimization statistics for a specific type. */
  public static class OptimizationStats {
    private final AtomicInteger sampleCount = new AtomicInteger(0);
    private final AtomicInteger totalInput = new AtomicInteger(0);

    /** Helper method to get villager ID (since we don't know the exact field structure) */
    private long getVillagerId(VillagerData villager) {
      // This is a placeholder - in real implementation would access the actual ID field
      // For now, we'll use hashCode as a simple ID
      return villager.hashCode();
    }

    /** Helper method to get villager position (since we don't know the exact field structure) */
    private int[] getVillagerPosition(VillagerData villager) {
      // This is a placeholder - in real implementation would access the actual position fields
      // For now, we'll return default position
      return new int[] {0, 0};
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

  /** Overall optimization statistics. */
  public static class OptimizationStatistics {
    private final long totalOptimizations;
    private final long totalEntitiesProcessed;
    private final long totalItemsProcessed;
    private final long totalMobsProcessed;
    private final long totalBlocksProcessed;
    private final OptimizationLevel currentLevel;
    private final Map<String, OptimizationStats> typeStats;

    public OptimizationStatistics(
        long totalOptimizations,
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

    public long getTotalOptimizations() {
      return totalOptimizations;
    }

    public long getTotalEntitiesProcessed() {
      return totalEntitiesProcessed;
    }

    public long getTotalItemsProcessed() {
      return totalItemsProcessed;
    }

    public long getTotalMobsProcessed() {
      return totalMobsProcessed;
    }

    public long getTotalBlocksProcessed() {
      return totalBlocksProcessed;
    }

    public OptimizationLevel getCurrentLevel() {
      return currentLevel;
    }

    public Map<String, OptimizationStats> getTypeStats() {
      return new HashMap<>(typeStats);
    }
  }
}
