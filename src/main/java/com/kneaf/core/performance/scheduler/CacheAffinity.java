package com.kneaf.core.performance.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cache affinity tracking for spatial locality optimization.
 * Extracted from ParallelRustVectorProcessor.
 */
public class CacheAffinity {
    private final List<Integer> preferredBlocks;
    private Integer lastAccessedBlock;
    private double cacheHitRate;
    private final AtomicInteger cacheHits;
    private final AtomicInteger totalAccesses;

    public CacheAffinity() {
        this.preferredBlocks = new ArrayList<>();
        this.lastAccessedBlock = null;
        this.cacheHitRate = 0.0;
        this.cacheHits = new AtomicInteger(0);
        this.totalAccesses = new AtomicInteger(0);
    }

    public void updatePreferredBlock(int block) {
        this.lastAccessedBlock = block;
        totalAccesses.incrementAndGet();

        if (!preferredBlocks.contains(block)) {
            preferredBlocks.add(block);
            if (preferredBlocks.size() > 8) {
                preferredBlocks.remove(0); // Keep only recent blocks
            }
        } else {
            cacheHits.incrementAndGet();
        }

        updateCacheHitRate();
    }

    private void updateCacheHitRate() {
        int hits = cacheHits.get();
        int total = totalAccesses.get();
        if (total > 0) {
            cacheHitRate = (double) hits / total;
        }
    }

    public List<Integer> getPreferredBlocks() {
        return new ArrayList<>(preferredBlocks);
    }

    public double getCacheHitRate() {
        return cacheHitRate;
    }

    public Integer getLastAccessedBlock() {
        return lastAccessedBlock;
    }
}
