/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RedstoneGraph - Topological sorting for redstone wire updates.
 * 
 * Algorithm: Kahn's Topological Sort + Lazy Evaluation
 * - Builds dependency graph for redstone components
 * - Processes updates in topological order (each wire exactly once)
 * - Caches power levels for unchanged wires
 * - Handles cycles gracefully
 * 
 * Performance: O(n + e) where n = wires, e = connections
 * Vanilla: O(n²) due to recursive updates
 * Expected: 5-10x faster for large contraptions
 */
public class RedstoneGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/RedstoneGraph");

    // Graph structure
    private final Map<BlockPos, Node> nodes = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> powerCache = new ConcurrentHashMap<>();

    // Statistics
    private long graphBuilds = 0;
    private long topologicalSorts = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;

    /**
     * Node in the redstone dependency graph.
     */
    private static class Node {
        final BlockPos pos;
        final Set<BlockPos> dependencies = new HashSet<>(); // Nodes this depends on
        final Set<BlockPos> dependents = new HashSet<>(); // Nodes that depend on this
        int inDegree = 0;
        int cachedPower = -1;
        boolean dirty = true;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    /**
     * Build or update the dependency graph for a redstone wire.
     */
    public void addWire(Level level, BlockPos pos) {
        Node node = nodes.computeIfAbsent(pos, Node::new);
        node.dirty = true;

        // Find all wires this one depends on (inputs)
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);

            if (neighborState.getBlock() instanceof RedStoneWireBlock) {
                Node neighborNode = nodes.computeIfAbsent(neighbor, Node::new);

                // Add dependency: this node depends on neighbor
                if (node.dependencies.add(neighbor)) {
                    neighborNode.dependents.add(pos);
                    node.inDegree++;
                }
            }
        }

        graphBuilds++;
    }

    /**
     * Remove a wire from the graph.
     */
    public void removeWire(BlockPos pos) {
        Node node = nodes.remove(pos);
        if (node != null) {
            // Remove from all dependents
            for (BlockPos dep : node.dependencies) {
                Node depNode = nodes.get(dep);
                if (depNode != null) {
                    depNode.dependents.remove(pos);
                }
            }

            // Remove from all dependencies
            for (BlockPos dependent : node.dependents) {
                Node dependentNode = nodes.get(dependent);
                if (dependentNode != null) {
                    dependentNode.dependencies.remove(pos);
                    dependentNode.inDegree--;
                }
            }
        }
        powerCache.remove(pos);
    }

    /**
     * Mark a wire as dirty (needs recalculation).
     */
    public void markDirty(BlockPos pos) {
        Node node = nodes.get(pos);
        if (node != null) {
            node.dirty = true;
            // Propagate dirty flag to dependents
            for (BlockPos dependent : node.dependents) {
                Node dependentNode = nodes.get(dependent);
                if (dependentNode != null) {
                    dependentNode.dirty = true;
                }
            }
        }
    }

    /**
     * Get cached power level for a wire.
     * Returns -1 if not cached or dirty.
     */
    public int getCachedPower(BlockPos pos) {
        Node node = nodes.get(pos);
        if (node != null && !node.dirty && node.cachedPower >= 0) {
            cacheHits++;
            return node.cachedPower;
        }
        cacheMisses++;
        return -1;
    }

    /**
     * Update cached power level for a wire.
     */
    public void updatePower(BlockPos pos, int power) {
        Node node = nodes.get(pos);
        if (node != null) {
            // Only mark dirty if power actually changed
            if (node.cachedPower != power) {
                node.cachedPower = power;
                node.dirty = false;
                powerCache.put(pos, power);

                // Mark dependents as dirty
                for (BlockPos dependent : node.dependents) {
                    markDirty(dependent);
                }
            }
        }
    }

    /**
     * Perform topological sort and return update order.
     * Uses Kahn's algorithm with cycle detection.
     * 
     * @return List of positions in topological order, or null if cycle detected
     */
    public List<BlockPos> getUpdateOrder(Set<BlockPos> affectedWires) {
        if (affectedWires.isEmpty()) {
            return Collections.emptyList();
        }

        topologicalSorts++;

        // Create working copy of in-degrees
        Map<BlockPos, Integer> inDegrees = new HashMap<>();
        for (BlockPos pos : affectedWires) {
            Node node = nodes.get(pos);
            if (node != null) {
                inDegrees.put(pos, node.inDegree);
            }
        }

        // Queue for nodes with no dependencies
        Queue<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos pos : affectedWires) {
            if (inDegrees.getOrDefault(pos, 0) == 0) {
                queue.offer(pos);
            }
        }

        List<BlockPos> result = new ArrayList<>();

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            result.add(current);

            Node node = nodes.get(current);
            if (node != null) {
                // Reduce in-degree for all dependents
                for (BlockPos dependent : node.dependents) {
                    if (affectedWires.contains(dependent)) {
                        int newDegree = inDegrees.get(dependent) - 1;
                        inDegrees.put(dependent, newDegree);

                        if (newDegree == 0) {
                            queue.offer(dependent);
                        }
                    }
                }
            }
        }

        // Check for cycles
        if (result.size() < affectedWires.size()) {
            LOGGER.debug("Cycle detected in redstone graph, falling back to vanilla order");
            return null; // Cycle detected, fall back to vanilla
        }

        return result;
    }

    /**
     * Clear all cached data (for testing or memory management).
     */
    public void clear() {
        nodes.clear();
        powerCache.clear();
    }

    /**
     * Get statistics for monitoring.
     */
    public String getStatistics() {
        long totalCacheAccess = cacheHits + cacheMisses;
        double hitRate = totalCacheAccess > 0 ? (cacheHits * 100.0 / totalCacheAccess) : 0.0;

        return String.format(
                "RedstoneGraph{nodes=%d, builds=%d, sorts=%d, cacheHits=%d, cacheMisses=%d, hitRate=%.1f%%}",
                nodes.size(), graphBuilds, topologicalSorts, cacheHits, cacheMisses, hitRate);
    }
}
