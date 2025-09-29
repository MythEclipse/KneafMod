package com.kneaf.core.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import com.kneaf.core.data.EntityData;
import com.kneaf.core.data.ItemEntityData;
import com.kneaf.core.data.MobData;
import com.kneaf.core.data.BlockEntityData;
import com.kneaf.core.data.PlayerData;

/**
 * Manages performance optimizations for the Minecraft server.
 * Handles entity ticking, item merging, mob AI optimization, and block entity management.
 * Now includes multithreading for server tasks, network optimization, and chunk generation.
 */
public class PerformanceManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCounter = 0;
    private static long lastTickTime = 0;

    // Configuration (loaded from config/kneaf-performance.properties)
    private static final PerformanceConfig CONFIG = PerformanceConfig.load();

    // Profiling configuration
    private static final boolean PROFILING_ENABLED = CONFIG.isProfilingEnabled();
    private static final long SLOW_TICK_THRESHOLD_MS = CONFIG.getSlowTickThresholdMs();
    private static final int PROFILING_SAMPLE_RATE = CONFIG.getProfilingSampleRate();

    // Advanced ThreadPoolExecutor with dynamic sizing and monitoring
    private static ThreadPoolExecutor serverTaskExecutor = null;
    private static final Object executorLock = new Object();
    
    // Executor monitoring and metrics
    public static final class ExecutorMetrics {
        long totalTasksSubmitted = 0;
        long totalTasksCompleted = 0;
        long totalTasksRejected = 0;
        long currentQueueSize = 0;
        double currentUtilization = 0.0;
        int currentThreadCount = 0;
        int peakThreadCount = 0;
        long lastScaleUpTime = 0;
        long lastScaleDownTime = 0;
        int scaleUpCount = 0;
        int scaleDownCount = 0;
        
        String toJson() {
            return String.format(
                "{\"totalTasksSubmitted\":%d,\"totalTasksCompleted\":%d,\"totalTasksRejected\":%d," +
                "\"currentQueueSize\":%d,\"currentUtilization\":%.2f,\"currentThreadCount\":%d," +
                "\"peakThreadCount\":%d,\"scaleUpCount\":%d,\"scaleDownCount\":%d}",
                totalTasksSubmitted, totalTasksCompleted, totalTasksRejected,
                currentQueueSize, currentUtilization, currentThreadCount,
                peakThreadCount, scaleUpCount, scaleDownCount
            );
        }
    }
    
    private static final ExecutorMetrics executorMetrics = new ExecutorMetrics();

    // Rolling TPS average to make decisions about whether to offload work
    private static final int TPS_WINDOW = 20;
    private static final double[] tpsWindow = new double[TPS_WINDOW];
    private static int tpsWindowIndex = 0;

    // Dynamic async thresholding based on executor queue size
    private static double currentTpsThreshold;
    private static final double MIN_TPS_THRESHOLD = 15.0;
    private static final double MAX_TPS_THRESHOLD = 19.5;
    private static final int QUEUE_SIZE_HIGH_THRESHOLD = 10;
    private static final int QUEUE_SIZE_LOW_THRESHOLD = 3;
    private static final double THRESHOLD_ADJUSTMENT_RATE = 0.1;

    // Profiling data structures
    private static final class ProfileData {
        long entityCollectionTime = 0;
        long itemConsolidationTime = 0;
        long optimizationProcessingTime = 0;
        long optimizationApplicationTime = 0;
        long spatialGridTime = 0;
        long totalTickTime = 0;
        int entitiesProcessed = 0;
        int itemsProcessed = 0;
        int executorQueueSize = 0;
        
        boolean isSlowTick() {
            return totalTickTime > SLOW_TICK_THRESHOLD_MS * 1_000_000; // Convert ms to ns
        }
        
        String toJson() {
            return String.format(
                "{\"entityCollectionMs\":%.2f,\"itemConsolidationMs\":%.2f,\"optimizationProcessingMs\":%.2f,\"optimizationApplicationMs\":%.2f,\"spatialGridMs\":%.2f,\"totalTickMs\":%.2f,\"entitiesProcessed\":%d,\"itemsProcessed\":%d,\"executorQueueSize\":%d}",
                entityCollectionTime / 1_000_000.0,
                itemConsolidationTime / 1_000_000.0,
                optimizationProcessingTime / 1_000_000.0,
                optimizationApplicationTime / 1_000_000.0,
                spatialGridTime / 1_000_000.0,
                totalTickTime / 1_000_000.0,
                entitiesProcessed,
                itemsProcessed,
                executorQueueSize
            );
        }
    }
    
    private static final ThreadLocal<ProfileData> profileData = new ThreadLocal<ProfileData>() {
        @Override
        protected ProfileData initialValue() {
            return new ProfileData();
        }
    };

    private static ThreadPoolExecutor getExecutor() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null || serverTaskExecutor.isShutdown()) {
                createAdvancedThreadPool();
            }
            return serverTaskExecutor;
        }
    }
    
    private static void createAdvancedThreadPool() {
        // give threads unique names to make debugging easier
        AtomicInteger threadIndex = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "kneaf-perf-worker-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        
        int coreThreads = CONFIG.getMinThreadPoolSize();
        int maxThreads = CONFIG.getMaxThreadPoolSize();
        
        // CPU-aware sizing if enabled
        if (CONFIG.isCpuAwareThreadSizing()) {
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            double cpuLoad = getSystemCpuLoad();
            
            if (cpuLoad < CONFIG.getCpuLoadThreshold()) {
                // CPU is not heavily loaded, can use more threads
                maxThreads = Math.min(maxThreads, availableProcessors);
            } else {
                // CPU is heavily loaded, be conservative
                maxThreads = Math.min(maxThreads, Math.max(1, availableProcessors / 2));
            }
            
            coreThreads = Math.min(coreThreads, maxThreads);
        }
        
        // Adaptive sizing based on available processors if enabled
        if (CONFIG.isAdaptiveThreadPool()) {
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            maxThreads = clamp(availableProcessors - 1, 1, maxThreads);
            coreThreads = Math.min(coreThreads, maxThreads);
        }
        
        // Create work-stealing queue if enabled
        LinkedBlockingQueue<Runnable> workQueue;
        if (CONFIG.isWorkStealingEnabled()) {
            workQueue = new LinkedBlockingQueue<>(CONFIG.getWorkStealingQueueSize());
        } else {
            workQueue = new LinkedBlockingQueue<>();
        }
        
        serverTaskExecutor = new ThreadPoolExecutor(
            coreThreads,
            maxThreads,
            CONFIG.getThreadPoolKeepAliveSeconds(),
            TimeUnit.SECONDS,
            workQueue,
            factory
        );
        
        // Allow core threads to timeout if not needed
        serverTaskExecutor.allowCoreThreadTimeOut(true);
        
        // Initialize metrics
        executorMetrics.currentThreadCount = coreThreads;
        executorMetrics.peakThreadCount = coreThreads;
        
        LOGGER.info("Created advanced ThreadPoolExecutor: core={}, max={}, workStealing={}, cpuAware={}",
                   coreThreads, maxThreads, CONFIG.isWorkStealingEnabled(), CONFIG.isCpuAwareThreadSizing());
        
        // Initialize dynamic threshold with config value
        currentTpsThreshold = CONFIG.getTpsThresholdForAsync();
    }
    
    private static double getSystemCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            // Use system CPU load instead of process CPU load for broader availability
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad < 0) {
                // Fallback: estimate based on available processors
                int availableProcessors = osBean.getAvailableProcessors();
                return Math.min(1.0, systemLoad / availableProcessors);
            }
            // Normalize system load to 0-1 range based on available processors
            int availableProcessors = osBean.getAvailableProcessors();
            return Math.min(1.0, systemLoad / availableProcessors);
        } catch (Exception e) {
            LOGGER.debug("Could not get CPU load, using default", e);
            return 0.0;
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public static void shutdown() {
        synchronized (executorLock) {
            if (serverTaskExecutor != null) {
                try {
                    LOGGER.info("Shutting down ThreadPoolExecutor. Metrics: {}", executorMetrics.toJson());
                    serverTaskExecutor.shutdown();
                    if (!serverTaskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOGGER.warn("ThreadPoolExecutor did not terminate gracefully, forcing shutdown");
                        serverTaskExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    serverTaskExecutor.shutdownNow();
                } finally {
                    serverTaskExecutor = null;
                }
            }
        }
        
        // Clean up spatial grids
        synchronized (spatialGridLock) {
            levelSpatialGrids.clear();
        }
    }
    
    /**
     * Get current executor metrics for monitoring and debugging
     */
    public static String getExecutorMetrics() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null) {
                return "{\"status\":\"not_initialized\"}";
            }
            return executorMetrics.toJson();
        }
    }
    
    /**
     * Get current executor status including pool configuration
     */
    public static String getExecutorStatus() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null) {
                return "Executor not initialized";
            }
            
            return String.format("ThreadPoolExecutor[core=%d, max=%d, current=%d, active=%d, queue=%d, completed=%d]",
                serverTaskExecutor.getCorePoolSize(),
                serverTaskExecutor.getMaximumPoolSize(),
                serverTaskExecutor.getPoolSize(),
                serverTaskExecutor.getActiveCount(),
                serverTaskExecutor.getQueue().size(),
                serverTaskExecutor.getCompletedTaskCount());
        }
    }
    
    /**
     * Validate executor health and configuration
     */
    public static boolean isExecutorHealthy() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null || serverTaskExecutor.isShutdown() || serverTaskExecutor.isTerminated()) {
                return false;
            }
            
            // Check if queue is not excessively backed up
            int queueSize = serverTaskExecutor.getQueue().size();
            int maxQueueSize = CONFIG.isWorkStealingEnabled() ? CONFIG.getWorkStealingQueueSize() : 1000;
            
            if (queueSize > maxQueueSize * 0.9) {
                LOGGER.warn("Executor queue is nearly full: {}/{}", queueSize, maxQueueSize);
                return false;
            }
            
            // Check if we're at maximum threads and still highly utilized
            double utilization = getExecutorUtilization();
            if (serverTaskExecutor.getPoolSize() >= serverTaskExecutor.getMaximumPoolSize() && utilization > 0.95) {
                LOGGER.warn("Executor at max capacity with high utilization: {} threads, {:.2f} utilization",
                           serverTaskExecutor.getPoolSize(), utilization);
                return false;
            }
            
            return true;
        }
    }

    /**
     * Get the current executor queue size for dynamic threshold adjustment.
     * Returns 0 if executor is not available.
     */
    private static int getExecutorQueueSize() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null) return 0;
            return serverTaskExecutor.getQueue().size();
        }
    }
    
    /**
     * Get executor utilization for dynamic scaling decisions
     */
    private static double getExecutorUtilization() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null) return 0.0;
            int activeThreads = serverTaskExecutor.getActiveCount();
            int poolSize = Math.max(1, serverTaskExecutor.getPoolSize());
            return (double) activeThreads / poolSize;
        }
    }
    
    /**
     * Update executor metrics for monitoring
     */
    private static void updateExecutorMetrics() {
        synchronized (executorLock) {
            if (serverTaskExecutor == null) return;
            
            executorMetrics.currentQueueSize = serverTaskExecutor.getQueue().size();
            executorMetrics.currentThreadCount = serverTaskExecutor.getPoolSize();
            executorMetrics.peakThreadCount = Math.max(executorMetrics.peakThreadCount, executorMetrics.currentThreadCount);
            executorMetrics.currentUtilization = getExecutorUtilization();
        }
    }

    /**
     * Adjust TPS threshold dynamically based on executor queue size.
     * Lower threshold when queue grows to maintain responsiveness.
     */
    private static void adjustDynamicThreshold() {
        int queueSize = getExecutorQueueSize();
        int currentTick = tickCounter % TPS_WINDOW;
        
        // Adjust threshold based on queue pressure
        if (queueSize > QUEUE_SIZE_HIGH_THRESHOLD) {
            // High queue pressure - lower threshold to reduce async load
            currentTpsThreshold = Math.max(MIN_TPS_THRESHOLD, currentTpsThreshold - THRESHOLD_ADJUSTMENT_RATE);
        } else if (queueSize < QUEUE_SIZE_LOW_THRESHOLD && currentTick % 5 == 0) {
            // Low queue pressure - gradually raise threshold (every 5 ticks to avoid oscillation)
            currentTpsThreshold = Math.min(MAX_TPS_THRESHOLD, currentTpsThreshold + (THRESHOLD_ADJUSTMENT_RATE * 0.5));
        }
        
        // Ensure threshold stays within config bounds
        currentTpsThreshold = clamp(currentTpsThreshold, MIN_TPS_THRESHOLD, MAX_TPS_THRESHOLD);
    }

    private PerformanceManager() {}

    // Runtime toggle (initialized from config)
    private static volatile boolean enabled = CONFIG.isEnabled();

    // Spatial grid for efficient player position queries per level
    private static final Map<ServerLevel, SpatialGrid> levelSpatialGrids = new HashMap<>();
    private static final Object spatialGridLock = new Object();
    
    // Asynchronous distance calculation configuration
    private static final int DISTANCE_CALCULATION_INTERVAL = 5; // Calculate distances every 5 ticks
    private static final Map<ServerLevel, CompletableFuture<Void>> pendingDistanceCalculations = new HashMap<>();

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean val) { enabled = val; }

    private record EntityDataCollection(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, List<BlockEntityData> blockEntities, List<PlayerData> players) {}

    /**
     * Called on every server tick to perform performance optimizations.
     * Now uses multithreading for processing optimizations asynchronously.
     */
    public static void onServerTick(MinecraftServer server) {
        // Respect runtime toggle first (can be flipped without restarting)
        if (!enabled) return;

        long tickStartTime = PROFILING_ENABLED ? System.nanoTime() : 0;
        ProfileData profile = PROFILING_ENABLED ? profileData.get() : null;
        
        updateTPS();
        tickCounter++;

        // Adjust dynamic threshold based on executor metrics
        if (tickCounter % 2 == 0) { // Adjust every 2 ticks for responsiveness
            adjustDynamicThreshold();
        }

        // Respect configured scan interval to reduce overhead on busy servers
        if (tickCounter % CONFIG.getScanIntervalTicks() != 0) {
            return;
        }

        // Sample profiling based on configured rate
        boolean shouldProfile = PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0);
        if (shouldProfile && profile != null) {
            profile.executorQueueSize = getExecutorQueueSize();
            profile.totalTickTime = System.nanoTime() - tickStartTime;
        }

        // Collect data synchronously (cheap collection)
        long entityCollectionStart = shouldProfile ? System.nanoTime() : 0;
        EntityDataCollection data = collectEntityData(server);
        if (shouldProfile && profile != null) {
            profile.entityCollectionTime = System.nanoTime() - entityCollectionStart;
            profile.entitiesProcessed = data.entities().size();
            profile.itemsProcessed = data.items().size();
        }

        // Consolidate collected items (aggregation by chunk+type) to reduce downstream work
        long consolidationStart = shouldProfile ? System.nanoTime() : 0;
        List<ItemEntityData> consolidated = consolidateItemEntities(data.items());
        if (shouldProfile && profile != null) {
            profile.itemConsolidationTime = System.nanoTime() - consolidationStart;
        }
        data = new EntityDataCollection(data.entities(), consolidated, data.mobs(), data.blockEntities(), data.players());

        // Decide whether to offload heavy processing based on rolling TPS and dynamic threshold
        double avgTps = getRollingAvgTPS();
        if (avgTps >= currentTpsThreshold) {
            submitAsyncOptimizations(server, data, shouldProfile);
        } else {
            runSynchronousOptimizations(server, data, shouldProfile);
        }
        
        // Log slow ticks with detailed profiling
        if (shouldProfile && profile != null && profile.isSlowTick()) {
            logSlowTick(profile);
        }
    }

    private static void submitAsyncOptimizations(MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
        try {
            getExecutor().submit(() -> {
                try {
                    long processingStart = shouldProfile ? System.nanoTime() : 0;
                    OptimizationResults results = processOptimizations(data);
                    if (shouldProfile) {
                        ProfileData profile = profileData.get();
                        if (profile != null) {
                            profile.optimizationProcessingTime = System.nanoTime() - processingStart;
                        }
                    }
                    
                    // Schedule modifications back on server thread to stay thread-safe with Minecraft internals
                    server.execute(() -> {
                        try {
                            long applicationStart = shouldProfile ? System.nanoTime() : 0;
                            applyOptimizations(server, results);
                            if (shouldProfile) {
                                ProfileData profile = profileData.get();
                                if (profile != null) {
                                    profile.optimizationApplicationTime = System.nanoTime() - applicationStart;
                                }
                            }
                            logOptimizations(results);
                            removeItems(server, results.itemResult());
                        } catch (Exception e) {
                            LOGGER.warn("Error applying optimizations on server thread", e);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.warn("Error during async processing of optimizations", e);
                }
            });
        } catch (Exception e) {
            // Fallback to synchronous processing if executor rejects
            LOGGER.debug("Executor rejected task; running synchronously", e);
            runSynchronousOptimizations(server, data, shouldProfile);
        }
    }

    private static void runSynchronousOptimizations(MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
        try {
            long processingStart = shouldProfile ? System.nanoTime() : 0;
            OptimizationResults results = processOptimizations(data);
            if (shouldProfile) {
                ProfileData profile = profileData.get();
                if (profile != null) {
                    profile.optimizationProcessingTime = System.nanoTime() - processingStart;
                }
            }
            
            long applicationStart = shouldProfile ? System.nanoTime() : 0;
            applyOptimizations(server, results);
            if (shouldProfile) {
                ProfileData profile = profileData.get();
                if (profile != null) {
                    profile.optimizationApplicationTime = System.nanoTime() - applicationStart;
                }
            }
            
            if (tickCounter % CONFIG.getLogIntervalTicks() == 0) logOptimizations(results);
            removeItems(server, results.itemResult());
        } catch (Exception ex) {
            LOGGER.warn("Error processing optimizations synchronously", ex);
        }
    }

    private static void updateTPS() {
        long currentTime = System.nanoTime();
        if (lastTickTime != 0) {
            long delta = currentTime - lastTickTime;
            double tps = 1_000_000_000.0 / delta;
            double capped = Math.min(tps, 20.0);
            RustPerformance.setCurrentTPS(capped);
            // update rolling window
            tpsWindow[tpsWindowIndex % TPS_WINDOW] = capped;
            tpsWindowIndex = (tpsWindowIndex + 1) % TPS_WINDOW;
        }
        lastTickTime = currentTime;
    }

    private static double getRollingAvgTPS() {
        double sum = 0.0;
        int count = 0;
        for (double v : tpsWindow) {
            if (v > 0) { sum += v; count++; }
        }
        return count == 0 ? 20.0 : sum / count;
    }

    private static EntityDataCollection collectEntityData(MinecraftServer server) {
        List<EntityData> entities = new ArrayList<>();
        List<ItemEntityData> items = new ArrayList<>();
        List<MobData> mobs = new ArrayList<>();
        List<BlockEntityData> blockEntities = new ArrayList<>();
        List<PlayerData> players = new ArrayList<>();

        int maxEntities = CONFIG.getMaxEntitiesToCollect();
        double distanceCutoff = CONFIG.getEntityDistanceCutoff();
        for (ServerLevel level : server.getAllLevels()) {
            // Precompute player positions once per level and pass into entity collection to avoid duplicate work
            List<PlayerData> levelPlayers = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                levelPlayers.add(new PlayerData(p.getId(), p.getX(), p.getY(), p.getZ()));
            }
            collectEntitiesFromLevel(level, entities, items, mobs, maxEntities, distanceCutoff, levelPlayers);
            // collect into global players list used by Rust processing
            players.addAll(levelPlayers);
            if (entities.size() >= maxEntities) break; // global cap
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities, players);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, int maxEntities, double distanceCutoff, List<PlayerData> players) {
        String[] excluded = CONFIG.getExcludedEntityTypes();
        double cutoffSq = distanceCutoff * distanceCutoff;
        
        // Get or create spatial grid for this level with profiling
        long spatialStart = PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0) ? System.nanoTime() : 0;
        SpatialGrid spatialGrid = getOrCreateSpatialGrid(level, players);
        if (PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0)) {
            ProfileData profile = profileData.get();
            if (profile != null) {
                profile.spatialGridTime += System.nanoTime() - spatialStart;
            }
        }

        // Create bounding box that encompasses all players within distance cutoff
        AABB searchBounds = createSearchBounds(players, distanceCutoff);
        
        // Use asynchronous distance calculations every N ticks to reduce CPU load
        if (tickCounter % DISTANCE_CALCULATION_INTERVAL == 0) {
            // Full synchronous distance calculation
            for (Entity entity : level.getEntities(null, searchBounds)) {
                // If we've reached the maximum, stop scanning further entities in this level
                if (entities.size() >= maxEntities) break;
                // Use spatial grid for efficient distance calculation
                double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, distanceCutoff);
                if (minSq <= cutoffSq) {
                    processEntityWithinCutoff(entity, minSq, excluded, entities, items, mobs);
                }
            }
        } else {
            // Reduced frequency calculation - use cached distances or approximate
            performReducedDistanceCalculation(level, searchBounds, entities, items, mobs, maxEntities,
                                            excluded, spatialGrid, cutoffSq, distanceCutoff);
        }
    }

    // Helper to compute squared distance to nearest player
    private static double computeMinSquaredDistanceToPlayers(Entity entity, List<PlayerData> players) {
        double minSq = Double.MAX_VALUE;
        for (PlayerData p : players) {
            double dx = entity.getX() - p.x();
            double dy = entity.getY() - p.y();
            double dz = entity.getZ() - p.z();
            double sq = dx*dx + dy*dy + dz*dz;
            if (sq < minSq) minSq = sq;
        }
        return minSq;
    }

    // Optimized version using spatial grid - O(log M) instead of O(M)
    private static double computeMinSquaredDistanceToPlayersOptimized(Entity entity, SpatialGrid spatialGrid, double maxSearchRadius) {
        return spatialGrid.findMinSquaredDistance(entity.getX(), entity.getY(), entity.getZ(), maxSearchRadius);
    }

    // Create search bounds based on player positions and distance cutoff
    private static AABB createSearchBounds(List<PlayerData> players, double distanceCutoff) {
        if (players.isEmpty()) {
            // If no players, return a minimal bounds that will return no entities
            return new AABB(0, 0, 0, 0, 0, 0);
        }
        
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        double maxZ = Double.MIN_VALUE;
        
        // Find bounds encompassing all players
        for (PlayerData player : players) {
            minX = Math.min(minX, player.x());
            minY = Math.min(minY, player.y());
            minZ = Math.min(minZ, player.z());
            maxX = Math.max(maxX, player.x());
            maxY = Math.max(maxY, player.y());
            maxZ = Math.max(maxZ, player.z());
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

    // Get or create spatial grid for a level, updating it with current player positions
    private static SpatialGrid getOrCreateSpatialGrid(ServerLevel level, List<PlayerData> players) {
        long startTime = PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0) ? System.nanoTime() : 0;
        
        synchronized (spatialGridLock) {
            SpatialGrid grid = levelSpatialGrids.get(level);
            if (grid == null) {
                // Create grid with cell size based on distance cutoff for optimal performance
                double cellSize = Math.max(CONFIG.getEntityDistanceCutoff() / 4.0, 16.0);
                grid = new SpatialGrid(cellSize);
                levelSpatialGrids.put(level, grid);
            }
            
            // Update grid with current player positions
            grid.clear();
            for (PlayerData player : players) {
                grid.updatePlayer(player);
            }
            
            return grid;
        }
        
        // Note: spatial grid time will be recorded by the caller if profiling is enabled
    }

    // Helper to process an entity that is within distance cutoff
    private static void processEntityWithinCutoff(Entity entity, double minSq, String[] excluded, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs) {
        String typeStr = entity.getType().toString();
        if (isExcludedType(typeStr, excluded)) return;
        double distance = Math.sqrt(minSq);
        boolean isBlockEntity = false; // Regular entities are not block entities
        entities.add(new EntityData(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), distance, isBlockEntity, typeStr));

        if (entity instanceof ItemEntity itemEntity) {
            collectItemEntity(entity, itemEntity, items);
        } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
            collectMobEntity(entity, mob, mobs, distance, typeStr);
        }
    }

    private static boolean isExcludedType(String typeStr, String[] excluded) {
        if (excluded == null || excluded.length == 0) return false;
        for (String ex : excluded) {
            if (ex == null || ex.isEmpty()) continue;
            if (typeStr.contains(ex)) return true;
        }
        return false;
    }

    private static void collectItemEntity(Entity entity, ItemEntity itemEntity, List<ItemEntityData> items) {
        var chunkPos = entity.chunkPosition();
        var itemStack = itemEntity.getItem();
        var itemType = itemStack.getItem().getDescriptionId();
        var count = itemStack.getCount();
        var ageSeconds = itemEntity.getAge() / 20;
        items.add(new ItemEntityData(entity.getId(), chunkPos.x, chunkPos.z, itemType, count, ageSeconds));
    }

    /**
     * Perform reduced frequency distance calculation to optimize performance.
     * Uses cached distances and approximate calculations when full precision is not needed.
     */
    private static void performReducedDistanceCalculation(ServerLevel level, AABB searchBounds,
                                                         List<EntityData> entities, List<ItemEntityData> items,
                                                         List<MobData> mobs, int maxEntities, String[] excluded,
                                                         SpatialGrid spatialGrid, double cutoffSq, double distanceCutoff) {
        // Use a larger search radius to reduce false negatives, then filter more precisely for close entities
        double approximateCutoff = distanceCutoff * 1.2; // 20% larger for safety margin
        double approximateCutoffSq = approximateCutoff * approximateCutoff;
        
        // First pass: quick approximate filtering using spatial grid
        List<Entity> candidateEntities = new ArrayList<>();
        for (Entity entity : level.getEntities(null, searchBounds)) {
            if (entities.size() >= maxEntities) break;
            
            // Quick approximate check using spatial grid
            double approxMinSq = spatialGrid.findMinSquaredDistance(
                entity.getX(), entity.getY(), entity.getZ(), approximateCutoff);
            
            if (approxMinSq <= approximateCutoffSq) {
                candidateEntities.add(entity);
            }
        }
        
        // Second pass: precise calculation only for candidates
        for (Entity entity : candidateEntities) {
            if (entities.size() >= maxEntities) break;
            
            String typeStr = entity.getType().toString();
            if (isExcludedType(typeStr, excluded)) continue;
            
            // Precise distance calculation
            double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, distanceCutoff);
            if (minSq <= cutoffSq) {
                processEntityWithinCutoff(entity, minSq, excluded, entities, items, mobs);
            }
        }
    }

    private static void collectMobEntity(Entity entity, net.minecraft.world.entity.Mob mob, List<MobData> mobs, double distance, String typeStr) {
        // Use the precomputed distance from caller to avoid an extra player iteration
        boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
        mobs.add(new MobData(entity.getId(), distance, isPassive, typeStr));
    }




    /**
     * Pack chunk coordinates and item type hash into a composite long key.
     * Format: [chunkX (21 bits)][chunkZ (21 bits)][itemTypeHash (22 bits)]
     * This provides efficient HashMap operations without string allocations.
     */
    private static long packItemKey(int chunkX, int chunkZ, String itemType) {
        // Use 21 bits for each coordinate (covers ±1 million chunks) and 22 bits for hash
        long packedChunkX = ((long) chunkX) & 0x1FFFFF; // 21 bits
        long packedChunkZ = ((long) chunkZ) & 0x1FFFFF; // 21 bits
        long itemHash = itemType == null ? 0 : ((long) itemType.hashCode()) & 0x3FFFFF; // 22 bits
        
        return (packedChunkX << 43) | (packedChunkZ << 22) | itemHash;
    }
    
    /**
     * Consolidate collected item entity data by chunk X/Z and item type to reduce the number
     * of items we hand to the downstream Rust processing. This only aggregates the collected
     * snapshot and does not modify world state directly, so it is safe and purely an optimization.
     */
    private static List<ItemEntityData> consolidateItemEntities(List<ItemEntityData> items) {
        if (items == null || items.isEmpty()) return items;
        
        // Use composite long keys to eliminate string allocations and speed up HashMap operations
        Map<Long, ItemEntityData> agg = new HashMap<>();
        
        for (ItemEntityData it : items) {
            // Pack chunk coordinates and item type into a composite long key
            long key = packItemKey(it.chunkX(), it.chunkZ(), it.itemType());
            
            ItemEntityData cur = agg.get(key);
            if (cur == null) {
                agg.put(key, it);
            } else {
                // sum counts and keep smallest age to represent the merged group
                int newCount = cur.count() + it.count();
                int newAge = Math.min(cur.ageSeconds(), it.ageSeconds());
                agg.put(key, new ItemEntityData(-1, it.chunkX(), it.chunkZ(), it.itemType(), newCount, newAge));
            }
        }
        return new ArrayList<>(agg.values());
    }

    // (Intentionally using direct HashMap construction for simplicity)

    private static OptimizationResults processOptimizations(EntityDataCollection data) {
        // Use parallel performance optimizations
        List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities(), data.players());
        List<Long> blockResult = RustPerformance.getBlockEntitiesToTick(data.blockEntities());
        RustPerformance.ItemProcessResult itemResult = RustPerformance.processItemEntities(data.items());
        RustPerformance.MobProcessResult mobResult = RustPerformance.processMobAI(data.mobs());
        return new OptimizationResults(toTick, blockResult, itemResult, mobResult);
    }

    private static void applyOptimizations(MinecraftServer server, OptimizationResults results) {
        applyItemUpdates(server, results.itemResult());
        applyMobOptimizations(server, results.mobResult());
    }

    private static void applyItemUpdates(MinecraftServer server, RustPerformance.ItemProcessResult itemResult) {
        if (itemResult == null || itemResult.getItemUpdates() == null) return;
        
        // Batch entity lookup: create a map of entity IDs to updates for O(1) lookup
        Map<Integer, RustPerformance.ItemUpdate> updateMap = new HashMap<>();
        for (var update : itemResult.getItemUpdates()) {
            updateMap.put((int) update.getId(), update);
        }
        
        // Process all levels once - O(U + L) complexity
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<Integer, RustPerformance.ItemUpdate> entry : updateMap.entrySet()) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity instanceof ItemEntity itemEntity) {
                    RustPerformance.ItemUpdate update = entry.getValue();
                    itemEntity.getItem().setCount(update.getNewCount());
                }
            }
        }
    }

    private static void applyMobOptimizations(MinecraftServer server, RustPerformance.MobProcessResult mobResult) {
        if (mobResult == null) return;
        
        // Batch entity lookup for mobs to disable AI
        Set<Integer> disableAiIds = new HashSet<>();
        for (Long id : mobResult.getMobsToDisableAI()) {
            disableAiIds.add(id.intValue());
        }
        
        // Batch entity lookup for mobs to simplify AI
        Set<Integer> simplifyAiIds = new HashSet<>();
        for (Long id : mobResult.getMobsToSimplifyAI()) {
            simplifyAiIds.add(id.intValue());
        }
        
        // Process all levels once - O(U + L) complexity
        for (ServerLevel level : server.getAllLevels()) {
            // Process mobs to disable AI
            for (Integer id : disableAiIds) {
                Entity entity = level.getEntity(id);
                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setNoAi(true);
                }
            }
            
            // Process mobs to simplify AI
            for (Integer id : simplifyAiIds) {
                Entity entity = level.getEntity(id);
                if (entity instanceof net.minecraft.world.entity.Mob) {
                    LOGGER.debug("Simplifying AI for mob {}", id);
                }
            }
        }
    }

    private static void logOptimizations(OptimizationResults results) {
        // Human-readable logs for meaningful changes (less frequent)
        if (tickCounter % 100 == 0 && hasMeaningfulOptimizations(results)) {
            logReadableOptimizations(results);
        }

        // Log spatial grid statistics periodically for performance monitoring
        if (tickCounter % 1000 == 0) {
            logSpatialGridStats();
        }

        // Compact metrics line periodically
        if (tickCounter % CONFIG.getLogIntervalTicks() == 0 && hasMeaningfulOptimizations(results)) {
            String summary = buildOptimizationSummary(results);
            PerformanceMetricsLogger.logOptimizations(summary);
        }
    }

    private static void logReadableOptimizations(OptimizationResults results) {
        if (results == null) return;
        if (results.itemResult() != null) {
            long merged = results.itemResult().getMergedCount();
            long despawned = results.itemResult().getDespawnedCount();
            if (merged > 0 || despawned > 0) {
                LOGGER.info("Item optimization: {} merged, {} despawned", merged, despawned);
            }
            if (!results.itemResult().getItemsToRemove().isEmpty()) {
                LOGGER.info("Items removed: {}", results.itemResult().getItemsToRemove().size());
            }
        }

        if (results.mobResult() != null) {
            int disabled = results.mobResult().getMobsToDisableAI().size();
            int simplified = results.mobResult().getMobsToSimplifyAI().size();
            if (disabled > 0 || simplified > 0) {
                LOGGER.info("Mob AI optimization: {} disabled, {} simplified", disabled, simplified);
            }
        }

        if (results.blockResult() != null && !results.blockResult().isEmpty()) {
            LOGGER.info("Block entities to tick (reduced): {}", results.blockResult().size());
        }
    }

    private static void logSpatialGridStats() {
        synchronized (spatialGridLock) {
            int totalLevels = levelSpatialGrids.size();
            int totalPlayers = 0;
            int totalCells = 0;
            
            for (SpatialGrid grid : levelSpatialGrids.values()) {
                SpatialGrid.GridStats stats = grid.getStats();
                totalPlayers += stats.totalPlayers();
                totalCells += stats.totalCells();
            }
            
            if (totalLevels > 0) {
                LOGGER.debug("SpatialGrid stats: {} levels, {} players, {} cells, avg {:.2f} players/cell",
                    totalLevels, totalPlayers, totalCells,
                    totalCells > 0 ? (double) totalPlayers / totalCells : 0.0);
            }
        }
    }

    /**
     * Log slow ticks with detailed timing breakdown in JSON format for structured logging.
     */
    private static void logSlowTick(ProfileData profile) {
        if (profile == null) return;
        
        String jsonProfile = profile.toJson();
        String message = String.format("SLOW_TICK: tick=%d avgTps=%.2f threshold=%.2f %s",
            tickCounter, getRollingAvgTPS(), currentTpsThreshold, jsonProfile);
        
        LOGGER.warn(message);
        PerformanceMetricsLogger.logLine("SLOW_TICK " + message);
    }

    private static String buildOptimizationSummary(OptimizationResults results) {
        double avg = getRollingAvgTPS();
        long itemsMerged = results.itemResult() == null ? 0L : results.itemResult().getMergedCount();
        long itemsDespawned = results.itemResult() == null ? 0L : results.itemResult().getDespawnedCount();
        int mobsDisabled = results.mobResult() == null ? 0 : results.mobResult().getMobsToDisableAI().size();
        int mobsSimplified = results.mobResult() == null ? 0 : results.mobResult().getMobsToSimplifyAI().size();
        int itemsRemoved = results.itemResult() == null ? 0 : results.itemResult().getItemsToRemove().size();
        int blockEntities = results.blockResult() == null ? 0 : results.blockResult().size();
        int queueSize = getExecutorQueueSize();
        return String.format("avgTps=%.2f currentThreshold=%.2f queueSize=%d itemsMerged=%d itemsDespawned=%d mobsDisabled=%d mobsSimplified=%d itemsRemoved=%d blockEntities=%d",
                avg,
                currentTpsThreshold,
                queueSize,
                itemsMerged,
                itemsDespawned,
                mobsDisabled,
                mobsSimplified,
                itemsRemoved,
                blockEntities);
    }

    /**
     * Return true only if optimizations include changes that actually mutate the world or remove entities.
     */
    private static boolean hasMeaningfulOptimizations(OptimizationResults results) {
        if (results == null) return false;
        if (results.itemResult() != null) {
            if (results.itemResult().getMergedCount() > 0) return true;
            if (results.itemResult().getDespawnedCount() > 0) return true;
            if (!results.itemResult().getItemsToRemove().isEmpty()) return true;
        }
        if (results.mobResult() != null) {
            if (!results.mobResult().getMobsToDisableAI().isEmpty()) return true;
            if (!results.mobResult().getMobsToSimplifyAI().isEmpty()) return true;
        }
            return results.blockResult() != null && !results.blockResult().isEmpty();
    }

    

    private static void removeItems(MinecraftServer server, RustPerformance.ItemProcessResult itemResult) {
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


    


    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, RustPerformance.ItemProcessResult itemResult, RustPerformance.MobProcessResult mobResult) {}

    // Static initializer to set initial threshold
    static {
        currentTpsThreshold = CONFIG.getTpsThresholdForAsync();
    }
}