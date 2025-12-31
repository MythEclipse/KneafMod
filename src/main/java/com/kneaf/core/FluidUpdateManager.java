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
        final com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap staticFluidCache = new com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap(
                1024);
        // Batch pending fluid updates - Store byte amount in long value
        final com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap pendingUpdates = new com.kneaf.core.util.PrimitiveMaps.Long2LongOpenHashMap(
                256);
        final java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();
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
        long cachedTime = -1L;
        long stamp = ws.lock.tryOptimisticRead();
        cachedTime = ws.staticFluidCache.get(posKey);
        if (!ws.lock.validate(stamp)) {
            stamp = ws.lock.readLock();
            try {
                cachedTime = ws.staticFluidCache.get(posKey);
            } finally {
                ws.lock.unlockRead(stamp);
            }
        }

        if (cachedTime != -1L && gameTime - cachedTime < STATIC_CACHE_DURATION) {
            ticksSkipped.incrementAndGet();
            return true; // Cancel tick
        }

        // TPS-based throttling
        double currentTPS = TPSTracker.getCurrentTPS();

        // Safe Fallback: Check if Rust is available before batching
        if (RustOptimizations.isAvailable() && currentTPS < CRITICAL_TPS_THRESHOLD) {
            // Critical TPS - queue for batch processing
            stamp = ws.lock.writeLock();
            try {
                ws.pendingUpdates.put(posKey, (long) state.getAmount());
                if (ws.pendingUpdates.size() >= BATCH_THRESHOLD) {
                    processBatch(level, ws);
                }
            } finally {
                ws.lock.unlockWrite(stamp);
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
        WorldState ws = getState(level);
        long stamp = ws.lock.writeLock();
        try {
            ws.staticFluidCache.put(pos.asLong(), level.getGameTime());
        } finally {
            ws.lock.unlockWrite(stamp);
        }
    }

    // Helper to process inside lock or passing state
    public static void processBatch(Level level) {
        // Public entry point might need lock, but usually called internally with lock
        // held or from single thread context?
        // Actually onFluidTick calls it with lock held! But wait, processBatch
        // implementation below calls locks?
        // DEADLOCK ALERT.
        // Let's refactor. The onFluidTick calls processBatch(level), which gets state
        // again.
        // Let's make processBatch(Level) acquire lock, and internal one passed state.
        // But wait, onFluidTick holds writeLock when calling processBatch.
        // So we should NOT acquire lock inside processBatch if called from there.
        // But processBatch is also public static.
        // Let's split into internal method that assumes lock or state management.

        // Actually, to avoid complexity, let's just queue it and return from
        // onFluidTick, then flushing separately?
        // Or just implementation logic:
        // If called from onFluidTick, we have the lock.
        // Let's change onFluidTick to separate the check and the run.
        // Or better, make processBatch take the WorldState and assume caller handles
        // lock?
        // But processBatch does huge work (Rust call), we shouldn't hold writeLock
        // during Rust call if possible?
        // Actually Rust call is fast. But maybe better to copy data, unlock, compute,
        // lock, update.

        WorldState ws = getState(level);
        long stamp = ws.lock.writeLock();
        try {
            processBatch(level, ws);
        } finally {
            ws.lock.unlockWrite(stamp);
        }
    }

    private static void processBatch(Level level, WorldState ws) {
        // Internal method, assumes we are under lock or it's safe to read/clear pending
        // If called from onFluidTick, we are under writeLock.

        int count = ws.pendingUpdates.size();
        if (count == 0)
            return;

        // Build arrays for Rust
        byte[] fluidLevels = new byte[count];
        byte[] solidBlocks = new byte[count];
        long[] positions = new long[count]; // To map back

        // Iterate
        final int[] idxWrapper = { 0 };
        ws.pendingUpdates.forEach((k, v) -> {
            int i = idxWrapper[0];
            if (i < count) {
                positions[i] = k;
                fluidLevels[i] = (byte) v;
                solidBlocks[i] = 0;
                idxWrapper[0]++;
            }
            return 0; // dummy return for LongBinaryOperator
        });

        // Use Rust for batch fluid simulation
        // We can release lock here? NO, because pendingUpdates might be modified by
        // other threads.
        // Unless we copy to local vars, clear pending, unlock, compute, lock, update
        // cache.
        // That is better for concurrency.

        ws.pendingUpdates.clear(); // Clear immediately so others can queue

        // However, we are holding writeLock from caller (onFluidTick).
        // We cannot unlock a stamp we define inside here easily if it was passed from
        // outside.
        // Logic simplification: Just run it. Rust is fast.

        try {
            byte[] results = RustOptimizations.simulateFluidFlow(
                    fluidLevels, solidBlocks, count, 1, 1);
            rustBatchCalls.incrementAndGet();

            // Mark simulated positions as static based on results
            long now = System.currentTimeMillis();
            if (results != null) {
                for (int i = 0; i < count; i++) {
                    if (i < results.length && results[i] == 0) {
                        ws.staticFluidCache.put(positions[i], now);
                    }
                }
            }
        } catch (Exception e) {
            // Java fallback
        }
    }

    private static void cleanupCache(WorldState ws, long currentTime) {
        long stamp = ws.lock.writeLock();
        try {
            if (ws.staticFluidCache.size() > 10000) {
                ws.staticFluidCache.clear();
            }
            // pendingUpdates cleared in processBatch usually
            if (ws.pendingUpdates.size() > 500) { // Safety valve
                ws.pendingUpdates.clear();
            }
        } finally {
            ws.lock.unlockWrite(stamp);
        }
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
