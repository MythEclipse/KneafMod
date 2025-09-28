package com.kneaf.core.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import com.kneaf.core.data.EntityData;
import com.kneaf.core.data.ItemEntityData;
import com.kneaf.core.data.MobData;
import com.kneaf.core.data.BlockEntityData;

/**
 * Manages performance optimizations for the Minecraft server.
 * Handles entity ticking, item merging, mob AI optimization, and block entity management.
 */
public class PerformanceManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int tickCounter = 0;
    private static long lastTickTime = 0;

    private PerformanceManager() {}

    /**
     * Called on every server tick to perform performance optimizations.
     */
    public static void onServerTick(MinecraftServer server) {
        updateTPS();
        tickCounter++;

        EntityDataCollection data = collectEntityData(server);
        OptimizationResults results = processOptimizations(data);
        applyOptimizations(server, results);
        logOptimizations(results);
        removeItems(server, results.itemResult());
    }

    private static void updateTPS() {
        long currentTime = System.nanoTime();
        if (lastTickTime != 0) {
            long delta = currentTime - lastTickTime;
            double tps = 1_000_000_000.0 / delta;
            RustPerformance.setCurrentTPS(Math.min(tps, 20.0));
        }
        lastTickTime = currentTime;
    }

    private static EntityDataCollection collectEntityData(MinecraftServer server) {
        List<EntityData> entities = new ArrayList<>();
        List<ItemEntityData> items = new ArrayList<>();
        List<MobData> mobs = new ArrayList<>();
        List<BlockEntityData> blockEntities = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            collectEntitiesFromLevel(level, entities, items, mobs);
            collectBlockEntitiesFromLevel(level, blockEntities);
        }
        return new EntityDataCollection(entities, items, mobs, blockEntities);
    }

    private static void collectEntitiesFromLevel(ServerLevel level, List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof ItemEntity itemEntity) {
                collectItemEntity(entity, itemEntity, level, entities, items);
            } else if (entity instanceof net.minecraft.world.entity.Mob mob) {
                collectMobEntity(entity, mob, level, mobs);
            }
        }
    }

    private static void collectItemEntity(Entity entity, ItemEntity itemEntity, ServerLevel level, List<EntityData> entities, List<ItemEntityData> items) {
        double distance = calculateDistanceToNearestPlayer(entity, level);
        entities.add(new EntityData(entity.getId(), distance, false, entity.getType().toString()));
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

    private static void collectBlockEntitiesFromLevel(ServerLevel level, List<BlockEntityData> blockEntities) {
        try {
            Field tickersField = ServerLevel.class.getDeclaredField("blockEntityTickers");
            tickersField.setAccessible(true); // NOSONAR
            @SuppressWarnings("unchecked")
            Map<BlockPos, TickingBlockEntity> tickers = (Map<BlockPos, TickingBlockEntity>) tickersField.get(level);
            for (var entry : tickers.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    double distance = calculateDistanceToNearestPlayer(pos, level);
                    String blockType = be.getType().toString();
                    long id = ((long) pos.getX() << 32) | ((long) pos.getZ() << 16) | pos.getY();
                    blockEntities.add(new BlockEntityData(id, distance, blockType, pos.getX(), pos.getY(), pos.getZ()));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to collect block entities", e);
        }
    }

    private static OptimizationResults processOptimizations(EntityDataCollection data) {
        List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities());
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

    private static double calculateDistanceToNearestPlayer(BlockPos pos, ServerLevel level) {
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double dist = Math.sqrt(pos.distSqr(player.blockPosition()));
            if (dist < minDist) minDist = dist;
        }
        return minDist;
    }

    private record EntityDataCollection(List<EntityData> entities, List<ItemEntityData> items, List<MobData> mobs, List<BlockEntityData> blockEntities) {}
    private record OptimizationResults(List<Long> toTick, List<Long> blockResult, RustPerformance.ItemProcessResult itemResult, RustPerformance.MobProcessResult mobResult) {}
}