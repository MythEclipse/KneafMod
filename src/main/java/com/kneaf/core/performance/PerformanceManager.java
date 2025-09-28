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
import java.util.Map;
import java.util.HashMap;
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
            int threads = CONFIG.getThreadPoolSize();
            if (CONFIG.isAdaptiveThreadPool()) {
                threads = clamp(Runtime.getRuntime().availableProcessors() - 1, 1, CONFIG.getMaxThreadPoolSize());
            }
            serverTaskExecutor = Executors.newFixedThreadPool(threads, factory);
        }
        return serverTaskExecutor;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
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

        // Respect configured scan interval to reduce overhead on busy servers
        if (tickCounter % CONFIG.getScanIntervalTicks() != 0) {
            return;
        }

        // Collect data synchronously (cheap collection)
        EntityDataCollection data = collectEntityData(server);

        // Consolidate collected items (aggregation by chunk+type) to reduce downstream work
        List<ItemEntityData> consolidated = consolidateItemEntities(data.items());
        data = new EntityDataCollection(data.entities(), consolidated, data.mobs(), data.blockEntities(), data.players());

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

        int maxEntities = CONFIG.getMaxEntitiesToCollect();
        double distanceCutoff = CONFIG.getEntityDistanceCutoff();
        for (ServerLevel level : server.getAllLevels()) {
            collectEntitiesFromLevel(level, entities, items, mobs, maxEntities, distanceCutoff);
            // Block entity collection skipped for performance - can be added if needed
            collectPlayersFromLevel(level, players);
            if (entities.size() >= maxEntities) break; // global cap
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities, players);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, int maxEntities, double distanceCutoff) {
        // Cache distances for this level pass to avoid repeated player distance calculations
        Map<Integer, Double> distanceCache = new HashMap<>();
        String[] excluded = CONFIG.getExcludedEntityTypes();

        // Precompute player positions once per level to speed up distance checks
        List<PlayerData> players = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            players.add(new PlayerData(p.getId(), p.getX(), p.getY(), p.getZ()));
        }

        for (Entity entity : level.getEntities().getAll()) {
            // If we've reached the maximum, stop scanning further entities in this level
            if (entities.size() >= maxEntities) break;

            String typeStr = entity.getType().toString();
            double distance = distanceCache.computeIfAbsent(entity.getId(), id -> calculateDistanceToNearestPlayer(entity, players));

            // single combined skip condition implemented as a guarded block to avoid multiple continue/break
            if (!(distance > distanceCutoff || isExcludedType(typeStr, excluded))) {
                boolean isBlockEntity = false; // Regular entities are not block entities
                entities.add(new EntityData(entity.getId(), entity.getX(), entity.getY(), entity.getZ(), distance, isBlockEntity, typeStr));

                if (entity instanceof ItemEntity itemEntity) {
                    collectItemEntity(entity, itemEntity, items);
                } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
                    collectMobEntity(entity, mob, level, mobs);
                }
            }
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

    /**
     * Consolidate collected item entity data by chunk X/Z and item type to reduce the number
     * of items we hand to the downstream Rust processing. This only aggregates the collected
     * snapshot and does not modify world state directly, so it is safe and purely an optimization.
     */
    private static List<ItemEntityData> consolidateItemEntities(List<ItemEntityData> items) {
        if (items == null || items.isEmpty()) return items;
        class Key {
            final int cx, cz;
            final String type;

            Key(int cx, int cz, String t) {
                this.cx = cx;
                this.cz = cz;
                this.type = t;
            }

            @Override
            public int hashCode() {
                return (cx * 31 + cz) * 31 + (type == null ? 0 : type.hashCode());
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Key)) return false;
                Key k = (Key) o;
                return k.cx == cx && k.cz == cz && (type == null ? k.type == null : type.equals(k.type));
            }
        }
        Map<Key, ItemEntityData> agg = new HashMap<>();
        for (ItemEntityData it : items) {
            Key k = new Key(it.chunkX(), it.chunkZ(), it.itemType());
            ItemEntityData cur = agg.get(k);
            if (cur == null) {
                agg.put(k, it);
            } else {
                // sum counts and keep smallest age to represent the merged group
                int newCount = cur.count() + it.count();
                int newAge = Math.min(cur.ageSeconds(), it.ageSeconds());
                agg.put(k, new ItemEntityData(-1, k.cx, k.cz, k.type, newCount, newAge));
            }
        }
        return new ArrayList<>(agg.values());
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

    // Overload that accepts a precomputed list of PlayerData to avoid iterating server player collections repeatedly
    private static double calculateDistanceToNearestPlayer(Entity entity, List<PlayerData> players) {
        double minDist = Double.MAX_VALUE;
        for (PlayerData p : players) {
            double dx = entity.getX() - p.x();
            double dy = entity.getY() - p.y();
            double dz = entity.getZ() - p.z();
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < minDist) minDist = dist;
        }
        return minDist == Double.MAX_VALUE ? Double.MAX_VALUE : minDist;
    }


    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, RustPerformance.ItemProcessResult itemResult, RustPerformance.MobProcessResult mobResult) {}
}