package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Rust-inspired memory management system with zero-copy semantics and efficient resource handling.
 * Implements patterns similar to Rust's ownership model with proper cleanup guarantees.
 */
public final class RustMemoryManager {
    private static final RustMemoryManager INSTANCE = new RustMemoryManager();
    
    // Track allocated memory blocks with automatic cleanup
    private final Map<Long, MemoryBlock> activeMemoryBlocks = new ConcurrentHashMap<>();
    private final AtomicInteger nextMemoryId = new AtomicInteger(1);
    
    // Memory pressure monitoring
    private final AtomicInteger allocatedBytes = new AtomicInteger(0);
    private final AtomicInteger peakAllocatedBytes = new AtomicInteger(0);
    
    // Configuration
    private final int maxMemoryPressureThreshold = 90; // Percentage
    private final int softMemoryLimit;
    private final int hardMemoryLimit;
    
    // Listener for memory pressure events
    private List<MemoryPressureListener> pressureListeners = new CopyOnWriteArrayList<>();

    private RustMemoryManager() {
        // Initialize limits based on system memory (simplified for example)
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        this.softMemoryLimit = (int) (totalMemory * 0.7);
        this.hardMemoryLimit = (int) (totalMemory * 0.9);
    }

    /**
     * Get the singleton instance of RustMemoryManager.
     * @return Singleton instance
     */
    public static RustMemoryManager getInstance() {
        return INSTANCE;
    }

    /**
     * Allocate memory for a byte array with automatic cleanup.
     * @param data The data to store in memory
     * @return MemoryHandle for the allocated memory
     */
    public MemoryHandle allocate(byte[] data) {
        return allocate(data, 0, data.length);
    }

    /**
     * Allocate memory for a portion of a byte array with automatic cleanup.
     * @param data The source data
     * @param offset The starting offset in the source array
     * @param length The number of bytes to allocate
     * @return MemoryHandle for the allocated memory
     */
    public MemoryHandle allocate(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "Data cannot be null");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }

        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        
        long memoryId = nextMemoryId.getAndIncrement();
        MemoryBlock block = new MemoryBlock(memoryId, copy);
        activeMemoryBlocks.put(memoryId, block);
        
        updateMemoryPressure(copy.length);
        checkMemoryPressure();
        
        return new MemoryHandle(memoryId);
    }

    /**
     * Allocate memory for a string with automatic cleanup.
     * @param str The string to store in memory
     * @return MemoryHandle for the allocated memory
     */
    public MemoryHandle allocate(String str) {
        try {
            return allocate(str.getBytes("UTF-8"));
        } catch (Exception e) {
            return allocate(str.getBytes());
        }
    }

    /**
     * Allocate memory for a ByteBuffer with automatic cleanup.
     * @param buffer The buffer to store in memory
     * @return MemoryHandle for the allocated memory
     */
    public MemoryHandle allocate(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "Buffer cannot be null");
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return allocate(data);
    }

    /**
     * Get a byte array view of the memory block without copying.
     * @param handle The memory handle
     * @return Byte array view of the memory
     * @throws IllegalArgumentException If the handle is invalid
     */
    public byte[] get(MemoryHandle handle) {
        MemoryBlock block = getBlock(handle);
        return block.getData().clone(); // Return clone to maintain ownership semantics
    }

    /**
     * Get a ByteBuffer view of the memory block without copying.
     * @param handle The memory handle
     * @return ByteBuffer view of the memory
     * @throws IllegalArgumentException If the handle is invalid
     */
    public ByteBuffer getByteBuffer(MemoryHandle handle) {
        MemoryBlock block = getBlock(handle);
        ByteBuffer buffer = ByteBuffer.wrap(block.getData());
        buffer.order(ByteOrder.LITTLE_ENDIAN); // Use little-endian by default for cross-language compatibility
        return buffer.asReadOnlyBuffer(); // Return read-only to prevent external modification
    }

    /**
     * Free memory associated with a handle.
     * @param handle The memory handle to free
     * @return true if memory was successfully freed, false if handle was already invalid
     */
    public boolean free(MemoryHandle handle) {
        MemoryBlock block = activeMemoryBlocks.remove(handle.getId());
        if (block != null) {
            updateMemoryPressure(-block.getData().length);
            return true;
        }
        return false;
    }

    /**
     * Free all allocated memory.
     */
    public void freeAll() {
        activeMemoryBlocks.clear();
        allocatedBytes.set(0);
        peakAllocatedBytes.set(0);
    }

    /**
     * Get current memory usage statistics.
     * @return MemoryUsage statistics
     */
    public MemoryUsage getMemoryUsage() {
        return new MemoryUsage(
            allocatedBytes.get(),
            softMemoryLimit,
            hardMemoryLimit,
            peakAllocatedBytes.get()
        );
    }

    /**
     * Check if we're under memory pressure.
     * @return true if under memory pressure, false otherwise
     */
    public boolean isUnderMemoryPressure() {
        int usagePercent = calculateUsagePercent();
        return usagePercent > maxMemoryPressureThreshold;
    }

    /**
     * Add a listener for memory pressure events.
     * @param listener The listener to add
     */
    public void addMemoryPressureListener(MemoryPressureListener listener) {
        pressureListeners.add(listener);
    }

    /**
     * Remove a memory pressure listener.
     * @param listener The listener to remove
     */
    public void removeMemoryPressureListener(MemoryPressureListener listener) {
        pressureListeners.remove(listener);
    }

    /**
     * Internal method to get a memory block (package-private for testing).
     * @param handle The memory handle
     * @return The memory block
     * @throws IllegalArgumentException If the handle is invalid
     */
    MemoryBlock getBlock(MemoryHandle handle) {
        MemoryBlock block = activeMemoryBlocks.get(handle.getId());
        if (block == null) {
            throw new IllegalArgumentException("Invalid memory handle: " + handle.getId());
        }
        return block;
    }

    /**
     * Update memory pressure statistics.
     * @param delta The change in allocated bytes (positive for allocation, negative for deallocation)
     */
    private void updateMemoryPressure(int delta) {
        int newAllocated = allocatedBytes.addAndGet(delta);
        int currentPeak = peakAllocatedBytes.get();
        if (newAllocated > currentPeak) {
            peakAllocatedBytes.set(newAllocated);
        }
    }

    /**
     * Check memory pressure and notify listeners if needed.
     */
    private void checkMemoryPressure() {
        int usagePercent = calculateUsagePercent();
        if (usagePercent > maxMemoryPressureThreshold) {
            notifyPressureListeners(usagePercent);
        }
    }

    /**
     * Calculate current memory usage percentage.
     * @return Memory usage percentage
     */
    private int calculateUsagePercent() {
        int allocated = allocatedBytes.get();
        if (allocated == 0) return 0;
        return (int) ((double) allocated / softMemoryLimit * 100);
    }

    /**
     * Notify all pressure listeners of current memory pressure.
     * @param usagePercent Current memory usage percentage
     */
    private void notifyPressureListeners(int usagePercent) {
        for (MemoryPressureListener listener : pressureListeners) {
            listener.onMemoryPressure(usagePercent, getMemoryUsage());
        }
    }

    /**
     * Handle for managing allocated memory with automatic cleanup.
     * Implements AutoCloseable for use with try-with-resources.
     */
    public static final class MemoryHandle implements AutoCloseable {
        private final long id;

        MemoryHandle(long id) {
            this.id = id;
        }

        /**
         * Get the unique ID of this memory handle.
         * @return Memory handle ID
         */
        public long getId() {
            return id;
        }

        /**
         * Free the memory associated with this handle.
         * Automatically called when used with try-with-resources.
         */
        @Override
        public void close() {
            RustMemoryManager.getInstance().free(this);
        }

        /**
         * Convert to string representation.
         * @return String representation
         */
        @Override
        public String toString() {
            return "MemoryHandle{" + "id=" + id + '}';
        }
    }

    /**
     * Internal class representing a block of allocated memory.
     */
    static final class MemoryBlock {
        private final long id;
        private final byte[] data;

        MemoryBlock(long id, byte[] data) {
            this.id = id;
            this.data = data;
        }

        /**
         * Get the data stored in this memory block.
         * @return The data bytes
         */
        byte[] getData() {
            return data;
        }

        /**
         * Get the ID of this memory block.
         * @return Memory block ID
         */
        long getId() {
            return id;
        }
    }

    /**
     * Record containing memory usage statistics.
     */
    public static final class MemoryUsage {
        private final int allocatedBytes;
        private final int softLimit;
        private final int hardLimit;
        private final int peakUsage;

        MemoryUsage(int allocatedBytes, int softLimit, int hardLimit, int peakUsage) {
            this.allocatedBytes = allocatedBytes;
            this.softLimit = softLimit;
            this.hardLimit = hardLimit;
            this.peakUsage = peakUsage;
        }

        /**
         * Get currently allocated bytes.
         * @return Allocated bytes
         */
        public int getAllocatedBytes() {
            return allocatedBytes;
        }

        /**
         * Get soft memory limit.
         * @return Soft limit in bytes
         */
        public int getSoftLimit() {
            return softLimit;
        }

        /**
         * Get hard memory limit.
         * @return Hard limit in bytes
         */
        public int getHardLimit() {
            return hardLimit;
        }

        /**
         * Get peak memory usage.
         * @return Peak usage in bytes
         */
        public int getPeakUsage() {
            return peakUsage;
        }

        /**
         * Calculate current usage percentage relative to soft limit.
         * @return Usage percentage
         */
        public int getUsagePercent() {
            if (allocatedBytes == 0) return 0;
            return (int) ((double) allocatedBytes / softLimit * 100);
        }

        /**
         * Check if we're over the soft memory limit.
         * @return true if over soft limit, false otherwise
         */
        public boolean isOverSoftLimit() {
            return allocatedBytes > softLimit;
        }

        /**
         * Check if we're over the hard memory limit.
         * @return true if over hard limit, false otherwise
         */
        public boolean isOverHardLimit() {
            return allocatedBytes > hardLimit;
        }

        /**
         * Convert to string representation.
         * @return String representation
         */
        @Override
        public String toString() {
            return "MemoryUsage{" +
                    "allocatedBytes=" + allocatedBytes +
                    ", softLimit=" + softLimit +
                    ", hardLimit=" + hardLimit +
                    ", peakUsage=" + peakUsage +
                    ", usagePercent=" + getUsagePercent() + "%" +
                    '}';
        }
    }

    /**
     * Listener interface for memory pressure events.
     */
    public interface MemoryPressureListener {
        /**
         * Called when memory pressure is detected.
         * @param usagePercent Current memory usage percentage
         * @param usage Memory usage statistics
         */
        void onMemoryPressure(int usagePercent, MemoryUsage usage);
    }

    /**
     * Builder for creating memory-managed operations.
     */
    public static class OperationBuilder {
        private final RustMemoryManager manager;
        private List<MemoryHandle> handles = new ArrayList<>();

        OperationBuilder(RustMemoryManager manager) {
            this.manager = manager;
        }

        /**
         * Allocate memory for the operation.
         * @param data The data to allocate
         * @return OperationBuilder with allocated memory
         */
        public OperationBuilder allocate(byte[] data) {
            handles.add(manager.allocate(data));
            return this;
        }

        /**
         * Allocate memory for the operation.
         * @param str The string to allocate
         * @return OperationBuilder with allocated memory
         */
        public OperationBuilder allocate(String str) {
            handles.add(manager.allocate(str));
            return this;
        }

        /**
         * Execute the operation with automatic memory cleanup.
         * @param operation The operation to execute
         * @param <T> The return type of the operation
         * @return The result of the operation
         */
        public <T> T execute(Function<List<MemoryHandle>, T> operation) {
            try {
                return operation.apply(handles);
            } finally {
                handles.forEach(MemoryHandle::close);
                handles.clear();
            }
        }
    }

    /**
     * Create a new OperationBuilder for managing memory during operations.
     * @return OperationBuilder instance
     */
    public OperationBuilder operation() {
        return new OperationBuilder(this);
    }
}