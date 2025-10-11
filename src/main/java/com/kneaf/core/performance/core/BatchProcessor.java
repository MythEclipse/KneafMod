package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.exceptions.OptimizedProcessingException;
import com.kneaf.core.performance.bridge.NativeBridgeUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletionException;
import com.kneaf.core.performance.RustPerformance;

/** Handles batch processing of performance operations with optimized memory management. */
@SuppressWarnings({"deprecation"})
public class BatchProcessor {

  // Use a parameterized queue to avoid raw-type usage
  private final ConcurrentLinkedQueue<BatchRequest<?>> pendingRequests =
      new ConcurrentLinkedQueue<>();
  private final EntityProcessor entityProcessor;
  private final PerformanceMonitor monitor;
  private final NativeBridgeProvider bridgeProvider;
  // No runtime circuit-breaker dependency: use simple async execution for native bridge calls.

  private volatile boolean BATCH_PROCESSOR_RUNNING = false;
  private final Object batchLock = new Object();
  
  // Configuration
 private static final com.kneaf.core.performance.monitoring.PerformanceConfig CONFIG = com.kneaf.core.performance.monitoring.PerformanceConfig.load();

 // Entity collection optimizations
 private final AtomicInteger entityCollectionCounter = new AtomicInteger(0);
 private final Map<Long, EntityData> entityMemoryPool = new ConcurrentHashMap<>();
 private final AtomicLong poolSize = new AtomicLong(0);
 private final int MAX_POOL_SIZE;
 private final double SAMPLING_RATE;
 private final int CHUNK_SIZE;
 private final boolean ENABLE_OPTIMIZATIONS;
 private final boolean CHUNK_BASED_PARALLELISM;

 // JNI batch processing configuration - increased minimum from 25 to 100+
 private static final int DEFAULT_JNI_MIN_BATCH_SIZE = 100;
 private static final int DEFAULT_JNI_MAX_BATCH_SIZE = 500;
 private final int JNI_MIN_BATCH_SIZE;
 private final int JNI_MAX_BATCH_SIZE;
 private final boolean COMBINE_MULTIPLE_OPS;

  // Batch processing configuration (computed dynamically)

  public BatchProcessor(
      EntityProcessor entityProcessor,
      PerformanceMonitor monitor,
      NativeBridgeProvider bridgeProvider) {
    this.entityProcessor = entityProcessor;
    this.monitor = monitor;
    this.bridgeProvider = bridgeProvider;
    
    // Initialize entity collection optimizations
    this.ENABLE_OPTIMIZATIONS = CONFIG.isEntityCollectionOptimizationsEnabled();
    this.SAMPLING_RATE = CONFIG.getEntitySamplingRate();
    this.CHUNK_SIZE = CONFIG.getEntityChunkSize();
    this.MAX_POOL_SIZE = CONFIG.getEntityPoolSize();
    this.CHUNK_BASED_PARALLELISM = CONFIG.isChunkBasedParallelProcessing();
    
    // Initialize JNI batch processing configuration
    this.JNI_MIN_BATCH_SIZE = CONFIG.getJniMinimumBatchSize();
    this.JNI_MAX_BATCH_SIZE = CONFIG.getJniMaximumBatchSize();
    this.COMBINE_MULTIPLE_OPS = CONFIG.isCombineMultipleOperations();
 // No circuit-breaker initialization (resilience4j not required at runtime)
 }

  /**
   * Synchronous wrapper for existing call-sites that expect a blocking method.
   * Delegates to the async implementation and waits for completion.
   */
  private void processBatchWithNativeBridge(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    try {
      processBatchWithNativeBridgeAsync(batch).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof OptimizedProcessingException) throw (OptimizedProcessingException) cause;
      throw OptimizedProcessingException.nativeLibraryError(
          "processBatchWithNativeBridge", "Native bridge processing failed", e);
    }
  }

  /** Submit batch request and return future result without blocking. */
  public <T> CompletableFuture<T> submitBatchRequest(String type, Object data) {
    CompletableFuture<T> future = new CompletableFuture<>();
    BatchRequest<T> request = new BatchRequest<>(type, data, future);

    pendingRequests.offer(request);

    // Start batch processor if not running
    if (!BATCH_PROCESSOR_RUNNING) {
      startBatchProcessor();
    }

    // Return future immediately without blocking
    return future;
  }
  
  /** Submit batch request for List<Long> result type. */
  public CompletableFuture<List<Long>> submitLongListRequest(String type, Object data) {
    return submitBatchRequest(type, data);
  }
  
  /** Submit batch request for ItemProcessResult type. */
  public CompletableFuture<ItemProcessResult> submitItemRequest(String type, Object data) {
    return submitBatchRequest(type, data);
  }
  
  /** Submit batch request for MobProcessResult type. */
  public CompletableFuture<MobProcessResult> submitMobRequest(String type, Object data) {
    return submitBatchRequest(type, data);
  }

  /** Start batch processor if not already running. */
  private void startBatchProcessor() {
    synchronized (batchLock) {
      if (BATCH_PROCESSOR_RUNNING) return;
      BATCH_PROCESSOR_RUNNING = true;

      CompletableFuture.runAsync(() -> {
        while (BATCH_PROCESSOR_RUNNING) {
          try {
            // Process batch directly without unnecessary nested async
            processBatchOptimized();
            
            // Non-blocking sleep with proper interruption handling
            Thread.sleep(getBatchProcessorSleepMs());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }).exceptionally(ex -> {
        KneafCore.LOGGER.error("Error in batch processor main loop", ex);
        return null;
      });
    }
  }

  /** Process batch using optimized NativeBridge asynchronously. */
  private void processBatchOptimized() {
    List<BatchRequest<?>> batch = collectBatch();

    if (batch.isEmpty()) return;

    // Use NativeBridge for optimized batch processing if available
    if (bridgeProvider.isNativeAvailable()
        && batch.size() >= JNI_MIN_BATCH_SIZE) {
      try {
        // existing call sites expect a synchronous call; delegate to the async implementation and wait
        processBatchWithNativeBridge(batch);
      } catch (OptimizedProcessingException e) {
        KneafCore.LOGGER.warn(
            "NativeBridge batch processing failed, falling back to regular processing: {}",
            e.getMessage());
        // Fall through to regular processing directly (async handling already in place)
        Map<String, List<BatchRequest<?>>> batchedByType = new HashMap<>();
        for (BatchRequest<?> req : batch) {
          batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
        }
        processBatchedRequests(batchedByType);
      } catch (Exception e) {
        KneafCore.LOGGER.error("Unexpected error in native batch processing", e);
        completeBatchRequestsWithException(batch, e);
      }
      return;
    }

    // Regular batch processing directly (remove unnecessary nested async)
    try {
      Map<String, List<BatchRequest<?>>> batchedByType = new HashMap<>();
      for (BatchRequest<?> req : batch) {
        batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
      }
      processBatchedRequests(batchedByType);
    } catch (Exception e) {
      KneafCore.LOGGER.error("Unexpected error in regular batch processing", e);
      completeBatchRequestsWithException(batch, e);
    }
  }
  
  
  /** Complete all batch requests with exception asynchronously. */
  private void completeBatchRequestsWithException(List<BatchRequest<?>> batch, Throwable exception) {
    CompletableFuture.runAsync(() -> {
      for (BatchRequest<?> req : batch) {
        // completeExceptionally is safe to call on a typed CompletableFuture
        if (!req.future.isDone()) {
          req.future.completeExceptionally(exception);
        }
      }
    });
  }

  /** Process batch using optimized NativeBridge. */
  private CompletableFuture<Void> processBatchWithNativeBridgeAsync(List<BatchRequest<?>> batch) {
    // Run native bridge processing asynchronously. CircuitBreaker integration removed
    // to avoid compile-time API mismatches; failures are translated to OptimizedProcessingException.
    return CompletableFuture.runAsync(() -> {
          Map<String, List<BatchRequest<?>>> batchedByType = new HashMap<>();
          for (BatchRequest<?> req : batch) {
              batchedByType.computeIfAbsent(req.type, k -> new ArrayList<>()).add(req);
          }

          // Process each type batch using NativeBridge with combined operations support
          for (Map.Entry<String, List<BatchRequest<?>>> entry : batchedByType.entrySet()) {
              String type = entry.getKey();
              List<BatchRequest<?>> typeBatch = entry.getValue();

              try {
                  switch (type) {
                      case PerformanceConstants.ENTITIES_KEY:
                          if (COMBINE_MULTIPLE_OPS && typeBatch.size() > 1) {
                              processCombinedEntitiesBatch(typeBatch);
                          } else {
                              processEntitiesBatchOptimized(typeBatch);
                          }
                          break;
                      case PerformanceConstants.ITEMS_KEY:
                          if (COMBINE_MULTIPLE_OPS && typeBatch.size() > 1) {
                              processCombinedItemsBatch(typeBatch);
                          } else {
                              processItemBatchOptimized(typeBatch);
                          }
                          break;
                      case PerformanceConstants.MOBS_KEY:
                          if (COMBINE_MULTIPLE_OPS && typeBatch.size() > 1) {
                              processCombinedMobsBatch(typeBatch);
                          } else {
                              processMobBatchOptimized(typeBatch);
                          }
                          break;
                      case PerformanceConstants.BLOCKS_KEY:
                          if (COMBINE_MULTIPLE_OPS && typeBatch.size() > 1) {
                              processCombinedBlocksBatch(typeBatch);
                          } else {
                              processBlockBatchOptimized(typeBatch);
                          }
                          break;
                      default:
                          // Fallback to individual processing
                          for (BatchRequest<?> req : typeBatch) {
                              completeFutureAsync(req.future, processIndividualRequest(req.type, req.data));
                          }
                  }
              } catch (Exception e) {
                  KneafCore.LOGGER.error("Error processing {} batch of size {}", type, typeBatch.size(), e);
                  // Complete all futures with exception asynchronously
                  completeBatchFuturesWithExceptionAsync(typeBatch, e);
              }
          }
      });
  }

  private void processEntitiesBatchOptimized(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<EntityData> allEntities = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<EntityData> entities = (List<EntityData>) req.data;
        allEntities.addAll(entities);
      }

      List<Long> result;
      
      // Apply chunk-based parallel processing if enabled
      if (CHUNK_BASED_PARALLELISM && allEntities.size() > CHUNK_SIZE) {
        result = processEntitiesInChunks(allEntities);
      } else {
        // Original processing logic with entity pooling
        List<EntityData> optimizedEntities = new ArrayList<>();
        for (EntityData entity : allEntities) {
          EntityData pooledEntity = getFromEntityPool(entity.getId());
          optimizedEntities.add(pooledEntity != null ? pooledEntity : entity);
        }
        result = RustPerformance.getEntitiesToTick(optimizedEntities, new ArrayList<>());
      }
      
      // Store processed entities back in pool for reuse
      for (EntityData entity : allEntities) {
        putToEntityPool(entity.getId(), entity);
      }

      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processEntitiesBatchOptimized", "Failed to process entity batch of size " + batch.size(), e);
    }
  }
  
  /**
   * Process entities in chunks for parallel processing.
   */
  private List<Long> processEntitiesInChunks(List<EntityData> allEntities) {
    List<CompletableFuture<List<Long>>> futures = new ArrayList<>();
    List<Long> finalResult = new ArrayList<>();
    
    // Split entities into chunks
    int numChunks = (allEntities.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
    
    for (int i = 0; i < numChunks; i++) {
      final int chunkIndex = i;
      int start = chunkIndex * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, allEntities.size());
      final List<EntityData> chunk = allEntities.subList(start, end);
      
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          // Reuse entity data structures from pool when possible
          List<EntityData> optimizedChunk = new ArrayList<>();
          for (EntityData entity : chunk) {
            EntityData pooledEntity = getFromEntityPool(entity.getId());
            optimizedChunk.add(pooledEntity != null ? pooledEntity : entity);
          }
          
          return RustPerformance.getEntitiesToTick(optimizedChunk, new ArrayList<>());
        } catch (Exception e) {
          KneafCore.LOGGER.warn("Error processing chunk " + chunkIndex, e);
          return new ArrayList<>();
        }
      }));
    }
    
    // Wait for all chunks to complete and combine results
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    for (CompletableFuture<List<Long>> future : futures) {
      try {
        finalResult.addAll(future.get());
      } catch (Exception e) {
        KneafCore.LOGGER.warn("Error combining chunk results", e);
      }
    }
    
    return finalResult;
  }

  /** Process item batch using optimized NativeBridge. */
  private void processItemBatchOptimized(List<BatchRequest<?>> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<ItemEntityData> allItems = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<ItemEntityData> items = (List<ItemEntityData>) req.data;
        allItems.addAll(items);
      }

      ItemProcessResult result = processItemEntitiesDirect(allItems);

      // For simplicity, distribute results equally among batch requests
      for (BatchRequest<?> req : batch) {
        // use helper that performs an unchecked-complete under a single suppressed cast
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processItemBatchOptimized", "Failed to process item batch of size " + batch.size(), e);
    }
  }

  /** Process mob batch using optimized NativeBridge. */
  private void processMobBatchOptimized(List<BatchRequest<?>> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<MobData> allMobs = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<MobData> mobs = (List<MobData>) req.data;
        allMobs.addAll(mobs);
      }

      MobProcessResult result = processMobAIDirect(allMobs);

      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processMobBatchOptimized", "Failed to process mob batch of size " + batch.size(), e);
    }
  }

  /** Process block batch using optimized NativeBridge. */
  private void processBlockBatchOptimized(List<BatchRequest<?>> batch)
      throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<BlockEntityData> allBlocks = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
        allBlocks.addAll(blocks);
      }

      List<Long> results = getBlockEntitiesToTickDirect(allBlocks);

      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, results);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processBlockBatchOptimized", "Failed to process block batch of size " + batch.size(), e);
    }
  }
  /** Process combined entities batch in a single JNI call. */
  private void processCombinedEntitiesBatch(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<EntityData> allEntities = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<EntityData> entities = (List<EntityData>) req.data;
        allEntities.addAll(entities);
      }

      // Apply chunk-based parallel processing if enabled
      List<Long> result;
      if (CHUNK_BASED_PARALLELISM && allEntities.size() > CHUNK_SIZE) {
        result = processEntitiesInChunks(allEntities);
      } else {
        // Combined processing: optimize entities and process in single JNI call
        List<EntityData> optimizedEntities = new ArrayList<>();
        for (EntityData entity : allEntities) {
          EntityData pooledEntity = getFromEntityPool(entity.getId());
          optimizedEntities.add(pooledEntity != null ? pooledEntity : entity);
        }
        result = RustPerformance.getEntitiesToTick(optimizedEntities, new ArrayList<>());
      }

      // Store processed entities back in pool for reuse
      for (EntityData entity : allEntities) {
        putToEntityPool(entity.getId(), entity);
      }

      // Distribute results to all batch requests
      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processCombinedEntitiesBatch", "Failed to process combined entity batch of size " + batch.size(), e);
    }
  }

  /** Process combined items batch in a single JNI call. */
  private void processCombinedItemsBatch(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<ItemEntityData> allItems = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<ItemEntityData> items = (List<ItemEntityData>) req.data;
        allItems.addAll(items);
      }

      // Process all items in a single JNI call
      ItemProcessResult result = processItemEntitiesDirect(allItems);

      // Distribute results to all batch requests
      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processCombinedItemsBatch", "Failed to process combined item batch of size " + batch.size(), e);
    }
  }

  /** Process combined mobs batch in a single JNI call. */
  private void processCombinedMobsBatch(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<MobData> allMobs = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<MobData> mobs = (List<MobData>) req.data;
        allMobs.addAll(mobs);
      }

      // Process all mobs in a single JNI call
      MobProcessResult result = processMobAIDirect(allMobs);

      // Distribute results to all batch requests
      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, result);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processCombinedMobsBatch", "Failed to process combined mob batch of size " + batch.size(), e);
    }
  }

  /** Process combined blocks batch in a single JNI call. */
  private void processCombinedBlocksBatch(List<BatchRequest<?>> batch) throws OptimizedProcessingException {
    if (batch.isEmpty()) return;

    try {
      List<BlockEntityData> allBlocks = new ArrayList<>();
      for (BatchRequest<?> req : batch) {
        List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
        allBlocks.addAll(blocks);
      }

      // Process all blocks in a single JNI call
      List<Long> results = getBlockEntitiesToTickDirect(allBlocks);

      // Distribute results to all batch requests
      for (BatchRequest<?> req : batch) {
        completeFutureAsync(req.future, results);
      }
    } catch (Exception e) {
      throw OptimizedProcessingException.batchProcessingError(
          "processCombinedBlocksBatch", "Failed to process combined block batch of size " + batch.size(), e);
    }
  }

  /** Collect all pending requests without timeout waiting. */
  private List<BatchRequest<?>> collectBatch() {
    List<BatchRequest<?>> batch = new ArrayList<>();
    BatchRequest<?> request;

    // Collect all available requests up to batch size limit
    int targetSize = getBatchSize();
    
    // Apply entity collection optimizations if enabled
    if (ENABLE_OPTIMIZATIONS) {
      batch = collectBatchWithOptimizations(targetSize);
    } else {
      // Original collection logic
      while (batch.size() < targetSize && (request = pendingRequests.poll()) != null) {
        batch.add(request);
      }

      // If we collected some requests, return immediately
      if (!batch.isEmpty()) {
        return batch;
      }

      // If no requests available, wait briefly but don't block indefinitely
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      // Try one more time to collect any requests that arrived during the sleep
      while (batch.size() < targetSize && (request = pendingRequests.poll()) != null) {
        batch.add(request);
      }
    }

    return batch;
  }
  
  /**
   * Collect batch with entity collection optimizations enabled.
   * Implements sampling and chunk-based parallel processing.
   */
  private List<BatchRequest<?>> collectBatchWithOptimizations(int targetSize) {
    List<BatchRequest<?>> batch = new ArrayList<>();
    BatchRequest<?> request;
    
    // Apply sampling if rate is less than 1.0
    if (SAMPLING_RATE < 1.0) {
      int sampleSize = (int) Math.max(1, targetSize * SAMPLING_RATE);
      int sampleInterval = (int) Math.max(1, 1.0 / SAMPLING_RATE);
      
      while (batch.size() < sampleSize && (request = pendingRequests.poll()) != null) {
        // Use counter to implement proper sampling
        if (entityCollectionCounter.incrementAndGet() % sampleInterval == 0) {
          batch.add(request);
        }
      }
    } else {
      // Full collection if sampling rate is 1.0 (no sampling)
      while (batch.size() < targetSize && (request = pendingRequests.poll()) != null) {
        batch.add(request);
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
            (NativeBridgeUtils.calculateOptimalBatchSize(base, JNI_MIN_BATCH_SIZE, JNI_MAX_BATCH_SIZE) * tpsFactor * delayFactor));
  }

  private int getBatchProcessorSleepMs() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    if (tps < 15.0)
      return Math.max(1, PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps) / 2);
    if (tps < 18.0) return PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps);
    return Math.max(1, PerformanceConstants.getAdaptiveBatchProcessorSleepMs(tps) * 2);
  }

  /** Process batch based on type with asynchronous error handling. */
  private void processBatchedRequests(Map<String, List<BatchRequest<?>>> batchedByType) {
    // Process each type batch asynchronously
    List<CompletableFuture<?>> processingTasks = new ArrayList<>();
    
    for (Map.Entry<String, List<BatchRequest<?>>> entry : batchedByType.entrySet()) {
      String type = entry.getKey();
      List<BatchRequest<?>> typeBatch = entry.getValue();

      CompletableFuture.runAsync(() -> {
        try {
          switch (type) {
            case PerformanceConstants.ENTITIES_KEY:
              entityProcessor.processEntityBatchOptimized(convertBatchRequests(typeBatch));
              break;
            case PerformanceConstants.ITEMS_KEY:
              processItemBatchAsync(typeBatch);
              break;
            case PerformanceConstants.MOBS_KEY:
              processMobBatchAsync(typeBatch);
              break;
            case PerformanceConstants.BLOCKS_KEY:
              processBlockBatchAsync(typeBatch);
              break;
            default:
              // Fallback to individual processing (already uses async completion)
              for (BatchRequest<?> req : typeBatch) {
                completeFutureAsync(req.future, processIndividualRequest(req.type, req.data));
              }
          }
        } catch (Exception e) {
          KneafCore.LOGGER.error("Error processing {} batch of size {}", type, typeBatch.size(), e);
          // Complete all futures with exception asynchronously
          completeBatchFuturesWithExceptionAsync(typeBatch, e);
        }
      });
    }
    
    // Wait for all processing tasks to complete (safe even with empty list)
    if (!processingTasks.isEmpty()) {
      CompletableFuture.allOf(processingTasks.toArray(new CompletableFuture[0])).join();
    }
  }
  
  /** Process item batch asynchronously. */
  private void processItemBatchAsync(List<BatchRequest<?>> batch) {
    if (batch.isEmpty()) return;

    List<ItemEntityData> allItems = new ArrayList<>();
    for (BatchRequest<?> req : batch) {
      List<ItemEntityData> items = (List<ItemEntityData>) req.data;
      allItems.addAll(items);
    }

    ItemProcessResult result = processItemEntitiesDirect(allItems);

    for (BatchRequest<?> req : batch) {
      completeFutureAsync(req.future, result);
    }
  }
  
  /** Process mob batch asynchronously. */
  private void processMobBatchAsync(List<BatchRequest<?>> batch) {
    if (batch.isEmpty()) return;

    List<MobData> allMobs = new ArrayList<>();
    for (BatchRequest<?> req : batch) {
      List<MobData> mobs = (List<MobData>) req.data;
      allMobs.addAll(mobs);
    }

    MobProcessResult result = processMobAIDirect(allMobs);

    for (BatchRequest<?> req : batch) {
      completeFutureAsync(req.future, result);
    }
  }
  
  /** Process block batch asynchronously. */
  private void processBlockBatchAsync(List<BatchRequest<?>> batch) {
    if (batch.isEmpty()) return;

    List<BlockEntityData> allBlocks = new ArrayList<>();
    for (BatchRequest<?> req : batch) {
      List<BlockEntityData> blocks = (List<BlockEntityData>) req.data;
      allBlocks.addAll(blocks);
    }

    List<Long> results = getBlockEntitiesToTickDirect(allBlocks);

    for (BatchRequest<?> req : batch) {
      completeFutureAsync(req.future, results);
    }
  }
  
  /** Complete future asynchronously. */
  private void completeFutureAsync(CompletableFuture<?> future, Object result) {
    CompletableFuture<Object> f = (CompletableFuture<Object>) future;
    CompletableFuture.runAsync(() -> {
      if (!f.isDone()) {
        f.complete(result);
      } else {
        KneafCore.LOGGER.warn("Attempted to complete already done future, skipping");
      }
    });
  }
  
  /**
   * Get entity data from memory pool (object pooling).
   */
  private EntityData getFromEntityPool(long entityId) {
    return entityMemoryPool.get(entityId);
  }
  
  /**
   * Put entity data into memory pool with size control.
   */
  private void putToEntityPool(long entityId, EntityData entityData) {
    if (poolSize.get() >= MAX_POOL_SIZE) {
      // Evict least recently used entity (simple implementation)
      evictFromEntityPool();
    }
    entityMemoryPool.put(entityId, entityData);
    poolSize.incrementAndGet();
  }
  
  /**
   * Evict entity from memory pool (simple implementation).
   */
  private void evictFromEntityPool() {
    if (entityMemoryPool.isEmpty()) return;
    
    // Simple eviction: remove first entry (could be enhanced with LRU strategy)
    Map.Entry<Long, EntityData> entry = entityMemoryPool.entrySet().iterator().next();
    entityMemoryPool.remove(entry.getKey());
    poolSize.decrementAndGet();
  }
  
  /**
   * Clear entity memory pool.
   */
  public void clearEntityPool() {
    entityMemoryPool.clear();
    poolSize.set(0);
  }
  
  /** Complete all batch futures with exception asynchronously. */
  private void completeBatchFuturesWithExceptionAsync(List<BatchRequest<?>> batch, Throwable exception) {
    CompletableFuture.runAsync(() -> {
      for (BatchRequest<?> req : batch) {
        if (!req.future.isDone()) {
          req.future.completeExceptionally(exception);
        }
      }
    });
  }


  /** Process item entities directly. */
  private ItemProcessResult processItemEntitiesDirect(List<ItemEntityData> items) {
    long startTime = System.currentTimeMillis();

    try {
      // Use binary protocol if available, fallback to JSON
      if (bridgeProvider.isNativeAvailable()) {
        ByteBuffer inputBuffer = ManualSerializers.serializeItemInput(tickCount.get(), items);
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
        ByteBuffer inputBuffer = ManualSerializers.serializeMobInput(tickCount.get(), mobs);
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
        long currentTick = tickCount.incrementAndGet();
        KneafCore.LOGGER.debug("Incrementing tickCount to {} for block processing", currentTick);
        ByteBuffer inputBuffer = ManualSerializers.serializeBlockInput(currentTick, blockEntities);
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
      long currentTick = tickCount.incrementAndGet();
      KneafCore.LOGGER.debug("Incrementing tickCount to {} for block JSON processing", currentTick + 1);
      String jsonInput =
          new com.google.gson.Gson()
              .toJson(
                  Map.of(
                      PerformanceConstants.TICK_COUNT_KEY,
                      currentTick,
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
      List<BatchRequest<?>> batchRequests) {
    List<EntityProcessor.BatchRequest> converted = new ArrayList<>();
    for (BatchRequest<?> req : batchRequests) {
      // cast future to a typed CompletableFuture<Object> for EntityProcessor compatibility
      CompletableFuture<Object> f = (CompletableFuture<Object>) req.future;
      converted.add(new EntityProcessor.BatchRequest(req.type, req.data, f));
    }
    return converted;
  }

  /** Process request directly without batching. */
  private Object processIndividualRequest(String type, Object data) {
    // Fallback for unhandled types
    return null;
  }

  // Gson is intentionally omitted; JSON fallback uses local Gson instances to avoid shared state
  private final AtomicLong tickCount = new AtomicLong(0);

  /** Batch request wrapper with generic type support. */
  public static class BatchRequest<T> {
    public final String type;
    public final Object data;
    public final CompletableFuture<T> future;

    public BatchRequest(String type, Object data, CompletableFuture<T> future) {
      this.type = type;
      this.data = data;
      this.future = future;
    }
  }
}
