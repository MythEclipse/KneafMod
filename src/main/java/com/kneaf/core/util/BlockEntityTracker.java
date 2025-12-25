/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized block entity activity tracking utility used by mixins.
 * This allows cross-mixin communication without public static methods in
 * mixins.
 */
public final class BlockEntityTracker {

    private BlockEntityTracker() {
        // Utility class
    }

    // Block entity idle tracking - key is block pos as long
    private static final ConcurrentHashMap<Long, Integer> idleTickCounts = new ConcurrentHashMap<>();

    // Configuration
    private static final int IDLE_THRESHOLD = 40; // 2 seconds without setChanged()
    private static final int SKIP_INTERVAL = 4; // Skip 3 out of 4 ticks when idle

    /**
     * Mark a block entity as active (called from BlockEntityMixin.setChanged).
     */
    public static void markBlockEntityActive(BlockPos pos) {
        if (pos != null) {
            idleTickCounts.put(pos.asLong(), 0);
        }
    }

    /**
     * Check if a block entity should be ticked based on idle state.
     * Returns true if the block entity should tick, false to skip.
     */
    public static boolean shouldTickBlockEntity(BlockPos pos) {
        if (pos == null) {
            return true;
        }

        long posKey = pos.asLong();
        int idleTicks = idleTickCounts.compute(posKey, (k, v) -> (v == null) ? 1 : v + 1);

        // If not idle, always tick
        if (idleTicks < IDLE_THRESHOLD) {
            return true;
        }

        // Idle - skip most ticks but still tick occasionally
        return idleTicks % SKIP_INTERVAL == 0;
    }

    /**
     * Clean up old entries from the idle tracking map.
     */
    public static void cleanup() {
        idleTickCounts.entrySet().removeIf(e -> e.getValue() > IDLE_THRESHOLD * 10);
    }

    /**
     * Get the number of tracked block entities.
     */
    public static int getTrackedCount() {
        return idleTickCounts.size();
    }

    /**
     * Clear all tracking data.
     */
    public static void clear() {
        idleTickCounts.clear();
    }
}
