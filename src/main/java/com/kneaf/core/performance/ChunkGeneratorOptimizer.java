package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Optimizes chunk generation using predictive loading and multithreading. Generates chunks
 * asynchronously based on player movement patterns to reduce server lag.
 */
@EventBusSubscriber(modid = "kneafcore")
public class ChunkGeneratorOptimizer {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Predictive loading configuration (adaptive) - values computed at runtime
  // Movement prediction tuning is now adaptive based on server performance
  private static int getPlayerMovementHistorySize() {
    return com.kneaf.core.performance.core.PerformanceConstants
        .getAdaptivePlayerMovementHistorySize(
            com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
  }

  private static double getMinVelocityThreshold() {
    return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveMinVelocityThreshold(
        com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
  }

  private static long getPredictionValidityMs() {
    return com.kneaf.core.performance.core.PerformanceConstants.getAdaptivePredictionValidityMs(
        com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
  }

  // Player movement tracking for predictive loading
  private static final Map<UUID, PlayerMovementData> PLAYER_MOVEMENT_DATA =
      new ConcurrentHashMap<>();
  private static final AtomicInteger PREDICTIVE_CHUNKS_GENERATED = new AtomicInteger(0);
  private static final AtomicInteger TOTAL_PREDICTIVE_ATTEMPTS = new AtomicInteger(0);

  // High speed movement threshold for simplified rendering
  private static final double HIGH_SPEED_THRESHOLD = 10.0; // blocks per second
  private static final int FAST_EXPLORATION_DELTA_THRESHOLD = 16; // chunks

  private ChunkGeneratorOptimizer() {}

  /** Player movement data for predictive loading */
  private static class PlayerMovementData {
    private final Queue<BlockPos> recentPositions =
        new ArrayDeque<>(getPlayerMovementHistorySize());
    private final Queue<Long> timestamps = new ArrayDeque<>(getPlayerMovementHistorySize());
    private long lastPredictionTime = 0;
    private double currentVelocity = 0.0; // blocks per second

    public void addPosition(BlockPos pos) {
      long currentTime = System.currentTimeMillis();

      // Maintain history size
      if (recentPositions.size() >= getPlayerMovementHistorySize()) {
        recentPositions.poll();
        timestamps.poll();
      }

      // Calculate velocity if we have previous position
      if (!recentPositions.isEmpty() && !timestamps.isEmpty()) {
        BlockPos prevPos = recentPositions.peek();
        long prevTime = timestamps.peek();
        double timeDelta = (currentTime - prevTime) / 1000.0; // seconds
        if (timeDelta > 0) {
          double distance = Math.sqrt(
              Math.pow(pos.getX() - prevPos.getX(), 2) +
              Math.pow(pos.getY() - prevPos.getY(), 2) +
              Math.pow(pos.getZ() - prevPos.getZ(), 2)
          );
          currentVelocity = distance / timeDelta;
        }
      }

      recentPositions.offer(pos);
      timestamps.offer(currentTime);
    }

    public BlockPos predictNextPosition() {
      if (recentPositions.size() < 2) {
        return null;
      }

      // Convert positions to chunk coordinates
      List<ChunkPos> chunkPositions = new ArrayList<>();
      for (BlockPos pos : recentPositions) {
        chunkPositions.add(new ChunkPos(pos));
      }

      // Calculate velocity and direction
      ChunkPos currentChunk = chunkPositions.get(chunkPositions.size() - 1);
      ChunkPos previousChunk = chunkPositions.get(chunkPositions.size() - 2);

      int deltaX = currentChunk.x - previousChunk.x;
      int deltaZ = currentChunk.z - previousChunk.z;

      // Only predict if movement is significant
      double minVel = getMinVelocityThreshold();
      if (Math.abs(deltaX) < minVel && Math.abs(deltaZ) < minVel) {
        return null;
      }

      // Predict next position based on movement trend
      int predictedX = currentChunk.x + deltaX;
      int predictedZ = currentChunk.z + deltaZ;

      return new BlockPos(predictedX * 16, 64, predictedZ * 16);
    }

    public boolean isPredictionValid() {
      return System.currentTimeMillis() - lastPredictionTime < getPredictionValidityMs();
    }

    public void updatePrediction(BlockPos predictedPos) {
      this.lastPredictionTime = System.currentTimeMillis();
    }

    public double getCurrentVelocity() {
      return currentVelocity;
    }
  }

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel) {
      // Pre-generate nearby chunks using Rust
      int chunkX = event.getChunk().getPos().x;
      int chunkZ = event.getChunk().getPos().z;
      RustPerformance.preGenerateNearbyChunks(chunkX, chunkZ, 1);
    }
  }

  @SubscribeEvent
  public static void onServerTick(ServerTickEvent.Post event) {
    // Perform predictive chunk loading every tick for the overworld
    ServerLevel overworld = event.getServer().overworld();
    if (overworld != null) {
      performPredictiveChunkLoading(overworld);
    }
  }

  /** Perform predictive chunk loading based on player movement patterns */
  private static void performPredictiveChunkLoading(ServerLevel level) {
    if (level == null) return;

    int chunksGeneratedThisTick = 0;

    // Track player movements and generate predictions
    for (ServerPlayer player : level.players()) {
      UUID playerId = player.getUUID();
      BlockPos currentPos = player.blockPosition();

      // Get or create movement data
      PlayerMovementData movementData =
          PLAYER_MOVEMENT_DATA.computeIfAbsent(playerId, k -> new PlayerMovementData());

      // Add current position to history
      movementData.addPosition(currentPos);

      // Check movement characteristics for optimization
      double velocity = movementData.getCurrentVelocity();
      boolean isHighSpeed = velocity > HIGH_SPEED_THRESHOLD;

      // Check for fast exploration (large chunk jumps)
      boolean isFastExploration = false;
      if (movementData.recentPositions.size() >= 2) {
        List<BlockPos> posList = new ArrayList<>(movementData.recentPositions);
        ChunkPos currentChunk = new ChunkPos(posList.get(posList.size() - 1));
        ChunkPos prevChunk = new ChunkPos(posList.get(posList.size() - 2));
        int deltaX = Math.abs(currentChunk.x - prevChunk.x);
        int deltaZ = Math.abs(currentChunk.z - prevChunk.z);
        isFastExploration = deltaX > FAST_EXPLORATION_DELTA_THRESHOLD || deltaZ > FAST_EXPLORATION_DELTA_THRESHOLD;
      }

      // Predict next position
      BlockPos predictedPos = movementData.predictNextPosition();
      if (predictedPos != null && !movementData.isPredictionValid()) {
        // Generate chunks around predicted position
        ChunkPos predictedChunk = new ChunkPos(predictedPos);

        int tpsAdaptiveRadius =
            com.kneaf.core.performance.core.PerformanceConstants.getAdaptivePredictiveRadius(
                com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
        int tpsAdaptiveMax =
            com.kneaf.core.performance.core.PerformanceConstants
                .getAdaptiveMaxPredictiveChunksPerTick(
                    com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());

        // Adjust parameters based on movement characteristics
        int adjustedRadius = tpsAdaptiveRadius;
        int adjustedMax = tpsAdaptiveMax;
        if (isHighSpeed) {
          // Reduce prefetch for high speed to focus on immediate area
          adjustedRadius = Math.max(1, tpsAdaptiveRadius / 2);
          adjustedMax = Math.max(1, tpsAdaptiveMax / 2);
        } else if (isFastExploration) {
          // Increase prefetch for fast exploration
          adjustedRadius = Math.min(tpsAdaptiveRadius * 2, 32);
          adjustedMax = Math.min(tpsAdaptiveMax * 2, 100);
        }
        for (int dx = -adjustedRadius;
            dx <= adjustedRadius && chunksGeneratedThisTick < adjustedMax;
            dx++) {
          for (int dz = -adjustedRadius;
              dz <= adjustedRadius && chunksGeneratedThisTick < adjustedMax;
              dz++) {
            int chunkX = predictedChunk.x + dx;
            int chunkZ = predictedChunk.z + dz;

            // Check if chunk is already generated
            if (!RustPerformance.isChunkGenerated(chunkX, chunkZ)) {
              // Generate chunk asynchronously
              int generated = RustPerformance.preGenerateNearbyChunks(chunkX, chunkZ, 0);
              if (generated > 0) {
                chunksGeneratedThisTick++;
                PREDICTIVE_CHUNKS_GENERATED.incrementAndGet();
                LOGGER.debug(
                    "Predictively generated chunk at ({}, {}) for player {}",
                    chunkX,
                    chunkZ,
                    player.getName().getString());
              }
            }
          }
        }

        movementData.updatePrediction(predictedPos);
        TOTAL_PREDICTIVE_ATTEMPTS.incrementAndGet();
      }
    }

    // Cleanup old player data
    cleanupOldPlayerData();
  }

  /** Clean up player movement data for players who have been offline */
  private static void cleanupOldPlayerData() {
    long currentTime = System.currentTimeMillis();
    PLAYER_MOVEMENT_DATA
        .entrySet()
        .removeIf(
            entry -> {
              // Remove players who haven't been updated in a while
              PlayerMovementData data = entry.getValue();
              return currentTime - data.lastPredictionTime > getPredictionValidityMs() * 2;
            });
  }

  /** Get predictive loading statistics */
  public static Map<String, Object> getPredictiveStats() {
    Map<String, Object> Stats = new HashMap<>();
    Stats.put("PREDICTIVE_CHUNKS_GENERATED", PREDICTIVE_CHUNKS_GENERATED.get());
    Stats.put("TOTAL_PREDICTIVE_ATTEMPTS", TOTAL_PREDICTIVE_ATTEMPTS.get());
    Stats.put("activePlayersTracked", PLAYER_MOVEMENT_DATA.size());

    if (TOTAL_PREDICTIVE_ATTEMPTS.get() > 0) {
      double successRate =
          (double) PREDICTIVE_CHUNKS_GENERATED.get() / TOTAL_PREDICTIVE_ATTEMPTS.get() * 100;
      Stats.put("predictiveSuccessRate", String.format("%.2f%%", successRate));
    } else {
      Stats.put("predictiveSuccessRate", "0.00%");
    }

    return Stats;
  }

  /** Force cleanup of predictive loading data */
  public static void cleanupPredictiveData() {
    PLAYER_MOVEMENT_DATA.clear();
    PREDICTIVE_CHUNKS_GENERATED.set(0);
    TOTAL_PREDICTIVE_ATTEMPTS.set(0);
    LOGGER.info("Predictive loading data cleaned up");
  }

  /** Shutdown the chunk executor. */
  public static void shutdown() {
    // Cleanup predictive loading data
    cleanupPredictiveData();
    LOGGER.info("ChunkGeneratorOptimizer shutdown complete");
  }
}
