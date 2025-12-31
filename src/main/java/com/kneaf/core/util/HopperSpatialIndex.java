package com.kneaf.core.util;

import net.minecraft.core.BlockPos;
import java.util.*;

/**
 * HopperSpatialIndex - 3D Spatial Hash Grid for fast inventory lookups.
 */
public class HopperSpatialIndex {

    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.Level, HopperSpatialIndex> SPATIAL_INDEXES = new java.util.concurrent.ConcurrentHashMap<>();

    public static HopperSpatialIndex getForLevel(net.minecraft.world.level.Level level) {
        return SPATIAL_INDEXES.computeIfAbsent(level, k -> new HopperSpatialIndex());
    }

    private static final int CELL_SIZE = 16;

    private final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<com.kneaf.core.util.PrimitiveMaps.LongOpenHashSet> grid = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            1024);
    private final com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap positionToCell = new com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap(
            1024);
    private final java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();

    private long indexedInventories = 0;
    private long queryCount = 0;

    private long getCellKey(BlockPos pos) {
        int cellX = Math.floorDiv(pos.getX(), CELL_SIZE);
        int cellY = Math.floorDiv(pos.getY(), CELL_SIZE);
        int cellZ = Math.floorDiv(pos.getZ(), CELL_SIZE);
        return ((long) cellX & 0x1FFFFF) << 42 | ((long) cellY & 0x1FFFFF) << 21 | ((long) cellZ & 0x1FFFFF);
    }

    public void addInventory(BlockPos pos) {
        long cellKey = getCellKey(pos);
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            com.kneaf.core.util.PrimitiveMaps.LongOpenHashSet cell = grid.get(cellKey);
            if (cell == null) {
                cell = new com.kneaf.core.util.PrimitiveMaps.LongOpenHashSet(16);
                grid.put(cellKey, cell);
            }
            if (cell.add(posKey)) {
                positionToCell.put(posKey, cellKey);
                indexedInventories++;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public void removeInventory(BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            long cellKey = positionToCell.get(posKey);
            if (cellKey != -1L) {
                positionToCell.remove(posKey);
                com.kneaf.core.util.PrimitiveMaps.LongOpenHashSet cell = grid.get(cellKey);
                if (cell != null) {
                    cell.remove(posKey);
                }
                indexedInventories--;
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    public List<BlockPos> queryNearby(double x, double y, double z, int range) {
        return queryNearby(new BlockPos((int) x, (int) y, (int) z), range);
    }

    public List<BlockPos> queryNearby(BlockPos pos, int range) {
        queryCount++;
        List<BlockPos> result = new ArrayList<>();
        int cellRange = (range + CELL_SIZE - 1) / CELL_SIZE;
        int baseCellX = Math.floorDiv(pos.getX(), CELL_SIZE);
        int baseCellY = Math.floorDiv(pos.getY(), CELL_SIZE);
        int baseCellZ = Math.floorDiv(pos.getZ(), CELL_SIZE);

        long stamp = lock.readLock();
        try {
            for (int dx = -cellRange; dx <= cellRange; dx++) {
                for (int dy = -cellRange; dy <= cellRange; dy++) {
                    for (int dz = -cellRange; dz <= cellRange; dz++) {
                        int cellX = baseCellX + dx;
                        int cellY = baseCellY + dy;
                        int cellZ = baseCellZ + dz;
                        long cellKey = ((long) cellX & 0x1FFFFF) << 42 | ((long) cellY & 0x1FFFFF) << 21
                                | ((long) cellZ & 0x1FFFFF);
                        com.kneaf.core.util.PrimitiveMaps.LongOpenHashSet cell = grid.get(cellKey);
                        if (cell != null) {
                            cell.forEach((long inventoryPosKey) -> {
                                BlockPos inventoryPos = BlockPos.of(inventoryPosKey);
                                if (inventoryPos.distManhattan(pos) <= range) {
                                    result.add(inventoryPos);
                                }
                            });
                        }
                    }
                }
            }
        } finally {
            lock.unlockRead(stamp);
        }
        return result;
    }

    public boolean hasInventoryAt(BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.tryOptimisticRead();
        boolean contains = positionToCell.containsKey(posKey);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                contains = positionToCell.containsKey(posKey);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return contains;
    }

    public void clear() {
        long stamp = lock.writeLock();
        try {
            grid.clear();
            positionToCell.clear();
            indexedInventories = 0;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
