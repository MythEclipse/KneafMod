package com.kneaf.core.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

    // Multithreading executor for server tasks - created lazily so we can respect config and JVM shutdown
    private static ExecutorService serverTaskExecutor = null;

    // Rolling TPS average to make decisions about whether to offload work
    private static final int TPS_WINDOW = 20;
    private static final double[] tpsWindow = new double[TPS_WINDOW];
    private static int tpsWindowIndex = 0;

    private static synchronized ExecutorService getExecutor() {
        if (serverTaskExecutor == null || serverTaskExecutor.isShutdown()) {
            ThreadFactory factory = r -> {
                Thread t = new Thread(r, "kneaf-perf-worker");
                t.setDaemon(true);
                return t;
            };
            serverTaskExecutor = Executors.newFixedThreadPool(CONFIG.getThreadPoolSize(), factory);
        }
        return serverTaskExecutor;
    }

    public static void shutdown() {
        if (serverTaskExecutor != null) {
            try {
                serverTaskExecutor.shutdown();
                serverTaskExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                serverTaskExecutor = null;
            }
        }
    }

    private PerformanceManager() {}

    // Runtime toggle (initialized from config)
    private static volatile boolean enabled = CONFIG.isEnabled();

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean val) { enabled = val; }

    private record EntityDataCollection(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, List<BlockEntityData> blockEntities, List<PlayerData> players) {}

    /**
     * Called on every server tick to perform performance optimizations.
     * Now uses multithreading for processing optimizations asynchronously.
     */
    public static void onServerTick(MinecraftServer server) {
        if (!CONFIG.isEnabled()) return;

        updateTPS();
        tickCounter++;

        // Collect data synchronously (cheap collection)
        EntityDataCollection data = collectEntityData(server);

        // Decide whether to offload heavy processing based on rolling TPS and config
        double avgTps = getRollingAvgTPS();
        if (avgTps >= CONFIG.getTpsThresholdForAsync()) {
            submitAsyncOptimizations(server, data);
        } else {
            runSynchronousOptimizations(server, data);
        }
    }

    private static void submitAsyncOptimizations(MinecraftServer server, EntityDataCollection data) {
        try {
            getExecutor().submit(() -> {
                try {
                    OptimizationResults results = processOptimizations(data);
                    // Schedule modifications back on server thread to stay thread-safe with Minecraft internals
                    server.execute(() -> {
                        try {
                            applyOptimizations(server, results);
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
            runSynchronousOptimizations(server, data);
        }
    }

    private static void runSynchronousOptimizations(MinecraftServer server, EntityDataCollection data) {
        try {
            OptimizationResults results = processOptimizations(data);
            applyOptimizations(server, results);
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
        for (ServerLevel level : server.getAllLevels()) {
            collectEntitiesFromLevel(level, entities, items, mobs);
            // Block entity collection skipped for performance - can be added if needed
            collectPlayersFromLevel(level, players);
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities, players);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs) {
        for (Entity entity : level.getEntities().getAll()) {
            double distance = calculateDistanceToNearestPlayer(entity, level);
            boolean isBlockEntity = false; // Regular entities are not block entities
            entities.add(new EntityData(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), distance, isBlockEntity, entity.getType().toString()));

            if (entity instanceof ItemEntity itemEntity) {
                collectItemEntity(entity, itemEntity, items);
            } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
                collectMobEntity(entity, mob, level, mobs);
            }
        }
    }

    private static void collectItemEntity(Entity entity, ItemEntity itemEntity, List<ItemEntityData> items) {
        var chunkPos = entity.chunkPosition();
        var itemStack = itemEntity.getItem();
        var itemType = itemStack.getItem().getDescriptionId();
        var count = itemStack.getCount();
        var ageSeconds = itemEntity.getAge() / 20;
        items.add(new ItemEntityData(entity.getId(), chunkPos.x, chunkPos.z, itemType, count, ageSeconds));
    }

    private static void collectMobEntity(Entity entity, net.minecraft.world.entity.Mob mob, ServerLevel level, List<MobData> mobs) {
        double distance = calculateDistanceToNearestPlayer(entity, level);
        boolean isPassive = !(mob instanceof net.minecraft.world.entity.monster.Monster);
        mobs.add(new MobData(entity.getId(), distance, isPassive, entity.getType().toString()));
    }



    private static void collectPlayersFromLevel(ServerLevel level, List<PlayerData> players) {
        for (ServerPlayer player : level.players()) {
            players.add(new PlayerData(player.getId(), player.getX(), player.getY(), player.getZ()));
        }
    }

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
        for (var update : itemResult.getItemUpdates()) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity((int) update.getId());
                if (entity instanceof ItemEntity itemEntity) {
                    itemEntity.getItem().setCount(update.getNewCount());
                }
            }
        }
    }

    private static void applyMobOptimizations(MinecraftServer server, RustPerformance.MobProcessResult mobResult) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Long id : mobResult.getMobsToDisableAI()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setNoAi(true);
                }
            }
            for (Long id : mobResult.getMobsToSimplifyAI()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity instanceof net.minecraft.world.entity.Mob) {
                    LOGGER.debug("Simplifying AI for mob {}", id);
                }
            }
        }
    }

    private static void logOptimizations(OptimizationResults results) {
        if (tickCounter % 100 == 0 && hasOptimizations(results)) {
            LOGGER.info("Entities to tick: {}", results.toTick().size());
            LOGGER.info("Block entities to tick: {}", results.blockResult().size());
            LOGGER.info("Item optimization: {} merged, {} despawned", results.itemResult().getMergedCount(), results.itemResult().getDespawnedCount());
            LOGGER.info("Mob AI optimization: {} disabled, {} simplified", results.mobResult().getMobsToDisableAI().size(), results.mobResult().getMobsToSimplifyAI().size());
        }

        // Also write a compact metrics line to the performance log periodically
        if (tickCounter % CONFIG.getLogIntervalTicks() == 0) {
            double avg = getRollingAvgTPS();
            String summary = String.format("avgTps=%.2f entitiesToTick=%d blockEntities=%d itemsMerged=%d itemsDespawned=%d mobsDisabled=%d mobsSimplified=%d itemsRemoved=%d",
                    avg,
                    results.toTick().size(),
                    results.blockResult().size(),
                    results.itemResult().getMergedCount(),
                    results.itemResult().getDespawnedCount(),
                    results.mobResult().getMobsToDisableAI().size(),
                    results.mobResult().getMobsToSimplifyAI().size(),
                    results.itemResult().getItemsToRemove().size());
            PerformanceMetricsLogger.logOptimizations(summary);
        }
    }

    private static boolean hasOptimizations(OptimizationResults results) {
        return !results.toTick().isEmpty() || results.itemResult().getMergedCount() > 0 || results.itemResult().getDespawnedCount() > 0 ||
               !results.mobResult().getMobsToDisableAI().isEmpty() || !results.mobResult().getMobsToSimplifyAI().isEmpty() || !results.blockResult().isEmpty();
    }

    private static void removeItems(MinecraftServer server, RustPerformance.ItemProcessResult itemResult) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Long id : itemResult.getItemsToRemove()) {
                Entity entity = level.getEntity(id.intValue());
                if (entity != null) {
                    entity.remove(RemovalReason.DISCARDED);
                }
            }
        }
    }

    private static double calculateDistanceToNearestPlayer(Entity entity, ServerLevel level) {
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = entity.distanceTo(player);
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }


    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, RustPerformance.ItemProcessResult itemResult, RustPerformance.MobProcessResult mobResult) {}
}