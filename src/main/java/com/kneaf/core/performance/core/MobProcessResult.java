package com.kneaf.core.performance.core;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Full implementation of mob processing results with AI optimization algorithms.
 * Handles mob AI simplification, despawning, and performance optimization.
 */
public class MobProcessResult implements Serializable {
    private static final long serialVersionUID = 1L;

    // Processing statistics
    private final int processedCount;
    private final int totalCount;
    private final long processingTimeMs;
    private final long timestamp;

    // Optimization results
    private final List<Long> disableList; // Mobs to disable (despawn)
    private final List<Long> simplifyList; // Mobs to simplify AI
    private final List<Long> freezeList; // Mobs to freeze (stop AI temporarily)
    private final List<Long> optimizeList; // Mobs that had AI optimized

    // Performance metrics
    private final int mobsDespawned;
    private final int mobsSimplified;
    private final int mobsFrozen;
    private final int mobsOptimized;
    private final long cpuSavedMs; // CPU time saved by optimizations
    private final long memorySavedBytes;

    // Detailed updates for tracking changes
    private final List<MobUpdate> updates;

    // Processing metadata
    private final Map<String, Object> metadata;
    private final ProcessingMode mode;

    /**
     * Processing mode enumeration.
     */
    public enum ProcessingMode {
        STANDARD,    // Normal processing with basic AI optimizations
        AGGRESSIVE,  // Aggressive optimization, more mobs processed
        CONSERVATIVE // Conservative processing, fewer changes
    }

    /**
     * Mob update types for tracking changes.
     */
    public enum UpdateType {
        DESPAWNED(1),   // Mob was despawned
        SIMPLIFIED(2),  // Mob AI was simplified
        FROZEN(3),      // Mob AI was frozen
        OPTIMIZED(4),   // Mob AI was optimized
        RESTORED(5);    // Mob AI was restored to normal

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
     * Builder for constructing MobProcessResult instances.
     */
    public static class Builder {
        private int processedCount = 0;
        private int totalCount = 0;
        private long processingTimeMs = 0;
        private final List<Long> disableList = new ArrayList<>();
        private final List<Long> simplifyList = new ArrayList<>();
        private final List<Long> freezeList = new ArrayList<>();
        private final List<Long> optimizeList = new ArrayList<>();
        private int mobsDespawned = 0;
        private int mobsSimplified = 0;
        private int mobsFrozen = 0;
        private int mobsOptimized = 0;
        private long cpuSavedMs = 0;
        private long memorySavedBytes = 0;
        private final List<MobUpdate> updates = new ArrayList<>();
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

        public Builder addDespawnedMob(long mobId) {
            disableList.add(mobId);
            updates.add(new MobUpdate(mobId, UpdateType.DESPAWNED));
            mobsDespawned++;
            processedCount++;
            return this;
        }

        public Builder addSimplifiedMob(long mobId) {
            simplifyList.add(mobId);
            updates.add(new MobUpdate(mobId, UpdateType.SIMPLIFIED));
            mobsSimplified++;
            processedCount++;
            return this;
        }

        public Builder addFrozenMob(long mobId) {
            freezeList.add(mobId);
            updates.add(new MobUpdate(mobId, UpdateType.FROZEN));
            mobsFrozen++;
            processedCount++;
            return this;
        }

        public Builder addOptimizedMob(long mobId) {
            optimizeList.add(mobId);
            updates.add(new MobUpdate(mobId, UpdateType.OPTIMIZED));
            mobsOptimized++;
            processedCount++;
            return this;
        }

        public Builder cpuSaved(long ms) {
            this.cpuSavedMs += ms;
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

        public MobProcessResult build() {
            return new MobProcessResult(
                processedCount, totalCount, processingTimeMs,
                new CopyOnWriteArrayList<>(disableList),
                new CopyOnWriteArrayList<>(simplifyList),
                new CopyOnWriteArrayList<>(freezeList),
                new CopyOnWriteArrayList<>(optimizeList),
                mobsDespawned, mobsSimplified, mobsFrozen, mobsOptimized,
                cpuSavedMs, memorySavedBytes,
                new CopyOnWriteArrayList<>(updates),
                new ConcurrentHashMap<>(metadata), mode
            );
        }
    }

    /**
     * Private constructor - use Builder.
     */
    private MobProcessResult(int processedCount, int totalCount, long processingTimeMs,
                           List<Long> disableList, List<Long> simplifyList,
                           List<Long> freezeList, List<Long> optimizeList,
                           int mobsDespawned, int mobsSimplified, int mobsFrozen, int mobsOptimized,
                           long cpuSavedMs, long memorySavedBytes,
                           List<MobUpdate> updates, Map<String, Object> metadata, ProcessingMode mode) {
        this.processedCount = processedCount;
        this.totalCount = totalCount;
        this.processingTimeMs = processingTimeMs;
        this.timestamp = System.currentTimeMillis();
        this.disableList = disableList;
        this.simplifyList = simplifyList;
        this.freezeList = freezeList;
        this.optimizeList = optimizeList;
        this.mobsDespawned = mobsDespawned;
        this.mobsSimplified = mobsSimplified;
        this.mobsFrozen = mobsFrozen;
        this.mobsOptimized = mobsOptimized;
        this.cpuSavedMs = cpuSavedMs;
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
    public List<Long> getFreezeList() { return freezeList; }
    public List<Long> getOptimizeList() { return optimizeList; }
    public int getMobsDespawned() { return mobsDespawned; }
    public int getMobsSimplified() { return mobsSimplified; }
    public int getMobsFrozen() { return mobsFrozen; }
    public int getMobsOptimized() { return mobsOptimized; }
    public long getCpuSavedMs() { return cpuSavedMs; }
    public long getMemorySavedBytes() { return memorySavedBytes; }
    public List<MobUpdate> getUpdates() { return updates; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public ProcessingMode getMode() { return mode; }

    /**
     * Get processing efficiency as a percentage.
     */
    public double getProcessingEfficiency() {
        return totalCount > 0 ? (processedCount * 100.0) / totalCount : 0.0;
    }

    /**
     * Get optimization ratio (mobs optimized / total mobs).
     */
    public double getOptimizationRatio() {
        int totalOptimized = mobsDespawned + mobsSimplified + mobsFrozen + mobsOptimized;
        return totalCount > 0 ? (totalOptimized * 100.0) / totalCount : 0.0;
    }

    /**
     * Get CPU savings as a percentage of total processing time.
     */
    public double getCpuSavingsPercent() {
        return processingTimeMs > 0 ? (cpuSavedMs * 100.0) / processingTimeMs : 0.0;
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
    public MobProcessResult merge(MobProcessResult other) {
        if (other == null) return this;

        Builder merged = new Builder()
            .totalCount(this.totalCount + other.totalCount)
            .processingTimeMs(Math.max(this.processingTimeMs, other.processingTimeMs))
            .mode(this.mode) // Keep original mode
            .cpuSaved(this.cpuSavedMs + other.cpuSavedMs)
            .memorySaved(this.memorySavedBytes + other.memorySavedBytes);

        // Merge lists
        merged.disableList.addAll(this.disableList);
        merged.disableList.addAll(other.disableList);
        merged.simplifyList.addAll(this.simplifyList);
        merged.simplifyList.addAll(other.simplifyList);
        merged.freezeList.addAll(this.freezeList);
        merged.freezeList.addAll(other.freezeList);
        merged.optimizeList.addAll(this.optimizeList);
        merged.optimizeList.addAll(other.optimizeList);

        // Merge statistics
        merged.mobsDespawned = this.mobsDespawned + other.mobsDespawned;
        merged.mobsSimplified = this.mobsSimplified + other.mobsSimplified;
        merged.mobsFrozen = this.mobsFrozen + other.mobsFrozen;
        merged.mobsOptimized = this.mobsOptimized + other.mobsOptimized;
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
    public static MobProcessResult fromByteArray(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (MobProcessResult) ois.readObject();
        }
    }

    @Override
    public String toString() {
        return String.format(
            "MobProcessResult{mode=%s, processed=%d/%d (%.1f%%), despawned=%d, simplified=%d, frozen=%d, optimized=%d, cpuSaved=%d ms, memorySaved=%d bytes, time=%d ms}",
            mode, processedCount, totalCount, getProcessingEfficiency(),
            mobsDespawned, mobsSimplified, mobsFrozen, mobsOptimized,
            cpuSavedMs, memorySavedBytes, processingTimeMs
        );
    }

    /**
     * Mob update container for tracking changes.
     */
    public static class MobUpdate implements Serializable {
        private static final long serialVersionUID = 1L;

        private final long mobId;
        private final UpdateType updateType;
        private final long timestamp;

        public MobUpdate(long mobId, UpdateType updateType) {
            this.mobId = mobId;
            this.updateType = updateType;
            this.timestamp = System.currentTimeMillis();
        }

        public long getMobId() { return mobId; }
        public UpdateType getUpdateType() { return updateType; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("MobUpdate{id=%d, type=%s, time=%d}", mobId, updateType, timestamp);
        }
    }
}