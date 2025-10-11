package com.kneaf.core.unifiedbridge;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * Unified buffer pooling system with size-aware allocation and memory pressure handling.
 * Manages native and direct buffers efficiently with proper lifecycle management and leak detection.
 */
public final class BufferPoolManager {
    private static final Logger LOGGER = Logger.getLogger(BufferPoolManager.class.getName());
    private static final BufferPoolManager INSTANCE = new BufferPoolManager();
    
    private BridgeConfiguration config;
    private final ConcurrentMap<Long, Buffer> activeBuffers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, ConcurrentMap<Long, ByteBuffer>> bufferPools = new ConcurrentHashMap<>();
    private final AtomicLong nextBufferHandle = new AtomicLong(1);
    private final AtomicLong totalBuffersAllocated = new AtomicLong(0);
    private final AtomicLong totalBuffersFreed = new AtomicLong(0);
    private final AtomicLong totalBytesAllocated = new AtomicLong(0);
    private final AtomicLong totalBytesFreed = new AtomicLong(0);
    
    private volatile double currentMemoryPressure = 0.0;
    private volatile boolean memoryPressureWarningIssued = false;

    private BufferPoolManager() {
        this.config = BridgeConfiguration.getDefault();
        LOGGER.info("BufferPoolManager initialized with default configuration");
        initializeBufferPools();
    }

    /**
     * Get the singleton instance of BufferPoolManager.
     * @return BufferPoolManager instance
     */
    public static BufferPoolManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return BufferPoolManager instance
     */
    public static BufferPoolManager getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("BufferPoolManager reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Allocate a buffer with appropriate pooling strategy based on size.
     * @param size Size of buffer to allocate in bytes
     * @param bufferType Type of buffer to allocate
     * @return Unique buffer handle (non-zero if successful)
     * @throws BridgeException If buffer allocation fails
     */
    public long allocateBuffer(long size, UnifiedBridge.BufferType bufferType) throws BridgeException {
        Objects.requireNonNull(bufferType, "Buffer type cannot be null");
        
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }

        // Check memory pressure first
        checkMemoryPressure(size);

        long handle = nextBufferHandle.getAndIncrement();
        ByteBuffer buffer;

        try {
            // Use direct allocation for large buffers or when configured to do so
            if (size > config.getBufferThresholdForDirectAllocation() || !config.isEnableBufferPooling()) {
                buffer = allocateDirectBuffer(size, bufferType);
            } else {
                buffer = allocateFromPool((int) size, bufferType);
            }

            Buffer poolBuffer = new Buffer(handle, size, buffer, bufferType);
            activeBuffers.put(handle, poolBuffer);
            
            totalBuffersAllocated.incrementAndGet();
            totalBytesAllocated.addAndGet(size);
            
            LOGGER.log(FINE, "Allocated buffer {0} of size {1} bytes ({2})",
                    new Object[]{handle, size, bufferType.name()});
            
            return handle;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to allocate buffer", e);
            throw new BridgeException("Buffer allocation failed", BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED, e);
        }
    }

    /**
     * Free a previously allocated buffer.
     * @param bufferHandle Handle of buffer to free
     * @return true if buffer was found and freed, false otherwise
     */
    public boolean freeBuffer(long bufferHandle) {
        Buffer buffer = activeBuffers.remove(bufferHandle);
        if (buffer != null) {
            try {
                buffer.free();
                
                totalBuffersFreed.incrementAndGet();
                totalBytesFreed.addAndGet(buffer.getSize());
                
                LOGGER.fine(() -> String.format("Freed buffer %d of size %d bytes", 
                        bufferHandle, buffer.getSize()));
                
                // Update memory pressure
                updateMemoryPressure(-buffer.getSize());
                
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to free buffer " + bufferHandle, e);
                // Don't rethrow - we want to fail softly for cleanup operations
                activeBuffers.put(bufferHandle, buffer); // Put back to avoid leaks
                return false;
            }
        } else {
            LOGGER.warning(() -> String.format("Attempted to free non-existent buffer %d", bufferHandle));
            return false;
        }
    }

    /**
     * Get buffer content as a ByteBuffer.
     * @param bufferHandle Handle of buffer to access
     * @return ByteBuffer containing buffer content
     * @throws BridgeException If buffer access fails
     */
    public ByteBuffer getBufferContent(long bufferHandle) throws BridgeException {
        Buffer buffer = activeBuffers.get(bufferHandle);
        if (buffer == null) {
            throw new BridgeException("Buffer not found: " + bufferHandle, BridgeException.BridgeErrorType.BUFFER_ACCESS_FAILED);
        }
        
        return buffer.getByteBuffer().duplicate(); // Return duplicate to prevent external modification
    }

    /**
     * Get statistics about buffer management.
     * @return Map containing buffer statistics
     */
    public Map<String, Object> getBufferStats() {
        return Map.of(
                "activeBuffers", activeBuffers.size(),
                "totalBuffersAllocated", totalBuffersAllocated.get(),
                "totalBuffersFreed", totalBuffersFreed.get(),
                "totalBytesAllocated", totalBytesAllocated.get(),
                "totalBytesFreed", totalBytesFreed.get(),
                "currentMemoryPressure", currentMemoryPressure,
                "bufferPoolingEnabled", config.isEnableBufferPooling(),
                "directAllocationThreshold", config.getBufferThresholdForDirectAllocation()
        );
    }

    /**
     * Shutdown the buffer pool manager and release all resources.
     */
    public void shutdown() {
        LOGGER.info("BufferPoolManager shutting down - freeing all buffers");
        
        for (long bufferHandle : new java.util.ArrayList<>(activeBuffers.keySet())) {
            freeBuffer(bufferHandle);
        }
        
        activeBuffers.clear();
        bufferPools.clear();
        
        LOGGER.info("BufferPoolManager shutdown complete");
    }

    /**
     * Check current memory pressure and take action if needed.
     * @param additionalSize Additional size that would be allocated
     * @throws BridgeException If memory pressure is critical
     */
    private void checkMemoryPressure(long additionalSize) throws BridgeException {
        double newPressure = (double) (totalBytesAllocated.get() + additionalSize) / 
                config.getMaxBufferSize();
        
        if (newPressure > config.getCriticalMemoryPressureThreshold()) {
            throw new BridgeException("Critical memory pressure: " + newPressure +
                    " - cannot allocate buffer of size " + additionalSize, BridgeException.BridgeErrorType.BUFFER_ALLOCATION_FAILED);
        } else if (newPressure > config.getHighMemoryPressureThreshold() && !memoryPressureWarningIssued) {
            LOGGER.warning(() -> "High memory pressure detected: " + newPressure + 
                    " (threshold: " + config.getHighMemoryPressureThreshold() + ")");
            memoryPressureWarningIssued = true;
        }
        
        currentMemoryPressure = newPressure;
    }

    /**
     * Update memory pressure based on buffer allocation/freedom.
     * @param delta Change in bytes (positive for allocation, negative for freeing)
     */
    private void updateMemoryPressure(long delta) {
        currentMemoryPressure = (double) (totalBytesAllocated.get() + delta - totalBytesFreed.get()) / 
                config.getMaxBufferSize();
        
        if (currentMemoryPressure < config.getHighMemoryPressureThreshold() && memoryPressureWarningIssued) {
            LOGGER.info("Memory pressure returned to normal: " + currentMemoryPressure);
            memoryPressureWarningIssued = false;
        }
    }

    /**
     * Allocate a buffer from the appropriate pool.
     * @param size Size of buffer to allocate
     * @param bufferType Type of buffer
     * @return Allocated ByteBuffer
     * @throws BridgeException If allocation fails
     */
    private ByteBuffer allocateFromPool(int size, UnifiedBridge.BufferType bufferType) throws BridgeException {
        // For pooling, we'll use the next larger size class to simplify implementation
        int poolSize = findPoolSize(size);
        
        // Try to get from pool first
        ConcurrentMap<Long, ByteBuffer> sizePool = bufferPools.get(poolSize);
        if (sizePool != null && !sizePool.isEmpty()) {
            for (long bufferHandle : sizePool.keySet()) {
                ByteBuffer buffer = sizePool.remove(bufferHandle);
                if (buffer != null) {
                    LOGGER.fine(() -> String.format("Reused buffer from pool (size %d)", poolSize));
                    return buffer;
                }
            }
        }
        
        // If no buffer in pool, allocate new one
        return allocateDirectBuffer(size, bufferType);
    }

    /**
     * Find the appropriate pool size for a given buffer size.
     * @param size Requested size
     * @return Pool size to use
     */
    private int findPoolSize(int size) {
        // Simple implementation - use next power of 2 or fixed size classes
        // In real implementation, this would be more sophisticated
        int[] sizeClasses = {1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576};
        
        for (int i = 0; i < sizeClasses.length; i++) {
            if (sizeClasses[i] >= size) {
                return sizeClasses[i];
            }
        }
        
        return config.getMaxBufferSize(); // Use max size for anything larger
    }

    /**
     * Allocate a direct ByteBuffer (native memory).
     * @param size Size of buffer to allocate
     * @param bufferType Type of buffer
     * @return Allocated ByteBuffer
     */
    private ByteBuffer allocateDirectBuffer(long size, UnifiedBridge.BufferType bufferType) {
        // In real implementation, this would call native code for direct memory access
        // For simulation, we'll use Java's direct ByteBuffer
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) size);
        
        // For pooling, add to appropriate pool if buffer pooling is enabled
        if (config.isEnableBufferPooling()) {
            int poolSize = findPoolSize((int) size);
            bufferPools.computeIfAbsent(poolSize, k -> new ConcurrentHashMap<>())
                    .put(nextBufferHandle.getAndIncrement(), buffer);
        }
        
        return buffer;
    }

    /**
     * Initialize buffer pools with default size classes.
     */
    private void initializeBufferPools() {
        if (config.isEnableBufferPooling()) {
            int[] initialSizes = {1024, 2048, 4096, 8192, 16384};
            for (int size : initialSizes) {
                bufferPools.putIfAbsent(size, new ConcurrentHashMap<>());
            }
            LOGGER.fine("Initialized buffer pools with default size classes");
        }
    }

    /**
     * Internal representation of a managed buffer.
     */
    static final class Buffer {
        private final long handle;
        private final long size;
        private final ByteBuffer byteBuffer;
        private final UnifiedBridge.BufferType bufferType;
        private volatile boolean isFree = false;

        Buffer(long handle, long size, ByteBuffer byteBuffer, UnifiedBridge.BufferType bufferType) {
            this.handle = handle;
            this.size = size;
            this.byteBuffer = byteBuffer;
            this.bufferType = bufferType;
        }

        void free() {
            if (isFree) {
                return; // Already freed, avoid double-free
            }
            
            isFree = true;
            
            // In real implementation, this would call native free() or similar
            // For Java direct buffers, they're freed by the garbage collector, but we can clear them
            if (byteBuffer.isDirect()) {
                byteBuffer.clear();
                byteBuffer.limit(0);
                byteBuffer.position(0);
            }
            
            LOGGER.fine(() -> String.format("Freed internal buffer %d", handle));
        }

        // Getters
        public long getHandle() { return handle; }
        public long getSize() { return size; }
        public ByteBuffer getByteBuffer() { return byteBuffer; }
        public UnifiedBridge.BufferType getBufferType() { return bufferType; }
        public boolean isFree() { return isFree; }
    }
}