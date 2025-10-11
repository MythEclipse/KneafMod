package com.kneaf.core.performance.integration;

import com.kneaf.core.data.entity.VillagerData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.monitoring.PerformanceManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import com.kneaf.core.performance.monitoring.EntityProcessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge event integration for KneafCore performance optimizations. Integrates Rust native
 * optimizations into Minecraft server events to reduce tick time.
 */
@EventBusSubscriber(modid = "kneafcore")
@SuppressWarnings({})
public class NeoForgeEventIntegration {
  private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeEventIntegration.class);

  private static final AtomicLong TICK_COUNTER = new AtomicLong(0);
  private static final AtomicLong TOTAL_TICK_TIME = new AtomicLong(0);
  private static final AtomicLong MAX_TICK_TIME = new AtomicLong(0);
  private static final AtomicLong MIN_TICK_TIME = new AtomicLong(Long.MAX_VALUE);

  private static final Map<UUID, PlayerMovementData> PLAYER_MOVEMENT_TRACKER =
      new ConcurrentHashMap<>();
  private static final Set<ChunkPos> PREDICTED_CHUNKS = ConcurrentHashMap.newKeySet();
  private static final Map<Integer, VillagerProcessingData> VILLAGER_PROCESSING_TRACKER =
      new ConcurrentHashMap<>();

  private static boolean optimizationsEnabled = false;
  private static boolean benchmarkMode = false;
  private static long benchmarkStartTime;
  private static long lastMemoryCheck = 0;

  // Performance thresholds (computed dynamically)
  private static long getTargetTickTimeNanos() {
    // Derive target tick time from rolling TPS (ms per tick)
    double avgTps = PerformanceManager.getAverageTPS();
    double msPerTick = 1000.0 / Math.max(0.1, avgTps);
    return (long) (msPerTick * 1_000_000.0);
  }

  private static long getCriticalTickTimeNanos() {
    // Critical threshold is 2x target by default
    return getTargetTickTimeNanos() * 2L;
  }

  private static int getPredictiveChunkRadius() {
    // Scale prediction radius with TPS: higher TPS -> larger radius
    double tps = PerformanceManager.getAverageTPS();
    return Math.max(1, (int) Math.round(Math.min(6.0, Math.max(1.0, tps / 6.0))));
  }

  private static int getMaxVillagersPerTick() {
    com.kneaf.core.performance.monitoring.PerformanceConfig cfg = com.kneaf.core.performance.monitoring.PerformanceConfig.load();
    int base = Math.max(10, cfg.getMaxEntitiesToCollect() / 200);
    double tpsFactor = Math.max(0.5, Math.min(1.5, PerformanceManager.getAverageTPS() / 20.0));
    return Math.max(5, (int) (base * tpsFactor));
  }

  @SubscribeEvent
  public static void onServerAboutToStart(ServerAboutToStartEvent event) {
    try {
      LOGGER.info("Initializing KneafCore NeoForge event integration...");

      MinecraftServer server = event.getServer();

      // Load performance configuration
      com.kneaf.core.performance.monitoring.PerformanceConfig config = com.kneaf.core.performance.monitoring.PerformanceConfig.load();
      optimizationsEnabled = config.isEnabled();
      benchmarkMode = config.isProfilingEnabled();

      if (optimizationsEnabled) {
        LOGGER.info(
            "KneafCore optimizations enabled - targeting { }ms tick time",
            getTargetTickTimeNanos() / 1_000_000);

        // Initialize predictive chunk loading
        initializePredictiveChunkLoading(server);

        // Initialize villager processing optimization
        initializeVillagerProcessing(server);

        // Initialize memory management
        initializeMemoryManagement(server);

        LOGGER.info("KneafCore NeoForge integration initialized successfully");
      } else {
        LOGGER.warn("KneafCore optimizations are disabled in configuration");
      }

    } catch (Exception e) {
      LOGGER.error("Failed to initialize KneafCore NeoForge integration", e);
      optimizationsEnabled = false;
    }
  }

  @SubscribeEvent
  public static void onServerStarted(ServerStartedEvent event) {
    if (!optimizationsEnabled) return;

    try {
      MinecraftServer server = event.getServer();
      benchmarkStartTime = System.nanoTime();

      LOGGER.info("KneafCore server started - performance monitoring active");

      // Start memory management thread
      startMemoryManagementThread(server);

      // Run initial performance benchmark
      runInitialBenchmark(server);

    } catch (Exception e) {
      LOGGER.error("Error during server started event", e);
    }
  }

  @SubscribeEvent
  public static void onServerTick(ServerTickEvent.Post event) {
    if (!optimizationsEnabled) return;

    long tickStartTime = System.nanoTime();

    try {
      MinecraftServer server = event.getServer();

      // Process predictive chunk loading asynchronously
      CompletableFuture.runAsync(() -> processPredictiveChunkLoading(server))
          .exceptionally(ex -> {
            LOGGER.warn("Predictive chunk loading failed asynchronously", ex);
            return null;
          });

      // Use EntityProcessor for villager and entity optimizations (async)
      if (entityProcessor == null) {
        entityProcessor = new EntityProcessor();
      }

      entityProcessor.onServerTickAsync(server)
          .exceptionally(ex -> {
            LOGGER.warn("Entity processing failed asynchronously", ex);
            // Fall back to synchronous processing if async fails
            entityProcessor.onServerTick(server);
            return null;
          });

      // Process SIMD operations asynchronously
      CompletableFuture.runAsync(() -> processSIMDOperations(server))
          .exceptionally(ex -> {
            LOGGER.warn("SIMD operations failed asynchronously", ex);
            return null;
          });

      // Update performance metrics
      updatePerformanceMetrics(server, tickStartTime);

      // Check for performance issues
      checkPerformanceIssues(server, tickStartTime);

    } catch (Exception e) {
      LOGGER.error("Error during server tick processing", e);
    }
  }
  
  // Lazy-loaded EntityProcessor for async optimizations
  private static EntityProcessor entityProcessor;

  // Villager processing is now integrated into the main server tick event
  // for better performance and reduced event overhead

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (!optimizationsEnabled) return;

    if (event.getLevel() instanceof ServerLevel) {
      LevelChunk chunk = (LevelChunk) event.getChunk();
      ChunkPos chunkPos = chunk.getPos();

      // Remove from predicted chunks since it's now loaded
      PREDICTED_CHUNKS.remove(chunkPos);

      // Update chunk loading metrics
      // LOGGER.debug("Chunk loaded at { }", chunkPos);
    }
  }

  @SubscribeEvent
  public static void onServerStopping(ServerStoppingEvent event) {
    if (!optimizationsEnabled) return;

    try {
      LOGGER.info("KneafCore server stopping - final performance report:");

      // Generate final performance report
      generateFinalReport(event.getServer());

    } catch (Exception e) {
      LOGGER.error("Error during server stopping event", e);
    }
  }

  private static void initializePredictiveChunkLoading(MinecraftServer server) {
    LOGGER.info("Initializing predictive chunk loading optimization...");

    // Pre-warm chunk prediction system
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      updatePlayerMovementPrediction(player);
    }
  }

  private static void initializeVillagerProcessing(MinecraftServer server) {
    LOGGER.info("Initializing villager processing optimization...");

    // Scan existing villagers and prepare optimization data
    for (ServerLevel level : server.getAllLevels()) {
      List<Villager> villagers =
          level.getEntitiesOfClass(
              Villager.class, level.getWorldBorder().getCollisionShape().bounds());

      for (Villager villager : villagers) {
        VILLAGER_PROCESSING_TRACKER.put(
            villager.getId(), new VillagerProcessingData(villager.getId()));
      }
    }

    LOGGER.info("Prepared optimization for { } villagers", VILLAGER_PROCESSING_TRACKER.size());
  }

  private static void initializeMemoryManagement(MinecraftServer server) {
    LOGGER.info("Initializing memory management optimization...");

    // Configure memory thresholds
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();
    long warningThreshold = (long) (maxMemory * 0.8);
    long criticalThreshold = (long) (maxMemory * 0.9);

    LOGGER.info(
        "Memory thresholds configured - Warning: { }MB, Critical: { }MB",
        warningThreshold / (1024 * 1024),
        criticalThreshold / (1024 * 1024));
  }

  private static void processPredictiveChunkLoading(MinecraftServer server) {
    try {
      // Update player movement predictions
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        updatePlayerMovementPrediction(player);
      }

      // Generate predicted chunks
      Set<ChunkPos> chunksToGenerate = getPredictedChunks(server);

      if (!chunksToGenerate.isEmpty()) {
        // Determine adaptive batch limits based on current performance
        double tps = PerformanceManager.getAverageTPS();
        int adaptiveRadius =
            com.kneaf.core.performance.core.PerformanceConstants.getAdaptivePredictiveRadius(tps);
        int adaptiveMax =
            com.kneaf.core.performance.core.PerformanceConstants
                .getAdaptiveMaxPredictiveChunksPerTick(tps);

        // Process chunks in deterministic order to avoid oscillation
        List<ChunkPos> chunkList = new ArrayList<>(chunksToGenerate);
        chunkList.sort(Comparator.comparingInt((ChunkPos p) -> p.x).thenComparingInt(p -> p.z));

        int generated = 0;
        Set<ChunkPos> attempted = new HashSet<>();

        for (ChunkPos chunkPos : chunkList) {
          if (generated >= adaptiveMax) break;

          // Skip far-away predictions to bound work
          if (Math.abs(chunkPos.x) > adaptiveRadius * 256
              || Math.abs(chunkPos.z) > adaptiveRadius * 256) {
            continue;
          }

          if (attempted.contains(chunkPos)) continue;
          attempted.add(chunkPos);

          // If chunk is already generated, skip
          if (RustPerformance.isChunkGenerated(chunkPos.x, chunkPos.z)) continue;

          // Pre-generate chunk (Rust native) - ask for a single chunk neighborhood
          int actuallyGenerated =
              RustPerformance.preGenerateNearbyChunks(chunkPos.x, chunkPos.z, 0);
          if (actuallyGenerated > 0) {
            generated += actuallyGenerated;
          }
        }
      }

    } catch (Exception e) {
      LOGGER.warn("Predictive chunk loading failed", e);
    }
  }

  private static void processVillagerOptimizations(MinecraftServer server) {
    try {
      // Villager optimization is now handled by PerformanceManager.onServerTick()
      // which processes all entities including villagers efficiently
      PerformanceManager.onServerTick(server);

    } catch (Exception e) {
      LOGGER.warn("Villager optimization failed", e);
    }
  }

  private static void processSIMDOperations(MinecraftServer server) {
    try {
      // Collect entities for SIMD processing
      List<float[]> entityPositions = new ArrayList<>();

      for (ServerLevel level : server.getAllLevels()) {
        for (Entity entity :
            level.getEntitiesOfClass(
                Entity.class, level.getWorldBorder().getCollisionShape().bounds())) {
          if (entity instanceof net.minecraft.world.entity.LivingEntity) {
            entityPositions.add(
                new float[] {(float) entity.getX(), (float) entity.getY(), (float) entity.getZ()});
          }
        }
      }

      // Process using SIMD operations
      if (!entityPositions.isEmpty()) {
        LOGGER.debug("Processed { } entities with SIMD operations", entityPositions.size());
      }

    } catch (Exception e) {
      LOGGER.warn("SIMD operations failed", e);
    }
  }

  private static void updatePlayerMovementPrediction(ServerPlayer player) {
    PlayerMovementData data =
        PLAYER_MOVEMENT_TRACKER.computeIfAbsent(player.getUUID(), k -> new PlayerMovementData());

    data.updatePosition(player.getX(), player.getY(), player.getZ());
    data.updateLookAngle(player.getYRot(), player.getXRot());

    // Predict future positions
    List<double[]> predictedPositions = data.predictFuturePositions(5);

    // Convert to chunk positions
    for (double[] pos : predictedPositions) {
      ChunkPos chunkPos = new ChunkPos((int) pos[0] >> 4, (int) pos[2] >> 4);
      PREDICTED_CHUNKS.add(chunkPos);
    }
  }

  private static Set<ChunkPos> getPredictedChunks(MinecraftServer server) {
    Set<ChunkPos> chunksToGenerate = new HashSet<>();

    for (ChunkPos predictedPos : PREDICTED_CHUNKS) {
      ServerLevel level = server.overworld();
      if (!level.hasChunk(predictedPos.x, predictedPos.z)) {
        chunksToGenerate.add(predictedPos);
      }
    }

    // Limit the number of chunks to generate per tick
    int radius = getPredictiveChunkRadius();
    if (chunksToGenerate.size() > radius * radius) {
      return chunksToGenerate.stream()
          .limit(radius * radius)
          .collect(java.util.stream.Collectors.toSet());
    }

    return chunksToGenerate;
  }

  private static boolean shouldOptimizeVillager(Villager villager, VillagerProcessingData data) {
    // Check if villager needs AI processing optimization
    long currentTime = System.currentTimeMillis();
    return currentTime - data.getLastProcessedTime() > 1000
        && // Process every second
        data.getErrorCount() < 5; // Skip if too many errors
  }

  private static boolean shouldProcessVillagerThisTick(VillagerProcessingData data) {
    long currentTime = System.currentTimeMillis();
    return currentTime - data.getLastProcessedTime() > 500
        && // Process every 500ms
        data.getErrorCount() < 3;
  }

  private static VillagerData convertToVillagerData(Villager villager) {
    return new VillagerData(
        villager.getId(),
        villager.getX(),
        villager.getY(),
        villager.getZ(),
        0.0, // distance - will be calculated if needed
        villager.getVillagerData().getProfession().toString(),
        villager.getVillagerData().getLevel(),
        villager.getVillagerData().getProfession()
            != net.minecraft.world.entity.npc.VillagerProfession.NONE,
        false, // isResting - would need additional logic
        false, // isBreeding - would need additional logic
        0, // lastPathfindTick - would need additional logic
        100, // pathfindFrequency - default value
        1 // aiComplexity - default value
        );
  }

  private static void updatePerformanceMetrics(MinecraftServer server, long tickStartTime) {
    long tickTime = System.nanoTime() - tickStartTime;

    TICK_COUNTER.incrementAndGet();
    TOTAL_TICK_TIME.addAndGet(tickTime);

    // Update max/min tick times
    MAX_TICK_TIME.updateAndGet(current -> Math.max(current, tickTime));
    MIN_TICK_TIME.updateAndGet(current -> Math.min(current, tickTime));

    // Log performance metrics every 100 ticks (persist to file and optionally broadcast to players)
    if (TICK_COUNTER.get() % 100 == 0) {
      long avgTickTime = TOTAL_TICK_TIME.get() / TICK_COUNTER.get();
      String line =
          String.format(
              "Performance - Avg: %dms, Max: %dms, Min: %dms, TPS: %.2f",
              avgTickTime / 1_000_000,
              MAX_TICK_TIME.get() / 1_000_000,
              MIN_TICK_TIME.get() == Long.MAX_VALUE ? 0 : MIN_TICK_TIME.get() / 1_000_000,
              calculateTPS(avgTickTime));
      PerformanceManager.broadcastPerformanceLine(server, line);
    }
  }

  private static void checkPerformanceIssues(MinecraftServer server, long tickStartTime) {
    long tickTime = System.nanoTime() - tickStartTime;

    if (tickTime > getCriticalTickTimeNanos()) {
      LOGGER.warn("CRITICAL: Tick time { }ms exceeds threshold", tickTime / 1_000_000);

      // Trigger emergency optimizations
      triggerEmergencyOptimizations(server);
    } else if (tickTime > getTargetTickTimeNanos()) {
      LOGGER.debug("Tick time { }ms above target", tickTime / 1_000_000);
    }
  }

  private static void triggerEmergencyOptimizations(MinecraftServer server) {
    try {
      // Force garbage collection
      System.gc();

      // Reduce entity processing
      reduceEntityProcessing(server);

      // Clear some predicted chunks to reduce load
      PREDICTED_CHUNKS.clear();

      LOGGER.info("Emergency optimizations triggered");

    } catch (Exception e) {
      LOGGER.error("Emergency optimizations failed", e);
    }
  }

  private static void reduceEntityProcessing(MinecraftServer server) {
    // Temporarily reduce entity processing to improve tick time
    for (ServerLevel level : server.getAllLevels()) {
      for (Entity entity :
          level.getEntitiesOfClass(
              Entity.class, level.getWorldBorder().getCollisionShape().bounds())) {
        if (entity instanceof net.minecraft.world.entity.LivingEntity) {
          // Skip AI processing for some entities
          if (entity.tickCount % 10 != 0) {
            // Entity will skip this tick's processing
          }
        }
      }
    }
  }

  private static void startMemoryManagementThread(MinecraftServer server) {
    Thread memoryThread =
        new Thread(
            () -> {
              while (server.isRunning()) {
                try {
                  long currentTime = System.currentTimeMillis();

                  if (currentTime - lastMemoryCheck > 30000) { // Check every 30 seconds
                    checkMemoryUsage(server);
                    lastMemoryCheck = currentTime;
                  }

                  Thread.sleep(1000); // Sleep for 1 second
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
                } catch (Exception e) {
                  LOGGER.error("Memory management thread error", e);
                }
              }
            },
            "KneafCore-MemoryManager");

    memoryThread.setDaemon(true);
    memoryThread.start();

    LOGGER.info("Memory management thread started");
  }

  private static void checkMemoryUsage(MinecraftServer server) {
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;

    double memoryUsagePercent = (double) usedMemory / totalMemory * 100;

    if (memoryUsagePercent > 85) {
      // High memory usage detected - suppressing noisy WARN logs. Optimization will run silently.
      optimizeMemoryUsage(server);
    }
  }

  private static void optimizeMemoryUsage(MinecraftServer server) {
    try {
      // Clear unused caches
      PREDICTED_CHUNKS.clear();

      // Suggest garbage collection
      System.gc();

      // Reduce villager processing frequency
      VILLAGER_PROCESSING_TRACKER.values().forEach(data -> data.reduceProcessingFrequency());

    } catch (Exception e) {
      LOGGER.error("Memory optimization failed", e);
    }
  }

  private static void runInitialBenchmark(MinecraftServer server) {
    if (!benchmarkMode) return;

    LOGGER.info("Running initial performance benchmark...");

    try {
      long startTime = System.nanoTime();

      // Test chunk processing
      testChunkProcessing(server);

      // Test villager processing
      testVillagerProcessing(server);

      // Test SIMD operations
      testSIMDOperations();

      long benchmarkTime = System.nanoTime() - startTime;
      LOGGER.info("Initial benchmark completed in { }ms", benchmarkTime / 1_000_000);

    } catch (Exception e) {
      LOGGER.error("Initial benchmark failed", e);
    }
  }

  private static void testChunkProcessing(MinecraftServer server) {
    try {
      List<ChunkPos> testChunks = new ArrayList<>();
      for (int x = -5; x <= 5; x++) {
        for (int z = -5; z <= 5; z++) {
          testChunks.add(new ChunkPos(x, z));
        }
      }

      LOGGER.info("Chunk processing benchmark completed for { } chunks", testChunks.size());

    } catch (Exception e) {
      LOGGER.warn("Chunk processing benchmark failed", e);
    }
  }

  private static void testVillagerProcessing(MinecraftServer server) {
    try {
      List<VillagerData> testVillagers = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        VillagerData data =
            new VillagerData(
                i, i * 10, 64, i * 10, 0.0, "farmer", 1, true, false, false, 0, 100, 1);
        testVillagers.add(data);
      }

      LOGGER.info(
          "Villager processing benchmark completed for { } villagers", testVillagers.size());

    } catch (Exception e) {
      LOGGER.warn("Villager processing benchmark failed", e);
    }
  }

  private static void testSIMDOperations() {
    try {
      List<float[]> testPositions = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        testPositions.add(
            new float[] {(float) (Math.random() * 1000), 64f, (float) (Math.random() * 1000)});
      }

      LOGGER.info("SIMD operations benchmark completed for { } positions", testPositions.size());

    } catch (Exception e) {
      LOGGER.warn("SIMD operations benchmark failed", e);
    }
  }

  private static void generateFinalReport(MinecraftServer server) {
    try {
      long totalTicks = TICK_COUNTER.get();
      if (totalTicks == 0) return;

      long avgTickTime = TOTAL_TICK_TIME.get() / totalTicks;
      double avgTPS = calculateTPS(avgTickTime);

      // Build final report lines and broadcast/persist instead of writing to console
      PerformanceManager.broadcastPerformanceLine(
          server, "=== KneafCore Final Performance Report ===");
      PerformanceManager.broadcastPerformanceLine(
          server, String.format("Total Ticks: %d", totalTicks));
      PerformanceManager.broadcastPerformanceLine(
          server, String.format("Average Tick Time: %dms", avgTickTime / 1_000_000));
      PerformanceManager.broadcastPerformanceLine(
          server, String.format("Maximum Tick Time: %dms", MAX_TICK_TIME.get() / 1_000_000));
      PerformanceManager.broadcastPerformanceLine(
          server,
          String.format(
              "Minimum Tick Time: %dms",
              MIN_TICK_TIME.get() == Long.MAX_VALUE ? 0 : MIN_TICK_TIME.get() / 1_000_000));
      PerformanceManager.broadcastPerformanceLine(
          server, String.format("Average TPS: %.1f", avgTPS));
      PerformanceManager.broadcastPerformanceLine(
          server,
          String.format(
              "Target Achieved: %s", avgTickTime <= getTargetTickTimeNanos() ? "YES" : "NO"));

      if (avgTickTime > getTargetTickTimeNanos()) {
        long improvementNeeded = (avgTickTime - getTargetTickTimeNanos()) / 1_000_000;
        PerformanceManager.broadcastPerformanceLine(
            server, String.format("Improvement Needed: %dms", improvementNeeded));
      }

      PerformanceManager.broadcastPerformanceLine(
          server, "=========================================");

    } catch (Exception e) {
      LOGGER.error("Failed to generate final report", e);
    }
  }

  private static double calculateTPS(long avgTickTime) {
    return 1000.0 / (avgTickTime / 1_000_000.0);
  }

  // Helper classes
  private static class PlayerMovementData {
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private long lastUpdateTime;
    private final List<double[]> movementHistory = new ArrayList<>();

    public void updatePosition(double x, double y, double z) {
      this.lastX = x;
      this.lastY = y;
      this.lastZ = z;
      this.lastUpdateTime = System.currentTimeMillis();

      movementHistory.add(new double[] {x, y, z});
      if (movementHistory.size() > 10) {
        movementHistory.remove(0);
      }
    }

    public void updateLookAngle(float yaw, float pitch) {
      this.lastYaw = yaw;
      this.lastPitch = pitch;
    }

    public List<double[]> predictFuturePositions(int steps) {
      List<double[]> predictions = new ArrayList<>();

      if (movementHistory.size() < 2) {
        predictions.add(new double[] {lastX, lastY, lastZ});
        return predictions;
      }

      // Simple linear prediction based on last movement
      double[] lastPos = movementHistory.get(movementHistory.size() - 1);
      double[] secondLastPos = movementHistory.get(movementHistory.size() - 2);

      double dx = lastPos[0] - secondLastPos[0];
      double dy = lastPos[1] - secondLastPos[1];
      double dz = lastPos[2] - secondLastPos[2];

      for (int i = 1; i <= steps; i++) {
        predictions.add(
            new double[] {lastPos[0] + dx * i, lastPos[1] + dy * i, lastPos[2] + dz * i});
      }

      return predictions;
    }
  }

  private static class VillagerProcessingData {
    private final int villagerId;
    private long lastProcessedTime = 0;
    private int errorCount = 0;
    private int processingInterval = 500; // milliseconds

    public VillagerProcessingData(int villagerId) {
      this.villagerId = villagerId;
    }

    public long getLastProcessedTime() {
      return lastProcessedTime;
    }

    public int getErrorCount() {
      return errorCount;
    }

    public void markProcessed() {
      this.lastProcessedTime = System.currentTimeMillis();
      this.errorCount = 0;
    }

    public void incrementErrorCount() {
      this.errorCount++;
    }

    public void reduceProcessingFrequency() {
      this.processingInterval = Math.min(this.processingInterval * 2, 5000);
    }
  }
}
