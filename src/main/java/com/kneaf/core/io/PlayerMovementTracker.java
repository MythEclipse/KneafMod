/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.io;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts player movement and determines which chunks to prefetch.
 * 
 * Uses velocity-based prediction with cone-shaped prefetch area.
 */
public final class PlayerMovementTracker {

    // Configuration (TPS-adaptive)
    private static int prefetchDistance = 8; // chunks ahead
    private static int prefetchConeAngle = 60; // degrees
    private static double minVelocityThreshold = 0.1; // blocks/tick

    private PlayerMovementTracker() {
        // Utility class
    }

    /**
     * Predict which chunks player will visit next.
     * 
     * @param player The player to predict for
     * @return List of chunk positions, ordered by priority (closest first)
     */
    @SuppressWarnings("null")
    public static List<ChunkPos> predict(Player player) {
        List<ChunkPos> predicted = new ArrayList<>();

        Vec3 pos = player.position();
        if (pos == null)
            return new ArrayList<>();
        Vec3 velocity = player.getDeltaMovement();
        if (velocity == null)
            velocity = Vec3.ZERO;

        // If player not moving, only prefetch view distance
        double speed = velocity.length();
        if (speed < minVelocityThreshold) {
            return predictStationary(player);
        }

        // Calculate future position based on velocity
        // Predict 2-5 seconds ahead depending on speed
        double predictionTime = Math.min(100, 40 + speed * 20); // ticks
        Vec3 futurePos = pos.add(velocity.scale(predictionTime));
        if (futurePos == null)
            futurePos = pos;

        // Calculate cone of chunks in direction of movement
        predicted.addAll(calculateConeChunks(pos, futurePos, prefetchDistance, prefetchConeAngle));

        // Sort by distance (closest first = highest priority)
        ChunkPos currentChunk = new ChunkPos(BlockPos.containing(pos));
        predicted.sort((a, b) -> {
            double distA = distanceSq(a, currentChunk);
            double distB = distanceSq(b, currentChunk);
            return Double.compare(distA, distB);
        });

        return predicted;
    }

    /**
     * Predict for stationary player (just view distance).
     */
    @SuppressWarnings("null")
    private static List<ChunkPos> predictStationary(Player player) {
        List<ChunkPos> chunks = new ArrayList<>();
        Vec3 playerPos = player.position();
        if (playerPos == null)
            playerPos = Vec3.ZERO;
        ChunkPos center = new ChunkPos(BlockPos.containing(playerPos));

        // Small radius for stationary players (use fixed value)
        int radius = 4; // Fixed radius since clientViewDistance() doesn't exist
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    chunks.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }
        }

        return chunks;
    }

    /**
     * Calculate chunks in cone from current position towards future position.
     */
    @SuppressWarnings("null")
    private static List<ChunkPos> calculateConeChunks(Vec3 currentPos, Vec3 futurePos,
            int maxDistance, int coneAngle) {
        List<ChunkPos> chunks = new ArrayList<>();

        ChunkPos current = new ChunkPos(BlockPos.containing(currentPos));
        @SuppressWarnings("unused")
        ChunkPos future = new ChunkPos(BlockPos.containing(futurePos));

        Vec3 direction = futurePos.subtract(currentPos);
        if (direction.lengthSqr() > 0) {
            direction = direction.normalize();
        } else {
            direction = new Vec3(0, 0, 1); // Default direction
        }

        // Half-angle in radians
        double halfAngleRad = Math.toRadians(coneAngle / 2.0);
        double cosHalfAngle = Math.cos(halfAngleRad);

        // Check chunks in square around future position
        for (int dx = -maxDistance; dx <= maxDistance; dx++) {
            for (int dz = -maxDistance; dz <= maxDistance; dz++) {
                ChunkPos candidate = new ChunkPos(current.x + dx, current.z + dz);

                // Skip current chunk
                if (candidate.equals(current)) {
                    continue;
                }

                // Check if chunk is within cone
                if (isInCone(currentPos, direction, candidate, cosHalfAngle, maxDistance)) {
                    chunks.add(candidate);
                }
            }
        }

        return chunks;
    }

    /**
     * Check if chunk is within the prefetch cone.
     */
    @SuppressWarnings("null")
    private static boolean isInCone(Vec3 origin, Vec3 direction, ChunkPos chunk,
            double cosHalfAngle, int maxDistance) {
        // Chunk center position
        Vec3 chunkCenter = new Vec3(
                chunk.x * 16 + 8,
                origin.y,
                chunk.z * 16 + 8);

        Vec3 toChunk = chunkCenter.subtract(origin);
        if (toChunk == null)
            return false;
        double distance = toChunk.length();

        // Check distance
        if (distance > maxDistance * 16) {
            return false;
        }

        // Check angle
        Vec3 toChunkNorm = toChunk.normalize();
        double dotProduct = direction.dot(toChunkNorm);

        return dotProduct >= cosHalfAngle;
    }

    /**
     * Calculate squared distance between two chunk positions.
     */
    private static double distanceSq(ChunkPos a, ChunkPos b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /**
     * Adjust prefetch parameters based on TPS.
     */
    public static void updateConfiguration(double currentTPS) {
        if (currentTPS > 19.0) {
            // High TPS - aggressive prefetch
            prefetchDistance = 8;
            prefetchConeAngle = 60;
        } else if (currentTPS > 15.0) {
            // Medium TPS - normal prefetch
            prefetchDistance = 6;
            prefetchConeAngle = 45;
        } else {
            // Low TPS - conservative prefetch
            prefetchDistance = 4;
            prefetchConeAngle = 30;
        }
    }

    /**
     * Calculate priority for chunk (higher = more important).
     * Closer chunks have higher priority.
     */
    public static int calculatePriority(ChunkPos target, ChunkPos current) {
        double distSq = distanceSq(target, current);
        // Priority inversely proportional to distance
        // Max priority: 1000 (adjacent chunk), Min: 1 (far chunk)
        return (int) Math.max(1, 1000 - distSq * 10);
    }

    public static int getPrefetchDistance() {
        return prefetchDistance;
    }

    public static int getPrefetchConeAngle() {
        return prefetchConeAngle;
    }
}
