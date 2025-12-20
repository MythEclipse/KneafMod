package com.kneaf.core.pathfinding;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for pathfinding services.
 * Provides methods for both synchronous and asynchronous pathfinding.
 */
public interface PathfindingService {
    /**
     * Performs A* pathfinding on a 2D grid.
     *
     * @param grid   the grid as a boolean array (true for obstacles)
     * @param width  the width of the grid
     * @param height the height of the grid
     * @param startX the starting X coordinate
     * @param startY the starting Y coordinate
     * @param goalX  the goal X coordinate
     * @param goalY  the goal Y coordinate
     * @return the path as an array of coordinates [x1, y1, x2, y2, ...] or null if
     *         no path found
     */
    int[] findPath(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY);

    /**
     * Performs A* pathfinding asynchronously.
     *
     * @param grid   the grid as a boolean array (true for obstacles)
     * @param width  the width of the grid
     * @param height the height of the grid
     * @param startX the starting X coordinate
     * @param startY the starting Y coordinate
     * @param goalX  the goal X coordinate
     * @param goalY  the goal Y coordinate
     * @return a CompletableFuture that completes with the path
     */
    CompletableFuture<int[]> findPathAsync(boolean[] grid, int width, int height, int startX, int startY, int goalX,
            int goalY);
}
