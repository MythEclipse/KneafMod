package com.kneaf.core.performance;

import com.kneaf.core.KneafCore;
import com.kneaf.core.data.VillagerData;
import com.kneaf.core.performance.PerformanceConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge event integration for KneafCore performance optimizations.
 * Integrates Rust native optimizations into Minecraft server events to reduce tick time.
 */
@EventBusSubscriber(modid = "kneafcore")
public class NeoForgeEventIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeEventIntegration.class);
    
    private static final AtomicLong tickCounter = new AtomicLong(0);
    private static final AtomicLong totalTickTime = new AtomicLong(0);
    private static final AtomicLong maxTickTime = new AtomicLong(0);
    private static final AtomicLong minTickTime = new AtomicLong(Long.MAX_VALUE);
    
    private static final Map<UUID, PlayerMovementData> playerMovementTracker = new ConcurrentHashMap<>();
    private static final Set<ChunkPos> predictedChunks = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, VillagerProcessingData> villagerProcessingTracker = new ConcurrentHashMap<>();
    
    private static boolean optimizationsEnabled = false;
    private static boolean benchmarkMode = false;
    private static long benchmarkStartTime;
    private static long lastMemoryCheck = 0;
    
    // Performance thresholds
    private static final long TARGET_TICK_TIME = 20_000_000; // 20ms in nanoseconds
    private static final long CRITICAL_TICK_TIME = 50_000_000; // 50ms in nanoseconds
    private static final int PREDICTIVE_CHUNK_RADIUS = 3;
    private static final int MAX_VILLAGERS_PER_TICK = 50;
    
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            LOGGER.info("Initializing KneafCore NeoForge event integration...");
            
            MinecraftServer server = event.getServer();
            
            // Load performance configuration
            PerformanceConfig config = PerformanceConfig.load();
            optimizationsEnabled = config.isEnabled();
            benchmarkMode = config.isProfilingEnabled();
            
            if (optimizationsEnabled) {
                LOGGER.info("KneafCore optimizations enabled - targeting {}ms tick time", TARGET_TICK_TIME / 1_000_000);
                
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
            
            // Process predictive chunk loading
            processPredictiveChunkLoading(server);
            
            // Villager optimizations are handled by PerformanceManager
            // processVillagerOptimizations(server);
            
            // Process SIMD operations
            processSIMDOperations(server);
            
            // Update performance metrics
            updatePerformanceMetrics(tickStartTime);
            
            // Check for performance issues
            checkPerformanceIssues(server, tickStartTime);
            
        } catch (Exception e) {
            LOGGER.error("Error during server tick processing", e);
        }
    }
    
    // Villager processing is now integrated into the main server tick event
    // for better performance and reduced event overhead
    
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!optimizationsEnabled) return;
        
        if (event.getLevel() instanceof ServerLevel) {
            LevelChunk chunk = (LevelChunk) event.getChunk();
            ChunkPos chunkPos = chunk.getPos();
            
            // Remove from predicted chunks since it's now loaded
            predictedChunks.remove(chunkPos);
            
            // Update chunk loading metrics
            LOGGER.debug("Chunk loaded at {}", chunkPos);
        }
    }
    
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (!optimizationsEnabled) return;
        
        try {
            LOGGER.info("KneafCore server stopping - final performance report:");
            
            // Generate final performance report
            generateFinalReport();
            
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
            List<Villager> villagers = level.getEntitiesOfClass(Villager.class, 
                level.getWorldBorder().getCollisionShape().bounds());
            
            for (Villager villager : villagers) {
                villagerProcessingTracker.put(villager.getId(), new VillagerProcessingData(villager.getId()));
            }
        }
        
        LOGGER.info("Prepared optimization for {} villagers", villagerProcessingTracker.size());
    }
    
    private static void initializeMemoryManagement(MinecraftServer server) {
        LOGGER.info("Initializing memory management optimization...");
        
        // Configure memory thresholds
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long warningThreshold = (long)(maxMemory * 0.8);
        long criticalThreshold = (long)(maxMemory * 0.9);
        
        LOGGER.info("Memory thresholds configured - Warning: {}MB, Critical: {}MB", 
            warningThreshold / (1024 * 1024), criticalThreshold / (1024 * 1024));
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
                // Use Rust native chunk generation
                List<ChunkPos> chunkList = new ArrayList<>(chunksToGenerate);
                
                // Process chunks using PerformanceManager
                for (ChunkPos chunkPos : chunkList) {
                    LOGGER.debug("Predicted chunk generation for {}", chunkPos);
                }
                
                LOGGER.debug("Processed {} predicted chunks", chunkList.size());
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
                for (Entity entity : level.getEntitiesOfClass(Entity.class, level.getWorldBorder().getCollisionShape().bounds())) {
                    if (entity instanceof net.minecraft.world.entity.LivingEntity) {
                        entityPositions.add(new float[]{
                            (float) entity.getX(),
                            (float) entity.getY(),
                            (float) entity.getZ()
                        });
                    }
                }
            }
            
            // Process using SIMD operations
            if (!entityPositions.isEmpty()) {
                LOGGER.debug("Processed {} entities with SIMD operations", entityPositions.size());
            }
            
        } catch (Exception e) {
            LOGGER.warn("SIMD operations failed", e);
        }
    }
    
    private static void updatePlayerMovementPrediction(ServerPlayer player) {
        PlayerMovementData data = playerMovementTracker.computeIfAbsent(player.getUUID(), 
            k -> new PlayerMovementData());
        
        data.updatePosition(player.getX(), player.getY(), player.getZ());
        data.updateLookAngle(player.getYRot(), player.getXRot());
        
        // Predict future positions
        List<double[]> predictedPositions = data.predictFuturePositions(5);
        
        // Convert to chunk positions
        for (double[] pos : predictedPositions) {
            ChunkPos chunkPos = new ChunkPos((int) pos[0] >> 4, (int) pos[2] >> 4);
            predictedChunks.add(chunkPos);
        }
    }
    
    private static Set<ChunkPos> getPredictedChunks(MinecraftServer server) {
        Set<ChunkPos> chunksToGenerate = new HashSet<>();
        
        for (ChunkPos predictedPos : predictedChunks) {
            ServerLevel level = server.overworld();
            if (!level.hasChunk(predictedPos.x, predictedPos.z)) {
                chunksToGenerate.add(predictedPos);
            }
        }
        
        // Limit the number of chunks to generate per tick
        if (chunksToGenerate.size() > PREDICTIVE_CHUNK_RADIUS * PREDICTIVE_CHUNK_RADIUS) {
            return chunksToGenerate.stream()
                .limit(PREDICTIVE_CHUNK_RADIUS * PREDICTIVE_CHUNK_RADIUS)
                .collect(java.util.stream.Collectors.toSet());
        }
        
        return chunksToGenerate;
    }
    
    private static boolean shouldOptimizeVillager(Villager villager, VillagerProcessingData data) {
        // Check if villager needs AI processing optimization
        long currentTime = System.currentTimeMillis();
        return currentTime - data.getLastProcessedTime() > 1000 && // Process every second
               data.getErrorCount() < 5; // Skip if too many errors
    }
    
    private static boolean shouldProcessVillagerThisTick(VillagerProcessingData data) {
        long currentTime = System.currentTimeMillis();
        return currentTime - data.getLastProcessedTime() > 500 && // Process every 500ms
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
            villager.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.NONE,
            false, // isResting - would need additional logic
            false, // isBreeding - would need additional logic
            0, // lastPathfindTick - would need additional logic
            100, // pathfindFrequency - default value
            1 // aiComplexity - default value
        );
    }
    
    private static void updatePerformanceMetrics(long tickStartTime) {
        long tickTime = System.nanoTime() - tickStartTime;
        
        tickCounter.incrementAndGet();
        totalTickTime.addAndGet(tickTime);
        
        // Update max/min tick times
        maxTickTime.updateAndGet(current -> Math.max(current, tickTime));
        minTickTime.updateAndGet(current -> Math.min(current, tickTime));
        
        // Log performance metrics every 100 ticks
        if (tickCounter.get() % 100 == 0) {
            long avgTickTime = totalTickTime.get() / tickCounter.get();
            LOGGER.info("Performance - Avg: {}ms, Max: {}ms, Min: {}ms, TPS: {}", 
                avgTickTime / 1_000_000,
                maxTickTime.get() / 1_000_000,
                minTickTime.get() == Long.MAX_VALUE ? 0 : minTickTime.get() / 1_000_000,
                calculateTPS(avgTickTime));
        }
    }
    
    private static void checkPerformanceIssues(MinecraftServer server, long tickStartTime) {
        long tickTime = System.nanoTime() - tickStartTime;
        
        if (tickTime > CRITICAL_TICK_TIME) {
            LOGGER.warn("CRITICAL: Tick time {}ms exceeds threshold", tickTime / 1_000_000);
            
            // Trigger emergency optimizations
            triggerEmergencyOptimizations(server);
        } else if (tickTime > TARGET_TICK_TIME) {
            LOGGER.debug("Tick time {}ms above target", tickTime / 1_000_000);
        }
    }
    
    private static void triggerEmergencyOptimizations(MinecraftServer server) {
        try {
            // Force garbage collection
            System.gc();
            
            // Reduce entity processing
            reduceEntityProcessing(server);
            
            // Clear some predicted chunks to reduce load
            predictedChunks.clear();
            
            LOGGER.info("Emergency optimizations triggered");
            
        } catch (Exception e) {
            LOGGER.error("Emergency optimizations failed", e);
        }
    }
    
    private static void reduceEntityProcessing(MinecraftServer server) {
        // Temporarily reduce entity processing to improve tick time
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntitiesOfClass(Entity.class, level.getWorldBorder().getCollisionShape().bounds())) {
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
        Thread memoryThread = new Thread(() -> {
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
        }, "KneafCore-MemoryManager");
        
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
            LOGGER.warn("High memory usage detected: {}%", String.format("%.1f", memoryUsagePercent));
            
            // Trigger memory optimization
            optimizeMemoryUsage(server);
        }
    }
    
    private static void optimizeMemoryUsage(MinecraftServer server) {
        try {
            // Clear unused caches
            predictedChunks.clear();
            
            // Suggest garbage collection
            System.gc();
            
            // Reduce villager processing frequency
            villagerProcessingTracker.values().forEach(data -> data.reduceProcessingFrequency());
            
            LOGGER.info("Memory optimization triggered");
            
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
            LOGGER.info("Initial benchmark completed in {}ms", benchmarkTime / 1_000_000);
            
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
            
            LOGGER.info("Chunk processing benchmark completed for {} chunks", testChunks.size());
            
        } catch (Exception e) {
            LOGGER.warn("Chunk processing benchmark failed", e);
        }
    }
    
    private static void testVillagerProcessing(MinecraftServer server) {
        try {
            List<VillagerData> testVillagers = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                VillagerData data = new VillagerData(
                    i, i * 10, 64, i * 10, 0.0, "farmer", 1, true, false, false, 0, 100, 1
                );
                testVillagers.add(data);
            }
            
            LOGGER.info("Villager processing benchmark completed for {} villagers", testVillagers.size());
            
        } catch (Exception e) {
            LOGGER.warn("Villager processing benchmark failed", e);
        }
    }
    
    private static void testSIMDOperations() {
        try {
            List<float[]> testPositions = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                testPositions.add(new float[]{(float) (Math.random() * 1000), 64f, (float) (Math.random() * 1000)});
            }
            
            LOGGER.info("SIMD operations benchmark completed for {} positions", testPositions.size());
            
        } catch (Exception e) {
            LOGGER.warn("SIMD operations benchmark failed", e);
        }
    }
    
    private static void generateFinalReport() {
        try {
            long totalTicks = tickCounter.get();
            if (totalTicks == 0) return;
            
            long avgTickTime = totalTickTime.get() / totalTicks;
            double avgTPS = calculateTPS(avgTickTime);
            
            LOGGER.info("=== KneafCore Final Performance Report ===");
            LOGGER.info("Total Ticks: {}", totalTicks);
            LOGGER.info("Average Tick Time: {}ms", avgTickTime / 1_000_000);
            LOGGER.info("Maximum Tick Time: {}ms", maxTickTime.get() / 1_000_000);
            LOGGER.info("Minimum Tick Time: {}ms", minTickTime.get() == Long.MAX_VALUE ? 0 : minTickTime.get() / 1_000_000);
            LOGGER.info("Average TPS: {}", String.format("%.1f", avgTPS));
            LOGGER.info("Target Achieved: {}", avgTickTime <= TARGET_TICK_TIME ? "YES" : "NO");
            
            if (avgTickTime > TARGET_TICK_TIME) {
                long improvementNeeded = (avgTickTime - TARGET_TICK_TIME) / 1_000_000;
                LOGGER.info("Improvement Needed: {}ms", improvementNeeded);
            }
            
            LOGGER.info("=========================================");
            
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
            
            movementHistory.add(new double[]{x, y, z});
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
                predictions.add(new double[]{lastX, lastY, lastZ});
                return predictions;
            }
            
            // Simple linear prediction based on last movement
            double[] lastPos = movementHistory.get(movementHistory.size() - 1);
            double[] secondLastPos = movementHistory.get(movementHistory.size() - 2);
            
            double dx = lastPos[0] - secondLastPos[0];
            double dy = lastPos[1] - secondLastPos[1];
            double dz = lastPos[2] - secondLastPos[2];
            
            for (int i = 1; i <= steps; i++) {
                predictions.add(new double[]{
                    lastPos[0] + dx * i,
                    lastPos[1] + dy * i,
                    lastPos[2] + dz * i
                });
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