package com.kneaf.core.performance;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimizes chunk generation using predictive loading and multithreading.
 * Generates chunks asynchronously based on player movement patterns to reduce server lag.
 */
@EventBusSubscriber(modid = "kneafcore")
public class ChunkGeneratorOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Predictive loading configuration
    private static final int PREDICTIVE_RADIUS = 3; // Pre-generate chunks within 3 chunks of predicted position
    private static final int MAX_PREDICTIVE_CHUNKS_PER_TICK = 8; // Limit chunks generated per tick
    private static final int PLAYER_MOVEMENT_HISTORY_SIZE = 10; // Track last 10 positions for prediction
    private static final double MIN_VELOCITY_THRESHOLD = 0.1; // Minimum velocity to consider for prediction
    private static final long PREDICTION_VALIDITY_MS = 5000; // How long predictions remain valid
    
    // Player movement tracking for predictive loading
    private static final Map<UUID, PlayerMovementData> playerMovementData = new ConcurrentHashMap<>();
    private static final AtomicInteger predictiveChunksGenerated = new AtomicInteger(0);
    private static final AtomicInteger totalPredictiveAttempts = new AtomicInteger(0);
    
    private ChunkGeneratorOptimizer() {}
    
    /**
     * Player movement data for predictive loading
     */
    private static class PlayerMovementData {
        private final Queue<BlockPos> recentPositions = new ArrayDeque<>(PLAYER_MOVEMENT_HISTORY_SIZE);
        private final Queue<Long> timestamps = new ArrayDeque<>(PLAYER_MOVEMENT_HISTORY_SIZE);
        private BlockPos lastPredictedPosition = null;
        private long lastPredictionTime = 0;
        
        public void addPosition(BlockPos pos) {
            long currentTime = System.currentTimeMillis();
            
            // Maintain history size
            if (recentPositions.size() >= PLAYER_MOVEMENT_HISTORY_SIZE) {
                recentPositions.poll();
                timestamps.poll();
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
            if (Math.abs(deltaX) < MIN_VELOCITY_THRESHOLD && Math.abs(deltaZ) < MIN_VELOCITY_THRESHOLD) {
                return null;
            }
            
            // Predict next position based on movement trend
            int predictedX = currentChunk.x + deltaX;
            int predictedZ = currentChunk.z + deltaZ;
            
            return new BlockPos(predictedX * 16, 64, predictedZ * 16);
        }
        
        public boolean isPredictionValid() {
            return System.currentTimeMillis() - lastPredictionTime < PREDICTION_VALIDITY_MS;
        }
        
        public void updatePrediction(BlockPos predictedPos) {
            this.lastPredictedPosition = predictedPos;
            this.lastPredictionTime = System.currentTimeMillis();
        }
    }
    
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel) {
            // Pre-generate nearby chunks using Rust
            int chunkX = event.getChunk().getPos().x;
            int chunkZ = event.getChunk().getPos().z;
            int generated = RustPerformance.preGenerateNearbyChunks(chunkX, chunkZ, 1);
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
    
    /**
     * Perform predictive chunk loading based on player movement patterns
     */
    private static void performPredictiveChunkLoading(ServerLevel level) {
        if (level == null) return;
        
        int chunksGeneratedThisTick = 0;
        
        // Track player movements and generate predictions
        for (ServerPlayer player : level.players()) {
            UUID playerId = player.getUUID();
            BlockPos currentPos = player.blockPosition();
            
            // Get or create movement data
            PlayerMovementData movementData = playerMovementData.computeIfAbsent(playerId,
                k -> new PlayerMovementData());
            
            // Add current position to history
            movementData.addPosition(currentPos);
            
            // Predict next position
            BlockPos predictedPos = movementData.predictNextPosition();
            if (predictedPos != null && !movementData.isPredictionValid()) {
                // Generate chunks around predicted position
                ChunkPos predictedChunk = new ChunkPos(predictedPos);
                
                for (int dx = -PREDICTIVE_RADIUS; dx <= PREDICTIVE_RADIUS && chunksGeneratedThisTick < MAX_PREDICTIVE_CHUNKS_PER_TICK; dx++) {
                    for (int dz = -PREDICTIVE_RADIUS; dz <= PREDICTIVE_RADIUS && chunksGeneratedThisTick < MAX_PREDICTIVE_CHUNKS_PER_TICK; dz++) {
                        int chunkX = predictedChunk.x + dx;
                        int chunkZ = predictedChunk.z + dz;
                        
                        // Check if chunk is already generated
                        if (!RustPerformance.isChunkGenerated(chunkX, chunkZ)) {
                            // Generate chunk asynchronously
                            int generated = RustPerformance.preGenerateNearbyChunks(chunkX, chunkZ, 0);
                            if (generated > 0) {
                                chunksGeneratedThisTick++;
                                predictiveChunksGenerated.incrementAndGet();
                                LOGGER.debug("Predictively generated chunk at ({}, {}) for player {}",
                                    chunkX, chunkZ, player.getName().getString());
                            }
                        }
                    }
                }
                
                movementData.updatePrediction(predictedPos);
                totalPredictiveAttempts.incrementAndGet();
            }
        }
        
        // Cleanup old player data
        cleanupOldPlayerData();
    }
    
    /**
     * Clean up player movement data for players who have been offline
     */
    private static void cleanupOldPlayerData() {
        long currentTime = System.currentTimeMillis();
        playerMovementData.entrySet().removeIf(entry -> {
            // Remove players who haven't been updated in a while
            PlayerMovementData data = entry.getValue();
            return currentTime - data.lastPredictionTime > PREDICTION_VALIDITY_MS * 2;
        });
    }
    
    /**
     * Get predictive loading statistics
     */
    public static Map<String, Object> getPredictiveStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("predictiveChunksGenerated", predictiveChunksGenerated.get());
        stats.put("totalPredictiveAttempts", totalPredictiveAttempts.get());
        stats.put("activePlayersTracked", playerMovementData.size());
        
        if (totalPredictiveAttempts.get() > 0) {
            double successRate = (double) predictiveChunksGenerated.get() / totalPredictiveAttempts.get() * 100;
            stats.put("predictiveSuccessRate", String.format("%.2f%%", successRate));
        } else {
            stats.put("predictiveSuccessRate", "0.00%");
        }
        
        return stats;
    }
    
    /**
     * Force cleanup of predictive loading data
     */
    public static void cleanupPredictiveData() {
        playerMovementData.clear();
        predictiveChunksGenerated.set(0);
        totalPredictiveAttempts.set(0);
        LOGGER.info("Predictive loading data cleaned up");
    }

    /**
     * Shutdown the chunk executor.
     */
    public static void shutdown() {
        // Cleanup predictive loading data
        cleanupPredictiveData();
        LOGGER.info("ChunkGeneratorOptimizer shutdown complete");
    }
}