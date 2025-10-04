package com.kneaf.core.performance.core;

import java.util.Map;

/** Interface for performance monitoring and metrics collection. */
public interface PerformanceMonitor {

  /** Record entity processing metrics. */
  void recordEntityProcessing(int entitiesProcessed, long processingTime);

  /** Record item processing metrics. */
  void recordItemProcessing(
      int itemsProcessed, long itemsMerged, long itemsDespawned, long processingTime);

  /** Record mob processing metrics. */
  void recordMobProcessing(
      int mobsProcessed, int mobsDisabledAI, int mobsSimplifiedAI, long processingTime);

  /** Record villager processing metrics. */
  void recordVillagerProcessing(
      int villagersProcessed,
      int villagersDisabledAI,
      int villagersSimplifiedAI,
      int villagersReducedPathfinding,
      long processingTime);

  /** Record block entity processing metrics. */
  void recordBlockProcessing(int blocksProcessed, long processingTime);

  /** Record batch processing metrics. */
  void recordBatchProcessing(int batchSize, long processingTime);

  /** Record native call metrics. */
  void recordNativeCall(String operation, long duration, boolean success);

  /** Get current TPS (Ticks Per Second). */
  double getCurrentTPS();

  /** Set current TPS. */
  void setCurrentTPS(double tps);

  /** Get total entities processed. */
  long getTotalEntitiesProcessed();

  /** Get total items processed. */
  long getTotalItemsProcessed();

  /** Get total mobs processed. */
  long getTotalMobsProcessed();

  /** Get total blocks processed. */
  long getTotalBlocksProcessed();

  /** Get total items merged. */
  long getTotalItemsMerged();

  /** Get total items despawned. */
  long getTotalItemsDespawned();

  /** Get performance statistics as a map. */
  Map<String, Object> getPerformanceStats();

  /** Reset all metrics. */
  void resetMetrics();

  /** Log performance summary. */
  void logPerformanceSummary();
}
