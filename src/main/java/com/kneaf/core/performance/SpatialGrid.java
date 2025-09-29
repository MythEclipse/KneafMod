package com.kneaf.core.performance;

import com.kneaf.core.data.PlayerData;
import java.util.*;

/**
 * Spatial grid for efficient player position queries.
 * Divides the world into grid cells and maintains player positions per cell
 * to reduce distance calculation complexity from O(M) to O(log M) for nearby queries.
 */
public class SpatialGrid {
    private static final double DEFAULT_CELL_SIZE = 32.0; // 32 blocks per cell
    
    private final double cellSize;
    private final Map<Long, Set<PlayerData>> gridCells;
    private final Map<Long, PlayerData> playerPositionCache;
    
    public SpatialGrid() {
        this(DEFAULT_CELL_SIZE);
    }
    
    public SpatialGrid(double cellSize) {
        this.cellSize = cellSize;
        this.gridCells = new HashMap<>();
        this.playerPositionCache = new HashMap<>();
    }
    
    /**
     * Update or insert a player position in the spatial grid.
     */
    public void updatePlayer(PlayerData player) {
        // Remove from old position if exists
        PlayerData oldPlayer = playerPositionCache.get(player.id());
        if (oldPlayer != null) {
            removePlayerFromGrid(oldPlayer);
        }
        
        // Add to new position
        playerPositionCache.put(player.id(), player);
        addPlayerToGrid(player);
    }
    
    /**
     * Remove a player from the spatial grid.
     */
    public void removePlayer(long playerId) {
        PlayerData player = playerPositionCache.remove(playerId);
        if (player != null) {
            removePlayerFromGrid(player);
        }
    }
    
    /**
     * Find nearby players within a radius of the given position.
     * Returns players sorted by distance (closest first).
     */
    public List<PlayerDistance> findNearbyPlayers(double x, double y, double z, double radius) {
        // Pre-size the set to avoid resizing
        int estimatedSize = Math.min(32, (int)(radius * radius / (cellSize * cellSize)));
        Set<PlayerDistance> nearbyPlayers = new HashSet<>(estimatedSize); // NOSONAR
        double radiusSquared = radius * radius;
        
        // Calculate grid bounds for the search area
        int minCellX = (int) Math.floor((x - radius) / cellSize);
        int maxCellX = (int) Math.floor((x + radius) / cellSize);
        int minCellZ = (int) Math.floor((z - radius) / cellSize);
        int maxCellZ = (int) Math.floor((z + radius) / cellSize);
        
        // Search all cells in the bounding box
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long cellKey = getCellKey(cellX, cellZ);
                addNearbyPlayersFromCell(nearbyPlayers, cellKey, x, y, z, radiusSquared);
            }
        }
        
        // Sort by distance and return
        List<PlayerDistance> result = new ArrayList<>(nearbyPlayers);
        result.sort(Comparator.comparingDouble(PlayerDistance::distance));
        return result;
    }
    
    private void addNearbyPlayersFromCell(Set<PlayerDistance> nearbyPlayers, long cellKey, double x, double y, double z, double radiusSquared) {
        Set<PlayerData> cellPlayers = gridCells.get(cellKey);
        if (cellPlayers != null) {
            for (PlayerData player : cellPlayers) {
                double dx = player.x() - x;
                double dy = player.y() - y;
                double dz = player.z() - z;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (distanceSquared <= radiusSquared) {
                    nearbyPlayers.add(new PlayerDistance(player, Math.sqrt(distanceSquared)));
                }
            }
        }
    }
    
    private double getMinSquaredDistanceFromCell(long cellKey, double x, double y, double z) {
        Set<PlayerData> cellPlayers = gridCells.get(cellKey);
        if (cellPlayers == null) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (PlayerData player : cellPlayers) {
            double dx = player.x() - x;
            double dy = player.y() - y;
            double dz = player.z() - z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < min) {
                min = distanceSquared;
            }
        }
        return min;
    }
    
    /**
     * Find the minimum squared distance to any player.
     * This is optimized for the common case where we just need to know
     * if an entity is within a certain distance of any player.
     */
    public double findMinSquaredDistance(double x, double y, double z, double maxSearchRadius) {
        double minDistanceSquared = Double.MAX_VALUE;
        
        // Calculate grid bounds for the search area
        int minCellX = (int) Math.floor((x - maxSearchRadius) / cellSize);
        int maxCellX = (int) Math.floor((x + maxSearchRadius) / cellSize);
        int minCellZ = (int) Math.floor((z - maxSearchRadius) / cellSize);
        int maxCellZ = (int) Math.floor((z + maxSearchRadius) / cellSize);
        
        // Search all cells in the bounding box
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long cellKey = getCellKey(cellX, cellZ);
                double cellMin = getMinSquaredDistanceFromCell(cellKey, x, y, z);
                if (cellMin < minDistanceSquared) {
                    minDistanceSquared = cellMin;
                    // Early exit if we find a player very close
                    if (minDistanceSquared < 1.0) {
                        return minDistanceSquared;
                    }
                }
            }
        }
        
        return minDistanceSquared == Double.MAX_VALUE ? Double.MAX_VALUE : minDistanceSquared;
    }
    
    /**
     * Clear all players from the grid.
     */
    public void clear() {
        gridCells.clear();
        playerPositionCache.clear();
    }
    
    /**
     * Get the number of players in the grid.
     */
    public int getPlayerCount() {
        return playerPositionCache.size();
    }
    
    /**
     * Check if a player position has changed significantly and should be updated.
     * This supports incremental updates for better performance.
     */
    public boolean shouldUpdatePlayer(PlayerData player) {
        PlayerData cachedPlayer = playerPositionCache.get(player.id());
        if (cachedPlayer == null) {
            return true; // Player not in cache, needs update
        }
        
        // Check if position has changed significantly (more than 1 block)
        double dx = player.x() - cachedPlayer.x();
        double dy = player.y() - cachedPlayer.y();
        double dz = player.z() - cachedPlayer.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        
        return distanceSquared > 1.0; // Update if moved more than 1 block
    }
    
    /**
     * Get grid statistics for debugging.
     */
    public GridStats getStats() {
        int totalCells = gridCells.size();
        int totalPlayers = playerPositionCache.size();
        int maxPlayersPerCell = gridCells.values().stream()
            .mapToInt(Set::size)
            .max()
            .orElse(0);
        double avgPlayersPerCell = totalCells > 0 ? (double) totalPlayers / totalCells : 0.0;
        
        return new GridStats(totalCells, totalPlayers, maxPlayersPerCell, avgPlayersPerCell);
    }
    
    private void addPlayerToGrid(PlayerData player) {
        long cellKey = getCellKeyForPosition(player.x(), player.z());
        gridCells.computeIfAbsent(cellKey, k -> new HashSet<>()).add(player);
    }
    
    private void removePlayerFromGrid(PlayerData player) {
        long cellKey = getCellKeyForPosition(player.x(), player.z());
        Set<PlayerData> cellPlayers = gridCells.get(cellKey);
        if (cellPlayers != null) {
            cellPlayers.remove(player);
            if (cellPlayers.isEmpty()) {
                gridCells.remove(cellKey);
            }
        }
    }
    
    private long getCellKeyForPosition(double x, double z) {
        int cellX = (int) Math.floor(x / cellSize);
        int cellZ = (int) Math.floor(z / cellSize);
        return getCellKey(cellX, cellZ);
    }
    
    private long getCellKey(int cellX, int cellZ) {
        // Pack cell coordinates into a single long for efficient hashing
        return (((long) cellX) << 32) | (cellZ & 0xFFFFFFFFL);
    }
    
    /**
     * Represents a player with their distance from a query point.
     */
    public static class PlayerDistance {
        private final PlayerData player;
        private final double distance;
        
        public PlayerDistance(PlayerData player, double distance) {
            this.player = player;
            this.distance = distance;
        }
        
        public PlayerData player() { return player; }
        public double distance() { return distance; }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            PlayerDistance that = (PlayerDistance) obj;
            return player.id() == that.player.id();
        }
        
        @Override
        public int hashCode() {
            return Long.hashCode(player.id());
        }
    }
    
    /**
     * Grid statistics for performance monitoring.
     */
    public static class GridStats {
        private final int totalCells;
        private final int totalPlayers;
        private final int maxPlayersPerCell;
        private final double avgPlayersPerCell;
        
        public GridStats(int totalCells, int totalPlayers, int maxPlayersPerCell, double avgPlayersPerCell) {
            this.totalCells = totalCells;
            this.totalPlayers = totalPlayers;
            this.maxPlayersPerCell = maxPlayersPerCell;
            this.avgPlayersPerCell = avgPlayersPerCell;
        }
        
        public int totalCells() { return totalCells; }
        public int totalPlayers() { return totalPlayers; }
        public int maxPlayersPerCell() { return maxPlayersPerCell; }
        public double avgPlayersPerCell() { return avgPlayersPerCell; }
        
        @Override
        public String toString() {
            return String.format("GridStats{cells=%d, players=%d, maxPerCell=%d, avgPerCell=%.2f}",
                totalCells, totalPlayers, maxPlayersPerCell, avgPlayersPerCell);
        }
    }
}