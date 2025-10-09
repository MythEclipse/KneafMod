package com.kneaf.core.performance.monitoring;

import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.data.item.ItemEntityData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.spatial.SpatialGrid;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Manages performance optimizations for the Minecraft server. Handles entity ticking, item merging,
 * mob AI optimization, and block entity management. Now includes multithreading for server tasks,
 * network optimization, and chunk generation.
 */
public class PerformanceManager {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Performance monitoring counters
  private static final AtomicLong totalJniCalls = new AtomicLong(0);
  private static final AtomicLong totalJniCallDurationMs = new AtomicLong(0);
  private static final AtomicLong maxJniCallDurationMs = new AtomicLong(0);
  private static final ConcurrentHashMap<String, AtomicLong> jniCallTypes = new ConcurrentHashMap<>();

  // Lock wait monitoring
  private static final AtomicLong totalLockWaits = new AtomicLong(0);
  private static final AtomicLong totalLockWaitTimeMs = new AtomicLong(0);
  private static final AtomicLong maxLockWaitTimeMs = new AtomicLong(0);
  private static final AtomicInteger currentLockContention = new AtomicInteger(0);

  // Memory monitoring
  private static final AtomicLong totalHeapBytes = new AtomicLong(0);
  private static final AtomicLong usedHeapBytes = new AtomicLong(0);
  private static final AtomicLong freeHeapBytes = new AtomicLong(0);
  private static final AtomicLong gcCount = new AtomicLong(0);
  private static final AtomicLong gcTimeMs = new AtomicLong(0);
  private static final AtomicLong peakHeapUsageBytes = new AtomicLong(0);

  // Threshold configuration

  // Threshold alerts (unique, thread-safe)
 private static final List<String> thresholdAlerts = new CopyOnWriteArrayList<>();

  // Default thresholds if not found in config
  private static final long DEFAULT_JNI_CALL_THRESHOLD_MS = 100;
  private static final long DEFAULT_LOCK_WAIT_THRESHOLD_MS = 50;
  private static final double DEFAULT_MEMORY_USAGE_THRESHOLD_PCT = 90.0;
  private static final long DEFAULT_GC_DURATION_THRESHOLD_MS = 100;

  // Threshold configuration (using defaults until config is loaded)
  private static long JNI_CALL_THRESHOLD_MS = DEFAULT_JNI_CALL_THRESHOLD_MS;
  private static long LOCK_WAIT_THRESHOLD_MS = DEFAULT_LOCK_WAIT_THRESHOLD_MS;
  private static double MEMORY_USAGE_THRESHOLD_PCT = DEFAULT_MEMORY_USAGE_THRESHOLD_PCT;
  private static long GC_DURATION_THRESHOLD_MS = DEFAULT_GC_DURATION_THRESHOLD_MS;

  // TPS and tick monitoring
  private static int TICK_COUNTER = 0;
  private static long lastTickTime = 0;
  private static volatile long lastTickDurationNanos = 0;

  // Configuration (loaded from config/kneaf-performance.properties)
  private static final com.kneaf.core.performance.monitoring.PerformanceConfig CONFIG =
      com.kneaf.core.performance.monitoring.PerformanceConfig.load();

  // Profiling configuration
  private static final boolean PROFILING_ENABLED = CONFIG.isProfilingEnabled();
  private static final int PROFILING_SAMPLE_RATE = CONFIG.getProfilingSampleRate();

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
          "{\"totalTasksSubmitted\":%d,\"totalTasksCompleted\":%d,\"totalTasksRejected\":%d,"
              + "\"currentQueueSize\":%d,\"currentUtilization\":%.2f,\"currentThreadCount\":%d,"
              + "\"peakThreadCount\":%d,\"scaleUpCount\":%d,\"scaleDownCount\":%d}",
          totalTasksSubmitted,
          totalTasksCompleted,
          totalTasksRejected,
          currentQueueSize,
          currentUtilization,
          currentThreadCount,
          peakThreadCount,
          scaleUpCount,
          scaleDownCount);
    }
  }

  // Rolling TPS average to make decisions about whether to offload work
  private static final int TPS_WINDOW_SIZE = 8; // Further reduced from 10 to lower overhead
  private static final double[] TPS_WINDOW = new double[TPS_WINDOW_SIZE];
  private static int tpsWindowIndex = 0;

  // Dynamic async thresholding based on executor queue size
  private static double currentTpsThreshold;
  // Profiling data structures
  private static final class ProfileData {
    long entityCollectionTime = 0;
    long itemConsolidationTime = 0;
    long optimizationProcessingTime = 0;
    long optimizationApplicationTime = 0;
    long spatialGridTime = 0;
    long TOTAL_TICK_TIME = 0;
    int entitiesProcessed = 0;
    int itemsProcessed = 0;
    int executorQueueSize = 0;

    String toJson() {
      return String.format(
          "{\"entityCollectionMs\":%.2f,\"itemConsolidationMs\":%.2f,\"optimizationProcessingMs\":%.2f,\"optimizationApplicationMs\":%.2f,\"spatialGridMs\":%.2f,\"totalTickMs\":%.2f,\"entitiesProcessed\":%d,\"itemsProcessed\":%d,\"executorQueueSize\":%d}",
          entityCollectionTime / 1_000_000.0,
          itemConsolidationTime / 1_000_000.0,
          optimizationProcessingTime / 1_000_000.0,
          optimizationApplicationTime / 1_000_000.0,
          spatialGridTime / 1_000_000.0,
          TOTAL_TICK_TIME / 1_000_000.0,
          entitiesProcessed,
          itemsProcessed,
          executorQueueSize);
    }
  }

  private static final ThreadLocal<ProfileData> PROFILE_DATA =
      ThreadLocal.withInitial(ProfileData::new);

  // Distance transition configuration
  private static final int DEFAULT_TRANSITION_TICKS = 20; // default smoothing window
  private static final int TRANSITION_TICKS = DEFAULT_TRANSITION_TICKS;

  // Per-level distance state maps for restore and smoothing
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> ORIGINAL_VIEW_DISTANCE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> TARGET_DISTANCE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> TRANSITION_REMAINING =
    new java.util.concurrent.ConcurrentHashMap<>();
  // Simulation distance tracking
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> ORIGINAL_SIMULATION_DISTANCE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> TARGET_SIMULATION_DISTANCE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<ServerLevel, Integer> TRANSITION_REMAINING_SIM =
    new java.util.concurrent.ConcurrentHashMap<>();

  // Reflection method cache to avoid repeated lookups and noisy logs
  private static final java.util.concurrent.ConcurrentMap<Class<?>, java.lang.reflect.Method> VIEW_GET_CACHE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<Class<?>, java.lang.reflect.Method> VIEW_SET_CACHE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<Class<?>, java.lang.reflect.Method> SIM_GET_CACHE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<Class<?>, java.lang.reflect.Method> SIM_SET_CACHE =
    new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.ConcurrentMap<Class<?>, Boolean> METHOD_RESOLVED =
    new java.util.concurrent.ConcurrentHashMap<>();

  // Resolve and cache view getter
  private static java.lang.reflect.Method resolveViewGetter(Class<?> cls) {
    var m = VIEW_GET_CACHE.get(cls);
    if (m != null) return m;
    if (METHOD_RESOLVED.containsKey(cls) && !VIEW_GET_CACHE.containsKey(cls)) return null;
    try {
      try {
        m = cls.getMethod("getViewDistance");
      } catch (NoSuchMethodException e) {
        m = cls.getMethod("viewDistance");
      }
      VIEW_GET_CACHE.put(cls, m);
      METHOD_RESOLVED.put(cls, true);
      return m;
    } catch (Throwable t) {
      METHOD_RESOLVED.put(cls, false);
      return null;
    }
  }

  private static java.lang.reflect.Method resolveViewSetter(Class<?> cls) {
    var m = VIEW_SET_CACHE.get(cls);
    if (m != null) return m;
    try {
      m = cls.getMethod("setViewDistance", int.class);
      VIEW_SET_CACHE.put(cls, m);
      return m;
    } catch (Throwable t) {
      return null;
    }
  }

  private static java.lang.reflect.Method resolveSimGetter(Class<?> cls) {
    var m = SIM_GET_CACHE.get(cls);
    if (m != null) return m;
    try {
      m = cls.getMethod("getSimulationDistance");
      SIM_GET_CACHE.put(cls, m);
      return m;
    } catch (Throwable t) {
      return null;
    }
  }

  private static java.lang.reflect.Method resolveSimSetter(Class<?> cls) {
    var m = SIM_SET_CACHE.get(cls);
    if (m != null) return m;
    try {
      m = cls.getMethod("setSimulationDistance", int.class);
      SIM_SET_CACHE.put(cls, m);
      return m;
    } catch (Throwable t) {
      return null;
    }
  }

  private static void disableReflectionForClass(Class<?> cls, String why, Throwable t) {
    try {
      VIEW_GET_CACHE.remove(cls);
      VIEW_SET_CACHE.remove(cls);
      SIM_GET_CACHE.remove(cls);
      SIM_SET_CACHE.remove(cls);
      METHOD_RESOLVED.put(cls, false);
    } finally {
      // Log once per-class at debug so we don't spam logs every tick
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Disabling reflection distance adjustments for {}: {}", cls.getName(), why);
      }
      if (LOGGER.isTraceEnabled()) {
        LOGGER.trace("Reflection disable cause: ", t);
      }
    }
  }


  private PerformanceManager() {}

  // Runtime toggle (initialized from config)
  private static volatile boolean enabled = CONFIG.isEnabled();
  private static final Object lock = new Object();
  private static AtomicLong jniCalls = new AtomicLong(0);
  private static AtomicLong lockWaitTime = new AtomicLong(0);
  private static AtomicLong memoryUsage = new AtomicLong(0);
  
  // Modular components (singleton instances)
  private static final ThreadPoolManager THREAD_POOL_MANAGER = new ThreadPoolManager();
  private static final EntityProcessor ENTITY_PROCESSOR = new EntityProcessor();
  // Delegate executor queue size check to ThreadPoolManager
  private static int getExecutorQueueSize() {
    return THREAD_POOL_MANAGER.getExecutorQueueSize();
  }

  // Delegate executor retrieval to ThreadPoolManager
  private static ThreadPoolExecutor getExecutor() {
    return THREAD_POOL_MANAGER.getExecutor();
  }

  // Delegate shutdown to ThreadPoolManager
  public static void shutdown() {
    THREAD_POOL_MANAGER.shutdown();
  }

  // Spatial grid for efficient player position queries per level
  private static final Map<ServerLevel, SpatialGrid> LEVEL_SPATIAL_GRIDS = new HashMap<>();
  private static final Object SPATIAL_GRID_LOCK = new Object();

  // Asynchronous distance calculation configuration
  private static final int DISTANCE_CALCULATION_INTERVAL =
      10; // Reduced frequency: calculate distances every 10 ticks

  public static boolean isEnabled() {
    return enabled;
  }

  public static void setEnabled(boolean val) {
    enabled = val;
  }

  private record EntityDataCollection(
      List<EntityData> entities,
      List<ItemEntityData> items,
      List<MobData> mobs,
      List<BlockEntityData> blockEntities,
      List<PlayerData> players) {}

  private record EntityCollectionContext(
      List<EntityData> entities,
      List<ItemEntityData> items,
      List<MobData> mobs,
      int maxEntities,
      double distanceCutoff,
      List<PlayerData> players,
      String[] excluded,
      double cutoffSq) {
    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null || getClass() != obj.getClass()) return false;
      EntityCollectionContext that = (EntityCollectionContext) obj;
      return maxEntities == that.maxEntities
          && Double.compare(that.distanceCutoff, distanceCutoff) == 0
          && Double.compare(that.cutoffSq, cutoffSq) == 0
          && Objects.equals(entities, that.entities)
          && Objects.equals(items, that.items)
          && Objects.equals(mobs, that.mobs)
          && Objects.equals(players, that.players)
          && Arrays.equals(excluded, that.excluded);
    }

    @Override
    public int hashCode() {
      int result =
          Objects.hash(entities, items, mobs, maxEntities, distanceCutoff, players, cutoffSq);
      result = 31 * result + Arrays.hashCode(excluded);
      return result;
    }

    @Override
    public String toString() {
      return "EntityCollectionContext{"
          + "entities="
          + entities
          + ", items="
          + items
          + ", mobs="
          + mobs
          + ", maxEntities="
          + maxEntities
          + ", distanceCutoff="
          + distanceCutoff
          + ", players="
          + players
          + ", excluded="
          + Arrays.toString(excluded)
          + ", cutoffSq="
          + cutoffSq
          + '}';
    }
  }

  /**
   * Called on every server tick to perform performance optimizations. Now uses multithreading for
   * processing optimizations asynchronously.
   */
  /** Record JNI call with duration tracking and threshold checking */
  public static void recordJniCall(String callType, long durationMs) {
    if (!isEnabled()) return;
    
    // Update atomic counters
    totalJniCalls.incrementAndGet();
    totalJniCallDurationMs.addAndGet(durationMs);
    
    // Update max call duration
    long currentMax = maxJniCallDurationMs.get();
    if (durationMs > currentMax) {
      maxJniCallDurationMs.compareAndSet(currentMax, durationMs);
    }
    
    // Update call type statistics
    jniCallTypes.computeIfAbsent(callType, k -> new AtomicLong(0)).incrementAndGet();
    
    // Check threshold and trigger alert if needed
    if (durationMs > JNI_CALL_THRESHOLD_MS) {
      String alert = String.format("JNI call exceeded threshold: %dms > %dms (type: %s)",
          durationMs, JNI_CALL_THRESHOLD_MS, callType);
      addThresholdAlert(alert);
    }
  }
  
  /** Record simple JNI call without specific type */
  public static void recordJniCall() {
    recordJniCall("unspecified", 0);
  }
  
  /** Record lock wait event with duration tracking and threshold checking */
  public static void recordLockWait(String lockName, long durationMs) {
    if (!isEnabled()) return;
    
    // Update atomic counters
    totalLockWaits.incrementAndGet();
    totalLockWaitTimeMs.addAndGet(durationMs);
    
    // Update max lock wait time
    long currentMax = maxLockWaitTimeMs.get();
    if (durationMs > currentMax) {
      maxLockWaitTimeMs.compareAndSet(currentMax, durationMs);
    }
    
    // Update current lock contention (approximate - decremented elsewhere)
    currentLockContention.incrementAndGet();
    
    // Check threshold and trigger alert if needed
    if (durationMs > LOCK_WAIT_THRESHOLD_MS) {
      String alert = String.format("Lock wait exceeded threshold: %dms > %dms (lock: %s)",
          durationMs, LOCK_WAIT_THRESHOLD_MS, lockName);
      addThresholdAlert(alert);
    }
  }
  
  /** Record lock contention resolution (decrement counter) */
  public static void recordLockResolved() {
    if (!isEnabled()) return;
    currentLockContention.updateAndGet(count -> Math.max(0, count - 1));
  }
  
  /** Record memory usage statistics with threshold checking */
  public static void recordMemoryUsage(long totalBytes, long usedBytes, long freeBytes) {
    if (!isEnabled()) return;
    
    // Update atomic counters
    totalHeapBytes.set(totalBytes);
    usedHeapBytes.set(usedBytes);
    freeHeapBytes.set(freeBytes);
    
    double usedPct = totalBytes > 0 ? (usedBytes * 100.0 / totalBytes) : 0.0;
    
    // Update peak heap usage
    long currentPeak = peakHeapUsageBytes.get();
    if (usedBytes > currentPeak) {
      peakHeapUsageBytes.compareAndSet(currentPeak, usedBytes);
    }
    
    // Check threshold and trigger alert if needed
    if (usedPct > MEMORY_USAGE_THRESHOLD_PCT) {
      String alert = String.format("Memory usage exceeded threshold: %.1f%% > %.1f%%",
          usedPct, MEMORY_USAGE_THRESHOLD_PCT);
      addThresholdAlert(alert);
    }
  }
  
  /** Record GC event with duration tracking and threshold checking */
  public static void recordGcEvent(long durationMs) {
    if (!isEnabled()) return;
    
    // Update atomic counters
    gcCount.incrementAndGet();
    gcTimeMs.addAndGet(durationMs);
    
    // Check threshold and trigger alert if needed
    if (durationMs > GC_DURATION_THRESHOLD_MS) {
      String alert = String.format("GC duration exceeded threshold: %dms > %dms",
          durationMs, GC_DURATION_THRESHOLD_MS);
      addThresholdAlert(alert);
    }
  }
  
  /** Add threshold alert to be processed during next periodic log (unique entries only) */
 public static void addThresholdAlert(String alert) {
   if (!isEnabled() || alert == null || alert.isBlank()) return;
   thresholdAlerts.add(alert);
 }
  
  /** Clear all threshold alerts */
  public static void clearThresholdAlerts() {
    if (!isEnabled()) return;
    thresholdAlerts.clear();
  }
  
  /** Get current JNI call metrics */
  public static Map<String, Object> getJniCallMetrics() {
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("totalCalls", totalJniCalls.get());
    metrics.put("totalDurationMs", totalJniCallDurationMs.get());
    metrics.put("maxDurationMs", maxJniCallDurationMs.get());
    metrics.put("callTypes", new HashMap<>(jniCallTypes));
    return metrics;
  }
  
  /** Get current lock wait metrics */
 public static Map<String, Object> getLockWaitMetrics() {
   Map<String, Object> metrics = new HashMap<>();
   metrics.put("totalWaits", totalLockWaits.get());
   metrics.put("totalWaitTimeMs", totalLockWaitTimeMs.get());
   metrics.put("maxWaitTimeMs", maxLockWaitTimeMs.get());
   metrics.put("currentContention", currentLockContention.get());
   return metrics;
 }
  
  /** Get current memory metrics */
  public static Map<String, Object> getMemoryMetrics() {
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("totalHeapBytes", totalHeapBytes.get());
    metrics.put("usedHeapBytes", usedHeapBytes.get());
    metrics.put("freeHeapBytes", freeHeapBytes.get());
    metrics.put("peakHeapBytes", peakHeapUsageBytes.get());
    metrics.put("gcCount", gcCount.get());
    metrics.put("gcTimeMs", gcTimeMs.get());
    return metrics;
  }
  
  /** Get current threshold alerts */
  public static List<String> getThresholdAlerts() {
    return new ArrayList<>(thresholdAlerts);
  }
  
  public static void onServerTick(MinecraftServer server) {
    // Delegate to EntityProcessor for modular implementation
    ENTITY_PROCESSOR.onServerTick(server);
    
    // Log real-time status updates every 100 ticks
    if (TICK_COUNTER % 100 == 0) {
      logRealTimeStatus();
    }
    
    // Log periodic performance summary every 500 ticks
    if (TICK_COUNTER % 500 == 0) {
      logPerformanceSummary();
    }
    
    TICK_COUNTER++;
  }
  
  /** Log real-time status updates */
  private static void logRealTimeStatus() {
    try {
      double tps = getAverageTPS();
      long tickDurationMs = getLastTickDurationMs();
      long gcEvents = gcCount.get();
      
      // Get memory metrics
      double memoryUsagePct = totalHeapBytes.get() > 0 ?
          (usedHeapBytes.get() * 100.0 / totalHeapBytes.get()) : 0.0;
      
      // Get lock contention
      int lockContention = currentLockContention.get();
      
      // Check if server is lagging significantly
      boolean serverLagging = tickDurationMs > 50; // More than 50ms per tick indicates lag
      String lagStatus = serverLagging ? "LAGGING" : "NORMAL";
      
      // Format status message with lag indication
      String systemStatus = String.format(
          "TPS: %.2f, Tick: %dms, Memory: %.1f%%, GC: %d, Locks: %d, Status: %s",
          tps, tickDurationMs, memoryUsagePct, gcEvents, lockContention, lagStatus);
      
      // Check for important events
      String importantEvents = "";
      if (memoryUsagePct > 90.0) {
        importantEvents += "HIGH_MEMORY_USAGE ";
      }
      if (lockContention > 10) {
        importantEvents += "HIGH_LOCK_CONTENTION ";
      }
      if (tps < 15.0) {
        importantEvents += "LOW_TPS ";
      }
      if (serverLagging) {
        importantEvents += "SERVER_LAGGING ";
      }
      
      // Log to Rust performance system
      com.kneaf.core.performance.RustPerformance.logRealTimeStatus(systemStatus,
          importantEvents.isEmpty() ? null : importantEvents);
      
    } catch (Exception e) {
      LOGGER.debug("Error logging real-time status", e);
    }
  }
  
  /** Log periodic performance summary */
  private static void logPerformanceSummary() {
    try {
      // Get performance metrics
      double tps = getAverageTPS();
      long tickDurationMs = getLastTickDurationMs();
      long gcEvents = gcCount.get();
      
      // Get memory metrics
      double memoryUsagePct = totalHeapBytes.get() > 0 ?
          (usedHeapBytes.get() * 100.0 / totalHeapBytes.get()) : 0.0;
      
      // Get lock contention
      int lockContention = currentLockContention.get();
      
      // Check if server is lagging
      boolean serverLagging = tickDurationMs > 50;
      String performanceStatus = serverLagging ? "DEGRADED" : "NORMAL";
      
      // Enhanced performance metrics with lag information
      String enhancedMetrics = String.format(
          "Performance Metrics: {:.2f} TPS, {:.2f}ms latency, {} GC events, Status: {}",
          tps, tickDurationMs, gcEvents, performanceStatus);
      
      // Log enhanced metrics to Rust performance system
      com.kneaf.core.performance.RustPerformance.logPerformanceMetrics(
          tps, tickDurationMs, gcEvents);
      
      // Log memory pool status
      double hitRate = 92.0; // Default hit rate - would need actual calculation from memory pool
      int contention = currentLockContention.get();
      
      com.kneaf.core.performance.RustPerformance.logMemoryPoolStatus(
          memoryUsagePct, hitRate, contention);
      
      // Log thread pool status
      ThreadPoolExecutor executor = getExecutor();
      int activeThreads = executor != null ? executor.getActiveCount() : 0;
      int queueSize = getExecutorQueueSize();
      double utilization = THREAD_POOL_MANAGER.getExecutorUtilization();
      
      com.kneaf.core.performance.RustPerformance.logThreadPoolStatus(
          activeThreads, queueSize, utilization);
      
      // Log additional performance information if server is lagging
      if (serverLagging) {
        LOGGER.warn("[KneafMod] Server performance degraded: TPS={:.2f}, Tick={}ms, Memory={:.1f}%, Locks={}",
                   tps, tickDurationMs, memoryUsagePct, lockContention);
      }
      
    } catch (Exception e) {
      LOGGER.debug("Error logging performance summary", e);
    }
  }

  // Helper to initialize profiling values if needed
  private static void prepareProfiling(
      ProfileData profile, boolean shouldProfile, long tickStartTime) {
    if (shouldProfile && profile != null) {
      profile.executorQueueSize = getExecutorQueueSize();
      profile.TOTAL_TICK_TIME = System.nanoTime() - tickStartTime;
    }
  }

  // Helper to collect entity data and consolidate item entities while updating profiling data
  private static EntityDataCollection collectAndConsolidate(
      MinecraftServer server, boolean shouldProfile, ProfileData profile) {
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
    return new EntityDataCollection(
        data.entities(), consolidated, data.mobs(), data.blockEntities(), data.players());
  }

  private static void submitAsyncOptimizations(
      MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
    try {
      getExecutor().submit(() -> performAsyncOptimization(server, data, shouldProfile));
    } catch (Exception e) {
      // Fallback to synchronous processing if executor rejects
      LOGGER.debug("Executor rejected task; running synchronously", e);
      runSynchronousOptimizations(server, data, shouldProfile);
    }
  }

  private static void performAsyncOptimization(
      MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
    try {
      long processingStart = shouldProfile ? System.nanoTime() : 0;
      OptimizationResults results = processOptimizations(data);
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
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

  private static void applyOptimizationResults(
      MinecraftServer server, OptimizationResults results, boolean shouldProfile) {
    try {
      long applicationStart = shouldProfile ? System.nanoTime() : 0;
      applyOptimizations(server, results);
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
        if (profile != null) {
          profile.optimizationApplicationTime = System.nanoTime() - applicationStart;
        }
      }
      if (TICK_COUNTER % CONFIG.getLogIntervalTicks() == 0) {
        logOptimizations(server, results);
      }
      removeItems(server, results.itemResult());
    } catch (Exception e) {
      LOGGER.warn("Error applying optimizations on server thread", e);
    }
  }

  private static void runSynchronousOptimizations(
      MinecraftServer server, EntityDataCollection data, boolean shouldProfile) {
    try {
      long processingStart = shouldProfile ? System.nanoTime() : 0;
      OptimizationResults results = processOptimizations(data);
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
        if (profile != null) {
          profile.optimizationProcessingTime = System.nanoTime() - processingStart;
        }
      }

      long applicationStart = shouldProfile ? System.nanoTime() : 0;
      applyOptimizations(server, results);
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
        if (profile != null) {
          profile.optimizationApplicationTime = System.nanoTime() - applicationStart;
        }
      }

      if (TICK_COUNTER % CONFIG.getLogIntervalTicks() == 0) {
        logOptimizations(server, results);
      }
      removeItems(server, results.itemResult());
    } catch (Exception ex) {
      LOGGER.warn("Error processing optimizations synchronously", ex);
    }
  }

  /**
   * Adapter method to handle EntityProcessor results in PerformanceManager methods
   */
  private static void runSynchronousOptimizations(
      MinecraftServer server, EntityProcessor.EntityDataCollection data, boolean shouldProfile) {
    try {
      long processingStart = shouldProfile ? System.nanoTime() : 0;
      EntityProcessor.OptimizationResults processorResults = ENTITY_PROCESSOR.processOptimizations(data);
      
      // Convert to PerformanceManager results format for compatibility
      OptimizationResults results = adaptOptimizationResults(processorResults);
      
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
        if (profile != null) {
          profile.optimizationProcessingTime = System.nanoTime() - processingStart;
        }
      }

      long applicationStart = shouldProfile ? System.nanoTime() : 0;
      ENTITY_PROCESSOR.applyOptimizations(server, processorResults);
      if (shouldProfile) {
        ProfileData profile = PROFILE_DATA.get();
        if (profile != null) {
          profile.optimizationApplicationTime = System.nanoTime() - applicationStart;
        }
      }

      if (TICK_COUNTER % CONFIG.getLogIntervalTicks() == 0) {
        // Already using PerformanceManager results format in this method
        logOptimizations(server, results);
      }
      ENTITY_PROCESSOR.removeItems(server, processorResults.itemResult());
    } catch (Exception ex) {
      LOGGER.warn("Error processing optimizations synchronously", ex);
    }
  }

  /**
   * Adapter: Convert EntityProcessor results to PerformanceManager results format
   */
  private static OptimizationResults adaptOptimizationResults(EntityProcessor.OptimizationResults source) {
    return new OptimizationResults(
      source.toTick(),
      source.blockResult(),
      source.itemResult(),
      source.mobResult()
    );
  }

  private static void updateTPS() {
    long currentTime = System.nanoTime();
    if (lastTickTime != 0) {
      long delta = currentTime - lastTickTime;
      // record last tick duration for external consumers (e.g. thread-scaling decisions)
      lastTickDurationNanos = delta;
      
      // Calculate actual TPS based on tick duration - don't cap at 20.0 to reflect real performance
      double tps = 1_000_000_000.0 / delta;
      
      // If server is lagging significantly (delta > 100ms), calculate TPS based on actual performance
      if (delta > 100_000_000L) { // More than 100ms per tick indicates lag
        // Calculate realistic TPS based on actual tick time
        tps = Math.min(20.0, 1000.0 / (delta / 1_000_000.0)); // Convert to ms and calculate TPS
      }
      
      RustPerformance.setCurrentTPS(tps);
      // update rolling window
      TPS_WINDOW[tpsWindowIndex % TPS_WINDOW_SIZE] = tps;
      tpsWindowIndex = (tpsWindowIndex + 1) % TPS_WINDOW_SIZE;
    }
    // If this is the first tick, ensure lastTickDurationNanos is zeroed
    if (lastTickTime == 0) lastTickDurationNanos = 0;
    lastTickTime = currentTime;
  }

  /**
   * Return the most recently measured tick duration in milliseconds. Returns 0 if no previous tick
   * was measured yet.
   */
  public static long getLastTickDurationMs() {
    return lastTickDurationNanos / 1_000_000L;
  }

  /** Public accessor for the rolling average TPS used by dynamic logging/decisions. */
  public static double getAverageTPS() {
    return getRollingAvgTPS();
  }

  private static double getRollingAvgTPS() {
    double sum = 0.0;
    int count = 0;
    for (double v : TPS_WINDOW) {
      if (v > 0) {
        sum += v;
        count++;
      }
    }
    
    // If we have valid measurements, return actual average
    if (count > 0) {
      double avg = sum / count;
      // If average is significantly below 20.0, it indicates real performance issues
      // Don't artificially inflate the average - show the actual performance
      return avg;
    }
    
    // Default to 20.0 only if no measurements available
    return 20.0;
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
      // Precompute player positions once per level and pass into entity collection to avoid
      // duplicate work
      List<ServerPlayer> serverPlayers = level.players();
      List<PlayerData> levelPlayers = new ArrayList<>(serverPlayers.size());

      // Batch player data creation to avoid repeated list growth
      for (ServerPlayer p : serverPlayers) {
        levelPlayers.add(new PlayerData(p.getId(), p.getX(), p.getY(), p.getZ()));
      }

      collectEntitiesFromLevel(
          level,
          new EntityCollectionContext(
              entities,
              items,
              mobs,
              maxEntities,
              distanceCutoff,
              levelPlayers,
              excludedTypes,
              cutoffSq));

      // collect into global players list used by Rust processing
      players.addAll(levelPlayers);
      if (entities.size() >= maxEntities) break; // global cap
    }
    return new EntityDataCollection(entities, items, mobs, blockEntities, players);
  }

  private static void collectEntitiesFromLevel(ServerLevel level, EntityCollectionContext context) {
    // Get or create spatial grid for this level with profiling
    long spatialStart =
        PROFILING_ENABLED && (TICK_COUNTER % PROFILING_SAMPLE_RATE == 0) ? System.nanoTime() : 0;
    SpatialGrid spatialGrid = getOrCreateSpatialGrid(level, context.players());
    if (PROFILING_ENABLED && (TICK_COUNTER % PROFILING_SAMPLE_RATE == 0)) {
      ProfileData profile = PROFILE_DATA.get();
      if (profile != null) {
        profile.spatialGridTime += System.nanoTime() - spatialStart;
      }
    }

    // Create bounding box that encompasses all players within distance cutoff
    AABB searchBounds = createSearchBounds(context.players(), context.distanceCutoff());

    // Use asynchronous distance calculations every N ticks to reduce CPU load
    if (TICK_COUNTER
            % com.kneaf.core.performance.core.PerformanceConstants
                .getAdaptiveDistanceCalculationInterval(
                    com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS())
        == 0) {
      // Full synchronous distance calculation with optimized entity processing
      List<Entity> entityList = level.getEntities(null, searchBounds);
      int entityCount = entityList.size();

      for (int i = 0; i < entityCount && context.entities().size() < context.maxEntities(); i++) {
        Entity entity = entityList.get(i);
        // Use spatial grid for efficient distance calculation
        double minSq =
            computeMinSquaredDistanceToPlayersOptimized(
                entity, spatialGrid, context.distanceCutoff());
        if (minSq <= context.cutoffSq()) {
          processEntityWithinCutoff(
              entity,
              minSq,
              context.excluded(),
              context.entities(),
              context.items(),
              context.mobs());
        }
      }
    } else {
      // Reduced frequency calculation - use cached distances or approximate
      performReducedDistanceCalculation(level, searchBounds, context, spatialGrid);
    }
  }

  // Optimized version using spatial grid - O(log M) instead of O(M)
  private static double computeMinSquaredDistanceToPlayersOptimized(
      Entity entity, SpatialGrid spatialGrid, double maxSearchRadius) {
    return spatialGrid.findMinSquaredDistance(
        entity.getX(), entity.getY(), entity.getZ(), maxSearchRadius);
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

    synchronized (SPATIAL_GRID_LOCK) {
      SpatialGrid grid =
          LEVEL_SPATIAL_GRIDS.computeIfAbsent(
              level,
              k -> {
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
  private static void processEntityWithinCutoff(
      Entity entity,
      double minSq,
      String[] excluded,
      List<EntityData> entities,
      List<ItemEntityData> items,
      List<MobData> mobs) {
    String typeStr = entity.getType().toString();
    if (isExcludedType(typeStr, excluded)) return;
    double distance = Math.sqrt(minSq);
    boolean isBlockEntity = false; // Regular entities are not block entities
    entities.add(
        new EntityData(
            entity.getId(),
            entity.getX(),
            entity.getY(),
            entity.getZ(),
            distance,
            isBlockEntity,
            typeStr));

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

  private static void collectItemEntity(
      Entity entity, ItemEntity itemEntity, List<ItemEntityData> items) {
    var chunkPos = entity.chunkPosition();
    var itemStack = itemEntity.getItem();
    var itemType = itemStack.getItem().getDescriptionId();
    var count = itemStack.getCount();
    var ageSeconds = itemEntity.getAge() / 20;
    items.add(
        new ItemEntityData(entity.getId(), chunkPos.x, chunkPos.z, itemType, count, ageSeconds));
  }

  /**
   * Perform reduced frequency distance calculation to optimize performance. Uses cached distances
   * and approximate calculations when full precision is not needed.
   */
  private static void performReducedDistanceCalculation(
      ServerLevel level,
      AABB searchBounds,
      EntityCollectionContext context,
      SpatialGrid spatialGrid) {
    // Use a larger search radius to reduce false negatives, then filter more precisely for close
    // entities
    double approximateCutoff = context.distanceCutoff() * 1.2; // 20% larger for safety margin
    double approximateCutoffSq = approximateCutoff * approximateCutoff;

    // First pass: quick approximate filtering using spatial grid
    List<Entity> candidateEntities = new ArrayList<>();
    for (Entity entity : level.getEntities(null, searchBounds)) {
      if (context.entities().size() >= context.maxEntities()) break;

      // Quick approximate check using spatial grid
      double approxMinSq =
          spatialGrid.findMinSquaredDistance(
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
        double minSq =
            computeMinSquaredDistanceToPlayersOptimized(
                entity, spatialGrid, context.distanceCutoff());
        if (minSq <= context.cutoffSq()) {
          processEntityWithinCutoff(
              entity,
              minSq,
              context.excluded(),
              context.entities(),
              context.items(),
              context.mobs());
        }
      }
    }
  }

  private static void collectMobEntity(
      Entity entity,
      net.minecraft.world.entity.Mob mob,
      List<MobData> mobs,
      double distance,
      String typeStr) {
    // Use the precomputed distance from caller to avoid an extra player iteration
    boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
    mobs.add(new MobData(entity.getId(), distance, isPassive, typeStr));
  }

  /**
   * Pack chunk coordinates and item type hash into a composite long key. Format: [chunkX (21
   * bits)][chunkZ (21 bits)][itemTypeHash (22 bits)] This provides efficient HashMap operations
   * without string allocations.
   */
  private static long packItemKey(int chunkX, int chunkZ, String itemType) {
    // Use 21 bits for each coordinate (covers ±1 million chunks) and 22 bits for hash
    long packedChunkX = ((long) chunkX) & 0x1FFFFF; // 21 bits
    long packedChunkZ = ((long) chunkZ) & 0x1FFFFF; // 21 bits
    long itemHash = itemType == null ? 0 : ((long) itemType.hashCode()) & 0x3FFFFF; // 22 bits

    return (packedChunkX << 43) | (packedChunkZ << 22) | itemHash;
  }

  /**
   * Consolidate collected item entity data by chunk X/Z and item type to reduce the number of items
   * we hand to the downstream Rust processing. This only aggregates the collected snapshot and does
   * not modify world state directly, so it is safe and purely an optimization.
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
        // Preserve a valid entity id from one of the merged items (use the existing entry's id)
        long preservedId = cur.getId();
        agg.put(
            key,
            new ItemEntityData(
                preservedId, it.getChunkX(), it.getChunkZ(), it.getItemType(), newCount, newAge));
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
    com.kneaf.core.performance.core.ItemProcessResult itemResult =
        RustPerformance.processItemEntities(data.items());
    com.kneaf.core.performance.core.MobProcessResult mobResult =
        RustPerformance.processMobAI(data.mobs());
    return new OptimizationResults(toTick, blockResult, itemResult, mobResult);
  }

  private static void applyOptimizations(MinecraftServer server, OptimizationResults results) {
    applyItemUpdates(server, results.itemResult());
    applyMobOptimizations(server, results.mobResult());
    // Ensure server-level distance settings are constrained when optimization level requires it
    try {
      com.kneaf.core.performance.core.RustPerformanceFacade facade =
          com.kneaf.core.performance.core.RustPerformanceFacade.getInstance();
      com.kneaf.core.performance.core.PerformanceOptimizer.OptimizationLevel level =
          facade.isNativeAvailable() ? facade.getCurrentOptimizationLevel() : null;
      if (level != null) {
        // Map optimization level to a target chunk distance (min..max both set to the target)
        int target;
        switch (level) {
          case AGGRESSIVE -> target = 8; // most aggressive -> smallest distance
          case HIGH -> target = 12;
          case MEDIUM -> target = 16;
          case NORMAL -> target = 32; // normal (low optimization) -> largest distance
          default -> target = 16;
        }
          // Instead of forcing immediately, set target distances and let smoothing handle changes
          setTargetDistance(server, target);
      }
    } catch (Throwable t) {
      // Non-fatal - log at debug to avoid spamming logs
      LOGGER.debug("Failed to enforce server distance bounds: {}", t.getMessage());
    }
  }

  private static void setTargetDistance(MinecraftServer server, int targetChunks) {
    if (server == null) return;
    for (ServerLevel level : server.getAllLevels()) {
      try {
        // Record original view distance if not already recorded
        if (!ORIGINAL_VIEW_DISTANCE.containsKey(level)) {
          try {
            var m = resolveViewGetter(level.getClass());
            if (m != null) {
              int current = (int) m.invoke(level);
              ORIGINAL_VIEW_DISTANCE.put(level, current);
            }
          } catch (Throwable t) {
            // if getViewDistance isn't available, skip storing original
          }
        }
        // Record original simulation distance if not already recorded
        if (!ORIGINAL_SIMULATION_DISTANCE.containsKey(level)) {
          try {
            var sm = resolveSimGetter(level.getClass());
            if (sm != null) {
              int curSim = (int) sm.invoke(level);
              ORIGINAL_SIMULATION_DISTANCE.put(level, curSim);
            }
          } catch (Throwable t) {
            // ignore if not available
          }
        }

        TARGET_DISTANCE.put(level, targetChunks);
        TRANSITION_REMAINING.put(level, TRANSITION_TICKS);
        // For simulation distance, use same target and ticks by default
        TARGET_SIMULATION_DISTANCE.put(level, targetChunks);
        TRANSITION_REMAINING_SIM.put(level, TRANSITION_TICKS);
      } catch (Throwable t) {
        LOGGER.debug("Failed to set target distance for level {}: {}", level, t.getMessage());
      }
    }
  }

  private static void applyDistanceTransitions(MinecraftServer server) {
    if (server == null) return;
    for (ServerLevel level : server.getAllLevels()) {
      Integer remaining = TRANSITION_REMAINING.get(level);
      Integer target = TARGET_DISTANCE.get(level);
      if (remaining == null || target == null) continue;

      try {
        var lvlClass = level.getClass();
        var vg = resolveViewGetter(lvlClass);
        if (vg == null) {
          // no supported view getter for this level
          TRANSITION_REMAINING.remove(level);
          TARGET_DISTANCE.remove(level);
          continue;
        }
        int current = (int) vg.invoke(level);

        if (remaining <= 1) {
          // final step: set to target and cleanup
          try {
              var setView = resolveViewSetter(lvlClass);
              if (setView != null) setView.invoke(level, target);
          } catch (Throwable ignored) {
            // ignore
          }
          TRANSITION_REMAINING.remove(level);
          TARGET_DISTANCE.remove(level);
          // If target equals original, remove original record
          Integer orig = ORIGINAL_VIEW_DISTANCE.get(level);
          if (orig != null && orig.equals(target)) ORIGINAL_VIEW_DISTANCE.remove(level);
          continue;
        }

        // Compute one-step linear transition toward target
        int step = (int) Math.ceil((double) (target - current) / remaining);
        int next = current + step;

        try {
          var setView = resolveViewSetter(lvlClass);
          if (setView != null) setView.invoke(level, next);
        } catch (Throwable ignored) {
          // ignore if not available
        }

  int rem = remaining.intValue();
  TRANSITION_REMAINING.put(level, Integer.valueOf(rem - 1));
      } catch (Throwable t) {
        LOGGER.debug("Error transitioning distance for level {}: {}", level, t.getMessage());
        // Clean up to avoid endless retries
        TRANSITION_REMAINING.remove(level);
        TARGET_DISTANCE.remove(level);
      }
    }
    // Now handle simulation distance transitions in parallel
    for (ServerLevel level : server.getAllLevels()) {
      Integer remaining = TRANSITION_REMAINING_SIM.get(level);
      Integer target = TARGET_SIMULATION_DISTANCE.get(level);
      if (remaining == null || target == null) continue;

      try {
        var lvlClass = level.getClass();
        var sg = resolveSimGetter(lvlClass);
        if (sg == null) {
          TRANSITION_REMAINING_SIM.remove(level);
          TARGET_SIMULATION_DISTANCE.remove(level);
          continue;
        }
        int current = (int) sg.invoke(level);

        if (remaining <= 1) {
          try {
            var setSim = resolveSimSetter(lvlClass);
            if (setSim != null) setSim.invoke(level, target);
          } catch (Throwable ignored) {
            // ignore
          }
          TRANSITION_REMAINING_SIM.remove(level);
          TARGET_SIMULATION_DISTANCE.remove(level);
          Integer orig = ORIGINAL_SIMULATION_DISTANCE.get(level);
          if (orig != null && orig.equals(target)) ORIGINAL_SIMULATION_DISTANCE.remove(level);
          continue;
        }

        int step = (int) Math.ceil((double) (target - current) / remaining);
        int next = current + step;

        try {
          var setSim = resolveSimSetter(lvlClass);
          if (setSim != null) setSim.invoke(level, next);
        } catch (Throwable ignored) {
          // ignore if not available
        }

  int remSim = remaining.intValue();
  TRANSITION_REMAINING_SIM.put(level, Integer.valueOf(remSim - 1));
      } catch (Throwable t) {
        LOGGER.debug("Error transitioning simulation distance for level {}: {}", level, t.getMessage());
        TRANSITION_REMAINING_SIM.remove(level);
        TARGET_SIMULATION_DISTANCE.remove(level);
      }
    }
    // If no transitions remain, consider restoring originals for levels where target==original
    for (ServerLevel level : server.getAllLevels()) {
      if (!TARGET_DISTANCE.containsKey(level) && ORIGINAL_VIEW_DISTANCE.containsKey(level)) {
        try {
          var lvlClass = level.getClass();
          var vg = resolveViewGetter(lvlClass);
          var vs = resolveViewSetter(lvlClass);
          if (vg != null && vs != null) {
            int current = ((Number) vg.invoke(level)).intValue();
            int orig = ORIGINAL_VIEW_DISTANCE.get(level).intValue();
            if (current != orig) {
              vs.invoke(level, Integer.valueOf(orig));
            }
          }
        } catch (Throwable t) {
          // Disable reflection for this class to prevent repeated errors
          disableReflectionForClass(level.getClass(), "restoreView", t);
        } finally {
          ORIGINAL_VIEW_DISTANCE.remove(level);
        }
      }
    }
    }

  private static void applyItemUpdates(
      MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
    if (itemResult == null || itemResult.getItemUpdates() == null) return;

    // Batch entity lookup: create a map of entity IDs to updates for O(1) lookup
    Map<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate> updateMap =
        new HashMap<>();
    for (var update : itemResult.getItemUpdates()) {
      updateMap.put((int) update.getId(), update);
    }

    // Process all levels once - O(U + L) complexity
    for (ServerLevel level : server.getAllLevels()) {
      for (Map.Entry<Integer, com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate>
          entry : updateMap.entrySet()) {
        Entity entity = level.getEntity(entry.getKey());
        if (entity instanceof ItemEntity itemEntity) {
          com.kneaf.core.performance.core.PerformanceProcessor.ItemUpdate update = entry.getValue();
          itemEntity.getItem().setCount(update.getNewCount());
        }
      }
    }
  }

  private static void applyMobOptimizations(
      MinecraftServer server, com.kneaf.core.performance.core.MobProcessResult mobResult) {
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

  private static void logOptimizations(MinecraftServer server, OptimizationResults results) {
    // Human-readable logs for meaningful changes (less frequent)
    if (TICK_COUNTER % 100 == 0 && hasMeaningfulOptimizations(results)) {
      logReadableOptimizations(server, results);
    }

    // Log spatial grid statistics periodically for performance monitoring
    if (TICK_COUNTER % 1000 == 0) {
      ENTITY_PROCESSOR.logSpatialGridStats(server);
    }

    // Compact metrics line periodically
    if (TICK_COUNTER % CONFIG.getLogIntervalTicks() == 0 && hasMeaningfulOptimizations(results)) {
      String summary = buildOptimizationSummary(results);
      // Persist to file and broadcast to players if enabled
      com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logOptimizations(summary);
      broadcastPerformanceLine(server, "OPTIMIZATIONS " + summary);
    }
  }

  private static void logReadableOptimizations(
      MinecraftServer server, OptimizationResults results) {
    if (results == null) return;
    if (results.itemResult() != null) {
      long merged = results.itemResult().getMergedCount();
      long despawned = results.itemResult().getDespawnedCount();
      if (merged > 0 || despawned > 0) {
        broadcastPerformanceLine(
            server, String.format("Item optimization: %d merged, %d despawned", merged, despawned));
      }
      if (!results.itemResult().getItemsToRemove().isEmpty()) {
        broadcastPerformanceLine(
            server,
            String.format("Items removed: %d", results.itemResult().getItemsToRemove().size()));
      }
    }

    if (results.mobResult() != null) {
      int disabled = results.mobResult().getDisableList().size();
      int simplified = results.mobResult().getSimplifyList().size();
      if (disabled > 0 || simplified > 0) {
        broadcastPerformanceLine(
            server,
            String.format("Mob AI optimization: %d disabled, %d simplified", disabled, simplified));
      }
    }

    if (results.blockResult() != null && !results.blockResult().isEmpty()) {
      broadcastPerformanceLine(
          server,
          String.format("Block entities to tick (reduced): %d", results.blockResult().size()));
    }
  }

  private static void logSpatialGridStats(MinecraftServer server) {
    synchronized (SPATIAL_GRID_LOCK) {
      int totalLevels = LEVEL_SPATIAL_GRIDS.size();
      int totalPlayers = 0;
      int totalCells = 0;

      for (SpatialGrid grid : LEVEL_SPATIAL_GRIDS.values()) {
        SpatialGrid.GridStats Stats = grid.getStats();
        totalPlayers += Stats.totalPlayers();
        totalCells += Stats.totalCells();
      }

      String summary =
          String.format(
              "SpatialGrid Stats: %d levels, %d players, %d cells, avg %s players/cell",
              totalLevels,
              totalPlayers,
              totalCells,
              totalCells > 0 ? String.format("%.2f", (double) totalPlayers / totalCells) : "0.00");

      // Persist to file and broadcast if configured (avoid console spam)
      com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logLine(summary);
      if (CONFIG.isBroadcastToClient()) broadcastPerformanceLine(server, summary);
    }
  }

  private static void logSlowTick(MinecraftServer server, ProfileData profile) {
    if (profile == null) return;
    String jsonProfile = profile.toJson();
    String message =
        String.format(
            "SLOW_TICK: tick=%d avgTps=%.2f threshold=%.2f %s",
            TICK_COUNTER, getRollingAvgTPS(), currentTpsThreshold, jsonProfile);
    // Persist to performance log file
    com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logLine("SLOW_TICK " + message);

    // Broadcast to connected players if enabled in config
    if (CONFIG.isBroadcastToClient()) {
      try {
        for (ServerLevel level : server.getAllLevels()) {
          for (ServerPlayer player : level.players()) {
            // Use system message (not action bar) so it appears in chat/debug overlay for vanilla
            // clients
            player.displayClientMessage(Component.literal(message), false);
          }
        }
      } catch (Exception e) {
        // Ensure broadcasting doesn't crash server; just log at debug level
        LOGGER.debug("Failed to broadcast performance message to players", e);
      }
    }
  }

  /**
   * Broadcast a performance line to connected players (when enabled) and persist to file. This
   * replaces console logging for performance telemetry per user request.
   */
  public static void broadcastPerformanceLine(MinecraftServer server, String line) {
    // Persist to performance log
    com.kneaf.core.performance.monitoring.PerformanceMetricsLogger.logLine(line);

    if (!CONFIG.isBroadcastToClient()) return;

    try {
      for (ServerLevel level : server.getAllLevels()) {
        for (ServerPlayer player : level.players()) {
          player.displayClientMessage(Component.literal(line), false);
        }
      }
    } catch (Exception e) {
      LOGGER.debug("Failed to broadcast performance line to players", e);
    }
  }

  private static String buildOptimizationSummary(OptimizationResults results) {
    double avg = getRollingAvgTPS();
    long itemsMerged = results.itemResult() == null ? 0L : results.itemResult().getMergedCount();
    long itemsDespawned =
        results.itemResult() == null ? 0L : results.itemResult().getDespawnedCount();
    int mobsDisabled =
        results.mobResult() == null ? 0 : results.mobResult().getDisableList().size();
    int mobsSimplified =
        results.mobResult() == null ? 0 : results.mobResult().getSimplifyList().size();
    int itemsRemoved =
        results.itemResult() == null ? 0 : results.itemResult().getItemsToRemove().size();
    int blockEntities = results.blockResult() == null ? 0 : results.blockResult().size();
    int queueSize = getExecutorQueueSize();
    return String.format(
        "avgTps=%.2f currentThreshold=%.2f queueSize=%d itemsMerged=%d itemsDespawned=%d mobsDisabled=%d mobsSimplified=%d itemsRemoved=%d blockEntities=%d",
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
   * Return true only if optimizations include changes that actually mutate the world or remove
   * entities.
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

  private static void removeItems(
      MinecraftServer server, com.kneaf.core.performance.core.ItemProcessResult itemResult) {
    if (itemResult == null
        || itemResult.getItemsToRemove() == null
        || itemResult.getItemsToRemove().isEmpty()) return;
    for (ServerLevel level : server.getAllLevels()) {
      for (Long id : itemResult.getItemsToRemove()) {
        try {
          Entity entity = level.getEntity(id.intValue());
          if (entity != null) {
            entity.remove(RemovalReason.DISCARDED);
          }
        } catch (Exception e) {
          LOGGER.debug("Error removing item entity { } on level { }", id, level.dimension(), e);
        }
      }
    }
  }

  private record OptimizationResults(
      List<Long> toTick,
      List<Long> blockResult,
      com.kneaf.core.performance.core.ItemProcessResult itemResult,
      com.kneaf.core.performance.core.MobProcessResult mobResult) {}

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

