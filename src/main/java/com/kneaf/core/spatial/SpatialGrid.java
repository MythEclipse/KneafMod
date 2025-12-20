package com.kneaf.core.spatial;

import com.kneaf.core.util.concurrent.LockFreeHashMap;
import com.kneaf.core.util.concurrent.LockFreeHashSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Lock-free spatial grid for efficient collision detection and entity queries
 * Uses atomic operations and lock-free data structures to eliminate
 * ConcurrentHashMap overhead
 */
public class SpatialGrid {
    private final double cellSize;
    private final LockFreeHashMap<String, LockFreeHashSet<Long>> grid;
    private final LockFreeHashMap<Long, String> entityCells;

    public SpatialGrid(double cellSize) {
        this.cellSize = cellSize;
        this.grid = new LockFreeHashMap<>();
        this.entityCells = new LockFreeHashMap<>();
    }

    public void updateEntity(long entityId, double x, double y, double z) {
        String newCell = getCellKey(x, y, z);

        // Remove from old cell if exists using lock-free operations
        String oldCell = entityCells.remove(entityId);
        if (oldCell != null && !oldCell.equals(newCell)) {
            LockFreeHashSet<Long> entities = grid.get(oldCell);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) {
                    grid.remove(oldCell);
                }
            }
        }

        // Add to new cell using lock-free operations
        if (!newCell.equals(oldCell)) {
            entityCells.put(entityId, newCell);
            grid.computeIfAbsent(newCell, k -> new LockFreeHashSet<>()).add(entityId);
        }
    }

    public void removeEntity(long entityId) {
        String cell = entityCells.remove(entityId);
        if (cell != null) {
            LockFreeHashSet<Long> entities = grid.get(cell);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) {
                    grid.remove(cell);
                }
            }
        }
    }

    public Set<Long> getNearbyEntities(double x, double y, double z, double radius) {
        // OPTIMIZED: Avoid string operations - calculate cell coordinates directly
        int centerX = (int) (x / cellSize);
        int centerY = (int) (y / cellSize);
        int centerZ = (int) (z / cellSize);
        int radiusCells = (int) (radius / cellSize) + 1;
        Set<Long> nearby = new HashSet<>();

        // Check surrounding cells using lock-free access
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    // OPTIMIZED: Use getCellKeyDirect to avoid redundant calculations
                    String cell = getCellKeyDirect(centerX + dx, centerY + dy, centerZ + dz);
                    LockFreeHashSet<Long> entities = grid.get(cell);
                    if (entities != null) {
                        nearby.addAll(entities.toSet());
                    }
                }
            }
        }

        return nearby;
    }

    private String getCellKey(double x, double y, double z) {
        int cellX = (int) (x / cellSize);
        int cellY = (int) (y / cellSize);
        int cellZ = (int) (z / cellSize);
        return getCellKeyDirect(cellX, cellY, cellZ);
    }

    // OPTIMIZED: Direct cell key generation from integer coordinates
    private String getCellKeyDirect(int cellX, int cellY, int cellZ) {
        return cellX + "," + cellY + "," + cellZ;
    }

    public void clear() {
        grid.clear();
        entityCells.clear();
    }

    public int getGridSize() {
        return grid.size.get();
    }
}
