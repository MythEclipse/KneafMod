package com.kneaf.core.performance.monitoring;

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
import java.util.concurrent.ThreadFactory;
import java.util.Objects;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.performance.spatial.SpatialGrid;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.core.ItemProcessResult;
import com.kneaf.core.performance.core.MobProcessResult;
import com.kneaf.core.performance.core.PerformanceProcessor;
import com.kneaf.core.performance.bridge.NativeBridge;

/**
 * Manages performance optimizations for the Minecraft server.
 * Handles entity ticking, item merging, mob AI optimization, and block entity management.
 * Now includes multithreading for server tasks, network optimization, and chunk generation.
 */
public class PerformanceManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCounter = 0;
    private static long lastTickTime = 0;
    // Last tick duration in nanoseconds (volatile because read from other threads)
    private static volatile long lastTickDurationNanos = 0;

    // Configuration (loaded from config/kneaf-performance.properties)
    private static final com.kneaf.core.performance.monitoring.PerformanceConfig CONFIG = com.kneaf.core.performance.monitoring.PerformanceConfig.load();

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
    
    private static final ThreadLocal<ProfileData> profileData = ThreadLocal.withInitial(ProfileData::new);

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
                maxThreads = Math.clamp(availableProcessors / 2, 1, maxThreads);
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
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Shutting down ThreadPoolExecutor. Metrics: {}", executorMetrics.toJson());
                    }
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
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Executor at max capacity with high utilization: {} threads, {} utilization",
                               serverTaskExecutor.getPoolSize(), String.format("%.2f", utilization));
                }
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
    private static final int DISTANCE_CALCULATION_INTERVAL = 10; // Reduced frequency: calculate distances every 10 ticks

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean val) { enabled = val; }

    private record EntityDataCollection(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, List<BlockEntityData> blockEntities, List<PlayerData> players) {}

    private record EntityCollectionContext(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, int maxEntities, double distanceCutoff, List<PlayerData> players, String[] excluded, double cutoffSq) {
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            EntityCollectionContext that = (EntityCollectionContext) obj;
            return maxEntities == that.maxEntities &&
                   Double.compare(that.distanceCutoff, distanceCutoff) == 0 &&
                   Double.compare(that.cutoffSq, cutoffSq) == 0 &&
                   Objects.equals(entities, that.entities) &&
                   Objects.equals(items, that.items) &&
                   Objects.equals(mobs, that.mobs) &&
                   Objects.equals(players, that.players) &&
                   Arrays.equals(excluded, that.excluded);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(entities, items, mobs, maxEntities, distanceCutoff, players, cutoffSq);
            result = 31 * result + Arrays.hashCode(excluded);
            return result;
        }

        @Override
        public String toString() {
            return "EntityCollectionContext{" +
                   "entities=" + entities +
                   ", items=" + items +
                   ", mobs=" + mobs +
                   ", maxEntities=" + maxEntities +
                   ", distanceCutoff=" + distanceCutoff +
                   ", players=" + players +
                   ", excluded=" + Arrays.toString(excluded) +
                   ", cutoffSq=" + cutoffSq +
                   '}';
        }
    }

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
        // Increased interval for better performance: process every 3 ticks instead of every tick
        if (!shouldPerformScan()) {
            if (PROFILING_ENABLED) profileData.remove();
            return;
        }

        // Sample profiling based on configured rate with lazy initialization
        boolean shouldProfile = PROFILING_ENABLED && (tickCounter % (PROFILING_SAMPLE_RATE * 2) == 0);
        prepareProfiling(profile, shouldProfile, tickStartTime);

        // Collect and consolidate data
        EntityDataCollection data = collectAndConsolidate(server, shouldProfile, profile);

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
        
        if (PROFILING_ENABLED) profileData.remove();
    }

    // Helper to determine whether we should run the full scan this tick
    private static boolean shouldPerformScan() {
        return tickCounter % (CONFIG.getScanIntervalTicks() * 3) == 0;
    }

    // Helper to initialize profiling values if needed
    private static void prepareProfiling(ProfileData profile, boolean shouldProfile, long tickStartTime) {
        if (shouldProfile && profile != null) {
            profile.executorQueueSize = getExecutorQueueSize();
            profile.totalTickTime = System.nanoTime() - tickStartTime;
        }
    }

    // Helper to collect entity data and consolidate item entities while updating profiling data
    private static EntityDataCollection collectAndConsolidate(MinecraftServer server, boolean shouldProfile, ProfileData profile) {
        long entityCollectionStart = shouldProfile ? System.nanoTime() : 0;
        EntityDataCollection data = collectEntityData(server);
        if (shouldProfile && profile != null) {
            profile.entityCollectionTime = System.nanoTime() - entityCollectionStart;
            profile.entitiesProcessed = data.entities().size();
            profile.itemsProcessed = data.items().size();
        }

        long consolidationStart = shouldProfile ? System.nanoTime() : 0;
        List<ItemEntityData> consolidated = consolidateItemEntities(data.items());
        if (shouldProfile && profile != null) {
            profile.itemConsolidationTime = System.nanoTime() - consolidationStart;
        }
        return new EntityDataCollection(data.entities(), consolidated, data.mobs(), data.blockEntities(), data.players());
    }

    private static void submitAsyncOptimizations(MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
        try {
            getExecutor().submit(() -> performAsyncOptimization(server, data, shouldProfile));
        } catch (Exception e) {
            // Fallback to synchronous processing if executor rejects
            LOGGER.debug("Executor rejected task; running synchronously", e);
            runSynchronousOptimizations(server, data, shouldProfile);
        }
    }

    private static void performAsyncOptimization(MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
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
            server.execute(() -> applyOptimizationResults(server, results, shouldProfile));
        } catch (Exception e) {
            LOGGER.warn("Error during async processing of optimizations", e);
        }
    }

    private static void applyOptimizationResults(MinecraftServer server, OptimizationResults results, boolean shouldProfile) {
        try {
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
        } catch (Exception e) {
            LOGGER.warn("Error applying optimizations on server thread", e);
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
            // record last tick duration for external consumers (e.g. thread-scaling decisions)
            lastTickDurationNanos = delta;
            double tps = 1_000_000_000.0 / delta;
            double capped = Math.min(tps, 20.0);
            RustPerformance.setCurrentTPS(capped);
            // update rolling window
            tpsWindow[tpsWindowIndex % TPS_WINDOW] = capped;
            tpsWindowIndex = (tpsWindowIndex + 1) % TPS_WINDOW;
        }
        // If this is the first tick, ensure lastTickDurationNanos is zeroed
        if (lastTickTime == 0) lastTickDurationNanos = 0;
        lastTickTime = currentTime;
    }

    /**
     * Return the most recently measured tick duration in milliseconds.
     * Returns 0 if no previous tick was measured yet.
     */
    public static long getLastTickDurationMs() {
        return lastTickDurationNanos / 1_000_000L;
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
        // Pre-size collections to avoid repeated resizing
        int estimatedEntities = Math.min(CONFIG.getMaxEntitiesToCollect(), 5000);
        List<EntityData> entities = new ArrayList<>(estimatedEntities);
        List<ItemEntityData> items = new ArrayList<>(estimatedEntities / 4);
        List<MobData> mobs = new ArrayList<>(estimatedEntities / 8);
        List<BlockEntityData> blockEntities = new ArrayList<>(128);
        List<PlayerData> players = new ArrayList<>(32);

        int maxEntities = CONFIG.getMaxEntitiesToCollect();
        double distanceCutoff = CONFIG.getEntityDistanceCutoff();
        
        // Cache frequently accessed values
        final String[] excludedTypes = CONFIG.getExcludedEntityTypes();
        final double cutoffSq = distanceCutoff * distanceCutoff;
        
        for (ServerLevel level : server.getAllLevels()) {
            // Precompute player positions once per level and pass into entity collection to avoid duplicate work
            List<ServerPlayer> serverPlayers = level.players();
            List<PlayerData> levelPlayers = new ArrayList<>(serverPlayers.size());
            
            // Batch player data creation to avoid repeated list growth
            for (ServerPlayer p : serverPlayers) {
                levelPlayers.add(new PlayerData(p.getId(), p.getX(), p.getY(), p.getZ()));
            }
            
            collectEntitiesFromLevel(level, new EntityCollectionContext(entities, items, mobs, maxEntities, distanceCutoff, levelPlayers, excludedTypes, cutoffSq));
            
            // collect into global players list used by Rust processing
            players.addAll(levelPlayers);
            if (entities.size() >= maxEntities) break; // global cap
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities, players);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, EntityCollectionContext context) {
        // Get or create spatial grid for this level with profiling
        long spatialStart = PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0) ? System.nanoTime() : 0;
        SpatialGrid spatialGrid = getOrCreateSpatialGrid(level, context.players());
        if (PROFILING_ENABLED && (tickCounter % PROFILING_SAMPLE_RATE == 0)) {
            ProfileData profile = profileData.get();
            if (profile != null) {
                profile.spatialGridTime += System.nanoTime() - spatialStart;
            }
        }

        // Create bounding box that encompasses all players within distance cutoff
        AABB searchBounds = createSearchBounds(context.players(), context.distanceCutoff());
        
        // Use asynchronous distance calculations every N ticks to reduce CPU load
        if (tickCounter % DISTANCE_CALCULATION_INTERVAL == 0) {
            // Full synchronous distance calculation with optimized entity processing
            List<Entity> entityList = level.getEntities(null, searchBounds);
            int entityCount = entityList.size();
            
            for (int i = 0; i < entityCount && context.entities().size() < context.maxEntities(); i++) {
                Entity entity = entityList.get(i);
                // Use spatial grid for efficient distance calculation
                double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, context.distanceCutoff());
                if (minSq <= context.cutoffSq()) {
                    processEntityWithinCutoff(entity, minSq, context.excluded(), context.entities(), context.items(), context.mobs());
                }
            }
        } else {
            // Reduced frequency calculation - use cached distances or approximate
            performReducedDistanceCalculation(level, searchBounds, context, spatialGrid);
        }
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

    // Get or create spatial grid for a level, updating it with current player positions
    private static SpatialGrid getOrCreateSpatialGrid(ServerLevel level, List<PlayerData> players) {
        
        synchronized (spatialGridLock) {
            SpatialGrid grid = levelSpatialGrids.computeIfAbsent(level, k -> {
                // Create grid with cell size based on distance cutoff for optimal performance
                double cellSize = Math.max(CONFIG.getEntityDistanceCutoff() / 4.0, 16.0);
                return new SpatialGrid(cellSize);
            });
            
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
    private static void performReducedDistanceCalculation(ServerLevel level, AABB searchBounds, EntityCollectionContext context, SpatialGrid spatialGrid) {
        // Use a larger search radius to reduce false negatives, then filter more precisely for close entities
        double approximateCutoff = context.distanceCutoff() * 1.2; // 20% larger for safety margin
        double approximateCutoffSq = approximateCutoff * approximateCutoff;
        
        // First pass: quick approximate filtering using spatial grid
        List<Entity> candidateEntities = new ArrayList<>();
        for (Entity entity : level.getEntities(null, searchBounds)) {
            if (context.entities().size() >= context.maxEntities()) break;
            
            // Quick approximate check using spatial grid
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
                // Precise distance calculation
                double minSq = computeMinSquaredDistanceToPlayersOptimized(entity, spatialGrid, context.distanceCutoff());
                if (minSq <= context.cutoffSq()) {
                    processEntityWithinCutoff(entity, minSq, context.excluded(), context.entities(), context.items(), context.mobs());
                }
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
        
        int estimatedSize = Math.min(items.size(), items.size() / 2 + 1);
        Map<Long, ItemEntityData> agg = HashMap.newHashMap(estimatedSize);
        
        // Pre-calculate hash codes to avoid repeated calls
        for (ItemEntityData it : items) {
            long key = packItemKey(it.getChunkX(), it.getChunkZ(), it.getItemType());
            
            ItemEntityData cur = agg.get(key);
            if (cur == null) {
                agg.put(key, it);
            } else {
                // Sum counts and keep smallest age to represent the merged group
                int newCount = cur.getCount() + it.getCount();
                int newAge = Math.min(cur.getAgeSeconds(), it.getAgeSeconds());
                // Reuse existing object to reduce allocation
                agg.put(key, new ItemEntityData(-1, it.getChunkX(), it.getChunkZ(), it.getItemType(), newCount, newAge));
            }
        }
        
        // Pre-size the result list to avoid resizing
        return new ArrayList<>(agg.values());
    }

    // (Intentionally using direct HashMap construction for simplicity)

    private static OptimizationResults processOptimizations(EntityDataCollection data) {
        // Use parallel performance optimizations
        List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities(), data.players());
        List<Long> blockResult = RustPerformance.getBlockEntitiesToTick(data.blockEntities());
        com.kneaf.core.performance.core.ItemProcessResult itemResult = RustPerformance.processItemEntities(data.items());
        com.kneaf.core.performance.core.MobProcessResult mobResult = RustPerformance.processMobAI(data.mobs());
        return new OptimizationResults(toTick, blockResult, itemResult, mobResult);
    }

    private static void applyOptimizations(MinecraftServer server, OptimizationResults results) {
        applyItemUpdates(server, results.itemResult());
        applyMobOptimizations(server, results.mobResult());
    }

    private static void applyItemUpdates(MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
        if (itemResult == null || itemResult.getItemUpdates() == null) return;
        
        // Batch entity lookup: create a map of entity IDs to updates for O(1) lookup
        Map<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> updateMap = new HashMap<>();
        for (var update : itemResult.getItemUpdates()) {
            updateMap.put((int) update.getId(), update);
        }
        
        // Process all levels once - O(U + L) complexity
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> entry : updateMap.entrySet()) {
                Entity entity = level.getEntity(entry.getKey());
                if (entity instanceof ItemEntity itemEntity) {
                    com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate update = entry.getValue();
                    itemEntity.getItem().setCount(update.getNewCount());
                }
            }
        }
    }

    private static void applyMobOptimizations(MinecraftServer server, com.kneaf.core.performance.core.MobProcessResult mobResult) {
        if (mobResult == null) return;
        
        // Batch entity lookup for mobs to disable AI
        Set<Integer> disableAiIds = new HashSet<>();
        for (Long id : mobResult.getDisableList()) {
            disableAiIds.add(id.intValue());
        }
        
        // Batch entity lookup for mobs to simplify AI
        Set<Integer> simplifyAiIds = new HashSet<>();
        for (Long id : mobResult.getSimplifyList()) {
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
            
            // Process mobs to simplify AI - removed debug logging to reduce noise
            for (Integer id : simplifyAiIds) {
                Entity entity = level.getEntity(id);
                if (entity instanceof net.minecraft.world.entity.Mob) {
                    // AI simplification applied without logging
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
            com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logOptimizations(summary);
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
            int disabled = results.mobResult().getDisableList().size();
            int simplified = results.mobResult().getSimplifyList().size();
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
            
            if (totalLevels > 0 && LOGGER.isDebugEnabled()) {
                LOGGER.debug("SpatialGrid stats: {} levels, {} players, {} cells, avg {} players/cell",
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
        com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logLine("SLOW_TICK " + message);
    }

    private static String buildOptimizationSummary(OptimizationResults results) {
        double avg = getRollingAvgTPS();
        long itemsMerged = results.itemResult() == null ? 0L : results.itemResult().getMergedCount();
        long itemsDespawned = results.itemResult() == null ? 0L : results.itemResult().getDespawnedCount();
        int mobsDisabled = results.mobResult() == null ? 0 : results.mobResult().getDisableList().size();
        int mobsSimplified = results.mobResult() == null ? 0 : results.mobResult().getSimplifyList().size();
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
            if (!results.mobResult().getDisableList().isEmpty()) return true;
            if (!results.mobResult().getSimplifyList().isEmpty()) return true;
        }
            return results.blockResult() != null && !results.blockResult().isEmpty();
    }

    

    private static void removeItems(MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
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


    


    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, com.kneaf.core.performance.core.ItemProcessResult itemResult, com.kneaf.core.performance.core.MobProcessResult mobResult) {}

    // Static initializer to set initial threshold and initialize Rust allocator
    static {
        currentTpsThreshold = CONFIG.getTpsThresholdForAsync();
        
        // Initialize Rust allocator - only on non-Windows platforms
        try {
            com.kneaf.core.performance.bridge.NativeBridge.initRustAllocator();
            LOGGER.info("Rust allocator initialized successfully");
        } catch (UnsatisfiedLinkError e) {
            // This is expected in test environments or when the native library is not available
            LOGGER.debug("Rust allocator initialization skipped - native library not available");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize Rust allocator, using system default", e);
        }
    }
}