/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.spatial;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * EntityOctree - Dynamic octree for fast entity collision detection.
 * 
 * Algorithm: Recursive Octree Spatial Partitioning
 * - Partitions 3D space recursively into 8 octants
 * - Stores entities in leaf nodes
 * - Queries only relevant octants for collision checks
 * - Rebuilds tree when entities move significantly
 * 
 * Performance: O(n log n) for collision detection
 * Vanilla: O(n²) for n entities
 * Expected: 10-100x faster with 100+ entities per chunk
 */
public class EntityOctree {
    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/EntityOctree");

    // Configuration
    private static final int MAX_ENTITIES_PER_NODE = 8;
    private static final int MAX_DEPTH = 6;
    private static final double MIN_NODE_SIZE = 4.0; // Minimum 4x4x4 blocks

    // Root node
    private OctreeNode root;

    // Statistics
    private long queryCount = 0;
    private long entitiesChecked = 0;
    private long rebuilds = 0;

    /**
     * Octree node representing a cubic region of space.
     */
    private static class OctreeNode {
        final AABB bounds;
        final int depth;

        // Leaf node data
        List<Entity> entities;

        // Internal node data
        OctreeNode[] children; // 8 octants

        OctreeNode(AABB bounds, int depth) {
            this.bounds = bounds;
            this.depth = depth;
            this.entities = new ArrayList<>();
        }

        boolean isLeaf() {
            return children == null;
        }

        /**
         * Split this node into 8 octants.
         */
        void split() {
            if (!isLeaf())
                return;

            Vec3 center = bounds.getCenter();
            double halfX = (bounds.maxX - bounds.minX) / 2.0;
            double halfY = (bounds.maxY - bounds.minY) / 2.0;
            double halfZ = (bounds.maxZ - bounds.minZ) / 2.0;

            children = new OctreeNode[8];

            // Create 8 octants
            for (int i = 0; i < 8; i++) {
                double minX = (i & 1) == 0 ? bounds.minX : center.x;
                double maxX = (i & 1) == 0 ? center.x : bounds.maxX;
                double minY = (i & 2) == 0 ? bounds.minY : center.y;
                double maxY = (i & 2) == 0 ? center.y : bounds.maxY;
                double minZ = (i & 4) == 0 ? bounds.minZ : center.z;
                double maxZ = (i & 4) == 0 ? center.z : bounds.maxZ;

                children[i] = new OctreeNode(new AABB(minX, minY, minZ, maxX, maxY, maxZ), depth + 1);
            }

            // Redistribute entities to children
            for (Entity entity : entities) {
                AABB entityBounds = entity.getBoundingBox();
                for (OctreeNode child : children) {
                    if (child.bounds.intersects(entityBounds)) {
                        child.entities.add(entity);
                    }
                }
            }

            entities = null; // Clear parent's entity list
        }
    }

    /**
     * Create octree for a region.
     */
    public EntityOctree(AABB bounds) {
        this.root = new OctreeNode(bounds, 0);
    }

    /**
     * Insert an entity into the octree.
     */
    public void insert(Entity entity) {
        if (entity == null || entity.isRemoved())
            return;

        AABB entityBounds = entity.getBoundingBox();
        if (!root.bounds.intersects(entityBounds)) {
            // Entity outside octree bounds
            return;
        }

        insertRecursive(root, entity, entityBounds);
    }

    private void insertRecursive(OctreeNode node, Entity entity, AABB entityBounds) {
        if (!node.bounds.intersects(entityBounds)) {
            return;
        }

        if (node.isLeaf()) {
            node.entities.add(entity);

            // Split if too many entities and not at max depth
            if (node.entities.size() > MAX_ENTITIES_PER_NODE &&
                    node.depth < MAX_DEPTH &&
                    (node.bounds.maxX - node.bounds.minX) > MIN_NODE_SIZE) {
                node.split();
            }
        } else {
            // Insert into children
            for (OctreeNode child : node.children) {
                insertRecursive(child, entity, entityBounds);
            }
        }
    }

    /**
     * Query entities within an AABB.
     * This is the main optimization - only checks relevant octants.
     */
    public List<Entity> query(AABB queryBounds) {
        queryCount++;
        List<Entity> result = new ArrayList<>();
        Set<Entity> seen = new HashSet<>(); // Avoid duplicates

        queryRecursive(root, queryBounds, result, seen);

        entitiesChecked += seen.size();
        return result;
    }

    private void queryRecursive(OctreeNode node, AABB queryBounds, List<Entity> result, Set<Entity> seen) {
        if (!node.bounds.intersects(queryBounds)) {
            return; // Early exit - this octant doesn't intersect query
        }

        if (node.isLeaf()) {
            for (Entity entity : node.entities) {
                if (!entity.isRemoved() && seen.add(entity)) {
                    AABB entityBounds = entity.getBoundingBox();
                    if (entityBounds.intersects(queryBounds)) {
                        result.add(entity);
                    }
                }
            }
        } else {
            // Recursively query children
            for (OctreeNode child : node.children) {
                queryRecursive(child, queryBounds, result, seen);
            }
        }
    }

    /**
     * Query entities near a point within radius.
     */
    public List<Entity> queryNearPoint(Vec3 point, double radius) {
        AABB queryBounds = new AABB(
                point.x - radius, point.y - radius, point.z - radius,
                point.x + radius, point.y + radius, point.z + radius);
        return query(queryBounds);
    }

    /**
     * Clear all entities from the octree.
     */
    public void clear() {
        root = new OctreeNode(root.bounds, 0);
        rebuilds++;
    }

    /**
     * Rebuild the octree with current entities.
     * Call this periodically when entities have moved significantly.
     */
    public void rebuild(List<Entity> entities) {
        clear();
        for (Entity entity : entities) {
            insert(entity);
        }
    }

    /**
     * Get statistics for monitoring.
     */
    public String getStatistics() {
        double avgChecked = queryCount > 0 ? (double) entitiesChecked / queryCount : 0.0;
        return String.format(
                "EntityOctree{queries=%d, avgChecked=%.1f, rebuilds=%d}",
                queryCount, avgChecked, rebuilds);
    }

    /**
     * Count total entities in the octree (for debugging).
     */
    public int countEntities() {
        return countEntitiesRecursive(root, new HashSet<>());
    }

    private int countEntitiesRecursive(OctreeNode node, Set<Entity> seen) {
        if (node.isLeaf()) {
            for (Entity entity : node.entities) {
                seen.add(entity);
            }
        } else {
            for (OctreeNode child : node.children) {
                countEntitiesRecursive(child, seen);
            }
        }
        return seen.size();
    }
}
