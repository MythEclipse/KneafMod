package com.kneaf.core;

import com.kneaf.core.util.TPSTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.resources.ResourceKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages fluid updates and optimization state per dimension.
 * Replaces static logic in FluidTickMixin to avoid crashes and dimension
 * collisions.
 */
public class FluidUpdateManager {

    // Per-world state storage
    private static class WorldState {
        // Cache for static fluid positions
        final Map<Long, Long> staticFluidCache = new ConcurrentHashMap<>(1024);
        // Batch pending fluid updates
        final Map<Long, Byte> pendingUpdates = new ConcurrentHashMap<>(256);
        long lastCacheCleanup = 0;
    }

    private static final Map<ResourceKey<Level>, WorldState> worldStates = new ConcurrentHashMap<>();

    // Statistics (Global)
    private static final AtomicLong ticksSkipped = new AtomicLong(0);
    private static final AtomicLong ticksProcessed = new AtomicLong(0);
    private static final AtomicLong rustBatchCalls = new AtomicLong(0);
    private static long lastLogTime = 0;

    // Configuration
    private static final double LOW_TPS_THRESHOLD = 15.0;
    private static final double CRITICAL_TPS_THRESHOLD = 10.0;
    private static final long STATIC_CACHE_DURATION = 100;
    private static final int BATCH_THRESHOLD = 64;

    private static WorldState getState(Level level) {
        return worldStates.computeIfAbsent(level.dimension(), k -> new WorldState());
    }

    public static boolean onFluidTick(Level level, BlockPos pos, FluidState state) {
        long gameTime = level.getGameTime();
        long posKey = pos.asLong();
        WorldState ws = getState(level);

        // Check static fluid cache
        Long lastStaticTime = ws.staticFluidCache.get(posKey);
        if (lastStaticTime != null && gameTime - lastStaticTime < STATIC_CACHE_DURATION) {
            ticksSkipped.incrementAndGet();
            return true; // Cancel tick
        }

        // TPS-based throttling
        double currentTPS = TPSTracker.getCurrentTPS();

        // Safe Fallback: Check if Rust is available before batching
        if (RustOptimizations.isAvailable() && currentTPS < CRITICAL_TPS_THRESHOLD) {
            // Critical TPS - queue for batch processing
            ws.pendingUpdates.put(posKey, (byte) state.getAmount());

            if (ws.pendingUpdates.size() >= BATCH_THRESHOLD) {
                processBatch(level);
            }

            ticksSkipped.incrementAndGet();
            return true; // Cancel tick (handled by batch/Rust)
        } else if (currentTPS < LOW_TPS_THRESHOLD) {
            if (Math.random() > 0.5) {
                ticksSkipped.incrementAndGet();
                return true; // Cancel tick (random skip)
            }
        }

        ticksProcessed.incrementAndGet();

        // Periodic cleanup
        if (gameTime - ws.lastCacheCleanup > 1200) {
            cleanupCache(ws, gameTime);
            ws.lastCacheCleanup = gameTime;
        }

        logStats();
        return false; // Allow tick
    }

    public static void markStatic(Level level, BlockPos pos) {
        getState(level).staticFluidCache.put(pos.asLong(), level.getGameTime());
    }

    public static void processBatch(Level level) {
        WorldState ws = getState(level);
        int count = ws.pendingUpdates.size();
        if (count == 0)
            return;

        // Build arrays for Rust
        byte[] fluidLevels = new byte[count];
        byte[] solidBlocks = new byte[count]; // Assume all passable for now

        int idx = 0;
        for (var entry : ws.pendingUpdates.entrySet()) {
            fluidLevels[idx] = entry.getValue();
            solidBlocks[idx] = 0; // Not solid
            idx++;
        }

        // Use Rust for batch fluid simulation
        try {
            byte[] results = RustOptimizations.simulateFluidFlow(
                    fluidLevels, solidBlocks, count, 1, 1);
            rustBatchCalls.incrementAndGet();

            // Mark simulated positions as static based on results
            long now = System.currentTimeMillis();
            if (results != null) {
                idx = 0;
                for (var entry : ws.pendingUpdates.entrySet()) {
                    // Use result to check if fluid is now static
                    if (idx < results.length && results[idx] == 0) {
                        ws.staticFluidCache.put(entry.getKey(), now);
                    }
                    idx++;
                }
            }
        } catch (Exception e) {
            // Java fallback - just clear pending
        }

        ws.pendingUpdates.clear();
    }

    private static void cleanupCache(WorldState ws, long currentTime) {
        if (ws.staticFluidCache.size() > 10000) {
            ws.staticFluidCache.clear();
        }
        ws.pendingUpdates.clear();
    }

    private static void logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - lastLogTime > 1000) {
            long skipped = ticksSkipped.get();
            long processed = ticksProcessed.get();
            long rust = rustBatchCalls.get();
            long total = skipped + processed;
            double timeDiff = (now - lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                PerformanceStats.fluidTicks = total / timeDiff;
                double skipRate = skipped * 100.0 / total;
                PerformanceStats.fluidSkippedPercent = skipRate;
                PerformanceStats.fluidRustBatches = (int) (rust / timeDiff);

                ticksSkipped.set(0);
                ticksProcessed.set(0);
                rustBatchCalls.set(0);
            } else {
                PerformanceStats.fluidTicks = 0;
            }
            lastLogTime = now;
        }
    }
}
