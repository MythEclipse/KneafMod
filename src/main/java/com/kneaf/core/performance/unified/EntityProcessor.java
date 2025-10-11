package com.kneaf.core.performance.unified;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.performance.spatial.SpatialGrid;
import com.kneaf.core.performance.core.MobProcessResult;
import com.kneaf.core.performance.RustPerformance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * Thread-safe entity processor for collection and optimization.
 * Handles entity data collection, spatial partitioning, and optimization.
 */
public class EntityProcessor {
    // Spatial grid for efficient player position queries per level
    private final Map<ServerLevel, SpatialGrid> levelSpatialGrids = new HashMap<>();
    // Lock striping for high contention scenarios (32 stripes by default)
    private final ReentrantLock[] stripedLocks = new ReentrantLock[32];
    // Cache for frequently accessed configuration
    private MonitoringConfig monitoringConfig;
    // Metrics collector for performance metrics
    private MetricsCollector metricsCollector;
    // TPS monitor for adaptive optimization
    private TPSMonitor tpsMonitor;
    
    // Atomic counters for profiling
    private final AtomicInteger totalEntitiesProcessed = new AtomicInteger(0);
    private final AtomicInteger totalMobsProcessed = new AtomicInteger(0);
    private final AtomicInteger totalBlocksProcessed = new AtomicInteger(0);

    /**
     * Create a new EntityProcessor.
     */
    public EntityProcessor() {
        initializeStripedLocks();
    }

    /**
     * Initialize lock striping for concurrent access.
     */
    private void initializeStripedLocks() {
        for (int i = 0; i < stripedLocks.length; i++) {
            stripedLocks[i] = new ReentrantLock(true); // Fair lock for better debugging
        }
    }

    /**
     * Configure the entity processor with monitoring components.
     * @param config monitoring configuration
     * @param metricsCollector metrics collector
     * @param tpsMonitor TPS monitor
     */
    public void configure(MonitoringConfig config, MetricsCollector metricsCollector, TPSMonitor tpsMonitor) {
        this.monitoringConfig = config;
        this.metricsCollector = metricsCollector;
        this.tpsMonitor = tpsMonitor;
    }

    /**
     * Process server tick for entity optimization.
     * @param server Minecraft server instance
     */
    public void onServerTick(MinecraftServer server) {
        if (monitoringConfig == null || !isMonitoringEnabled()) {
            return;
        }

        // Collect entity data
        EntityDataCollection entityData = collectEntityData(server);
        
        // Process optimizations asynchronously or synchronously based on TPS
        double currentTps = tpsMonitor.getAverageTPS();
        if (currentTps >= monitoringConfig.getTpsThresholdAsync()) {
            processEntitiesAsync(server, entityData);
        } else {
            processEntitiesSync(server, entityData);
        }

        // Log profiling data if enabled
        if (monitoringConfig.isProfilingEnabled() && shouldProfileThisTick()) {
            logProfilingData(entityData);
        }
    }

    /**
     * Collect entity data from the server.
     * @param server Minecraft server instance
     * @return collected entity data
     */
    public EntityDataCollection collectEntityData(MinecraftServer server) {
        List<EntityData> entities = new ArrayList<>();
        List<MobData> mobs = new ArrayList<>();
        List<BlockEntityData> blockEntities = new ArrayList<>();
        List<PlayerData> players = new ArrayList<>();

        int maxEntities = monitoringConfig.getMaxEntitiesToCollect();
        double distanceCutoff = monitoringConfig.getEntityDistanceCutoff();
        String[] excludedTypes = monitoringConfig.getExcludedEntityTypes();
        double cutoffSq = distanceCutoff * distanceCutoff;

        for (ServerLevel level : server.getAllLevels()) {
            if (entities.size() >= maxEntities) {
                break;
            }

            // Collect player data
            List<ServerPlayer> serverPlayers = level.players();
            List<PlayerData> levelPlayers = new ArrayList<>(serverPlayers.size());
            for (ServerPlayer player : serverPlayers) {
                levelPlayers.add(new PlayerData(player.getId(), player.getX(), player.getY(), player.getZ()));
            }
            players.addAll(levelPlayers);

            // Collect entities using spatial grid optimization
            collectEntitiesFromLevel(level, entities, mobs, blockEntities, levelPlayers, excludedTypes, cutoffSq);
        }

        return new EntityDataCollection(entities, mobs, blockEntities, players);
    }

    /**
     * Collect entities from a specific level using spatial grid optimization.
     * @param level server level
     * @param entities entity data list
     * @param mobs mob data list
     * @param blockEntities block entity data list
     * @param players player data list
     * @param excludedTypes excluded entity types
     * @param cutoffSq squared distance cutoff
     */
    private void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<MobData> mobs,
                                         List<BlockEntityData> blockEntities, List<PlayerData> players,
                                         String[] excludedTypes, double cutoffSq) {
        SpatialGrid spatialGrid = getOrCreateSpatialGrid(level, players);
        
        // Create search bounds based on player positions
        AABB searchBounds = createSearchBounds(players, monitoringConfig.getEntityDistanceCutoff());
        
        for (Entity entity : level.getEntities(null, searchBounds)) {
            if (entities.size() >= monitoringConfig.getMaxEntitiesToCollect()) {
                break;
            }

            // Quick distance check using spatial grid
            double minSq = spatialGrid.findMinSquaredDistance(
                entity.getX(), entity.getY(), entity.getZ(), monitoringConfig.getEntityDistanceCutoff());
            
            if (minSq <= cutoffSq && !isExcludedType(entity.getType().toString(), excludedTypes)) {
                processEntity(entity, minSq, entities, mobs);
            }
        }
    }

    /**
     * Process a single entity and add to appropriate lists.
     * @param entity Minecraft entity
     * @param minSq squared distance to nearest player
     * @param entities entity data list
     * @param mobs mob data list
     */
    private void processEntity(Entity entity, double minSq, List<EntityData> entities, List<MobData> mobs) {
        String typeStr = entity.getType().toString();
        double distance = Math.sqrt(minSq);

        entities.add(new EntityData(
            entity.getId(),
            entity.getX(),
            entity.getY(),
            entity.getZ(),
            distance,
            false, // Regular entities are not block entities
            typeStr));

        totalEntitiesProcessed.incrementAndGet();

        if (entity instanceof Mob mob) {
            boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
            mobs.add(new MobData(entity.getId(), distance, isPassive, typeStr));
            totalMobsProcessed.incrementAndGet();
        }
    }

    /**
     * Check if an entity type is excluded from processing.
     * @param typeStr entity type string
     * @param excludedTypes excluded types array
     * @return true if entity type is excluded
     */
    private boolean isExcludedType(String typeStr, String[] excludedTypes) {
        if (excludedTypes == null || excludedTypes.length == 0) {
            return false;
        }
        for (String ex : excludedTypes) {
            if (ex == null || ex.isEmpty()) {
                continue;
            }
            if (typeStr.contains(ex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create search bounds based on player positions.
     * @param players list of player data
     * @param distanceCutoff maximum distance for entity collection
     * @return axis-aligned bounding box for search
     */
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

        // Expand bounds by distance cutoff
        minX -= distanceCutoff;
        minY -= distanceCutoff;
        minZ -= distanceCutoff;
        maxX += distanceCutoff;
        maxY += distanceCutoff;
        maxZ += distanceCutoff;

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Get or create spatial grid for a level.
     * @param level server level
     * @param players player data list
     * @return spatial grid for the level
     */
    private SpatialGrid getOrCreateSpatialGrid(ServerLevel level, List<PlayerData> players) {
        ReentrantLock lock = getGridLock(level);
        lock.lock();
        try {
            return levelSpatialGrids.computeIfAbsent(level, k -> {
                double cellSize = Math.max(monitoringConfig.getEntityDistanceCutoff() / 4.0, 16.0);
                SpatialGrid grid = new SpatialGrid(cellSize);
                updateSpatialGridWithPlayers(grid, players);
                return grid;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update spatial grid with current player positions.
     * @param grid spatial grid to update
     * @param players player data list
     */
    private void updateSpatialGridWithPlayers(SpatialGrid grid, List<PlayerData> players) {
        grid.clear();
        for (PlayerData player : players) {
            grid.updatePlayer(player);
        }
    }

    /**
     * Get appropriate lock for a server level using lock striping.
     * @param level server level
     * @return reentrant lock for the level
     */
    private ReentrantLock getGridLock(ServerLevel level) {
        int stripeIndex = Math.abs(level.hashCode() % stripedLocks.length);
        return stripedLocks[stripeIndex];
    }

    /**
     * Process entities asynchronously.
     * @param server Minecraft server instance
     * @param entityData collected entity data
     */
    private void processEntitiesAsync(MinecraftServer server, EntityDataCollection entityData) {
        processEntitiesSync(server, entityData);
    }

    /**
     * Process entities synchronously.
     * @param server Minecraft server instance
     * @param entityData collected entity data
     */
    private void processEntitiesSync(MinecraftServer server, EntityDataCollection entityData) {
        try {
            // Process mob AI through Rust performance system
            MobProcessResult mobResult = RustPerformance.processMobAI(entityData.mobs());
            
            // Get entities to tick from Rust performance system
            List<Long> entitiesToTick = RustPerformance.getEntitiesToTick(entityData.entities(), entityData.players());
            
            // Get block entities to tick from Rust performance system
            List<Long> blockEntitiesToTick = RustPerformance.getBlockEntitiesToTick(entityData.blockEntities());
            
            // Create optimization results
            OptimizationResults results = new OptimizationResults(entitiesToTick, blockEntitiesToTick, mobResult);
            
            // Apply optimization results
            applyOptimizationResults(server, results);
            
        } catch (Exception e) {
            // Log error but continue processing
            if (monitoringConfig.getMonitoringLevel().isAlertsEnabled()) {
                metricsCollector.addThresholdAlert("Entity processing failed: " + e.getMessage());
            }
        }
    }

    /**
     * Apply optimization results to the server.
     * @param server Minecraft server instance
     * @param results optimization results
     */
    private void applyOptimizationResults(MinecraftServer server, OptimizationResults results) {
        if (results == null) {
            return;
        }

        // Apply mob optimizations
        applyMobOptimizations(server, results.mobResult());
        
        // Apply block entity optimizations
        applyBlockEntityOptimizations(server, results.blockResult());
        
        // Log optimization summary
        if (shouldLogOptimizations()) {
            logOptimizationSummary(results);
        }
    }

    /**
     * Apply mob optimizations to the server.
     * @param server Minecraft server instance
     * @param mobResult mob process result
     */
    private void applyMobOptimizations(MinecraftServer server, MobProcessResult mobResult) {
        if (mobResult == null) {
            return;
        }

        // Batch entity lookup for mobs to disable AI
        Set<Integer> disableAiIds = new CopyOnWriteArrayList<>(mobResult.getDisableList())
                .stream()
                .map(Long::intValue)
                .collect(Collectors.toSet());

        // Batch entity lookup for mobs to simplify AI
        Set<Integer> simplifyAiIds = new CopyOnWriteArrayList<>(mobResult.getSimplifyList())
                .stream()
                .map(Long::intValue)
                .collect(Collectors.toSet());

        // Process all levels
        for (ServerLevel level : server.getAllLevels()) {
            applyMobOptimizationsToLevel(level, disableAiIds, simplifyAiIds);
        }
    }

    /**
     * Apply mob optimizations to a specific level.
     * @param level server level
     * @param disableAiIds mob IDs to disable AI
     * @param simplifyAiIds mob IDs to simplify AI
     */
    private void applyMobOptimizationsToLevel(ServerLevel level, Set<Integer> disableAiIds, Set<Integer> simplifyAiIds) {
        // Process mobs to disable AI
        for (Integer id : disableAiIds) {
            Entity entity = level.getEntity(id);
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
            }
        }

        // Process mobs to simplify AI
        for (Integer id : simplifyAiIds) {
            Entity entity = level.getEntity(id);
            if (entity instanceof Mob) {
                // AI simplification logic would go here
            }
        }
    }

    /**
     * Apply block entity optimizations to the server.
     * @param server Minecraft server instance
     * @param blockResult block entity IDs to optimize
     */
    private void applyBlockEntityOptimizations(MinecraftServer server, List<Long> blockResult) {
        if (blockResult == null || blockResult.isEmpty()) {
            return;
        }
        totalBlocksProcessed.addAndGet(blockResult.size());
    }

    /**
     * Log optimization summary.
     * @param results optimization results
     */
    private void logOptimizationSummary(OptimizationResults results) {
        if (results == null) {
            return;
        }

        int mobsDisabled = results.mobResult() != null ? results.mobResult().getDisableList().size() : 0;
        int mobsSimplified = results.mobResult() != null ? results.mobResult().getSimplifyList().size() : 0;
        int blockEntities = results.blockResult() != null ? results.blockResult().size() : 0;

        String summary = String.format(
            "Optimization Summary: mobsDisabled=%d, mobsSimplified=%d, blockEntities=%d, entitiesProcessed=%d",
            mobsDisabled, mobsSimplified, blockEntities, getTotalEntitiesProcessed()
        );

        System.out.println("[KneafMod Performance] " + summary);
    }

    /**
     * Log profiling data.
     * @param entityData collected entity data
     */
    private void logProfilingData(EntityDataCollection entityData) {
        if (entityData == null) {
            return;
        }

        String profilingData = String.format(
            "Profiling Data: entities=%d, mobs=%d, blockEntities=%d, players=%d, tps=%.2f",
            entityData.entities().size(),
            entityData.mobs().size(),
            entityData.blockEntities().size(),
            entityData.players().size(),
            tpsMonitor.getAverageTPS()
        );

        System.out.println("[KneafMod Performance Profiling] " + profilingData);
    }

    /**
     * Check if monitoring is enabled.
     * @return true if monitoring is enabled
     */
    private boolean isMonitoringEnabled() {
        return monitoringConfig != null && monitoringConfig.getMonitoringLevel().isMetricsEnabled();
    }

    /**
     * Check if we should profile this tick based on sample rate.
     * @return true if profiling should occur
     */
    private boolean shouldProfileThisTick() {
        return monitoringConfig.isProfilingEnabled() && 
               (getTickCounter() % monitoringConfig.getProfilingSampleRate() == 0);
    }

    /**
     * Check if we should log optimizations this tick.
     * @return true if optimizations should be logged
     */
    private boolean shouldLogOptimizations() {
        return monitoringConfig.isProfilingEnabled() && 
               (getTickCounter() % monitoringConfig.getLogIntervalTicks() == 0);
    }

    /**
     * Get current tick counter (simplified for this example).
     * @return tick counter
     */
    private int getTickCounter() {
        return 0;
    }

    /**
     * Get total entities processed.
     * @return total entities processed
     */
    public int getTotalEntitiesProcessed() {
        return totalEntitiesProcessed.get();
    }

    /**
     * Get total mobs processed.
     * @return total mobs processed
     */
    public int getTotalMobsProcessed() {
        return totalMobsProcessed.get();
    }

    /**
     * Get total blocks processed.
     * @return total blocks processed
     */
    public int getTotalBlocksProcessed() {
        return totalBlocksProcessed.get();
    }

    /**
     * Reset all counters.
     */
    public void resetCounters() {
        totalEntitiesProcessed.set(0);
        totalMobsProcessed.set(0);
        totalBlocksProcessed.set(0);
    }

    /**
     * Record holding entity data collections.
     */
    public record EntityDataCollection(
        List<EntityData> entities,
        List<MobData> mobs,
        List<BlockEntityData> blockEntities,
        List<PlayerData> players) {}

    /**
     * Record holding optimization results.
     */
    public record OptimizationResults(
        List<Long> toTick,
        List<Long> blockResult,
        MobProcessResult mobResult) {}
}