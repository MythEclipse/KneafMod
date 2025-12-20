package com.kneaf.core.performance.scheduler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block distribution for cache-aware work stealing.
 * Extracted from ParallelRustVectorProcessor.
 */
public class BlockDistribution {
    private final ConcurrentHashMap<Integer, Integer> blockWorkload;
    private final ConcurrentHashMap<Integer, Integer> blockOwnership;
    private final AtomicInteger totalBlocks;

    public BlockDistribution() {
        this.blockWorkload = new ConcurrentHashMap<>();
        this.blockOwnership = new ConcurrentHashMap<>();
        this.totalBlocks = new AtomicInteger(0);
    }

    public void assignBlock(int block, int workerId) {
        blockOwnership.put(block, workerId);
        blockWorkload.put(block, 0);
        totalBlocks.incrementAndGet();
    }

    public void incrementBlockWorkload(int block) {
        blockWorkload.computeIfPresent(block, (k, v) -> v + 1);
    }

    public Integer getBlockOwner(int block) {
        return blockOwnership.get(block);
    }

    public double getLoadImbalance() {
        if (blockWorkload.isEmpty()) {
            return 0.0;
        }

        int maxLoad = blockWorkload.values().stream().max(Integer::compareTo).orElse(0);
        int minLoad = blockWorkload.values().stream().min(Integer::compareTo).orElse(0);
        double avgLoad = blockWorkload.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);

        if (avgLoad > 0.0) {
            return (maxLoad - minLoad) / avgLoad;
        } else {
            return 0.0;
        }
    }
}
