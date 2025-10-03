package com.kneaf.core.performance.core;

import java.util.List;

/**
 * Result of item entity processing operation.
 */
public class ItemProcessResult {
    private final List<Long> itemsToRemove;
    private final long mergedCount;
    private final long despawnedCount;
    private final List<PerformanceProcessor.ItemUpdate> itemUpdates;

    public ItemProcessResult(List<Long> itemsToRemove, long mergedCount, long despawnedCount,
                           List<PerformanceProcessor.ItemUpdate> itemUpdates) {
        this.itemsToRemove = itemsToRemove;
        this.mergedCount = mergedCount;
        this.despawnedCount = despawnedCount;
        this.itemUpdates = itemUpdates;
    }

    public List<Long> getItemsToRemove() {
        return itemsToRemove;
    }

    public long getMergedCount() {
        return mergedCount;
    }

    public long getDespawnedCount() {
        return despawnedCount;
    }

    public List<PerformanceProcessor.ItemUpdate> getItemUpdates() {
        return itemUpdates;
    }
}