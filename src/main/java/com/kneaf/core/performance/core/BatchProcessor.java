package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.exceptions.AsyncProcessingException;
import com.kneaf.core.exceptions.OptimizedProcessingException;
import com.kneaf.core.performance.bridge.NativeBridgeUtils;
import java.nio.ByteBuffer;
// cleaned: removed unused imports
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/** Handles batch processing of performance operations with optimized memory management. */
@SuppressWarnings({"deprecation"})
public class BatchProcessor {

  private final ConcurrentLinkedQueue<BatchRequest> pendingRequests = new ConcurrentLinkedQueue<>();
  private final EntityProcessor entityProcessor;
  private final PerformanceMonitor monitor;
  private final NativeBridgeProvider bridgeProvider;

  private volatile boolean BATCH_PROCESSOR_RUNNING = false;
  private final Object batchLock = new Object();

  // Batch processing configuration (computed dynamically)

  public BatchProcessor(
      EntityProcessor entityProcessor,
      PerformanceMonitor monitor,
      NativeBridgeProvider bridgeProvider) {
    this.entityProcessor = entityProcessor;
    this.monitor = monitor;
    this.bridgeProvider = bridgeProvider;

    // Timeout is computed dynamically per-request from adaptive batch timeout (ms -> seconds)
  }

  /** Submit batch request and return result. */
  public <T> T submitBatchRequest(String type, Object data) {
    CompletableFuture<Object> future = new CompletableFuture<>();
    BatchRequest request = new BatchRequest(type, data, future);

    pendingRequests.offer(request);

    // Start batch processor if not running
    if (!BATCH_PROCESSOR_RUNNING) {
      startBatchProcessor();
    }

    try {
      // Calculate adaptive timeout per-request
      long timeoutMs = getBatchTimeoutMs();
      long timeoutSeconds = Math.max(1, TimeUnit.MILLISECONDS.toSeconds(timeoutMs));
      @SuppressWarnings("unchecked")
      T result = (T) future.get(timeoutSeconds, TimeUnit.SECONDS);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw AsyncProcessingException.batchRequestInterrupted(type, e);
    } catch (Exception e) {
      KneafCore.LOGGER.error("Batch request timeout or error for type: { }", type, e);
      // Fallback to direct processing
      @SuppressWarnings("unchecked")
      T result = (T) processIndividualRequest(type, data);
      return result;
    }
  }

  /** Start batch processor if not already running. */
  private void startBatchProcessor() {
    synchronized (batchLock) {
      if (BATCH_PROCESSOR_RUNNING) return;
      BATCH_PROCESSOR_RUNNING = true;

      CompletableFuture.runAsync(
          () -> {
            while (BATCH_PROCESSOR_RUNNING) {
              try {
                processBatchOptimized();
                Thread.sleep(getBatchProcessorSleepMs());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
              } catch (OptimizedProcessingException e) {
                KneafCore.LOGGER.error(
                    "Optimized processing error in batch processor: { }", e.getMessage(), e);
              } catch (Exception e) {
                KneafCore.LOGGER.error("Unexpected error in batch processor", e);
              }
            }
          });
    }
  }

  /** Process batch using optimized NativeBridge. */
  private void processBatchOptimized() throws OptimizedProcessingException {
    List<BatchRequest> batch = collectBatch();

    if (batch.isEmpty()) return;

    // Use NativeBridge for optimized batch processing if available
    if (bridgeProvider.isNativeAvailable()
        && batch.size() >= NativeBridgeUtils.calculateOptimalBatchSize(1, 25, 200)) {
      try {
        processBatchWithNativeBridge(batch);
        return;
      } catch (OptimizedProcessingException e) {
        KneafCore.LOGGER.warn(
            "NativeBridge batch processing failed, falling back to regular processing: { }",
            e.getMessage());
        // Fall through to regular processing
      }
    }

    // Regular batch processing
    Map<String, List<BatchRequest>> batchedByType = new HashMap<>();
    for (BatchRequest req : batch) {
      batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
    }
    processBatchedRequests(batchedByType);
  }

  /** Process batch using optimized NativeBridge. */
  private void processBatchWithNativeBridge(List<BatchRequest> batch)
      throws OptimizedProcessingException {
    Map<String, List<BatchRequest>> batchedByType = new HashMap<>();
    for (BatchRequest req : batch) {
      batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
    }

    // Process each type batch using NativeBridge
    for (Map.Entry<String, List<BatchRequest>> entry : batchedByType.entrySet()) {
      String type = entry.getKey();
      List<BatchRequest> typeBatch = entry.getValue();

      try {
        switch (type) {
          case PerformanceConstants.ENTITIES_KEY:
            entityProcessor.processEntityBatchOptimized(convertBatchRequests(typeBatch));
            break;
          case PerformanceConstants.ITEMS_KEY:
            processItemBatchOptimized(typeBatch);
            break;
          case PerformanceConstants.MOBS_KEY:
            processMobBatchOptimized(typeBatch);
            break;
          case PerformanceConstants.BLOCKS_KEY:
            processBlockBatchOptimized(typeBatch);
            break;
          default:
            // Fallback to individual processing
            for (BatchRequest req : typeBatch) {
              req.future.complete(processIndividualRequest(req.type, req.data));
            }
        }
      } catch (Exception e) {
        KneafCore.LOGGER.error("Error processing { } batch of size { }", type, typeBatch.size(), e);
        // Complete all futures with exception
        for (BatchRequest req : typeBatch) {
          req.future.completeExceptionally(e);
        }
      }
    }
  }

  /** Process item batch using optimized NativeBridge. */
  private void processItemBatchOptimized(List<BatchRequest> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<ItemEntityData> allItems = new ArrayList<>();
      for (BatchRequest req : batch) {
        @SuppressWarnings("unchecked")
        List<ItemEntityData> items = (List<ItemEntityData>) req.data;
        allItems.addAll(items);
      }

      ItemProcessResult result = processItemEntitiesDirect(allItems);

      // For simplicity, distribute results equally among batch requests
      for (BatchRequest req : batch) {
        req.future.complete(result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processItemBatchOptimized", "Failed to process item batch of size " + batch.size(), e);
    }
  }

  /** Process mob batch using optimized NativeBridge. */
  private void processMobBatchOptimized(List<BatchRequest> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<MobData> allMobs = new ArrayList<>();
      for (BatchRequest req : batch) {
        @SuppressWarnings("unchecked")
        List<MobData> mobs = (List<MobData>) req.data;
        allMobs.addAll(mobs);
      }

      MobProcessResult result = processMobAIDirect(allMobs);

      // Distribute results equally among batch requests
      for (BatchRequest req : batch) {
        req.future.complete(result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processMobBatchOptimized", "Failed to process mob batch of size " + batch.size(), e);
    }
  }

  /** Process block batch using optimized NativeBridge. */
  private void processBlockBatchOptimized(List<BatchRequest> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<BlockEntityData> allBlocks = new ArrayList<>();
      for (BatchRequest req : batch) {
        @SuppressWarnings("unchecked")
        List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
        allBlocks.addAll(blocks);
      }

      List<Long> results = getBlockEntitiesToTickDirect(allBlocks);

      // Distribute results equally among batch requests
      for (BatchRequest req : batch) {
        req.future.complete(results);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processBlockBatchOptimized", "Failed to process block batch of size " + batch.size(), e);
    }
  }

  /** Collect batch requests with timeout. */
  private List<BatchRequest> collectBatch() {
    List<BatchRequest> batch = new ArrayList<>();
    BatchRequest request;

    // Collect batch with timeout
    long startTime = System.currentTimeMillis();
    boolean continueCollecting = true;
    while (continueCollecting
        && batch.size() < getBatchSize()
        && (System.currentTimeMillis() - startTime) < getBatchTimeoutMs()) {
      request = pendingRequests.poll();
      if (request != null) {
        batch.add(request);
      } else {
        // No more requests, break if we have some or wait a bit
        if (!batch.isEmpty()) {
          continueCollecting = false;
        } else {
          try {
            Thread.sleep(5); // Small wait for new requests
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            continueCollecting = false;
          }
        }
      }
    }

    return batch;
  }

  // Dynamic batch configuration getters
  private int getBatchSize() {
    // Scale batch size based on TPS and recent tick delay
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    long tickDelay =
        com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
    int base = PerformanceConstants.getAdaptiveBatchSize(tps, tickDelay);
    double tpsFactor = Math.max(0.5, Math.min(1.5, tps / 20.0));
    double delayFactor = 1.0;
    if (tickDelay > 50) delayFactor = Math.max(0.5, 50.0 / (double) tickDelay);
    return Math.max(
        1,
        (int)
            (NativeBridgeUtils.calculateOptimalBatchSize(base, 25, 200) * tpsFactor * delayFactor));
  }

  private long getBatchTimeoutMs() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    // If TPS low, wait longer to aggregate more requests
    long tickDelay =
        com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
    if (tps < 15.0)
      return Math.max(20, PerformanceConstants.getAdaptiveBatchTimeoutMs(tps, tickDelay) * 2);
    if (tps < 18.0)
      return Math.max(10, PerformanceConstants.getAdaptiveBatchTimeoutMs(tps, tickDelay));
    return PerformanceConstants.getAdaptiveBatchTimeoutMs(tps, tickDelay);
  }

  private int getBatchProcessorSleepMs() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    if (tps < 15.0)
      return Math.max(1, PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps) / 2);
    if (tps < 18.0) return PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps);
    return Math.max(1, PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps) * 2);
  }

  /** Process batch based on type. */
  private void processBatchedRequests(Map<String, List<BatchRequest>> batchedByType) {
    // Process each type batch
    for (Map.Entry<String, List<BatchRequest>> entry : batchedByType.entrySet()) {
      String type = entry.getKey();
      List<BatchRequest> typeBatch = entry.getValue();

      try {
        switch (type) {
          case PerformanceConstants.ENTITIES_KEY:
            entityProcessor.processEntityBatchOptimized(convertBatchRequests(typeBatch));
            break;
          case PerformanceConstants.ITEMS_KEY:
            processItemBatch(typeBatch);
            break;
          case PerformanceConstants.MOBS_KEY:
            processMobBatch(typeBatch);
            break;
          case PerformanceConstants.BLOCKS_KEY:
            processBlockBatch(typeBatch);
            break;
          default:
            // Fallback to individual processing
            for (BatchRequest req : typeBatch) {
              req.future.complete(processIndividualRequest(req.type, req.data));
            }
        }
      } catch (Exception e) {
        KneafCore.LOGGER.error("Error processing { } batch of size { }", type, typeBatch.size(), e);
        // Complete all futures with exception
        for (BatchRequest req : typeBatch) {
          req.future.completeExceptionally(e);
        }
      }
    }
  }

  /** Process item batch. */
  private void processItemBatch(List<BatchRequest> batch) {
    if (batch.isEmpty()) return;

    List<ItemEntityData> allItems = new ArrayList<>();
    for (BatchRequest req : batch) {
      @SuppressWarnings("unchecked")
      List<ItemEntityData> items = (List<ItemEntityData>) req.data;
      allItems.addAll(items);
    }

    ItemProcessResult result = processItemEntitiesDirect(allItems);

    // For simplicity, distribute results equally among batch requests
    for (BatchRequest req : batch) {
      req.future.complete(result);
    }
  }

  /** Process mob batch. */
  private void processMobBatch(List<BatchRequest> batch) {
    if (batch.isEmpty()) return;

    List<MobData> allMobs = new ArrayList<>();
    for (BatchRequest req : batch) {
      @SuppressWarnings("unchecked")
      List<MobData> mobs = (List<MobData>) req.data;
      allMobs.addAll(mobs);
    }

    MobProcessResult result = processMobAIDirect(allMobs);

    // Distribute results equally among batch requests
    for (BatchRequest req : batch) {
      req.future.complete(result);
    }
  }

  /** Process block batch. */
  private void processBlockBatch(List<BatchRequest> batch) {
    if (batch.isEmpty()) return;

    List<BlockEntityData> allBlocks = new ArrayList<>();
    for (BatchRequest req : batch) {
      @SuppressWarnings("unchecked")
      List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
      allBlocks.addAll(blocks);
    }

    List<Long> results = getBlockEntitiesToTickDirect(allBlocks);

    // Distribute results equally among batch requests
    for (BatchRequest req : batch) {
      req.future.complete(results);
    }
  }

  /** Process item entities directly. */
  private ItemProcessResult processItemEntitiesDirect(List<ItemEntityData> items) {
    long startTime = System.currentTimeMillis();

    try {
      // Use binary protocol if available, fallback to JSON
      if (bridgeProvider.isNativeAvailable()) {
        ByteBuffer inputBuffer = ManualSerializers.serializeItemInput(tickCount, items);
        byte[] resultBytes = bridgeProvider.processItemEntitiesBinary(inputBuffer);

        if (NativeBridgeUtils.isValidNativeResult(resultBytes)) {
          ByteBuffer resultBuffer = ByteBuffer.wrap(resultBytes);
          List<ItemEntityData> updatedItems =
              ManualSerializers.deserializeItemProcessResult(resultBuffer);

          // Convert to ItemProcessResult format
          List<Long> removeList = new ArrayList<>();
          List<PerformanceProcessor.ItemUpdate> updates = new ArrayList<>();

          for (ItemEntityData item : updatedItems) {
            if (item.getCount() == 0) {
              removeList.add(item.getId());
            } else {
              updates.add(new PerformanceProcessor.ItemUpdate(item.getId(), item.getCount()));
            }
          }

          ItemProcessResult result =
              new ItemProcessResult(removeList, updates.size(), removeList.size(), updates);

          monitor.recordItemProcessing(
              items.size(),
              updates.size(),
              removeList.size(),
              System.currentTimeMillis() - startTime);
          return result;
        }
      }

      // JSON fallback
      String jsonInput =
          new com.google.gson.Gson().toJson(Map.of(PerformanceConstants.ITEMS_KEY, items));
      String jsonResult = bridgeProvider.processItemEntitiesJson(jsonInput);

      if (NativeBridgeUtils.isValidJsonResult(jsonResult)) {
        PerformanceUtils.ItemParseResult parseResult =
            PerformanceUtils.parseItemResultFromJson(jsonResult);

        // Convert to ItemProcessResult format
        List<PerformanceProcessor.ItemUpdate> updates = new ArrayList<>();
        for (PerformanceUtils.ItemUpdateParseResult update : parseResult.getItemUpdates()) {
          updates.add(new PerformanceProcessor.ItemUpdate(update.getId(), update.getNewCount()));
        }

        ItemProcessResult result =
            new ItemProcessResult(
                parseResult.getItemsToRemove(),
                parseResult.getMergedCount(),
                parseResult.getDespawnedCount(),
                updates);

        monitor.recordItemProcessing(
            items.size(),
            (int) parseResult.getMergedCount(),
            (int) parseResult.getDespawnedCount(),
            System.currentTimeMillis() - startTime);
        return result;
      }

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing item entities", e);
    }

    // Fallback: no optimization
    monitor.recordItemProcessing(items.size(), 0, 0, System.currentTimeMillis() - startTime);
    return new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>());
  }

  /** Process mob AI directly. */
  private MobProcessResult processMobAIDirect(List<MobData> mobs) {
    long startTime = System.currentTimeMillis();

    try {
      // Use binary protocol if available, fallback to JSON
      if (bridgeProvider.isNativeAvailable()) {
        ByteBuffer inputBuffer = ManualSerializers.serializeMobInput(tickCount, mobs);
        byte[] resultBytes = bridgeProvider.processMobAiBinary(inputBuffer);

        if (NativeBridgeUtils.isValidNativeResult(resultBytes)) {
          ByteBuffer resultBuffer =
              ByteBuffer.wrap(resultBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
          List<MobData> updatedMobs = ManualSerializers.deserializeMobProcessResult(resultBuffer);

          // For now, assume all returned mobs need AI simplification
          List<Long> simplifyList = new ArrayList<>();
          for (MobData mob : updatedMobs) {
            simplifyList.add(mob.getId());
          }

          MobProcessResult result = new MobProcessResult(new ArrayList<>(), simplifyList);

          monitor.recordMobProcessing(
              mobs.size(), 0, simplifyList.size(), System.currentTimeMillis() - startTime);
          return result;
        }
      }

      // JSON fallback
      String jsonInput = new com.google.gson.Gson().toJson(Map.of("mobs", mobs));
      String jsonResult = bridgeProvider.processMobAiJson(jsonInput);

      if (NativeBridgeUtils.isValidJsonResult(jsonResult)) {
        PerformanceUtils.MobParseResult parseResult =
            PerformanceUtils.parseMobResultFromJson(jsonResult);

        MobProcessResult result =
            new MobProcessResult(parseResult.getDisableList(), parseResult.getSimplifyList());

        monitor.recordMobProcessing(
            mobs.size(),
            parseResult.getDisableList().size(),
            parseResult.getSimplifyList().size(),
            System.currentTimeMillis() - startTime);
        return result;
      }

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing mob AI", e);
    }

    // Fallback: no optimization
    monitor.recordMobProcessing(mobs.size(), 0, 0, System.currentTimeMillis() - startTime);
    return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
  }

  /** Get block entities to tick directly. */
  private List<Long> getBlockEntitiesToTickDirect(List<BlockEntityData> blockEntities) {
    long startTime = System.currentTimeMillis();

    try {
      // Use binary protocol if available, fallback to JSON
      if (bridgeProvider.isNativeAvailable()) {
        ByteBuffer inputBuffer = ManualSerializers.serializeBlockInput(tickCount++, blockEntities);
        byte[] resultBytes = bridgeProvider.processBlockEntitiesBinary(inputBuffer);

        if (NativeBridgeUtils.isValidNativeResult(resultBytes)) {
          // For now, return all block entities as the binary protocol
          // doesn't return a specific list of entities to tick
          List<Long> resultList = new ArrayList<>();
          for (BlockEntityData block : blockEntities) {
            resultList.add(block.getId());
          }

          monitor.recordBlockProcessing(resultList.size(), System.currentTimeMillis() - startTime);
          return resultList;
        }
      }

      // JSON fallback
      String jsonInput =
          new com.google.gson.Gson()
              .toJson(
                  Map.of(
                      PerformanceConstants.TICK_COUNT_KEY,
                      tickCount++,
                      "block_entities",
                      blockEntities));
      String jsonResult = bridgeProvider.processBlockEntitiesJson(jsonInput);

      if (NativeBridgeUtils.isValidJsonResult(jsonResult)) {
        List<Long> resultList = PerformanceUtils.parseBlockResultFromJson(jsonResult);
        monitor.recordBlockProcessing(resultList.size(), System.currentTimeMillis() - startTime);
        return resultList;
      }

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing block entities", e);
    }

    // Fallback: return all
    List<Long> all = new ArrayList<>();
    for (BlockEntityData e : blockEntities) {
      all.add(e.getId());
    }
    monitor.recordBlockProcessing(all.size(), System.currentTimeMillis() - startTime);
    return all;
  }

  /** Convert BatchProcessor.BatchRequest to EntityProcessor.BatchRequest. */
  private List<EntityProcessor.BatchRequest> convertBatchRequests(
      List<BatchRequest> batchRequests) {
    List<EntityProcessor.BatchRequest> converted = new ArrayList<>();
    for (BatchRequest req : batchRequests) {
      converted.add(new EntityProcessor.BatchRequest(req.type, req.data, req.future));
    }
    return converted;
  }

  /** Process request directly without batching. */
  private Object processIndividualRequest(String type, Object data) {
    // Fallback for unhandled types
    return null;
  }

  // Gson is intentionally omitted; JSON fallback uses local Gson instances to avoid shared state
  private long tickCount = 0;

  /** Batch request wrapper. */
  public static class BatchRequest {
    public final String type;
    public final Object data;
    public final CompletableFuture<Object> future;

    public BatchRequest(String type, Object data, CompletableFuture<Object> future) {
      this.type = type;
      this.data = data;
      this.future = future;
    }
  }
}
