/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.ParallelRustVectorProcessor;
import com.kneaf.core.util.CachedPath;
import com.kneaf.core.util.MixinHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PathFinderMixin - Aggressive mixin for PathFinder class.
 * 
 * Optimizations:
 * - Use Rust parallel A* for long-distance pathfinding
 * - Cache frequently-used paths
 * - Skip pathfinding for trivially short paths
 */
@Mixin(PathFinder.class)
public abstract class PathFinderMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/PathFinderMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static boolean kneaf$rustPathfindingFailed = false;

    // Configuration
    @Unique
    private static final int RUST_PATHFIND_THRESHOLD = 32; // Use Rust for paths > 32 blocks
    @Unique
    private static final int TRIVIAL_PATH_THRESHOLD = 4; // Skip optimization for < 4 blocks
    @Unique
    private static final int PATH_CACHE_SIZE = 1000;
    @Unique
    private static final long PATH_CACHE_TTL_MS = 5000; // Cache paths for 5 seconds

    // Metrics
    @Unique
    private static final AtomicLong kneaf$rustPathfinds = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$vanillaPathfinds = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);
    @Unique
    private static final AtomicLong kneaf$trivialSkips = new AtomicLong(0);

    // Path cache
    // Path cache with allocation-free keys
    @Unique
    private static final ConcurrentHashMap<PathCacheKey, CachedPath> kneaf$pathCache = new ConcurrentHashMap<>();

    // Record for zero-allocation cache keys
    @Unique
    private record PathCacheKey(long start, long target) {
    }

    /**
     * Inject at HEAD of findPath to use Rust for complex pathfinding.
     */
    @SuppressWarnings("null")
    @Inject(method = "findPath", at = @At("HEAD"), cancellable = true)
    private void kneaf$onFindPath(
            PathNavigationRegion region,
            Mob mob,
            Set<BlockPos> targetPositions,
            float maxRange,
            int accuracy,
            float searchDepthMultiplier,
            CallbackInfoReturnable<Path> cir) {

        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ PathFinderMixin applied successfully - Rust A* active!");
            kneaf$loggedFirstApply = true;
        }

        if (!MixinHelper.isAIOptimizationEnabled() || !MixinHelper.isNativeAvailable()) {
            kneaf$vanillaPathfinds.incrementAndGet();
            return;
        }

        if (targetPositions.isEmpty()) {
            return;
        }

        try {
            // Get start and target positions
            BlockPos startPos = mob.blockPosition();
            BlockPos targetPos = targetPositions.iterator().next();

            // Calculate distance
            double distanceSq = startPos.distSqr(targetPos);
            double distance = Math.sqrt(distanceSq);

            // Skip optimization for trivial paths
            if (distance < TRIVIAL_PATH_THRESHOLD) {
                kneaf$trivialSkips.incrementAndGet();
                return; // Let vanilla handle short paths
            }

            // Check cache
            PathCacheKey cacheKey = kneaf$createCacheKey(startPos, targetPos);
            CachedPath cached = kneaf$pathCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - cached.timestamp() < PATH_CACHE_TTL_MS) {
                kneaf$cacheHits.incrementAndGet();
                cir.setReturnValue(cached.path());
                return;
            }

            // Only use Rust for long paths
            if (distance < RUST_PATHFIND_THRESHOLD) {
                kneaf$vanillaPathfinds.incrementAndGet();
                return;
            }

            // Use Rust parallel A* for complex paths
            Path rustPath = kneaf$computeRustPath(region, startPos, targetPos, (int) maxRange);

            if (rustPath != null) {
                // Cache the result
                kneaf$cacheResult(cacheKey, rustPath);
                kneaf$rustPathfinds.incrementAndGet();
                cir.setReturnValue(rustPath);
            } else {
                kneaf$vanillaPathfinds.incrementAndGet();
            }

        } catch (Exception e) {
            kneaf$LOGGER.debug("Rust pathfinding failed, falling back to vanilla: {}", e.getMessage());
            kneaf$vanillaPathfinds.incrementAndGet();
        }
    }

    /**
     * Compute path using Rust parallel A*.
     */
    @Unique
    private Path kneaf$computeRustPath(PathNavigationRegion region, BlockPos start, BlockPos target, int maxRange) {
        if (kneaf$rustPathfindingFailed) {
            return null;
        }

        try {
            // Create grid for A* (simplified - in reality would need to query block
            // walkability)
            int width = maxRange * 2 + 1;
            int height = maxRange * 2 + 1;
            byte[] grid = new byte[width * height];

            BlockPos.MutableBlockPos mutPos = new BlockPos.MutableBlockPos();

            // Populate grid with collision data
            // We check the block at the entity's feet and head level
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int worldX = start.getX() + (x - maxRange);
                    int worldZ = start.getZ() + (z - maxRange);

                    // Check feet level
                    mutPos.set(worldX, start.getY(), worldZ);
                    boolean feetBlocked = !region.getBlockState(mutPos).getCollisionShape(region, mutPos).isEmpty();

                    // Check head level
                    mutPos.set(worldX, start.getY() + 1, worldZ);
                    boolean headBlocked = !region.getBlockState(mutPos).getCollisionShape(region, mutPos).isEmpty();

                    if (feetBlocked || headBlocked) {
                        grid[z * width + x] = 1; // Mark as obstacle
                    }
                }
            }

            int startX = maxRange;
            int startY = maxRange;
            int diffX = target.getX() - start.getX();
            int diffZ = target.getZ() - start.getZ();
            int goalX = maxRange + diffX;
            int goalY = maxRange + diffZ;

            // Strict bounds check - fallback to vanilla if goal is outside grid
            // Clamping produces invalid paths that stop at the edge
            if (goalX < 0 || goalX >= width || goalY < 0 || goalY >= height) {
                return null;
            }

            // Ensure start and goal are passable in grid (optional, but safe)
            // Call Rust A* - 3D signature: width, height, depth, startX, startY, startZ,
            // goalX, goalY, goalZ, threads
            int[] pathCoords = ParallelRustVectorProcessor.parallelAStarPathfind(
                    grid, width, height, 1, startX, startY, 0, goalX, goalY, 0, 4);

            if (pathCoords != null && pathCoords.length >= 4) {
                // Convert path coordinates to Minecraft Path
                List<Node> nodes = new ArrayList<>();
                for (int i = 0; i < pathCoords.length; i += 2) {
                    int gridX = pathCoords[i];
                    int gridZ = pathCoords[i + 1];
                    int worldX = start.getX() + (gridX - maxRange);
                    int worldZ = start.getZ() + (gridZ - maxRange);
                    nodes.add(new Node(worldX, start.getY(), worldZ));
                }

                // Create path with target as final goal
                return new Path(nodes, target, true);
            }

        } catch (Throwable e) {
            if (!kneaf$rustPathfindingFailed) {
                kneaf$LOGGER.error("Rust A* computation failed (disabling optimization): {}", e.toString());
                kneaf$rustPathfindingFailed = true;
            }
        }

        return null;
    }

    /**
     * Create cache key for path.
     */
    @Unique
    private static PathCacheKey kneaf$createCacheKey(BlockPos start, BlockPos target) {
        // Quantize coordinates for fuzzy matching (match original logic: / 4)
        long startKey = BlockPos.asLong(start.getX() / 4, start.getY() / 4, start.getZ() / 4);
        long targetKey = BlockPos.asLong(target.getX() / 4, target.getY() / 4, target.getZ() / 4);
        return new PathCacheKey(startKey, targetKey);
    }

    /**
     * Cache a computed path.
     */
    @Unique
    private static void kneaf$cacheResult(PathCacheKey key, Path path) {
        // Limit cache size
        if (kneaf$pathCache.size() >= PATH_CACHE_SIZE) {
            // Remove oldest entries (simple cleanup)
            long now = System.currentTimeMillis();
            kneaf$pathCache.entrySet().removeIf(e -> now - e.getValue().timestamp() > PATH_CACHE_TTL_MS);
        }

        kneaf$pathCache.put(key, new CachedPath(path, System.currentTimeMillis()));
    }

    /**
     * Get pathfinding statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        return String.format(
                "PathFindStats{rust=%d, vanilla=%d, cache=%d, trivial=%d}",
                kneaf$rustPathfinds.get(),
                kneaf$vanillaPathfinds.get(),
                kneaf$cacheHits.get(),
                kneaf$trivialSkips.get());
    }

    /**
     * Clear the path cache (for testing or memory management).
     */
    @Unique
    private static void kneaf$clearCache() {
        kneaf$pathCache.clear();
    }
}
