package com.kneaf.core.performance.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Full implementation of item processing results with optimization algorithms.
 * Handles item stack merging, despawning, and performance optimization.
 */
public class ItemProcessResult implements Serializable {
    private static final long serialVersionUID = 1L;

    // Processing statistics
    private final int processedCount;
    private final int totalCount;
    private final long processingTimeMs;
    private final long timestamp;

    // Optimization results
    private final List<Long> disableList; // Items to disable (remove from world)
    private final List<Long> simplifyList; // Items to simplify (reduce complexity)
    private final List<Long> mergeList; // Items that were merged into stacks

    // Performance metrics
    private final int stacksMerged;
    private final int itemsDespawned;
    private final int itemsSimplified;
    private final long memorySavedBytes;

    // Detailed updates for tracking changes
    private final List<ItemUpdate> updates;

    // Processing metadata
    private final Map<String, Object> metadata;
    private final ProcessingMode mode;

    /**
     * Processing mode enumeration.
     */
    public enum ProcessingMode {
        STANDARD,    // Normal processing with basic optimizations
        AGGRESSIVE,  // Aggressive optimization, more items processed
        CONSERVATIVE // Conservative processing, fewer changes
    }

    /**
     * Item update types for tracking changes.
     */
    public enum UpdateType {
        MERGED(1),      // Item was merged into a stack
        DESPAWNED(2),   // Item was despawned
        SIMPLIFIED(3),  // Item complexity was reduced
        DISABLED(4),    // Item was disabled
        OPTIMIZED(5);   // Item was optimized in some other way

        private final int code;

        UpdateType(int code) {
            this.code = code;
        }

        public int getCode() { return code; }

        public static UpdateType fromCode(int code) {
            for (UpdateType type : values()) {
                if (type.code == code) return type;
            }
            return OPTIMIZED;
        }
    }

    /**
     * Builder for constructing ItemProcessResult instances.
     */
    public static class Builder {
        private int processedCount = 0;
        private int totalCount = 0;
        private long processingTimeMs = 0;
        private final List<Long> disableList = new ArrayList<>();
        private final List<Long> simplifyList = new ArrayList<>();
        private final List<Long> mergeList = new ArrayList<>();
        private int stacksMerged = 0;
        private int itemsDespawned = 0;
        private int itemsSimplified = 0;
        private long memorySavedBytes = 0;
        private final List<ItemUpdate> updates = new ArrayList<>();
        private final Map<String, Object> metadata = new HashMap<>();
        private ProcessingMode mode = ProcessingMode.STANDARD;

        public Builder totalCount(int totalCount) {
            this.totalCount = totalCount;
            return this;
        }

        public Builder processingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
            return this;
        }

        public Builder mode(ProcessingMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder addDisabledItem(long itemId) {
            disableList.add(itemId);
            updates.add(new ItemUpdate(itemId, UpdateType.DISABLED));
            itemsDespawned++;
            processedCount++;
            return this;
        }

        public Builder addSimplifiedItem(long itemId) {
            simplifyList.add(itemId);
            updates.add(new ItemUpdate(itemId, UpdateType.SIMPLIFIED));
            itemsSimplified++;
            processedCount++;
            return this;
        }

        public Builder addMergedItem(long itemId) {
            mergeList.add(itemId);
            updates.add(new ItemUpdate(itemId, UpdateType.MERGED));
            stacksMerged++;
            processedCount++;
            return this;
        }

        public Builder memorySaved(long bytes) {
            this.memorySavedBytes += bytes;
            return this;
        }

        public Builder metadata(String key, Object value) {
            metadata.put(key, value);
            return this;
        }

        public ItemProcessResult build() {
            return new ItemProcessResult(
                processedCount, totalCount, processingTimeMs,
                new CopyOnWriteArrayList<>(disableList),
                new CopyOnWriteArrayList<>(simplifyList),
                new CopyOnWriteArrayList<>(mergeList),
                stacksMerged, itemsDespawned, itemsSimplified, memorySavedBytes,
                new CopyOnWriteArrayList<>(updates),
                new ConcurrentHashMap<>(metadata), mode
            );
        }
    }

    /**
     * Private constructor - use Builder.
     */
    private ItemProcessResult(int processedCount, int totalCount, long processingTimeMs,
                            List<Long> disableList, List<Long> simplifyList, List<Long> mergeList,
                            int stacksMerged, int itemsDespawned, int itemsSimplified,
                            long memorySavedBytes, List<ItemUpdate> updates,
                            Map<String, Object> metadata, ProcessingMode mode) {
        this.processedCount = processedCount;
        this.totalCount = totalCount;
        this.processingTimeMs = processingTimeMs;
        this.timestamp = System.currentTimeMillis();
        this.disableList = disableList;
        this.simplifyList = simplifyList;
        this.mergeList = mergeList;
        this.stacksMerged = stacksMerged;
        this.itemsDespawned = itemsDespawned;
        this.itemsSimplified = itemsSimplified;
        this.memorySavedBytes = memorySavedBytes;
        this.updates = updates;
        this.metadata = metadata;
        this.mode = mode;
    }

    // Getters
    public int getProcessedCount() { return processedCount; }
    public int getTotalCount() { return totalCount; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public long getTimestamp() { return timestamp; }
    public List<Long> getDisableList() { return disableList; }
    public List<Long> getSimplifyList() { return simplifyList; }
    public List<Long> getMergeList() { return mergeList; }
    public int getStacksMerged() { return stacksMerged; }
    public int getItemsDespawned() { return itemsDespawned; }
    public int getItemsSimplified() { return itemsSimplified; }
    public long getMemorySavedBytes() { return memorySavedBytes; }
    public List<ItemUpdate> getUpdates() { return updates; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public ProcessingMode getMode() { return mode; }

    /**
     * Get processing efficiency as a percentage.
     */
    public double getProcessingEfficiency() {
        return totalCount > 0 ? (processedCount * 100.0) / totalCount : 0.0;
    }

    /**
     * Get optimization ratio (items optimized / total items).
     */
    public double getOptimizationRatio() {
        int totalOptimized = stacksMerged + itemsDespawned + itemsSimplified;
        return totalCount > 0 ? (totalOptimized * 100.0) / totalCount : 0.0;
    }

    /**
     * Check if processing was successful (no errors).
     */
    public boolean isSuccessful() {
        return !metadata.containsKey("error");
    }

    /**
     * Get error message if processing failed.
     */
    public String getErrorMessage() {
        Object error = metadata.get("error");
        return error != null ? error.toString() : null;
    }

    /**
     * Merge this result with another result.
     */
    public ItemProcessResult merge(ItemProcessResult other) {
        if (other == null) return this;

        Builder merged = new Builder()
            .totalCount(this.totalCount + other.totalCount)
            .processingTimeMs(Math.max(this.processingTimeMs, other.processingTimeMs))
            .mode(this.mode) // Keep original mode
            .memorySaved(this.memorySavedBytes + other.memorySavedBytes);

        // Merge lists
        merged.disableList.addAll(this.disableList);
        merged.disableList.addAll(other.disableList);
        merged.simplifyList.addAll(this.simplifyList);
        merged.simplifyList.addAll(other.simplifyList);
        merged.mergeList.addAll(this.mergeList);
        merged.mergeList.addAll(other.mergeList);

        // Merge statistics
        merged.stacksMerged = this.stacksMerged + other.stacksMerged;
        merged.itemsDespawned = this.itemsDespawned + other.itemsDespawned;
        merged.itemsSimplified = this.itemsSimplified + other.itemsSimplified;
        merged.processedCount = this.processedCount + other.processedCount;

        // Merge updates
        merged.updates.addAll(this.updates);
        merged.updates.addAll(other.updates);

        // Merge metadata
        merged.metadata.putAll(this.metadata);
        merged.metadata.putAll(other.metadata);

        return merged.build();
    }

    /**
     * Serialize to byte array.
     */
    public byte[] toByteArray() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(this);
            return baos.toByteArray();
        }
    }

    /**
     * Deserialize from byte array.
     */
    public static ItemProcessResult fromByteArray(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (ItemProcessResult) ois.readObject();
        }
    }

    @Override
    public String toString() {
        return String.format(
            "ItemProcessResult{mode=%s, processed=%d/%d (%.1f%%), merged=%d, despawned=%d, simplified=%d, memorySaved=%d bytes, time=%d ms}",
            mode, processedCount, totalCount, getProcessingEfficiency(),
            stacksMerged, itemsDespawned, itemsSimplified, memorySavedBytes, processingTimeMs
        );
    }

    /**
     * Item update container for tracking changes.
     */
    public static class ItemUpdate implements Serializable {
        private static final long serialVersionUID = 1L;

        private final long itemId;
        private final UpdateType updateType;
        private final long timestamp;

        public ItemUpdate(long itemId, UpdateType updateType) {
            this.itemId = itemId;
            this.updateType = updateType;
            this.timestamp = System.currentTimeMillis();
        }

        public long getItemId() { return itemId; }
        public UpdateType getUpdateType() { return updateType; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("ItemUpdate{id=%d, type=%s, time=%d}", itemId, updateType, timestamp);
        }
    }
}