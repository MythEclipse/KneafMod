package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkProcessor - C2ME-style parallel chunk data processing
 * 
 * Handles chunk loading events and offloads heavy data processing
 * to a dedicated thread pool, keeping the main server thread responsive.
 * 
 * SAFETY POLICY:
 * - Only processes data that can be safely read in parallel
 * - Never modifies chunk block data directly from worker threads
 * - Uses read-only operations with Rust for computations
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ChunkProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkProcessor.class);

    // Thread pool for chunk processing (separate from entity processing)
    private static final ForkJoinPool chunkPool = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> LOGGER.error("Chunk processor error in {}: {}", t.getName(), e.getMessage()),
            true // async mode for better work-stealing
    );

    // Pending chunk tasks for synchronization
    private static final ConcurrentLinkedQueue<CompletableFuture<?>> pendingChunkTasks = new ConcurrentLinkedQueue<>();

    // Statistics
    private static final AtomicLong chunksProcessed = new AtomicLong(0);
    private static final AtomicLong asyncChunkOps = new AtomicLong(0);
    private static final AtomicInteger activeChunkTasks = new AtomicInteger(0);

    // Chunk positions being processed (to avoid duplicate processing)
    private static final Set<Long> processingChunks = ConcurrentHashMap.newKeySet();

    // Configuration
    private static volatile boolean enabled = true;
    private static final int MAX_CONCURRENT_CHUNKS = 16;

    // Native method declaration
    public static native double[] rustperf_analyze_chunk_sections(double[] sectionBlockCounts, int sectionCount);

    private ChunkProcessor() {
    }

    /**
     * Handle chunk load events - offload heavy processing to thread pool
     * This follows C2ME's pattern of parallelizing chunk operations
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!enabled)
            return;

        // Only process server-side chunks
        if (event.getLevel().isClientSide())
            return;

        // Limit concurrent chunk processing
        if (activeChunkTasks.get() >= MAX_CONCURRENT_CHUNKS) {
            return;
        }

        try {
            Object chunk = event.getChunk();
            if (chunk == null)
                return;

            // Get chunk position for deduplication
            long chunkPos = getChunkPos(chunk);
            if (chunkPos == Long.MIN_VALUE || !processingChunks.add(chunkPos)) {
                return; // Already processing this chunk
            }

            activeChunkTasks.incrementAndGet();

            // Submit chunk processing to thread pool
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processChunkDataAsync(chunk);
                    asyncChunkOps.incrementAndGet();
                } finally {
                    activeChunkTasks.decrementAndGet();
                    processingChunks.remove(chunkPos);
                }
            }, chunkPool).exceptionally(e -> {
                LOGGER.debug("Async chunk processing failed: {}", e.getMessage());
                activeChunkTasks.decrementAndGet();
                processingChunks.remove(chunkPos);
                return null;
            });

            pendingChunkTasks.add(future);
            chunksProcessed.incrementAndGet();

        } catch (Exception e) {
            LOGGER.debug("Chunk load handler error: {}", e.getMessage());
        }
    }

    /**
     * Process chunk data asynchronously
     * This is where we can call Rust for heavy computations
     */
    private static void processChunkDataAsync(Object chunk) {
        try {
            // Get chunk section data for processing
            int heightBlocks = getChunkHeight(chunk);
            int[] blockCounts = new int[16]; // Count per section

            // Analyze chunk structure (read-only, thread-safe)
            for (int section = 0; section < Math.min(16, heightBlocks / 16); section++) {
                blockCounts[section] = estimateSectionComplexity(chunk, section);
            }

            // If Rust is available, use it for heavy chunk analysis
            if (OptimizationInjector.isNativeLibraryLoaded()) {
                // Convert to format suitable for Rust processing
                double[] sectionData = new double[blockCounts.length];
                for (int i = 0; i < blockCounts.length; i++) {
                    sectionData[i] = blockCounts[i];
                }

                // Use Rust for parallel complex chunk analysis (C2ME style offloading)
                double[] complexityScores = rustperf_analyze_chunk_sections(sectionData, sectionData.length);

                // Use the analysis results (e.g., log high complexity chunks for debugging/profiling)
                // In a production scenario, this could trigger aggressive entity culling or lower ticking priority
                if (complexityScores != null && complexityScores.length > 0) {
                    double totalComplexity = 0;
                    for (double score : complexityScores) {
                        totalComplexity += score;
                    }
                    if (totalComplexity > 1000.0) {
                        LOGGER.debug("High complexity chunk detected at pos {}: score={}", 
                                getChunkPos(chunk), totalComplexity);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("Chunk data processing error: {}", e.getMessage());
        }
    }

    /**
     * Get chunk position as a long for deduplication
     */
    private static long getChunkPos(Object chunk) {
        try {
            java.lang.reflect.Method getPos = chunk.getClass().getMethod("getPos");
            Object chunkPos = getPos.invoke(chunk);
            if (chunkPos != null) {
                java.lang.reflect.Method toLong = chunkPos.getClass().getMethod("toLong");
                return (Long) toLong.invoke(chunkPos);
            }
        } catch (Exception e) {
            // Fallback
        }
        return Long.MIN_VALUE;
    }

    /**
     * Get chunk height (number of blocks vertically)
     */
    private static int getChunkHeight(Object chunk) {
        try {
            java.lang.reflect.Method getHeight = chunk.getClass().getMethod("getHeight");
            return (Integer) getHeight.invoke(chunk);
        } catch (Exception e) {
            return 256; // Default Minecraft height
        }
    }

    /**
     * Estimate section complexity (for LOD and optimization decisions)
     */
    private static int estimateSectionComplexity(Object chunk, int section) {
        // This is a simplified estimation
        // In a full implementation, we'd analyze block variety, entity count, etc.
        return 100 + (section * 10); // Placeholder
    }

    /**
     * Await all pending chunk tasks (called at level tick end)
     */
    public static void awaitPendingChunkTasks(long timeoutMs) {
        if (pendingChunkTasks.isEmpty())
            return;

        List<CompletableFuture<?>> tasks = new ArrayList<>();
        CompletableFuture<?> task;
        while ((task = pendingChunkTasks.poll()) != null) {
            tasks.add(task);
        }

        if (!tasks.isEmpty()) {
            try {
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(e -> null)
                        .join();
            } catch (Exception e) {
                LOGGER.debug("Chunk task await error: {}", e.getMessage());
            }
        }
    }

    /**
     * Get chunk processing statistics
     */
    public static String getChunkProcessingStats() {
        return String.format("ChunkStats{processed=%d, asyncOps=%d, active=%d, poolSize=%d}",
                chunksProcessed.get(),
                asyncChunkOps.get(),
                activeChunkTasks.get(),
                chunkPool.getPoolSize());
    }

    /**
     * Enable/disable chunk processing
     */
    public static void setEnabled(boolean enable) {
        enabled = enable;
        LOGGER.info("Chunk processor {}", enable ? "enabled" : "disabled");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Shutdown chunk processor thread pool
     */
    public static void shutdown() {
        LOGGER.info("Shutting down chunk processor...");
        chunkPool.shutdown();
        try {
            if (!chunkPool.awaitTermination(5, TimeUnit.SECONDS)) {
                chunkPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            chunkPool.shutdownNow();
        }
        LOGGER.info("Chunk processor shutdown complete. Stats: {}", getChunkProcessingStats());
    }
}
