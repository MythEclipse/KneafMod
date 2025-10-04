package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
// cleaned: removed unused imports
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.performance.bridge.NativeIntegrationManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FACADE class that provides a unified interface to the performance optimization system. This class
 * delegates to the underlying components: EntityProcessor, BatchProcessor,
 * NativeIntegrationManager, and PerformanceOptimizer.
 */
public class RustPerformanceFacade {

  private static RustPerformanceFacade instance;

  private final EntityProcessor entityProcessor;
  private final BatchProcessor batchProcessor;
  private final NativeIntegrationManager NATIVE_MANAGER;
  private final PerformanceOptimizer optimizer;
  private final PerformanceMonitor monitor;

  private final AtomicLong tickCount = new AtomicLong(0);
  private volatile boolean initialized = false;

  private RustPerformanceFacade() {
    // Initialize components
    this.monitor =
        new PerformanceMonitor() {
          private final AtomicLong totalEntities = new AtomicLong(0);
          private final AtomicLong totalItems = new AtomicLong(0);
          private final AtomicLong totalMobs = new AtomicLong(0);
          private final AtomicLong totalBlocks = new AtomicLong(0);
          private final AtomicLong totalItemsMerged = new AtomicLong(0);
          private final AtomicLong totalItemsDespawned = new AtomicLong(0);
          private volatile double currentTPS = 20.0;

          @Override
          public void recordEntityProcessing(int inputCount, long processingTime) {
            totalEntities.addAndGet(inputCount);
          }

          @Override
          public void recordItemProcessing(
              int inputCount, long mergedCount, long despawnedCount, long processingTime) {
            totalItems.addAndGet(inputCount);
            totalItemsMerged.addAndGet(mergedCount);
            totalItemsDespawned.addAndGet(despawnedCount);
          }

          @Override
          public void recordMobProcessing(
              int inputCount, int disableCount, int simplifyCount, long processingTime) {
            totalMobs.addAndGet(inputCount);
          }

          @Override
          public void recordVillagerProcessing(
              int inputCount,
              int disableCount,
              int simplifyCount,
              int reducePathfindCount,
              long processingTime) {
            // Simple implementation - just count total villagers
            totalMobs.addAndGet(inputCount);
          }

          @Override
          public void recordBlockProcessing(int processedCount, long processingTime) {
            totalBlocks.addAndGet(processedCount);
          }

          @Override
          public void recordNativeCall(String methodName, long processingTime, boolean success) {
            // Simple implementation
          }

          @Override
          public void recordBatchProcessing(int batchSize, long processingTime) {
            // Simple implementation
          }

          @Override
          public long getTotalEntitiesProcessed() {
            return totalEntities.get();
          }

          @Override
          public long getTotalItemsProcessed() {
            return totalItems.get();
          }

          @Override
          public long getTotalMobsProcessed() {
            return totalMobs.get();
          }

          @Override
          public long getTotalBlocksProcessed() {
            return totalBlocks.get();
          }

          @Override
          public long getTotalItemsMerged() {
            return totalItemsMerged.get();
          }

          @Override
          public long getTotalItemsDespawned() {
            return totalItemsDespawned.get();
          }

          @Override
          public double getCurrentTPS() {
            return currentTPS;
          }

          @Override
          public void setCurrentTPS(double tps) {
            this.currentTPS = tps;
          }

          @Override
          public Map<String, Object> getPerformanceStats() {
            Map<String, Object> Stats = new java.util.HashMap<>();
            Stats.put("totalEntities", totalEntities.get());
            Stats.put("totalItems", totalItems.get());
            Stats.put("totalMobs", totalMobs.get());
            Stats.put("totalBlocks", totalBlocks.get());
            Stats.put("currentTPS", currentTPS);
            return Stats;
          }

          @Override
          public void logPerformanceSummary() {
            KneafCore.LOGGER.info(
                "Performance Summary - Entities: { }, Items: { }, Mobs: { }, Blocks: { }, TPS: { }",
                totalEntities.get(),
                totalItems.get(),
                totalMobs.get(),
                totalBlocks.get(),
                currentTPS);
          }

          @Override
          public void resetMetrics() {
            totalEntities.set(0);
            totalItems.set(0);
            totalMobs.set(0);
            totalBlocks.set(0);
            totalItemsMerged.set(0);
            totalItemsDespawned.set(0);
          }
        };
    this.NATIVE_MANAGER = new NativeIntegrationManager();
    this.entityProcessor = new EntityProcessor(NATIVE_MANAGER, monitor);
    this.batchProcessor = new BatchProcessor(entityProcessor, monitor, NATIVE_MANAGER);
    this.optimizer =
        new PerformanceOptimizer(monitor, NATIVE_MANAGER, entityProcessor, batchProcessor);
  }

  /** Get the singleton instance of RustPerformanceFacade. */
  public static synchronized RustPerformanceFacade getInstance() {
    if (instance == null) {
      instance = new RustPerformanceFacade();
    }
    return instance;
  }

  /** Initialize the performance system. */
  public synchronized void initialize() {
    if (initialized) {
      return;
    }

    try {
      KneafCore.LOGGER.info("Initializing RustPerformance FACADE");

      // Initialize native integration
      boolean nativeAvailable = NATIVE_MANAGER.initialize();

      if (nativeAvailable) {
        KneafCore.LOGGER.info("Native integration initialized successfully");
      } else {
        KneafCore.LOGGER.warn("Native integration not available, using Java fallback");
      }

      initialized = true;
      KneafCore.LOGGER.info("RustPerformance FACADE initialization complete");

    } catch (Exception e) {
      KneafCore.LOGGER.error("Failed to initialize RustPerformance FACADE", e);
      throw new RuntimeException("Failed to initialize performance system", e);
    }
  }

  /** Shutdown the performance system and cleanup resources. */
  public synchronized void shutdown() {
    if (!initialized) {
      return;
    }

    KneafCore.LOGGER.info("Shutting down RustPerformance FACADE");

    try {
      // Shutdown native integration manager
      NATIVE_MANAGER.shutdown();

      // Reset state
      tickCount.set(0);
      initialized = false;

      KneafCore.LOGGER.info("RustPerformance FACADE shutdown complete");

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error during shutdown", e);
    }
  }

  /** Get entities that should be ticked based on optimization criteria. */
  public List<Long> getEntitiesToTick(List<EntityData> entities, List<PlayerData> players) {
    ensureInitialized();

    try {
      return optimizer.optimizeEntities(entities, players);
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error getting entities to tick", e);
      // Fallback: return all entities
      return entities.stream().map(entity -> entity.getId()).toList();
    }
  }

  /** Process item entities for merging and optimization. */
  public ItemProcessResult processItemEntities(List<ItemEntityData> items) {
    ensureInitialized();

    try {
      // Use batch processing for large item collections
      if (items.size() >= 25 && NATIVE_MANAGER.isNativeAvailable()) {
        return batchProcessor.submitBatchRequest(PerformanceConstants.ITEMS_KEY, items);
      } else {
        // Direct processing for small collections
        return entityProcessor.processItemEntities(items);
      }
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing item entities", e);
      // Fallback: no optimization
      return new ItemProcessResult(new ArrayList<Long>(), 0, 0, new ArrayList<>());
    }
  }

  /** Process mob AI for optimization. */
  public MobProcessResult processMobAI(List<MobData> mobs) {
    ensureInitialized();

    try {
      // Use batch processing for large mob collections
      if (mobs.size() >= 25 && NATIVE_MANAGER.isNativeAvailable()) {
        return batchProcessor.submitBatchRequest(PerformanceConstants.MOBS_KEY, mobs);
      } else {
        // Direct processing for small collections
        return entityProcessor.processMobAI(mobs);
      }
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing mob AI", e);
      // Fallback: no optimization
      return new MobProcessResult(new ArrayList<Long>(), new ArrayList<Long>());
    }
  }

  /** Process villager AI for optimization. */
  public List<Long> processVillagerAI(List<VillagerData> villagers) {
    ensureInitialized();

    try {
      // Use batch processing for large villager collections
      if (villagers.size() >= 25 && NATIVE_MANAGER.isNativeAvailable()) {
        return batchProcessor.submitBatchRequest("villagers", villagers);
      } else {
        // Direct processing for small collections
        return entityProcessor.processVillagerAI(villagers);
      }
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing villager AI", e);
      // Fallback: no optimization
      // Create a simple VillagerProcessResult equivalent
      return new ArrayList<Long>();
    }
  }

  /** Get block entities that should be ticked. */
  public List<Long> getBlockEntitiesToTick(List<BlockEntityData> blockEntities) {
    ensureInitialized();

    try {
      // Use batch processing for large block entity collections
      if (blockEntities.size() >= 25 && NATIVE_MANAGER.isNativeAvailable()) {
        return batchProcessor.submitBatchRequest(PerformanceConstants.BLOCKS_KEY, blockEntities);
      } else {
        // Direct processing for small collections
        return entityProcessor.getBlockEntitiesToTick(blockEntities);
      }
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error getting block entities to tick", e);
      // Fallback: return all block entities
      return blockEntities.stream().map(block -> block.getId()).toList();
    }
  }

  /** Optimize villager processing with spatial awareness. */
  public List<Long> optimizeVillagers(
      List<VillagerData> villagers, int centerX, int centerZ, int radius) {
    ensureInitialized();

    try {
      return optimizer.optimizeVillagers(villagers, centerX, centerZ, radius);
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error optimizing villagers", e);
      // Fallback: return all villagers
      return villagers.stream().map(v -> (long) v.hashCode()).toList();
    }
  }

  /** Perform memory optimization. */
  public void optimizeMemory() {
    ensureInitialized();

    try {
      optimizer.optimizeMemory();
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error during memory optimization", e);
    }
  }

  /** Get performance statistics. */
  public PerformanceStatistics getPerformanceStatistics() {
    ensureInitialized();

    return new PerformanceStatistics(
        monitor.getTotalEntitiesProcessed(),
        monitor.getTotalItemsProcessed(),
        monitor.getTotalMobsProcessed(),
        monitor.getTotalBlocksProcessed(),
        monitor.getCurrentTPS(), // Use TPS instead of average tick time
        NATIVE_MANAGER.isNativeAvailable(),
        optimizer.getOptimizationStatistics());
  }

  /** Get memory statistics from native code. */
  public String getMemoryStats() {
    ensureInitialized();

    try {
      return NATIVE_MANAGER.getMemoryStats();
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error getting memory Stats", e);
      return "{\"error\": \"Failed to get memory Stats\"}";
    }
  }

  /** Get CPU statistics from native code. */
  public String getCpuStats() {
    ensureInitialized();

    try {
      return NATIVE_MANAGER.getCpuStats();
    } catch (Exception e) {
      KneafCore.LOGGER.error("Error getting CPU Stats", e);
      return "{\"error\": \"Failed to get CPU Stats\"}";
    }
  }

  /** Pre-generate nearby chunks asynchronously. */
  public CompletableFuture<Integer> preGenerateNearbyChunksAsync(
      int centerX, int centerZ, int radius) {
    ensureInitialized();

    return NATIVE_MANAGER.preGenerateNearbyChunksAsync(centerX, centerZ, radius);
  }

  /** Check if a chunk is generated. */
  public boolean isChunkGenerated(int x, int z) {
    ensureInitialized();

    return NATIVE_MANAGER.isChunkGenerated(x, z);
  }

  /** Get the count of generated chunks. */
  public long getGeneratedChunkCount() {
    ensureInitialized();

    return NATIVE_MANAGER.getGeneratedChunkCount();
  }

  /** Get current TPS. */
  public double getCurrentTPS() {
    return monitor.getCurrentTPS();
  }

  /** Set current TPS. */
  public void setCurrentTPS(double tps) {
    monitor.setCurrentTPS(tps);
  }

  /** Get native worker queue depth. */
  public int getNativeWorkerQueueDepth() {
    ensureInitialized();

    return NATIVE_MANAGER.getNativeWorkerQueueDepth();
  }

  /** Get native worker average processing time. */
  public double getNativeWorkerAvgProcessingMs() {
    ensureInitialized();

    return NATIVE_MANAGER.getNativeWorkerAvgProcessingMs();
  }

  /** Check if native library is available. */
  public boolean isNativeAvailable() {
    return NATIVE_MANAGER.isNativeAvailable();
  }

  /** Ensure the system is initialized. */
  private void ensureInitialized() {
    if (!initialized) {
      throw new IllegalStateException(
          "RustPerformance FACADE not initialized. Call initialize() first.");
    }
  }

  /** Performance statistics data class. */
  public static class PerformanceStatistics {
    private final long totalEntitiesProcessed;
    private final long totalItemsProcessed;
    private final long totalMobsProcessed;
    private final long totalBlocksProcessed;
    private final double averageTickTime;
    private final boolean nativeAvailable;
    private final PerformanceOptimizer.OptimizationStatistics optimizationStats;

    public PerformanceStatistics(
        long totalEntitiesProcessed,
        long totalItemsProcessed,
        long totalMobsProcessed,
        long totalBlocksProcessed,
        double averageTickTime,
        boolean nativeAvailable,
        PerformanceOptimizer.OptimizationStatistics optimizationStats) {
      this.totalEntitiesProcessed = totalEntitiesProcessed;
      this.totalItemsProcessed = totalItemsProcessed;
      this.totalMobsProcessed = totalMobsProcessed;
      this.totalBlocksProcessed = totalBlocksProcessed;
      this.averageTickTime = averageTickTime;
      this.nativeAvailable = nativeAvailable;
      this.optimizationStats = optimizationStats;
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

    public double getAverageTickTime() {
      return averageTickTime;
    }

    public boolean isNativeAvailable() {
      return nativeAvailable;
    }

    public PerformanceOptimizer.OptimizationStatistics getOptimizationStats() {
      return optimizationStats;
    }

    @Override
    public String toString() {
      return String.format(
          "PerformanceStatistics{entities=%d, items=%d, mobs=%d, blocks=%d, avgTickTime=%.2fms, native=%s}",
          totalEntitiesProcessed,
          totalItemsProcessed,
          totalMobsProcessed,
          totalBlocksProcessed,
          averageTickTime,
          nativeAvailable);
    }
  }
}
