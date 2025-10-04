package com.kneaf.core.performance.core;

import com.kneaf.core.KneafCore;
import com.kneaf.core.binary.ManualSerializers;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.entity.VillagerData;
// cleaned: removed some unused imports
import com.kneaf.core.protocol.ProtocolProcessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handles entity processing operations including batch processing and optimization. */
@SuppressWarnings({"deprecation", "unused"})
public class EntityProcessor {

  private final ProtocolProcessor protocolProcessor;
  private final PerformanceMonitor monitor;
  private final NativeBridgeProvider bridgeProvider;
  private long tickCount = 0;

  public EntityProcessor(NativeBridgeProvider bridgeProvider, PerformanceMonitor monitor) {
    this.bridgeProvider = bridgeProvider;
    this.monitor = monitor;
    this.protocolProcessor = new ProtocolProcessor();
  }

  /** Process entities and return list of entity IDs that should be ticked. */
  public List<Long> processEntities(List<EntityData> entities, List<PlayerData> players) {
    long startTime = System.currentTimeMillis();

    try {
      // Use protocol processor for unified binary/JSON processing
      ProtocolProcessor.ProtocolResult<List<Long>> result =
          protocolProcessor.processWithFallback(
              new EntityInput(entities, players),
              "Entity processing",
              new ProtocolProcessor.BinarySerializer<EntityInput>() {
                @Override
                public ByteBuffer serialize(EntityInput input) throws Exception {
                  return ManualSerializers.serializeEntityInput(
                      tickCount++, input.entities, input.players);
                }
              },
              new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(ByteBuffer inputBuffer) throws Exception {
                  return bridgeProvider.processEntitiesBinary(inputBuffer);
                }
              },
              new ProtocolProcessor.BinaryDeserializer<List<Long>>() {
                @Override
                public List<Long> deserialize(byte[] resultBytes) throws Exception {
                  if (resultBytes != null) {
                    return PerformanceUtils.parseEntityResultWithFallback(resultBytes);
                  }
                  return new ArrayList<>();
                }
              },
              new ProtocolProcessor.JsonInputPreparer<EntityInput>() {
                @Override
                public Map<String, Object> prepareInput(EntityInput jsonInput) {
                  return prepareEntityJsonInput(jsonInput);
                }
              },
              new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                  return bridgeProvider.processEntitiesJson(jsonInput);
                }
              },
              new ProtocolProcessor.JsonResultParser<List<Long>>() {
                @Override
                public List<Long> parseResult(String jsonResult) throws Exception {
                  return PerformanceUtils.parseEntityResultFromJson(jsonResult);
                }
              },
              new ArrayList<>());

      List<Long> processedEntities = result.getDataOrThrow();
      monitor.recordEntityProcessing(
          processedEntities.size(), System.currentTimeMillis() - startTime);
      return processedEntities;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing entities", e);
      return new ArrayList<>();
    }
  }

  /** Prepare JSON input for entity processing. */
  private Map<String, Object> prepareEntityJsonInput(EntityInput input) {
    Map<String, Object> jsonInput = new HashMap<>();
    jsonInput.put(PerformanceConstants.TICK_COUNT_KEY, tickCount++);
    jsonInput.put(PerformanceConstants.ENTITIES_KEY, input.entities);
    jsonInput.put(PerformanceConstants.PLAYERS_KEY, input.players);

    // Add entity config (values adapt to current TPS / tick delay)
    Map<String, Object> config = new HashMap<>();
    config.put("closeRadius", getCloseRadius());
    config.put("mediumRadius", getMediumRadius());
    config.put("closeRate", getCloseRate());
    config.put("mediumRate", getMediumRate());
    config.put("farRate", getFarRate());
    config.put("useSpatialPartitioning", isSpatialPartitioningEnabled());

    // World bounds (example values)
    Map<String, Object> worldBounds = new HashMap<>();
    worldBounds.put("minX", -1000.0);
    worldBounds.put("minY", 0.0);
    worldBounds.put("minZ", -1000.0);
    worldBounds.put("maxX", 1000.0);
    worldBounds.put("maxY", 256.0);
    worldBounds.put("maxZ", 1000.0);
    config.put("worldBounds", worldBounds);

    config.put("quadtreeMaxEntities", getQuadtreeMaxEntities());
    config.put("quadtreeMaxDepth", getQuadtreeMaxDepth());
    jsonInput.put("entityConfig", config);

    return jsonInput;
  }

  // Dynamic entity processing configuration getters
  private double getCloseRadius() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return Math.max(8.0, 16.0 * (tps / 20.0));
  }

  private double getMediumRadius() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return Math.max(16.0, 32.0 * (tps / 20.0));
  }

  private double getCloseRate() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return Math.max(0.2, Math.min(1.0, tps / 20.0));
  }

  private double getMediumRate() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return Math.max(0.1, Math.min(0.8, (tps / 20.0) * 0.5));
  }

  private double getFarRate() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return Math.max(0.05, Math.min(0.5, (20.0 - tps) / 20.0 * 0.2 + 0.1));
  }

  private boolean isSpatialPartitioningEnabled() {
    // Disable spatial partitioning when TPS is very low to reduce overhead
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return tps >= 12.0;
  }

  private int getQuadtreeMaxEntities() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    int base = 1000;
    double factor = Math.max(0.5, Math.min(1.5, tps / 20.0));
    return Math.max(100, (int) (base * factor));
  }

  private int getQuadtreeMaxDepth() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    return tps > 18.0 ? 10 : (tps > 14.0 ? 8 : 6);
  }

  /** Process entity batch using optimized NativeBridge. */
  public void processEntityBatchOptimized(List<BatchRequest> batch) throws Exception {
    if (batch.isEmpty()) return;

    // Pre-size collections to avoid resizing
    int totalEntities = 0;
    int totalPlayers = 0;
    for (BatchRequest req : batch) {
      EntityInput input = (EntityInput) req.data;
      totalEntities += input.entities.size();
      totalPlayers += input.players.size();
    }

    // Extract data from all requests in batch
    List<EntityData> allEntities = new ArrayList<>(totalEntities);
    List<PlayerData> allPlayers = new ArrayList<>(totalPlayers);

    for (BatchRequest req : batch) {
      EntityInput input = (EntityInput) req.data;
      allEntities.addAll(input.entities);
      allPlayers.addAll(input.players);
    }

    // Process combined data using optimized method
    List<Long> results = processEntities(allEntities, allPlayers);

    // Create a Set for faster lookup
    Set<Long> resultSet = new HashSet<>(results);

    // Distribute results back to individual futures
    for (BatchRequest req : batch) {
      EntityInput input = (EntityInput) req.data;

      // Find results for this request
      List<Long> requestResults = new ArrayList<>();
      for (EntityData entity : input.entities) {
        if (resultSet.contains(entity.getId())) {
          requestResults.add(entity.getId());
        }
      }

      req.future.complete(requestResults);
    }
  }

  /** Helper class for entity input. */
  public static class EntityInput {
    public final List<EntityData> entities;
    public final List<PlayerData> players;

    public EntityInput(List<EntityData> entities, List<PlayerData> players) {
      this.entities = entities;
      this.players = players;
    }
  }

  /** Batch request wrapper. */
  public static class BatchRequest {
    public final String type;
    public final Object data;
    public final java.util.concurrent.CompletableFuture<Object> future;

    public BatchRequest(
        String type, Object data, java.util.concurrent.CompletableFuture<Object> future) {
      this.type = type;
      this.data = data;
      this.future = future;
    }
  }

  /** Process item entities using optimized batch processing. */
  public ItemProcessResult processItemEntities(
      List<com.kneaf.core.data.item.ItemEntityData> items) {
    long startTime = System.currentTimeMillis();

    try {
      // Use protocol processor for unified binary/JSON processing
      ProtocolProcessor.ProtocolResult<ItemProcessResult> result =
          protocolProcessor.processWithFallback(
              items,
              "Item processing",
              new ProtocolProcessor.BinarySerializer<
                  List<com.kneaf.core.data.item.ItemEntityData>>() {
                @Override
                public ByteBuffer serialize(List<com.kneaf.core.data.item.ItemEntityData> input)
                    throws Exception {
                  return ManualSerializers.serializeItemInput(tickCount, input);
                }
              },
              new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(ByteBuffer inputBuffer) throws Exception {
                  return bridgeProvider.processItemEntitiesBinary(inputBuffer);
                }
              },
              new ProtocolProcessor.BinaryDeserializer<ItemProcessResult>() {
                @Override
                public ItemProcessResult deserialize(byte[] resultBytes) throws Exception {
                  if (resultBytes != null) {
                    ByteBuffer resultBuffer = ByteBuffer.wrap(resultBytes);
                    List<com.kneaf.core.data.item.ItemEntityData> updatedItems =
                        ManualSerializers.deserializeItemProcessResult(resultBuffer);

                    // Convert to ItemProcessResult format
                    List<Long> removeList = new ArrayList<>();
                    List<PerformanceProcessor.ItemUpdate> updates = new ArrayList<>();

                    for (com.kneaf.core.data.item.ItemEntityData item : updatedItems) {
                      if (item.getCount() == 0) {
                        removeList.add(item.getId());
                      } else {
                        updates.add(
                            new PerformanceProcessor.ItemUpdate(item.getId(), item.getCount()));
                      }
                    }

                    return new ItemProcessResult(
                        removeList, updates.size(), removeList.size(), updates);
                  }
                  return new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>());
                }
              },
              new ProtocolProcessor.JsonInputPreparer<
                  List<com.kneaf.core.data.item.ItemEntityData>>() {
                @Override
                public Map<String, Object> prepareInput(
                    List<com.kneaf.core.data.item.ItemEntityData> jsonInput) {
                  Map<String, Object> input = new HashMap<>();
                  input.put(PerformanceConstants.ITEMS_KEY, jsonInput);
                  input.put(PerformanceConstants.TICK_COUNT_KEY, tickCount++);
                  return input;
                }
              },
              new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                  return bridgeProvider.processItemEntitiesJson(jsonInput);
                }
              },
              new ProtocolProcessor.JsonResultParser<ItemProcessResult>() {
                @Override
                public ItemProcessResult parseResult(String jsonResult) throws Exception {
                  PerformanceUtils.ItemParseResult parseResult =
                      PerformanceUtils.parseItemResultFromJson(jsonResult);

                  // Convert to ItemProcessResult format
                  List<PerformanceProcessor.ItemUpdate> updates = new ArrayList<>();
                  for (PerformanceUtils.ItemUpdateParseResult update :
                      parseResult.getItemUpdates()) {
                    updates.add(
                        new PerformanceProcessor.ItemUpdate(update.getId(), update.getNewCount()));
                  }

                  return new ItemProcessResult(
                      parseResult.getItemsToRemove(),
                      parseResult.getMergedCount(),
                      parseResult.getDespawnedCount(),
                      updates);
                }
              },
              new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>()));

      ItemProcessResult processedItems = result.getDataOrThrow();
      monitor.recordItemProcessing(
          items.size(),
          processedItems.getMergedCount(),
          processedItems.getDespawnedCount(),
          System.currentTimeMillis() - startTime);
      return processedItems;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing item entities", e);
      monitor.recordItemProcessing(items.size(), 0, 0, System.currentTimeMillis() - startTime);
      return new ItemProcessResult(new ArrayList<>(), 0, 0, new ArrayList<>());
    }
  }

  /** Process mob AI using optimized batch processing. */
  public MobProcessResult processMobAI(List<com.kneaf.core.data.entity.MobData> mobs) {
    long startTime = System.currentTimeMillis();

    try {
      // Use protocol processor for unified binary/JSON processing
      ProtocolProcessor.ProtocolResult<MobProcessResult> result =
          protocolProcessor.processWithFallback(
              mobs,
              "Mob processing",
              new ProtocolProcessor.BinarySerializer<List<com.kneaf.core.data.entity.MobData>>() {
                @Override
                public ByteBuffer serialize(List<com.kneaf.core.data.entity.MobData> input)
                    throws Exception {
                  return ManualSerializers.serializeMobInput(tickCount, input);
                }
              },
              new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(ByteBuffer inputBuffer) throws Exception {
                  return bridgeProvider.processMobAiBinary(inputBuffer);
                }
              },
              new ProtocolProcessor.BinaryDeserializer<MobProcessResult>() {
                @Override
                public MobProcessResult deserialize(byte[] resultBytes) throws Exception {
                  if (resultBytes != null) {
                    ByteBuffer resultBuffer =
                        ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
                    List<com.kneaf.core.data.entity.MobData> updatedMobs =
                        ManualSerializers.deserializeMobProcessResult(resultBuffer);

                    // For now, assume all returned mobs need AI simplification
                    List<Long> simplifyList = new ArrayList<>();
                    for (com.kneaf.core.data.entity.MobData mob : updatedMobs) {
                      simplifyList.add(mob.getId());
                    }

                    return new MobProcessResult(new ArrayList<>(), simplifyList);
                  }
                  return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
                }
              },
              new ProtocolProcessor.JsonInputPreparer<List<com.kneaf.core.data.entity.MobData>>() {
                @Override
                public Map<String, Object> prepareInput(
                    List<com.kneaf.core.data.entity.MobData> jsonInput) {
                  Map<String, Object> input = new HashMap<>();
                  input.put("mobs", jsonInput);
                  input.put(PerformanceConstants.TICK_COUNT_KEY, tickCount++);
                  return input;
                }
              },
              new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                  return bridgeProvider.processMobAiJson(jsonInput);
                }
              },
              new ProtocolProcessor.JsonResultParser<MobProcessResult>() {
                @Override
                public MobProcessResult parseResult(String jsonResult) throws Exception {
                  PerformanceUtils.MobParseResult parseResult =
                      PerformanceUtils.parseMobResultFromJson(jsonResult);
                  return new MobProcessResult(
                      parseResult.getDisableList(), parseResult.getSimplifyList());
                }
              },
              new MobProcessResult(new ArrayList<>(), new ArrayList<>()));

      MobProcessResult processedMobs = result.getDataOrThrow();
      monitor.recordMobProcessing(
          mobs.size(),
          processedMobs.getDisableList().size(),
          processedMobs.getSimplifyList().size(),
          System.currentTimeMillis() - startTime);
      return processedMobs;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing mob AI", e);
      monitor.recordMobProcessing(mobs.size(), 0, 0, System.currentTimeMillis() - startTime);
      return new MobProcessResult(new ArrayList<>(), new ArrayList<>());
    }
  }

  /** Process villager AI using optimized batch processing. */
  public List<Long> processVillagerAI(List<VillagerData> villagers) {
    long startTime = System.currentTimeMillis();

    try {
      // Use protocol processor for unified binary/JSON processing
      ProtocolProcessor.ProtocolResult<List<Long>> result =
          protocolProcessor.processWithFallback(
              villagers,
              "Villager processing",
              new ProtocolProcessor.BinarySerializer<List<VillagerData>>() {
                @Override
                public ByteBuffer serialize(List<VillagerData> input) throws Exception {
                  return ManualSerializers.serializeVillagerInput(tickCount, input);
                }
              },
              new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(ByteBuffer inputBuffer) throws Exception {
                  return bridgeProvider.processVillagerAiBinary(inputBuffer);
                }
              },
              new ProtocolProcessor.BinaryDeserializer<List<Long>>() {
                @Override
                public List<Long> deserialize(byte[] resultBytes) throws Exception {
                  if (resultBytes != null) {
                    // For now, return a simplified result - full deserialization would be
                    // implemented
                    List<Long> simplifyList = new ArrayList<>();
                    for (VillagerData villager : villagers) {
                      simplifyList.add((long) villager.hashCode());
                    }
                    return simplifyList;
                  }
                  return new ArrayList<>();
                }
              },
              new ProtocolProcessor.JsonInputPreparer<List<VillagerData>>() {
                @Override
                public Map<String, Object> prepareInput(List<VillagerData> jsonInput) {
                  Map<String, Object> input = new HashMap<>();
                  input.put("villagers", jsonInput);
                  input.put(PerformanceConstants.TICK_COUNT_KEY, tickCount++);
                  return input;
                }
              },
              new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                  return bridgeProvider.processVillagerAiJson(jsonInput);
                }
              },
              new ProtocolProcessor.JsonResultParser<List<Long>>() {
                @Override
                public List<Long> parseResult(String jsonResult) throws Exception {
                  // For now, return all villagers
                  List<Long> result = new ArrayList<>();
                  for (VillagerData villager : villagers) {
                    result.add((long) villager.hashCode());
                  }
                  return result;
                }
              },
              new ArrayList<Long>());

      List<Long> processedVillagers = result.getDataOrThrow();
      monitor.recordVillagerProcessing(
          villagers.size(),
          0,
          processedVillagers.size(),
          0,
          System.currentTimeMillis() - startTime);
      return processedVillagers;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing villager AI", e);
      List<Long> fallback = new ArrayList<>();
      for (VillagerData villager : villagers) {
        fallback.add((long) villager.hashCode());
      }
      monitor.recordVillagerProcessing(
          villagers.size(), 0, fallback.size(), 0, System.currentTimeMillis() - startTime);
      return fallback;
    }
  }

  /** Get block entities that should be ticked. */
  public List<Long> getBlockEntitiesToTick(
      List<com.kneaf.core.data.block.BlockEntityData> blockEntities) {
    long startTime = System.currentTimeMillis();

    try {
      // Use protocol processor for unified binary/JSON processing
      ProtocolProcessor.ProtocolResult<List<Long>> result =
          protocolProcessor.processWithFallback(
              blockEntities,
              "Block processing",
              new ProtocolProcessor.BinarySerializer<
                  List<com.kneaf.core.data.block.BlockEntityData>>() {
                @Override
                public ByteBuffer serialize(List<com.kneaf.core.data.block.BlockEntityData> input)
                    throws Exception {
                  return ManualSerializers.serializeBlockInput(tickCount++, input);
                }
              },
              new ProtocolProcessor.BinaryNativeCaller<byte[]>() {
                @Override
                public byte[] callNative(ByteBuffer inputBuffer) throws Exception {
                  return bridgeProvider.processBlockEntitiesBinary(inputBuffer);
                }
              },
              new ProtocolProcessor.BinaryDeserializer<List<Long>>() {
                @Override
                public List<Long> deserialize(byte[] resultBytes) throws Exception {
                  if (resultBytes != null) {
                    // For now, return all block entities as the binary protocol
                    // doesn't return a specific list of entities to tick
                    List<Long> resultList = new ArrayList<>();
                    for (com.kneaf.core.data.block.BlockEntityData block : blockEntities) {
                      resultList.add(block.getId());
                    }
                    return resultList;
                  }
                  return new ArrayList<>();
                }
              },
              new ProtocolProcessor.JsonInputPreparer<
                  List<com.kneaf.core.data.block.BlockEntityData>>() {
                @Override
                public Map<String, Object> prepareInput(
                    List<com.kneaf.core.data.block.BlockEntityData> jsonInput) {
                  Map<String, Object> input = new HashMap<>();
                  input.put(PerformanceConstants.TICK_COUNT_KEY, tickCount++);
                  input.put("block_entities", jsonInput);
                  return input;
                }
              },
              new ProtocolProcessor.JsonNativeCaller<String>() {
                @Override
                public String callNative(String jsonInput) throws Exception {
                  return bridgeProvider.processBlockEntitiesJson(jsonInput);
                }
              },
              new ProtocolProcessor.JsonResultParser<List<Long>>() {
                @Override
                public List<Long> parseResult(String jsonResult) throws Exception {
                  return PerformanceUtils.parseBlockResultFromJson(jsonResult);
                }
              },
              new ArrayList<Long>());

      List<Long> processedBlocks = result.getDataOrThrow();
      monitor.recordBlockProcessing(processedBlocks.size(), System.currentTimeMillis() - startTime);
      return processedBlocks;

    } catch (Exception e) {
      KneafCore.LOGGER.error("Error processing block entities", e);
      List<Long> all = new ArrayList<>();
      for (com.kneaf.core.data.block.BlockEntityData block : blockEntities) {
        all.add(block.getId());
      }
      monitor.recordBlockProcessing(all.size(), System.currentTimeMillis() - startTime);
      return all;
    }
  }
}
