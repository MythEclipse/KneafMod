/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe LRU cache for prefetched chunk NBT data.
 * 
 * Features:
 * - Time-based expiry (default 30 seconds)
 * - Size-based eviction (max 512 chunks)
 * - Thread-safe access via ConcurrentHashMap
 * - Metrics tracking (cache hits/misses)
 */
public final class PrefetchedChunkCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrefetchedChunkCache.class);

    // Configuration
    private static final int MAX_CACHE_SIZE = 512; // ~100-200MB depending on chunk complexity
    private static final long CACHE_EXPIRY_MS = 30_000; // 30 seconds

    // Cache storage
    private static final Map<ChunkPos, CachedChunk> cache = new ConcurrentHashMap<>(512);

    // Statistics
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);
    private static final AtomicLong cacheEvictions = new AtomicLong(0);
    private static final AtomicLong cacheInvalidData = new AtomicLong(0);
    private static long lastCleanupTime = 0;

    private PrefetchedChunkCache() {
        // Utility class
    }

    /**
     * Cached chunk entry with expiry.
     */
    private static class CachedChunk {
        final CompoundTag data;
        final long insertTime;

        CachedChunk(CompoundTag data) {
            this.data = data;
            this.insertTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - insertTime) > CACHE_EXPIRY_MS;
        }
    }

    /**
     * Store chunk data in cache.
     * Automatically evicts if cache is full.
     * Validates chunk data before caching to prevent corruption.
     */
    public static void put(ChunkPos pos, CompoundTag data) {
        if (pos == null || data == null) {
            return;
        }

        // ✅ VALIDATION: Don't cache invalid or corrupted chunk data
        if (!isValidChunkData(pos, data)) {
            cacheInvalidData.incrementAndGet();
            LOGGER.debug("Rejected invalid chunk data for {}", pos);
            return;
        }

        // Check size limit
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldest(cache.size() - MAX_CACHE_SIZE + 1);
        }

        cache.put(pos, new CachedChunk(data));
    }

    /**
     * Get chunk data from cache.
     * Returns empty if not cached or expired.
     */
    public static Optional<CompoundTag> get(ChunkPos pos) {
        if (pos == null) {
            return Optional.empty();
        }

        CachedChunk cached = cache.get(pos);
        if (cached == null) {
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }

        // Check expiry
        if (cached.isExpired()) {
            cache.remove(pos);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }

        // ✅ VALIDATION: Double-check data integrity before returning
        if (!isValidChunkData(pos, cached.data)) {
            cache.remove(pos);
            cacheInvalidData.incrementAndGet();
            cacheMisses.incrementAndGet();
            LOGGER.warn("Evicted corrupted cached chunk data for {}", pos);
            return Optional.empty();
        }

        cacheHits.incrementAndGet();
        return Optional.of(cached.data);
    }

    /**
     * Validate chunk data integrity.
     * Checks for required NBT fields and position match.
     */
    private static boolean isValidChunkData(ChunkPos expectedPos, CompoundTag data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        try {
            // Check for DataVersion (required in all valid chunks)
            if (!data.contains("DataVersion")) {
                return false;
            }

            // Check for position fields (xPos, zPos) if present
            if (data.contains("xPos") && data.contains("zPos")) {
                int xPos = data.getInt("xPos");
                int zPos = data.getInt("zPos");

                // Position must match expected position
                if (xPos != expectedPos.x || zPos != expectedPos.z) {
                    LOGGER.warn("Chunk position mismatch: expected ({}, {}), got ({}, {})",
                            expectedPos.x, expectedPos.z, xPos, zPos);
                    return false;
                }
            }

            // Check for sections (required for terrain data)
            // Note: Some chunks may not have sections yet (empty chunks)
            // So we just check data isn't completely empty
            return data.size() > 1; // More than just DataVersion

        } catch (Exception e) {
            LOGGER.debug("Chunk validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if chunk is cached (without retrieving).
     */
    public static boolean contains(ChunkPos pos) {
        CachedChunk cached = cache.get(pos);
        return cached != null && !cached.isExpired();
    }

    /**
     * Invalidate specific chunk.
     */
    public static void invalidate(ChunkPos pos) {
        cache.remove(pos);
    }

    /**
     * Clear all cached chunks.
     */
    public static void clear() {
        cache.clear();
        LOGGER.info("Prefetch cache cleared");
    }

    /**
     * Evict oldest N entries.
     */
    private static void evictOldest(int count) {
        if (count <= 0)
            return;

        cache.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e1.getValue().insertTime, e2.getValue().insertTime))
                .limit(count)
                .forEach(entry -> {
                    cache.remove(entry.getKey());
                    cacheEvictions.incrementAndGet();
                });
    }

    /**
     * Periodic cleanup of expired entries.
     * Should be called periodically (e.g., every 10 seconds).
     */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < 10000) {
            return; // Only cleanup every 10 seconds
        }

        int removed = 0;
        for (Map.Entry<ChunkPos, CachedChunk> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.debug("Cleaned up {} expired cache entries", removed);
        }

        lastCleanupTime = now;
    }

    /**
     * Get cache statistics.
     */
    public static String getStatistics() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (hits * 100.0 / total) : 0;

        return String.format(
                "PrefetchCache{size=%d/%d, hits=%d, misses=%d, hitRate=%.1f%%, evictions=%d, invalidData=%d}",
                cache.size(), MAX_CACHE_SIZE, hits, misses, hitRate, cacheEvictions.get(), cacheInvalidData.get());
    }

    /**
     * Get cache hit rate (0-100).
     */
    public static double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (hits * 100.0 / total) : 0;
    }

    /**
     * Get current cache size.
     */
    public static int getSize() {
        return cache.size();
    }

    /**
     * Reset statistics.
     */
    public static void resetStatistics() {
        cacheHits.set(0);
        cacheMisses.set(0);
        cacheEvictions.set(0);
    }
}
