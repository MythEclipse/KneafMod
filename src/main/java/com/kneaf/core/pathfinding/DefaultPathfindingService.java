package com.kneaf.core.pathfinding;

import com.kneaf.core.RustNativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Default implementation of PathfindingService.
 * Uses Rust native pathfinding if available, otherwise falls back to Java
 * implementation.
 */
public class DefaultPathfindingService implements PathfindingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPathfindingService.class);
    private static final DefaultPathfindingService INSTANCE = new DefaultPathfindingService();
    // Use centralized WorkerThreadPool instead of dedicated pool

    private DefaultPathfindingService() {
    }

    public static DefaultPathfindingService getInstance() {
        return INSTANCE;
    }

    @Override
    public int[] findPath(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY) {
        // Try Rust implementation first
        if (RustNativeLoader.isLibraryLoaded()) {
            try {
                return RustNativeLoader.rustperf_astar_pathfind(grid, width, height, startX, startY, goalX, goalY);
            } catch (Exception e) {
                LOGGER.warn("Rust pathfinding failed, falling back to Java implementation", e);
            }
        }

        // Fallback to Java implementation
        return findPathJava(grid, width, height, startX, startY, goalX, goalY);
    }

    @Override
    public CompletableFuture<int[]> findPathAsync(boolean[] grid, int width, int height, int startX, int startY,
            int goalX, int goalY) {
        return CompletableFuture.supplyAsync(() -> findPath(grid, width, height, startX, startY, goalX, goalY),
                com.kneaf.core.WorkerThreadPool.getComputePool());
    }

    private int[] findPathJava(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY) {
        if (grid == null)
            throw new IllegalArgumentException("Grid cannot be null");
        if (grid.length != width * height)
            throw new IllegalArgumentException("Grid size does not match width*height");
        if (startX < 0 || startY < 0 || goalX < 0 || goalY < 0 || startX >= width || goalX >= width || startY >= height
                || goalY >= height)
            throw new IllegalArgumentException("Start/goal outside grid");

        // A* implementation using simple arrays. 4-connected grid.
        final int W = width, H = height;
        final int N = W * H;
        int start = startY * W + startX;
        int goal = goalY * W + goalX;
        boolean[] closed = new boolean[N];
        int[] gScore = new int[N];
        int[] fScore = new int[N];
        int[] cameFrom = new int[N];
        java.util.Arrays.fill(gScore, Integer.MAX_VALUE / 2);
        java.util.Arrays.fill(fScore, Integer.MAX_VALUE / 2);
        java.util.Arrays.fill(cameFrom, -1);

        java.util.PriorityQueue<Integer> open = new java.util.PriorityQueue<>(11,
                (i, j) -> Integer.compare(fScore[i], fScore[j]));

        gScore[start] = 0;
        fScore[start] = heuristic(startX, startY, goalX, goalY);
        open.add(start);

        while (!open.isEmpty()) {
            int current = open.poll();
            if (current == goal) {
                // reconstruct path
                java.util.ArrayList<Integer> path = new java.util.ArrayList<>();
                int cur = current;
                while (cur != -1) {
                    path.add(cur);
                    cur = cameFrom[cur];
                }
                // reverse and convert to [x1,y1,x2,y2,...]
                int len = path.size();
                int[] coords = new int[len * 2];
                for (int i = 0; i < len; i++) {
                    int idx = path.get(len - 1 - i);
                    coords[i * 2] = idx % W;
                    coords[i * 2 + 1] = idx / W;
                }
                return coords;
            }

            closed[current] = true;
            int cx = current % W;
            int cy = current / W;

            int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H)
                    continue;
                int neighbor = ny * W + nx;
                if (closed[neighbor])
                    continue;
                if (grid[neighbor])
                    continue; // obstacle

                int tentativeG = gScore[current] + 1;
                if (tentativeG < gScore[neighbor]) {
                    cameFrom[neighbor] = current;
                    gScore[neighbor] = tentativeG;
                    fScore[neighbor] = tentativeG + heuristic(nx, ny, goalX, goalY);
                    if (!open.contains(neighbor))
                        open.add(neighbor);
                }
            }
        }

        return null; // no path
    }

    private int heuristic(int x, int y, int gx, int gy) {
        // Manhattan distance
        return Math.abs(x - gx) + Math.abs(y - gy);
    }
}
