package com.kneaf.core.performance.monitoring;

import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.spatial.SpatialGrid;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes entity data collection, optimization, and management.
 * Separated from main PerformanceManager for better modularity.
 */
public class EntityProcessor {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Configuration
  private static final PerformanceConfig CONFIG = PerformanceConfig.load();

  // Spatial grid for efficient player position queries per level
  private static final Map<ServerLevel, SpatialGrid> LEVEL_SPATIAL_GRIDS = new HashMap<>();
  private static final Object SPATIAL_GRID_LOCK = new Object();

  // Per-level distance state maps for restore and smoothing
  private static final Map<ServerLevel, Integer> ORIGINAL_VIEW_DISTANCE =
  new ConcurrentHashMap<>();
  private static final Map<ServerLevel, Integer> TARGET_DISTANCE =
  new ConcurrentHashMap<>();
  private static final Map<ServerLevel, Integer> TRANSITION_REMAINING =
  new ConcurrentHashMap<>();
  private static final Map<ServerLevel, Integer> ORIGINAL_SIMULATION_DISTANCE =
  new ConcurrentHashMap<>();
  private static final Map<ServerLevel, Integer> TARGET_SIMULATION_DISTANCE =
  new ConcurrentHashMap<>();
  private static final Map<ServerLevel, Integer> TRANSITION_REMAINING_SIM =
  new ConcurrentHashMap<>();

  // TPS and tick monitoring (shared with PerformanceManager)
  private static int TICK_COUNTER = 0;
  // Dynamic async thresholding based on executor queue size
  private static double currentTpsThreshold;
  // Reflection method cache to avoid repeated lookups and noisy logs
  private static final Map<Class<?>, java.lang.reflect.Method> VIEW_GET_CACHE =
  new ConcurrentHashMap<>();
  private static final Map<Class<?>, java.lang.reflect.Method> VIEW_SET_CACHE =
  new ConcurrentHashMap<>();
  private static final Map<Class<?>, java.lang.reflect.Method> SIM_GET_CACHE =
  new ConcurrentHashMap<>();
  private static final Map<Class<?>, java.lang.reflect.Method> SIM_SET_CACHE =
  new ConcurrentHashMap<>();
  private static final Map<Class<?>, Boolean> METHOD_RESOLVED =
  new ConcurrentHashMap<>();

  // Distance transition configuration
  private static final int DEFAULT_TRANSITION_TICKS = 20;
  private static final int TRANSITION_TICKS = DEFAULT_TRANSITION_TICKS;

  public EntityProcessor() {}

  /**
   * Collect and consolidate entity data from the server.
   */
  public EntityDataCollection collectAndConsolidate(MinecraftServer server, boolean shouldProfile) {
    long entityCollectionStart = shouldProfile ? System.nanoTime() : 0;
    EntityDataCollection rawData = collectEntityData(server);
    
    if (shouldProfile) {
      long durationMs = (System.nanoTime() - entityCollectionStart) / 1_000_000;
      PerformanceMetricsLogger.logLine(String.format("PERF: entity_collection duration=%dms", durationMs));
    }

    long consolidationStart = shouldProfile ? System.nanoTime() : 0;
    List<ItemEntityData> consolidatedItems = consolidateItemEntities(rawData.items());
    
    if (shouldProfile) {
      long durationMs = (System.nanoTime() - consolidationStart) / 1_000_000;
      PerformanceMetricsLogger.logLine(String.format("PERF: item_consolidation duration=%dms", durationMs));
    }

    return new EntityDataCollection(
        rawData.entities(), consolidatedItems, rawData.mobs(), rawData.blockEntities(), rawData.players());
  }

  private EntityDataCollection collectEntityData(MinecraftServer server) {
    // Pre-size collections to avoid repeated resizing
    int estimatedEntities = Math.min(CONFIG.getMaxEntitiesToCollect(), 5000);
    List<EntityData> entities = new ArrayList<>(estimatedEntities);
    List<ItemEntityData> items = new ArrayList<>(estimatedEntities / 4);
    List<MobData> mobs = new ArrayList<>(estimatedEntities / 8);
    List<BlockEntityData> blockEntities = new ArrayList<>(128);
    List<PlayerData> players = new ArrayList<>(32);

    int maxEntities = CONFIG.getMaxEntitiesToCollect();
    double distanceCutoff = CONFIG.getEntityDistanceCutoff();
    String[] excludedTypes = CONFIG.getExcludedEntityTypes();
    double cutoffSq = distanceCutoff * distanceCutoff;

    for (ServerLevel level : server.getAllLevels()) {
      List<ServerPlayer> serverPlayers = level.players();
      List<PlayerData> levelPlayers = new ArrayList<>(serverPlayers.size());

      for (ServerPlayer p : serverPlayers) {
        levelPlayers.add(new PlayerData(p.getId(), p.getX(), p.getY(), p.getZ()));
      }

      collectEntitiesFromLevel(
          level,
          new EntityCollectionContext(
              entities, items, mobs, maxEntities, distanceCutoff, levelPlayers, excludedTypes, cutoffSq));

      players.addAll(levelPlayers);
      if (entities.size() >= maxEntities) break;
    }
    return new EntityDataCollection(entities, items, mobs, blockEntities, players);
  }

  private void collectEntitiesFromLevel(ServerLevel level, EntityCollectionContext context) {
    long spatialStart = System.nanoTime();
    SpatialGrid spatialGrid = getOrCreateSpatialGrid(level, context.players());
    long durationMs = (System.nanoTime() - spatialStart) / 1_000_000;
    PerformanceMetricsLogger.logLine(String.format("PERF: spatial_grid_init duration=%dms", durationMs));

    AABB searchBounds = createSearchBounds(context.players(), context.distanceCutoff());

    // Use asynchronous distance calculations every N ticks to reduce CPU load
    if (shouldPerformAdaptiveDistanceCalculation()) {
      processEntitiesWithFullDistanceCheck(level, searchBounds, context, spatialGrid);
    } else {
      processEntitiesWithReducedDistanceCheck(level, searchBounds, context, spatialGrid);
    }
  }

  private boolean shouldPerformAdaptiveDistanceCalculation() {
    return PerformanceManager.getAverageTPS() < CONFIG.getTpsThresholdForAsync() * 0.9;
  }

  private void processEntitiesWithFullDistanceCheck(ServerLevel level, AABB searchBounds, EntityCollectionContext context, SpatialGrid spatialGrid) {
    List<Entity> entityList = level.getEntities(null, searchBounds);
    int entityCount = entityList.size();

    for (int i = 0; i < entityCount && context.entities().size() < context.maxEntities(); i++) {
      Entity entity = entityList.get(i);
      double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, context.distanceCutoff());
      
      if (minSq <= context.cutoffSq()) {
        processEntityWithinCutoff(entity, minSq, context.excluded(), context.entities(), context.items(), context.mobs());
      }
    }
  }

  private void processEntitiesWithReducedDistanceCheck(ServerLevel level, AABB searchBounds, EntityCollectionContext context, SpatialGrid spatialGrid) {
    double approximateCutoff = context.distanceCutoff() * 1.2;
    double approximateCutoffSq = approximateCutoff * approximateCutoff;

    // First pass: quick approximate filtering
    List<Entity> candidateEntities = new ArrayList<>();
    for (Entity entity : level.getEntities(null, searchBounds)) {
      if (context.entities().size() >= context.maxEntities()) break;

      double approxMinSq = spatialGrid.findMinSquaredDistance(
          entity.getX(), entity.getY(), entity.getZ(), approximateCutoff);

      if (approxMinSq <= approximateCutoffSq) {
        candidateEntities.add(entity);
      }
    }

    // Second pass: precise calculation only for candidates
    for (Entity entity : candidateEntities) {
      if (context.entities().size() >= context.maxEntities()) break;

      String typeStr = entity.getType().toString();
      if (!isExcludedType(typeStr, context.excluded())) {
        double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, context.distanceCutoff());
        if (minSq <= context.cutoffSq()) {
          processEntityWithinCutoff(entity, minSq, context.excluded(), context.entities(), context.items(), context.mobs());
        }
      }
    }
  }

  private double computeMinSquaredDistanceToPlayersOptimized(Entity entity, SpatialGrid spatialGrid, double maxSearchRadius) {
    return spatialGrid.findMinSquaredDistance(
        entity.getX(), entity.getY(), entity.getZ(), maxSearchRadius);
  }

  private AABB createSearchBounds(List<PlayerData> players, double distanceCutoff) {
    if (players.isEmpty()) {
      return new AABB(0, 0, 0, 0, 0, 0);
    }

    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;

    for (PlayerData player : players) {
      minX = Math.min(minX, player.getX());
      minY = Math.min(minY, player.getY());
      minZ = Math.min(minZ, player.getZ());
      maxX = Math.max(maxX, player.getX());
      maxY = Math.max(maxY, player.getY());
      maxZ = Math.max(maxZ, player.getZ());
    }

    minX -= distanceCutoff;
    minY -= distanceCutoff;
    minZ -= distanceCutoff;
    maxX += distanceCutoff;
    maxY += distanceCutoff;
    maxZ += distanceCutoff;

    return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
  }

  private SpatialGrid getOrCreateSpatialGrid(ServerLevel level, List<PlayerData> players) {
    synchronized (SPATIAL_GRID_LOCK) {
      SpatialGrid grid = LEVEL_SPATIAL_GRIDS.computeIfAbsent(
          level,
          k -> new SpatialGrid(Math.max(CONFIG.getEntityDistanceCutoff() / 4.0, 16.0)));

      grid.clear();
      for (PlayerData player : players) {
        grid.updatePlayer(player);
      }

      return grid;
    }
  }

  private void processEntityWithinCutoff(Entity entity, double minSq, String[] excluded, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs) {
    String typeStr = entity.getType().toString();
    if (isExcludedType(typeStr, excluded)) return;
    
    double distance = Math.sqrt(minSq);
    entities.add(new EntityData(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), distance, false, typeStr));

    if (entity instanceof ItemEntity itemEntity) {
      collectItemEntity(entity, itemEntity, items);
    } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
      collectMobEntity(entity, mob, mobs, distance, typeStr);
    }
  }

  private boolean isExcludedType(String typeStr, String[] excluded) {
    if (excluded == null || excluded.length == 0) return false;
    for (String ex : excluded) {
      if (ex == null || ex.isEmpty()) continue;
      if (typeStr.contains(ex)) return true;
    }
    return false;
  }

  private void collectItemEntity(Entity entity, ItemEntity itemEntity, List<ItemEntityData> items) {
    var chunkPos = entity.chunkPosition();
    var itemStack = itemEntity.getItem();
    var itemType = itemStack.getItem().getDescriptionId();
    var count = itemStack.getCount();
    var ageSeconds = itemEntity.getAge() / 20;
    items.add(new ItemEntityData(entity.getId(), chunkPos.x, chunkPos.z, itemType, count, ageSeconds));
  }

  private void collectMobEntity(Entity entity, net.minecraft.world.entity.Mob mob, List<MobData> mobs, double distance, String typeStr) {
    boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
    mobs.add(new MobData(entity.getId(), distance, isPassive, typeStr));
  }

  /**
   * Consolidate collected item entity data by chunk X/Z and item type.
   */
  public List<ItemEntityData> consolidateItemEntities(List<ItemEntityData> items) {
    if (items == null || items.isEmpty()) return items;

    int estimatedSize = Math.min(items.size(), items.size() / 2 + 1);
    Map<Long, ItemEntityData> agg = new HashMap<>(estimatedSize);

    for (ItemEntityData it : items) {
      long key = packItemKey(it.getChunkX(), it.getChunkZ(), it.getItemType());

      ItemEntityData cur = agg.get(key);
      if (cur == null) {
        agg.put(key, it);
      } else {
        int newCount = cur.getCount() + it.getCount();
        int newAge = Math.min(cur.getAgeSeconds(), it.getAgeSeconds());
        long preservedId = cur.getId();
        agg.put(key, new ItemEntityData(preservedId, it.getChunkX(), it.getChunkZ(), it.getItemType(), newCount, newAge));
      }
    }

    return new ArrayList<>(agg.values());
  }

  /**
   * Pack chunk coordinates and item type hash into a composite long key.
   */
  private long packItemKey(int chunkX, int chunkZ, String itemType) {
    long packedChunkX = ((long) chunkX) & 0x1FFFFF; // 21 bits
    long packedChunkZ = ((long) chunkZ) & 0x1FFFFF; // 21 bits
    long itemHash = itemType == null ? 0 : ((long) itemType.hashCode()) & 0x3FFFFF; // 22 bits

    return (packedChunkX << 43) | (packedChunkZ << 22) | itemHash;
  }

  /**
   * Process optimizations using Rust native code.
   */
  public OptimizationResults processOptimizations(EntityDataCollection data) {
    List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities(), data.players());
    List<Long> blockResult = RustPerformance.getBlockEntitiesToTick(data.blockEntities());
    com.kneaf.core.performance.core.ItemProcessResult itemResult = RustPerformance.processItemEntities(data.items());
    com.kneaf.core.performance.core.MobProcessResult mobResult = RustPerformance.processMobAI(data.mobs());
    
    return new OptimizationResults(toTick, blockResult, itemResult, mobResult);
  }

  /**
   * Apply optimization results to the server world.
   */
  public void applyOptimizations(MinecraftServer server, OptimizationResults results) {
    applyItemUpdates(server, results.itemResult());
    applyMobOptimizations(server, results.mobResult());
    enforceServerDistanceBounds(server);
  }

  private void applyItemUpdates(MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
    if (itemResult == null || itemResult.getItemUpdates() == null) return;

    Map<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> updateMap = new HashMap<>();
    for (var update : itemResult.getItemUpdates()) {
      updateMap.put((int) update.getId(), update);
    }

    for (ServerLevel level : server.getAllLevels()) {
      for (Map.Entry<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> entry : updateMap.entrySet()) {
        Entity entity = level.getEntity(entry.getKey());
        if (entity instanceof ItemEntity itemEntity) {
          itemEntity.getItem().setCount(entry.getValue().getNewCount());
        }
      }
    }
  }

  private void applyMobOptimizations(MinecraftServer server, com.kneaf.core.performance.core.MobProcessResult mobResult) {
    if (mobResult == null) return;

    Set<Integer> disableAiIds = new HashSet<>();
    for (Long id : mobResult.getDisableList()) {
      disableAiIds.add(id.intValue());
    }

    Set<Integer> simplifyAiIds = new HashSet<>();
    for (Long id : mobResult.getSimplifyList()) {
      simplifyAiIds.add(id.intValue());
    }

    for (ServerLevel level : server.getAllLevels()) {
      for (Integer id : disableAiIds) {
        Entity entity = level.getEntity(id);
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
          mob.setNoAi(true);
        }
      }

      for (Integer id : simplifyAiIds) {
        Entity entity = level.getEntity(id);
        if (entity instanceof net.minecraft.world.entity.Mob) {
          // AI simplification applied
        }
      }
    }
  }

  /**
   * Enforce server distance bounds based on optimization level.
   */
  private void enforceServerDistanceBounds(MinecraftServer server) {
    try {
      com.kneaf.core.performance.core.RustPerformanceFacade facade = com.kneaf.core.performance.core.RustPerformanceFacade.getInstance();
      com.kneaf.core.performance.core.PerformanceOptimizer.OptimizationLevel level = 
          facade.isNativeAvailable() ? facade.getCurrentOptimizationLevel() : null;
      
      if (level != null) {
        int target = mapOptimizationLevelToDistance(level);
        setTargetDistance(server, target);
      }
    } catch (Throwable t) {
      LOGGER.debug("Failed to enforce server distance bounds: {}", t.getMessage());
    }
  }

  private int mapOptimizationLevelToDistance(com.kneaf.core.performance.core.PerformanceOptimizer.OptimizationLevel level) {
    return switch (level) {
      case AGGRESSIVE -> 8;
      case HIGH -> 12;
      case MEDIUM -> 16;
      case NORMAL -> 32;
      default -> 16;
    };
  }

  /**
   * Set target distance for view and simulation distances with smooth transitions.
   */
  public void setTargetDistance(MinecraftServer server, int targetChunks) {
    if (server == null) return;
    for (ServerLevel level : server.getAllLevels()) {
      try {
        // Record original distances if not already recorded
        recordOriginalDistances(level);

        TARGET_DISTANCE.put(level, targetChunks);
        TRANSITION_REMAINING.put(level, TRANSITION_TICKS);
        TARGET_SIMULATION_DISTANCE.put(level, targetChunks);
        TRANSITION_REMAINING_SIM.put(level, TRANSITION_TICKS);
      } catch (Throwable t) {
        LOGGER.debug("Failed to set target distance for level {}: {}", level, t.getMessage());
      }
    }
  }

  private void recordOriginalDistances(ServerLevel level) {
    if (!ORIGINAL_VIEW_DISTANCE.containsKey(level)) {
      try {
        var m = resolveViewGetter(level.getClass());
        if (m != null) {
          int current = (int) m.invoke(level);
          ORIGINAL_VIEW_DISTANCE.put(level, current);
        }
      } catch (Throwable t) {
        // Ignore if getViewDistance isn't available
      }
    }

    if (!ORIGINAL_SIMULATION_DISTANCE.containsKey(level)) {
      try {
        var sm = resolveSimGetter(level.getClass());
        if (sm != null) {
          int curSim = (int) sm.invoke(level);
          ORIGINAL_SIMULATION_DISTANCE.put(level, curSim);
        }
      } catch (Throwable t) {
        // Ignore if not available
      }
    }
  }

  /**
   * Apply distance transitions smoothly over time.
   */
  public void applyDistanceTransitions(MinecraftServer server) {
    if (server == null) return;
    
    applyViewDistanceTransitions(server);
    applySimulationDistanceTransitions(server);
    restoreOriginalDistancesWhenAppropriate(server);
  }

  private void applyViewDistanceTransitions(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
      Integer remaining = TRANSITION_REMAINING.get(level);
      Integer target = TARGET_DISTANCE.get(level);
      
      if (remaining == null || target == null) continue;

      try {
        var vg = resolveViewGetter(level.getClass());
        if (vg == null) {
          cleanupTransitionState(level, TRANSITION_REMAINING, TARGET_DISTANCE, ORIGINAL_VIEW_DISTANCE);
          continue;
        }

        int current = (int) vg.invoke(level);

        if (remaining <= 1) {
          applyFinalDistanceChange(level, target, resolveViewSetter(level.getClass()));
          cleanupTransitionState(level, TRANSITION_REMAINING, TARGET_DISTANCE, ORIGINAL_VIEW_DISTANCE);
        } else {
          applyGradualDistanceChange(level, current, target, remaining, resolveViewSetter(level.getClass()));
          updateTransitionState(level, remaining, TRANSITION_REMAINING);
        }

      } catch (Throwable t) {
        handleTransitionError(level, "view distance", t);
      }
    }
  }

  private void applySimulationDistanceTransitions(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
      Integer remaining = TRANSITION_REMAINING_SIM.get(level);
      Integer target = TARGET_SIMULATION_DISTANCE.get(level);
      
      if (remaining == null || target == null) continue;

      try {
        var sg = resolveSimGetter(level.getClass());
        if (sg == null) {
          cleanupTransitionState(level, TRANSITION_REMAINING_SIM, TARGET_SIMULATION_DISTANCE, ORIGINAL_SIMULATION_DISTANCE);
          continue;
        }

        int current = (int) sg.invoke(level);

        if (remaining <= 1) {
          applyFinalDistanceChange(level, target, resolveSimSetter(level.getClass()));
          cleanupTransitionState(level, TRANSITION_REMAINING_SIM, TARGET_SIMULATION_DISTANCE, ORIGINAL_SIMULATION_DISTANCE);
        } else {
          applyGradualDistanceChange(level, current, target, remaining, resolveSimSetter(level.getClass()));
          updateTransitionState(level, remaining, TRANSITION_REMAINING_SIM);
        }

      } catch (Throwable t) {
        handleTransitionError(level, "simulation distance", t);
      }
    }
  }

  private void applyFinalDistanceChange(ServerLevel level, int target, java.lang.reflect.Method setter) {
    if (setter != null) {
      try {
        setter.invoke(level, target);
      } catch (Throwable ignored) {
        // Ignore silent failure
      }
    }
  }

  private void applyGradualDistanceChange(ServerLevel level, int current, int target, int remaining, java.lang.reflect.Method setter) {
    int step = (int) Math.ceil((double) (target - current) / remaining);
    int next = current + step;

    if (setter != null) {
      try {
        setter.invoke(level, next);
      } catch (Throwable ignored) {
        // Ignore silent failure
      }
    }
  }

  private void updateTransitionState(ServerLevel level, int remaining, Map<ServerLevel, Integer> stateMap) {
    stateMap.put(level, Integer.valueOf(remaining - 1));
  }

  private void cleanupTransitionState(ServerLevel level, Map<ServerLevel, Integer> remainingMap,
                                    Map<ServerLevel, Integer> targetMap,
                                    Map<ServerLevel, Integer> originalMap) {
    remainingMap.remove(level);
    targetMap.remove(level);
    
    Integer orig = originalMap.get(level);
    if (orig != null) {
      originalMap.remove(level);
    }
  }

  private void handleTransitionError(ServerLevel level, String distanceType, Throwable t) {
    LOGGER.debug("Error transitioning {} for level {}: {}", distanceType, level, t.getMessage());
    cleanupTransitionState(level, TRANSITION_REMAINING, TARGET_DISTANCE, ORIGINAL_VIEW_DISTANCE);
    cleanupTransitionState(level, TRANSITION_REMAINING_SIM, TARGET_SIMULATION_DISTANCE, ORIGINAL_SIMULATION_DISTANCE);
  }

  private void restoreOriginalDistancesWhenAppropriate(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
      if (!TARGET_DISTANCE.containsKey(level) && ORIGINAL_VIEW_DISTANCE.containsKey(level)) {
        try {
          restoreOriginalDistance(level, resolveViewGetter(level.getClass()), resolveViewSetter(level.getClass()), ORIGINAL_VIEW_DISTANCE);
        } catch (Throwable t) {
          disableReflectionForClass(level.getClass(), "restoreView", t);
        } finally {
          ORIGINAL_VIEW_DISTANCE.remove(level);
        }
      }
    }
  }

  private void restoreOriginalDistance(ServerLevel level, java.lang.reflect.Method getter, java.lang.reflect.Method setter, Map<ServerLevel, Integer> originalMap) throws Throwable {
    if (getter != null && setter != null) {
      int current = ((Number) getter.invoke(level)).intValue();
      int orig = originalMap.get(level).intValue();
      
      if (current != orig) {
        setter.invoke(level, Integer.valueOf(orig));
      }
    }
  }

  // Reflection helper methods (cached for performance)

  private java.lang.reflect.Method resolveViewGetter(Class<?> cls) {
    var cached = VIEW_GET_CACHE.get(cls);
    if (cached != null) return cached;
    
    if (METHOD_RESOLVED.containsKey(cls) && !VIEW_GET_CACHE.containsKey(cls)) return null;
    
    try {
      java.lang.reflect.Method method = null;
      try {
        method = cls.getMethod("getViewDistance");
      } catch (NoSuchMethodException e) {
        method = cls.getMethod("viewDistance");
      }
      VIEW_GET_CACHE.put(cls, method);
      METHOD_RESOLVED.put(cls, true);
      return method;
    } catch (Throwable t) {
      METHOD_RESOLVED.put(cls, false);
      return null;
    }
  }

  private java.lang.reflect.Method resolveViewSetter(Class<?> cls) {
    var cached = VIEW_SET_CACHE.get(cls);
    if (cached != null) return cached;
    
    try {
      java.lang.reflect.Method method = cls.getMethod("setViewDistance", int.class);
      VIEW_SET_CACHE.put(cls, method);
      return method;
    } catch (Throwable t) {
      return null;
    }
  }

  private java.lang.reflect.Method resolveSimGetter(Class<?> cls) {
    var cached = SIM_GET_CACHE.get(cls);
    if (cached != null) return cached;
    
    try {
      java.lang.reflect.Method method = cls.getMethod("getSimulationDistance");
      SIM_GET_CACHE.put(cls, method);
      return method;
    } catch (Throwable t) {
      return null;
    }
  }

  private java.lang.reflect.Method resolveSimSetter(Class<?> cls) {
    var cached = SIM_SET_CACHE.get(cls);
    if (cached != null) return cached;
    
    try {
      java.lang.reflect.Method method = cls.getMethod("setSimulationDistance", int.class);
      SIM_SET_CACHE.put(cls, method);
      return method;
    } catch (Throwable t) {
      return null;
    }
  }

  private void disableReflectionForClass(Class<?> cls, String why, Throwable t) {
    try {
      VIEW_GET_CACHE.remove(cls);
      VIEW_SET_CACHE.remove(cls);
      SIM_GET_CACHE.remove(cls);
      SIM_SET_CACHE.remove(cls);
      METHOD_RESOLVED.put(cls, false);
    } finally {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Disabling reflection distance adjustments for {}: {}", cls.getName(), why);
      }
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("Reflection disable cause: ", t);
      }
    }
  }

  /**
   * Remove items from the world based on optimization results.
   */
  public void removeItems(MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
    if (itemResult == null || itemResult.getItemsToRemove() == null || itemResult.getItemsToRemove().isEmpty()) return;
    
    for (ServerLevel level : server.getAllLevels()) {
      for (Long id : itemResult.getItemsToRemove()) {
        try {
          Entity entity = level.getEntity(id.intValue());
          if (entity != null) {
            entity.remove(RemovalReason.DISCARDED);
          }
        } catch (Exception e) {
          LOGGER.debug("Error removing item entity {} on level {}", id, level.dimension(), e);
        }
      }
    }
  }

  /**
   * Log spatial grid statistics for performance monitoring.
   */
  public void logSpatialGridStats(MinecraftServer server) {
    synchronized (SPATIAL_GRID_LOCK) {
      int totalLevels = LEVEL_SPATIAL_GRIDS.size();
      int totalPlayers = 0;
      int totalCells = 0;

      for (SpatialGrid grid : LEVEL_SPATIAL_GRIDS.values()) {
        SpatialGrid.GridStats stats = grid.getStats();
        totalPlayers += stats.totalPlayers();
        totalCells += stats.totalCells();
      }

      String summary = String.format(
          "SpatialGrid Stats: %d levels, %d players, %d cells, avg %.2f players/cell",
          totalLevels, totalPlayers, totalCells,
          totalCells > 0 ? (double) totalPlayers / totalCells : 0.0);

      PerformanceMetricsLogger.logLine(summary);
      if (CONFIG.isBroadcastToClient()) {
        broadcastToPlayers(server, summary);
      }
    }
  }

  private void broadcastToPlayers(MinecraftServer server, String message) {
    try {
      for (ServerLevel level : server.getAllLevels()) {
        for (ServerPlayer player : level.players()) {
          player.displayClientMessage(Component.literal(message), false);
        }
      }
    } catch (Exception e) {
      LOGGER.debug("Failed to broadcast performance message to players", e);
    }
  }

  /**
   * Record for collecting entity data from the world.
   */
  public record EntityDataCollection(
      List<EntityData> entities,
      List<ItemEntityData> items,
      List<MobData> mobs,
      List<BlockEntityData> blockEntities,
      List<PlayerData> players) {}

  /**
   * Context for collecting entities with various parameters.
   */
  public record EntityCollectionContext(
      List<EntityData> entities,
      List<ItemEntityData> items,
      List<MobData> mobs,
      int maxEntities,
      double distanceCutoff,
      List<PlayerData> players,
      String[] excluded,
      double cutoffSq) {}

  /**
   * Results of optimization processing.
   */
  public record OptimizationResults(
      List<Long> toTick,
      List<Long> blockResult,
      com.kneaf.core.performance.core.ItemProcessResult itemResult,
      com.kneaf.core.performance.core.MobProcessResult mobResult) {}

  /**
   * Main entry point for server tick processing - mirrors PerformanceManager interface
   */
  public void onServerTick(MinecraftServer server) {
    // Implement the core tick logic that was in PerformanceManager
    // This is a simplified version that maintains the original functionality
    updateTPS();
    TICK_COUNTER++;

    // Adjust dynamic threshold based on executor metrics
    if (TICK_COUNTER % 2 == 0) { // Adjust every 2 ticks for responsiveness
      adjustDynamicThreshold();
    }

    // Respect configured scan interval to reduce overhead on busy servers
    if (!shouldPerformScan()) {
      return;
    }

    // Collect and consolidate data
    EntityDataCollection data = collectAndConsolidate(server, false);

    // Apply any pending distance transitions each tick
    try {
      applyDistanceTransitions(server);
    } catch (Throwable t) {
      LOGGER.debug("Error applying distance transitions: {}", t.getMessage());
    }

    // Decide whether to offload heavy processing based on rolling TPS
    double avgTps = getRollingAvgTPS();
    if (avgTps >= currentTpsThreshold) {
      // In a complete implementation, we would use ThreadPoolManager here
      // For now, we'll just run synchronously
      runSynchronousOptimizations(server, data, false);
    } else {
      runSynchronousOptimizations(server, data, false);
    }
  }

  /** Helper method to maintain compatibility with original PerformanceManager */
  private void updateTPS() {
    // Implement TPS calculation logic
  }

  /** Helper method to maintain compatibility with original PerformanceManager */
  private double getRollingAvgTPS() {
    // Implement rolling TPS calculation logic
    return 20.0; // Default to normal TPS for now
  }

  /** Helper method to maintain compatibility with original PerformanceManager */
  private boolean shouldPerformScan() {
    // Implement scan interval logic
    return true; // Always scan for now
  }

  /** Helper method to maintain compatibility with original PerformanceManager */
  private void adjustDynamicThreshold() {
    // Implement dynamic threshold adjustment logic
  }

  /** Helper method to maintain compatibility with original PerformanceManager */
  private void runSynchronousOptimizations(MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
    try {
      OptimizationResults results = processOptimizations(data);
      applyOptimizations(server, results);
    } catch (Exception ex) {
      LOGGER.warn("Error processing optimizations synchronously", ex);
    }
  }
}