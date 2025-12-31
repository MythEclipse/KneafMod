/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Inspired by Starlight - Light engine optimization with Rust JNI.
 */
package com.kneaf.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import com.kneaf.core.RustOptimizations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LightEngineMixin - Light engine optimization with Rust JNI.
 * 
 * OPTIMIZATIONS:
 * 1. Cache light values and return cached results
 * 2. Skip redundant light checks for same position within tick
 * 3. Coalesce section updates to reduce propagation overhead
 * 4. Rust batch light propagation for bulk updates
 */
@Mixin(LevelLightEngine.class)
public abstract class LightEngineMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/LightEngineMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // OPTIMIZATION: Use Primitive Open Addressing Map (Linear Probing)
    // Eliminates entry objects and boxing overhead. Protected by StampedLock.
    @Unique
    private final Long2IntOpenHashMap kneaf$lightCache = new Long2IntOpenHashMap(4096);

    @Unique
    private final java.util.concurrent.locks.StampedLock kneaf$cacheLock = new java.util.concurrent.locks.StampedLock();

    // Track positions checked this tick to skip duplicates
    @Unique
    private final Set<Long> kneaf$checkedThisTick = ConcurrentHashMap.newKeySet();

    // Batch light updates for Rust processing
    @Unique
    private final Map<Long, Byte> kneaf$pendingLightUpdates = new ConcurrentHashMap<>(128);

    // Statistics
    @Unique
    private static final AtomicLong kneaf$cacheHits = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$cacheMisses = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$checksSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$rustBatchCalls = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Unique
    private static final int BATCH_THRESHOLD = 32;

    /**
     * OPTIMIZATION: Return cached light values.
     */
    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void kneaf$onGetRawBrightness(BlockPos pos, int ambientDarkness,
            CallbackInfoReturnable<Integer> cir) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ LightEngineMixin applied - Rust batch light + Fast Primitive Cache active!");
            kneaf$loggedFirstApply = true;
        }

        long posKey = pos.asLong();

        // Check primitive cache
        int cached = -1;
        long stamp = kneaf$cacheLock.tryOptimisticRead();
        cached = kneaf$lightCache.get(posKey);
        if (!kneaf$cacheLock.validate(stamp)) {
            stamp = kneaf$cacheLock.readLock();
            try {
                cached = kneaf$lightCache.get(posKey);
            } finally {
                kneaf$cacheLock.unlockRead(stamp);
            }
        }

        if (cached != -1) {
            // Apply ambient darkness adjustment
            int result = Math.max(0, cached - ambientDarkness);
            kneaf$cacheHits.incrementAndGet();
            cir.setReturnValue(result);
            return;
        }

        kneaf$cacheMisses.incrementAndGet();
    }

    /**
     * Cache result after computation.
     */
    @Inject(method = "getRawBrightness", at = @At("RETURN"))
    private void kneaf$afterGetRawBrightness(BlockPos pos, int ambientDarkness,
            CallbackInfoReturnable<Integer> cir) {
        long posKey = pos.asLong();

        // Store the raw value (before ambient darkness adjustment)
        int rawValue = cir.getReturnValue() + ambientDarkness;

        long stamp = kneaf$cacheLock.writeLock();
        try {
            kneaf$lightCache.put(posKey, rawValue);
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }

        kneaf$logStats();
    }

    /**
     * OPTIMIZATION: Skip duplicate checkBlock for same position + queue for batch.
     */
    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void kneaf$onCheckBlock(BlockPos pos, CallbackInfo ci) {
        // SAFE FALLBACK: If Rust is not available, do NOT intercept logic
        if (!RustOptimizations.isAvailable()) {
            return;
        }

        long posKey = pos.asLong();

        // Skip if already checked this tick
        if (!kneaf$checkedThisTick.add(posKey)) {
            kneaf$checksSkipped.incrementAndGet();
            ci.cancel();
            return;
        }

        // Queue for batch processing
        kneaf$pendingLightUpdates.put(posKey, (byte) 15); // Mark for update

        // Invalidate cache for this position
        long stamp = kneaf$cacheLock.writeLock();
        try {
            kneaf$lightCache.remove(posKey);
        } finally {
            kneaf$cacheLock.unlockWrite(stamp);
        }
    }

    /**
     * Process batched light updates using Rust.
     */
    @Inject(method = "runLightUpdates", at = @At("HEAD"))
    private void kneaf$onRunLightUpdates(CallbackInfoReturnable<Integer> cir) {
        // Clear per-tick tracking
        kneaf$checkedThisTick.clear();

        // Process batch ALWAYS if native is available (avoid delay/light lag)
        if (RustOptimizations.isAvailable() && !kneaf$pendingLightUpdates.isEmpty()) {
            kneaf$processBatchLightUpdates();
        }

        // Periodic cache cleanup
        if (kneaf$lightCache.size() > 2048) { // Half of 4096 capacity
            long stamp = kneaf$cacheLock.writeLock();
            try {
                // Simple clear to avoid complexity of resizing/cleaning
                // Real implementation would be LRU or similar, but active clear is fine for
                // light
                if (kneaf$lightCache.size() > 3000) {
                    kneaf$lightCache.clear();
                }
            } finally {
                kneaf$cacheLock.unlockWrite(stamp);
            }
        }
    }

    /**
     * Process queued light updates using Rust batch processing.
     */
    @Unique
    private void kneaf$processBatchLightUpdates() {
        int count = kneaf$pendingLightUpdates.size();
        if (count == 0)
            return;

        // Build arrays for Rust
        byte[] lightLevels = new byte[count];
        int[] positions = new int[count * 3];

        int idx = 0;
        for (var entry : kneaf$pendingLightUpdates.entrySet()) {
            long posKey = entry.getKey();
            int x = BlockPos.getX(posKey);
            int y = BlockPos.getY(posKey);
            int z = BlockPos.getZ(posKey);

            positions[idx * 3] = x;
            positions[idx * 3 + 1] = y;
            positions[idx * 3 + 2] = z;
            lightLevels[idx] = entry.getValue();
            idx++;
        }

        // Use Rust for batch light propagation
        try {
            int[] results = RustOptimizations.batchLight(positions, lightLevels, count);
            kneaf$rustBatchCalls.incrementAndGet();

            // Update cache with results
            if (results != null) {
                long stamp = kneaf$cacheLock.writeLock();
                try {
                    idx = 0;
                    for (var entry : kneaf$pendingLightUpdates.entrySet()) {
                        if (idx < results.length) {
                            kneaf$lightCache.put(entry.getKey(), results[idx] & 0xFF);
                        }
                        idx++;
                    }
                } finally {
                    kneaf$cacheLock.unlockWrite(stamp);
                }
            }
        } catch (Exception e) {
            // Java fallback - just clear pending
        }

        kneaf$pendingLightUpdates.clear();
    }

    /**
     * Invalidate cache when sections change.
     */
    @Inject(method = "updateSectionStatus", at = @At("HEAD"))
    private void kneaf$onUpdateSectionStatus(SectionPos pos, boolean isEmpty, CallbackInfo ci) {
        // Clear active cache on section updates to prevent stale lighting
        if (kneaf$lightCache.size() > 1000) {
            long stamp = kneaf$cacheLock.writeLock();
            try {
                kneaf$lightCache.clear();
            } finally {
                kneaf$cacheLock.unlockWrite(stamp);
            }
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long hits = kneaf$cacheHits.get();
            long misses = kneaf$cacheMisses.get();
            long skipped = kneaf$checksSkipped.get();
            long rust = kneaf$rustBatchCalls.get();
            long total = hits + misses;
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (total > 0) {
                // Update central stats
                double hitRate = hits * 100.0 / total;
                com.kneaf.core.PerformanceStats.lightCacheHitPercent = hitRate;
                com.kneaf.core.PerformanceStats.lightSkipped = skipped / timeDiff;
                com.kneaf.core.PerformanceStats.lightRustBatches = rust / timeDiff;

                kneaf$cacheHits.set(0);
                kneaf$cacheMisses.set(0);
                kneaf$checksSkipped.set(0);
                kneaf$rustBatchCalls.set(0);
            } else {
                com.kneaf.core.PerformanceStats.lightSkipped = 0;
            }
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        long total = kneaf$cacheHits.get() + kneaf$cacheMisses.get();
        double rate = total > 0 ? kneaf$cacheHits.get() * 100.0 / total : 0;
        return String.format("LightStats{hitRate=%.1f%%, skipped=%d, rust=%d}",
                rate, kneaf$checksSkipped.get(), kneaf$rustBatchCalls.get());
    }

    /**
     * Simple primitive Long2Int map using linear probing.
     * Not thread-safe (external locking required).
     */
    @Unique
    private static class Long2IntOpenHashMap {
        private final long[] keys;
        private final int[] values;
        private final boolean[] used;
        private final int capacity;
        private int size;

        public Long2IntOpenHashMap(int capacity) {
            this.capacity = capacity;
            this.keys = new long[capacity];
            this.values = new int[capacity];
            this.used = new boolean[capacity];
            this.size = 0;
        }

        public void put(long key, int value) {
            if (size >= capacity * 0.75) {
                // Determine overflow strategy: clear or drop?
                // For a cache, dropping random/old entries or just rejecting new ones is
                // acceptable.
                // Or simplified: clear when full for now.
                clear();
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return; // Should not happen with load factor check
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public int get(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return -1;
            }
            return -1;
        }

        public void remove(long key) {
            int idx = hash(key);
            int startIdx = idx;

            // Simplified remove (tombstone free for cache - just clear if collision chain
            // breaks, or ignore)
            // Proper removal in open addressing is complex (shift back).
            // For a transient cache, we can just mark unused BUT that breaks chains.
            // Since this is a light cache, exact correctness under heavy churn with removal
            // is tricky without complex logic.
            // Strategy: Do nothing or simple linear scan remove?
            // Actually, light cache removal is rare (only on update).
            // Let's implement full shift back for correctness.
            while (used[idx]) {
                if (keys[idx] == key) {
                    used[idx] = false;
                    size--;
                    // Shift following entries back to close gap
                    closeGap(idx);
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }
        }

        private void closeGap(int gapIdx) {
            int curr = (gapIdx + 1) % capacity;
            while (used[curr]) {
                int ideal = hash(keys[curr]);
                // If current item is NOT in its ideal position relative to gap...
                // (circular distance check)
                if ((gapIdx < curr ? (ideal <= gapIdx || ideal > curr) : (ideal <= gapIdx && ideal > curr))) {
                    keys[gapIdx] = keys[curr];
                    values[gapIdx] = values[curr];
                    used[gapIdx] = true;
                    used[curr] = false;
                    gapIdx = curr;
                }
                curr = (curr + 1) % capacity;
            }
        }

        public void clear() {
            java.util.Arrays.fill(used, false);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(long key) {
            // Jenkins hash mix or similar
            key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
            key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
            key = key ^ (key >>> 31);
            return (int) ((key & 0x7FFFFFFF) % capacity);
        }
    }
}
