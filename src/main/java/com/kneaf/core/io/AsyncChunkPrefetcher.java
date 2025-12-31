/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.io;

import com.kneaf.core.WorkerThreadPool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous chunk prefetcher with priority queue and rate limiting.
 * 
 * Features:
 * - Priority-based prefetching (closer chunks first)
 * - Rate limiting (max concurrent I/O operations)
 * - Deduplication (don't prefetch same chunk twice)
 * - Cancellation support
 * - Graceful error handling
 */
public final class AsyncChunkPrefetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncChunkPrefetcher.class);

    // Configuration
    private static int maxConcurrentIO = 16;
    private static final int QUEUE_CAPACITY = 256;

    // Prefetch queue (priority-based)
    private static final PriorityBlockingQueue<PrefetchTask> prefetchQueue = new PriorityBlockingQueue<>(
            QUEUE_CAPACITY);

    // Track in-flight prefetch operations
    private static final com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<CompletableFuture<CompoundTag>> inFlight = new com.kneaf.core.util.PrimitiveMaps.Long2ObjectOpenHashMap<>(
            256);
    private static final java.util.concurrent.locks.StampedLock lock = new java.util.concurrent.locks.StampedLock();

    // Rate limiter
    private static final Semaphore rateLimiter = new Semaphore(maxConcurrentIO);

    // Statistics
    private static final AtomicLong tasksScheduled = new AtomicLong(0);
    private static final AtomicLong tasksCompleted = new AtomicLong(0);
    private static final AtomicLong tasksCancelled = new AtomicLong(0);
    private static final AtomicLong tasksFailed = new AtomicLong(0);
    private static final AtomicInteger activeOperations = new AtomicInteger(0);
    private static final AtomicLong totalPrefetchTimeNs = new AtomicLong(0);

    // Background worker
    private static volatile boolean running = false;
    private static Thread workerThread;

    // Reference to RegionFileStorage (set by mixin)
    private static volatile RegionFileStorage regionFileStorage;

    private AsyncChunkPrefetcher() {
        // Utility class
    }

    /**
     * Initialize the prefetcher. Must be called on server start.
     */
    public static synchronized void initialize() {
        if (running) {
            return;
        }

        running = true;
        workerThread = new Thread(AsyncChunkPrefetcher::workerLoop, "Kneaf-ChunkPrefetch-Worker");
        workerThread.setDaemon(true);
        workerThread.start();

        LOGGER.info("AsyncChunkPrefetcher initialized (maxConcurrentIO={})", maxConcurrentIO);
    }

    /**
     * Shutdown the prefetcher. Must be called on server stop.
     */
    public static synchronized void shutdown() {
        if (!running) {
            return;
        }

        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }

        // Cancel all in-flight operations
        long stamp = lock.writeLock();
        try {
            inFlight.forEach((k, future) -> future.cancel(true));
            inFlight.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
        prefetchQueue.clear();

        LOGGER.info("AsyncChunkPrefetcher shutdown. Stats: {}", getStatistics());
    }

    /**
     * Schedule chunks for prefetching.
     * 
     * @param chunks List of chunks to prefetch, ordered by priority
     */
    public static void schedulePrefetch(List<ChunkPos> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        int scheduled = 0;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkPos pos = chunks.get(i);
            long posKey = pos.toLong();

            // Skip if already cached or in-flight
            boolean alreadyInFlight;
            long stamp = lock.tryOptimisticRead();
            alreadyInFlight = inFlight.containsKey(posKey);
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    alreadyInFlight = inFlight.containsKey(posKey);
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            if (PrefetchedChunkCache.contains(pos) || alreadyInFlight) {
                continue;
            }

            // Priority: first chunks in list have highest priority
            int priority = 1000 - i * 10; // Max 1000, decreases by 10 per position

            PrefetchTask task = new PrefetchTask(pos, priority);
            if (prefetchQueue.offer(task)) {
                tasksScheduled.incrementAndGet();
                scheduled++;
            }
        }

        if (scheduled > 0) {
            LOGGER.debug("Scheduled {} chunks for prefetch (queue size: {})",
                    scheduled, prefetchQueue.size());
        }
    }

    /**
     * Worker loop that processes prefetch queue.
     */
    private static void workerLoop() {
        LOGGER.info("Prefetch worker thread started");

        while (running) {
            try {
                // Take highest priority task from queue (blocking)
                PrefetchTask task = prefetchQueue.poll(1, TimeUnit.SECONDS);
                if (task == null) {
                    // Periodic cleanup during idle
                    PrefetchedChunkCache.cleanup();
                    continue;
                }

                // Skip if cancelled or too old
                if (task.isCancelled() || task.getAgeMs() > 30000) {
                    tasksCancelled.incrementAndGet();
                    continue;
                }

                // Acquire rate limit permit (blocking)
                rateLimiter.acquire();

                // Execute prefetch asynchronously
                CompletableFuture<CompoundTag> future = executePrefetch(task);

                long stamp = lock.writeLock();
                try {
                    inFlight.put(task.getPos().toLong(), future);
                } finally {
                    lock.unlockWrite(stamp);
                }

            } catch (InterruptedException e) {
                if (!running) {
                    break; // Normal shutdown
                }
                LOGGER.warn("Worker thread interrupted", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("Error in prefetch worker loop", e);
            }
        }

        LOGGER.info("Prefetch worker thread stopped");
    }

    /**
     * Execute async prefetch for a single chunk.
     */
    private static CompletableFuture<CompoundTag> executePrefetch(PrefetchTask task) {
        long startTime = System.nanoTime();
        activeOperations.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Read chunk NBT from disk (blocking I/O, but on background thread)
                CompoundTag data = readChunkFromDisk(task.getPos());
                if (data != null) {
                    PrefetchedChunkCache.put(task.getPos(), data);
                    tasksCompleted.incrementAndGet();
                    return data;
                }
                return null;

            } catch (Exception e) {
                LOGGER.debug("Prefetch failed for {}: {}", task.getPos(), e.getMessage());
                tasksFailed.incrementAndGet();
                return null;

            } finally {
                // Track timing
                long elapsed = System.nanoTime() - startTime;
                totalPrefetchTimeNs.addAndGet(elapsed);

                // Cleanup
                activeOperations.decrementAndGet();

                long stamp = lock.writeLock();
                try {
                    inFlight.remove(task.getPos().toLong());
                } finally {
                    lock.unlockWrite(stamp);
                }

                rateLimiter.release();
            }
        }, WorkerThreadPool.getIOPool());
    }

    /**
     * Read chunk NBT from disk using RegionFileStorage.
     * This performs the actual I/O operation on a background thread.
     * CRITICAL: Synchronized to prevent thread-safety issues in RegionFileStorage.
     */
    @SuppressWarnings("null")
    private static CompoundTag readChunkFromDisk(ChunkPos pos) {
        if (regionFileStorage == null) {
            LOGGER.debug("RegionFileStorage not yet initialized, skipping prefetch for {}", pos);
            return null;
        }

        try {
            // CRITICAL: RegionFileStorage is NOT thread-safe!
            // Multiple background threads calling read() simultaneously can corrupt
            // the internal Long2ObjectLinkedOpenHashMap, causing:
            // "ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 33"
            //
            // We MUST synchronize access to regionFileStorage to prevent this.
            CompoundTag chunkData;
            synchronized (regionFileStorage) {
                chunkData = regionFileStorage.read(pos);
            }

            if (chunkData != null) {
                LOGGER.trace("Successfully prefetched chunk {} ({} bytes)",
                        pos, chunkData.sizeInBytes());
            }

            return chunkData;

        } catch (Exception e) {
            LOGGER.debug("Error reading chunk {} from disk: {}", pos, e.getMessage());
            return null;
        }
    }

    /**
     * Set RegionFileStorage reference (called by mixin).
     */
    public static void setRegionFileStorage(RegionFileStorage storage) {
        regionFileStorage = storage;
    }

    /**
     * Cancel prefetch for specific chunk.
     */
    public static void cancelPrefetch(ChunkPos pos) {
        // Cancel in-flight operation
        long posKey = pos.toLong();
        CompletableFuture<CompoundTag> future = null;

        long stamp = lock.writeLock();
        try {
            future = inFlight.get(posKey);
            if (future != null) {
                inFlight.remove(posKey);
            }
        } finally {
            lock.unlockWrite(stamp);
        }

        if (future != null) {
            future.cancel(true);
            tasksCancelled.incrementAndGet();
        }

        // Remove from queue (expensive, but rare)
        prefetchQueue.removeIf(task -> task.getPos().equals(pos));
    }

    /**
     * Update configuration based on TPS.
     */
    public static void updateConfiguration(double currentTPS) {
        if (currentTPS > 19.0) {
            maxConcurrentIO = 16;
        } else if (currentTPS > 15.0) {
            maxConcurrentIO = 8;
        } else {
            maxConcurrentIO = 4;
        }

        // Update rate limiter
        int currentPermits = rateLimiter.availablePermits();
        int targetPermits = maxConcurrentIO;
        if (currentPermits < targetPermits) {
            rateLimiter.release(targetPermits - currentPermits);
        } else if (currentPermits > targetPermits) {
            rateLimiter.tryAcquire(currentPermits - targetPermits);
        }

        // Update movement tracker
        PlayerMovementTracker.updateConfiguration(currentTPS);
    }

    /**
     * Get prefetch statistics.
     */
    public static String getStatistics() {
        long scheduled = tasksScheduled.get();
        long completed = tasksCompleted.get();
        long cancelled = tasksCancelled.get();
        long failed = tasksFailed.get();
        double avgTimeMs = completed > 0 ? (totalPrefetchTimeNs.get() / 1_000_000.0) / completed : 0;

        return String.format(
                "Prefetch{scheduled=%d, completed=%d, cancelled=%d, failed=%d, " +
                        "active=%d, queue=%d, avgMs=%.2f, cache=%s}",
                scheduled, completed, cancelled, failed,
                activeOperations.get(), prefetchQueue.size(), avgTimeMs,
                PrefetchedChunkCache.getStatistics());
    }

    /**
     * Get active operation count.
     */
    public static int getActiveOperations() {
        return activeOperations.get();
    }

    /**
     * Get queue size.
     */
    public static int getQueueSize() {
        return prefetchQueue.size();
    }

    /**
     * Check if prefetcher is running.
     */
    public static boolean isRunning() {
        return running;
    }
}
