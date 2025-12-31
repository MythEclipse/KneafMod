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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

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
@SuppressWarnings("null")
public class RedstoneGraph {
    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/RedstoneGraph");

    // Graph structure
    private final PrimitiveMaps.Long2ObjectOpenHashMap<Node> nodes = new PrimitiveMaps.Long2ObjectOpenHashMap<>(1024);
    private final PrimitiveMaps.Long2IntOpenHashMap powerCache = new PrimitiveMaps.Long2IntOpenHashMap(1024);
    private final StampedLock lock = new StampedLock();

    // Statistics
    private final AtomicLong graphBuilds = new AtomicLong(0);
    private final AtomicLong topologicalSorts = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    /**
     * Node in the redstone dependency graph.
     */
    private static class Node {
        final PrimitiveMaps.LongOpenHashSet dependencies = new PrimitiveMaps.LongOpenHashSet(8); // Nodes this depends
                                                                                                 // on
        final PrimitiveMaps.LongOpenHashSet dependents = new PrimitiveMaps.LongOpenHashSet(8); // Nodes that depend on
                                                                                               // this
        int inDegree = 0;
        int cachedPower = -1;
        boolean dirty = true;

        Node() {
        }
    }

    /**
     * Check if a node exists in the graph.
     */
    public boolean hasNode(BlockPos pos) {
        long key = pos.asLong();
        long stamp = lock.tryOptimisticRead();
        boolean contains = nodes.containsKey(key);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                contains = nodes.containsKey(key);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return contains;
    }

    /**
     * Build or update the dependency graph for a redstone wire.
     */
    @SuppressWarnings("null")
    public void addWire(Level level, BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            if (nodes.containsKey(posKey))
                return;

            Node node = new Node();
            nodes.put(posKey, node);
            node.dirty = true;

            // Find all wires this one depends on (inputs)
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                BlockState neighborState = level.getBlockState(neighbor);

                if (neighborState.getBlock() instanceof RedStoneWireBlock) {
                    long neighborKey = neighbor.asLong();
                    Node neighborNode = nodes.get(neighborKey);
                    if (neighborNode == null) {
                        neighborNode = new Node();
                        nodes.put(neighborKey, neighborNode);
                    }

                    // Add dependency: this node depends on neighbor
                    if (node.dependencies.add(neighborKey)) {
                        neighborNode.dependents.add(posKey);
                        node.inDegree++;
                    }
                }
            }
            graphBuilds.incrementAndGet();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Remove a wire from the graph.
     */
    public void removeWire(BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            Node node = nodes.get(posKey);
            nodes.remove(posKey); // Long2ObjectMap remove(key)

            if (node != null) {
                // Remove from all dependents
                node.dependencies.forEach(depKey -> {
                    Node depNode = nodes.get(depKey);
                    if (depNode != null) {
                        depNode.dependents.remove(posKey); // LongOpenHashSet remove(key)
                    }
                });

                // Remove from all dependencies
                node.dependents.forEach(dependentKey -> {
                    Node dependentNode = nodes.get(dependentKey);
                    if (dependentNode != null) {
                        dependentNode.dependencies.remove(posKey);
                        dependentNode.inDegree--;
                    }
                });
            }
            powerCache.remove(posKey);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Mark a wire as dirty (needs recalculation).
     */
    public void markDirty(BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            Node node = nodes.get(posKey);
            if (node != null) {
                markDirtyRecursive(node);
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void markDirtyRecursive(Node node) {
        if (!node.dirty) {
            node.dirty = true;
            node.dependents.forEach(dependentKey -> {
                Node dependentNode = nodes.get(dependentKey);
                if (dependentNode != null) {
                    markDirtyRecursive(dependentNode);
                }
            });
        }
    }

    /**
     * Get cached power level for a wire.
     * Returns -1 if not cached or dirty.
     */
    public int getCachedPower(BlockPos pos) {
        long posKey = pos.asLong();
        long stamp = lock.tryOptimisticRead();
        Node node = nodes.get(posKey);
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                node = nodes.get(posKey);
            } finally {
                lock.unlockRead(stamp);
            }
        }

        if (node != null && !node.dirty && node.cachedPower >= 0) {
            cacheHits.incrementAndGet();
            return node.cachedPower;
        }
        cacheMisses.incrementAndGet();
        return -1;
    }

    /**
     * Update cached power level for a wire.
     */
    public void updatePower(BlockPos pos, int power) {
        long posKey = pos.asLong();
        long stamp = lock.writeLock();
        try {
            Node node = nodes.get(posKey);
            if (node != null) {
                // Only mark dirty if power actually changed
                if (node.cachedPower != power) {
                    node.cachedPower = power;
                    node.dirty = false;
                    powerCache.put(posKey, power);

                    // Mark dependents as dirty
                    node.dependents.forEach(dependentKey -> {
                        Node dependentNode = nodes.get(dependentKey);
                        if (dependentNode != null) {
                            markDirtyRecursive(dependentNode);
                        }
                    });
                }
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Perform topological sort and return update order.
     * Uses Kahn's algorithm with cycle detection.
     * 
     * @return List of positions in topological order, or null if cycle detected
     */
    public List<BlockPos> getUpdateOrder(PrimitiveMaps.LongOpenHashSet affectedWires) {
        if (affectedWires.size() == 0) {
            return Collections.emptyList();
        }

        topologicalSorts.incrementAndGet();
        long stamp = lock.readLock();
        try {
            // Step 1: Calc local In-Degrees
            PrimitiveMaps.Long2IntOpenHashMap localInDegrees = new PrimitiveMaps.Long2IntOpenHashMap(
                    affectedWires.size());
            // Using ArrayDeque for queue (autoboxing Long is acceptable here)
            Queue<Long> localQueue = new ArrayDeque<>(affectedWires.size());

            affectedWires.forEach(posKey -> {
                Node node = nodes.get(posKey);
                if (node != null) {
                    // Check dependencies
                    final int[] deg = { 0 };
                    node.dependencies.forEach(depKey -> {
                        if (affectedWires.contains(depKey)) {
                            deg[0]++;
                        }
                    });

                    localInDegrees.put(posKey, deg[0]);
                    if (deg[0] == 0) {
                        localQueue.offer(posKey);
                    }
                }
            });

            List<BlockPos> result = new ArrayList<>(affectedWires.size());

            while (!localQueue.isEmpty()) {
                long currentKey = localQueue.poll();
                result.add(BlockPos.of(currentKey));

                Node node = nodes.get(currentKey);
                if (node != null) {
                    node.dependents.forEach(depKey -> {
                        if (affectedWires.contains(depKey)) {
                            int currentDegree = localInDegrees.get(depKey);
                            if (currentDegree > 0) {
                                currentDegree--;
                                localInDegrees.put(depKey, currentDegree);
                                if (currentDegree == 0) {
                                    localQueue.offer(depKey);
                                }
                            }
                        }
                    });
                }
            }

            // Check for cycles
            if (result.size() < affectedWires.size()) {
                LOGGER.debug("Cycle detected in redstone graph, falling back to vanilla order");
                return null;
            }

            return result;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Clear all cached data (for testing or memory management).
     */
    public void clear() {
        long stamp = lock.writeLock();
        try {
            nodes.clear();
            powerCache.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Get statistics for monitoring.
     */
    public String getStatistics() {
        long hits = cacheHits.get();
        long totalCacheAccess = hits + cacheMisses.get();
        double hitRate = totalCacheAccess > 0 ? (hits * 100.0 / totalCacheAccess) : 0.0;

        long stamp = lock.tryOptimisticRead();
        int size = nodes.size();
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                size = nodes.size();
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return String.format(
                "RedstoneGraph{nodes=%d, builds=%d, sorts=%d, cacheHits=%d, cacheMisses=%d, hitRate=%.1f%%}",
                size, graphBuilds.get(), topologicalSorts.get(), hits, cacheMisses.get(), hitRate);
    }
}
