/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.spatial;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerLevelOctreeManager - Manages per-chunk octrees for entity collision.
 * 
 * Features:
 * - Per-chunk octree management
 * - Automatic entity tracking
 * - Periodic tree rebalancing
 * - Thread-safe operations
 */
public class ServerLevelOctreeManager {

    // Per-chunk octrees
    private final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<EntityOctree> chunkOctrees = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            512);
    private final java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();

    // Rebuild tracking
    private long lastRebuildTime = 0;
    private static final long REBUILD_INTERVAL = 100; // Rebuild every 5 seconds

    // Statistics
    private long totalQueries = 0;
    private long octreeHits = 0;

    /**
     * Get chunk key from position.
     */
    private long getChunkKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    /**
     * Get or create octree for a chunk.
     */
    public EntityOctree getOrCreateOctree(int chunkX, int chunkZ) {
        long key = getChunkKey(chunkX, chunkZ);

        long stamp = lock.writeLock();
        try {
            EntityOctree octree = chunkOctrees.get(key);
            if (octree == null) {
                // Create octree for 16x256x16 chunk bounds
                double minX = chunkX * 16.0;
                double minZ = chunkZ * 16.0;
                AABB bounds = new AABB(minX, -64, minZ, minX + 16, 320, minZ + 16);
                octree = new EntityOctree(bounds);
                chunkOctrees.put(key, octree);
            }
            return octree;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Add entity to appropriate octree(s).
     */
    public void addEntity(Entity entity) {
        if (entity == null || entity.isRemoved())
            return;

        AABB bounds = entity.getBoundingBox();
        int minChunkX = (int) Math.floor(bounds.minX / 16.0);
        int maxChunkX = (int) Math.floor(bounds.maxX / 16.0);
        int minChunkZ = (int) Math.floor(bounds.minZ / 16.0);
        int maxChunkZ = (int) Math.floor(bounds.maxZ / 16.0);

        // Add to all chunks the entity overlaps
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                EntityOctree octree = getOrCreateOctree(cx, cz);
                octree.insert(entity);
            }
        }
    }

    /**
     * Query entities within AABB across multiple chunks.
     */
    public List<Entity> queryEntities(AABB queryBounds) {
        totalQueries++;

        int minChunkX = (int) Math.floor(queryBounds.minX / 16.0);
        int maxChunkX = (int) Math.floor(queryBounds.maxX / 16.0);
        int minChunkZ = (int) Math.floor(queryBounds.minZ / 16.0);
        int maxChunkZ = (int) Math.floor(queryBounds.maxZ / 16.0);

        Set<Entity> result = new HashSet<>();

        long stamp = lock.readLock();
        try {
            // Query all relevant chunks
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    EntityOctree octree = chunkOctrees.get(getChunkKey(cx, cz));
                    if (octree != null) {
                        result.addAll(octree.query(queryBounds));
                        octreeHits++;
                    }
                }
            }
        } finally {
            lock.unlockRead(stamp);
        }

        return new ArrayList<>(result);
    }

    /**
     * Query entities near a point.
     */
    public List<Entity> queryNearPoint(Vec3 point, double radius) {
        AABB queryBounds = new AABB(
                point.x - radius, point.y - radius, point.z - radius,
                point.x + radius, point.y + radius, point.z + radius);
        return queryEntities(queryBounds);
    }

    /**
     * Rebuild octrees for a chunk with current entities.
     */
    public void rebuildChunk(int chunkX, int chunkZ, List<Entity> entities) {
        EntityOctree octree = getOrCreateOctree(chunkX, chunkZ);
        octree.rebuild(entities);
    }

    /**
     * Remove octree for unloaded chunk.
     */
    public void removeChunk(int chunkX, int chunkZ) {
        long stamp = lock.writeLock();
        try {
            chunkOctrees.remove(getChunkKey(chunkX, chunkZ));
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Periodic maintenance - rebuild octrees that need it.
     */
    public void tick(ServerLevel level, long gameTime) {
        if (gameTime - lastRebuildTime < REBUILD_INTERVAL) {
            return;
        }
        lastRebuildTime = gameTime;

        long stamp = lock.readLock();
        try {
            // Rebuild octrees for loaded chunks
            // Use forEach with bi-consumer
            chunkOctrees.forEach((key, octree) -> {
                int chunkX = ChunkPos.getX(key);
                int chunkZ = ChunkPos.getZ(key);

                // Get entities in this chunk
                AABB chunkBounds = new AABB(
                        chunkX * 16.0, -64, chunkZ * 16.0,
                        chunkX * 16.0 + 16, 320, chunkZ * 16.0 + 16);

                List<Entity> chunkEntities = level.getEntities((Entity) null, chunkBounds);
                octree.rebuild(chunkEntities);
            });
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Clear all octrees.
     */
    public void clear() {
        long stamp = lock.writeLock();
        try {
            chunkOctrees.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Get statistics for monitoring.
     */
    public String getStatistics() {
        double hitRate = totalQueries > 0 ? (octreeHits * 100.0 / totalQueries) : 0.0;
        return String.format(
                "OctreeManager{chunks=%d, queries=%d, hitRate=%.1f%%}",
                chunkOctrees.size(), totalQueries, hitRate);
    }
}
