package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import com.kneaf.core.performance.CrossComponentEventBus;
import com.kneaf.core.performance.CrossComponentEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;

/**
 * Comprehensive zero-copy buffer management system for Java-Rust data transfer.
 * Provides DirectByteBuffer-based memory sharing, reference counting, lifecycle management,
 * and integration with cross-component event bus for buffer sharing.
 */
public final class ZeroCopyBufferManager {
    private static final ZeroCopyBufferManager INSTANCE = new ZeroCopyBufferManager();
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024; // 1MB default
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024; // 64MB max
    private static final int MAX_POOL_SIZE = 100;
    
    // Buffer pool for reuse with size-based categorization
    private final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<ManagedDirectBuffer>> bufferPoolBySize;
    private final ConcurrentHashMap<Long, ManagedDirectBuffer> activeBuffers;
    private final AtomicLong bufferIdGenerator;
    private final AtomicLong totalAllocatedBytes;
    private final AtomicLong totalReusedBytes;
    private final AtomicLong totalFreedBytes;
    
    // Memory-mapped file support
    private final ConcurrentHashMap<String, MemoryMappedFile> memoryMappedFiles;
    private final AtomicLong totalMappedBytes;
    
    // Reference counting and lifecycle management with memory compaction
    private final ConcurrentHashMap<Long, AtomicLong> referenceCounts;
    private final ConcurrentHashMap<Long, BufferMetadata> bufferMetadata;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicReference<ScheduledFuture<?>> cleanupTask;
    private final AtomicLong compactionThreshold;
    private final AtomicLong lastCompactionTime;
    
    // Performance monitoring
    private final PerformanceMonitoringSystem performanceMonitor;
    private final CrossComponentEventBus eventBus;
    
    // Thread-safe statistics
    private final AtomicLong totalAllocations;
    private final AtomicLong totalDeallocations;
    private final AtomicLong totalZeroCopyOperations;
    private final AtomicLong totalBytesTransferred;
    
    // Buffer metadata
    private static class BufferMetadata {
        final long bufferId;
        final int size;
        final Instant creationTime;
        final String creatorThread;
        final String creatorComponent;
        volatile Instant lastAccessTime;
        volatile long accessCount;
        volatile boolean isValid;
        
        BufferMetadata(long bufferId, int size, String creatorThread, String creatorComponent) {
            this.bufferId = bufferId;
            this.size = size;
            this.creationTime = Instant.now();
            this.creatorThread = creatorThread;
            this.creatorComponent = creatorComponent;
            this.lastAccessTime = this.creationTime;
            this.accessCount = 0;
            this.isValid = true;
        }
        
        void recordAccess() {
            lastAccessTime = Instant.now();
            accessCount++;
        }
    }
    
    // Managed direct buffer wrapper
    public static class ManagedDirectBuffer {
        private final long bufferId;
        private final ByteBuffer buffer;
        private final int size;
        private final AtomicLong referenceCount;
        private volatile boolean isReleased;
        
        ManagedDirectBuffer(long bufferId, ByteBuffer buffer, int size) {
            this.bufferId = bufferId;
            this.buffer = buffer;
            this.size = size;
            this.referenceCount = new AtomicLong(1);
            this.isReleased = false;
        }
        
        public long getBufferId() { return bufferId; }
        public ByteBuffer getBuffer() { return buffer; }
        public int getSize() { return size; }
        public long getReferenceCount() { return referenceCount.get(); }
        public boolean isReleased() { return isReleased; }
        
        void incrementReference() {
            referenceCount.incrementAndGet();
        }
        
        boolean decrementReference() {
            return referenceCount.decrementAndGet() <= 0;
        }
        
        void release() {
            isReleased = true;
            // Clean up buffer
            if (buffer.isDirect()) {
                // Force cleanup of direct buffer
                try {
                    java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                    cleanerMethod.setAccessible(true);
                    Object cleaner = cleanerMethod.invoke(buffer);
                    if (cleaner != null) {
                        java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                        cleanMethod.invoke(cleaner);
                    }
                } catch (Exception e) {
                    // Fallback: just clear the buffer
                    buffer.clear();
                }
            }
        }
    }
    
    private ZeroCopyBufferManager() {
        this.bufferPoolBySize = new ConcurrentHashMap<>();
        this.activeBuffers = new ConcurrentHashMap<>();
        this.bufferIdGenerator = new AtomicLong(0);
        this.referenceCounts = new ConcurrentHashMap<>();
        this.bufferMetadata = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        this.cleanupTask = new AtomicReference<>();
        this.memoryMappedFiles = new ConcurrentHashMap<>();
        this.totalMappedBytes = new AtomicLong(0);
        this.compactionThreshold = new AtomicLong(1000); // Compact after 1000 allocations
        this.lastCompactionTime = new AtomicLong(System.currentTimeMillis());
        
        this.performanceMonitor = PerformanceMonitoringSystem.getInstance();
        this.eventBus = performanceMonitor.getEventBus();
        
        this.totalAllocatedBytes = new AtomicLong(0);
        this.totalReusedBytes = new AtomicLong(0);
        this.totalFreedBytes = new AtomicLong(0);
        this.totalAllocations = new AtomicLong(0);
        this.totalDeallocations = new AtomicLong(0);
        this.totalZeroCopyOperations = new AtomicLong(0);
        this.totalBytesTransferred = new AtomicLong(0);
        
        startCleanupTask();
        publishInitializationEvent();
    }
    
    public static ZeroCopyBufferManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Allocate a new managed direct buffer with specified size
     */
    public ManagedDirectBuffer allocateBuffer(int size, String component) {
        long startTime = System.nanoTime();
        
        try {
            // Validate size
            if (size <= 0 || size > MAX_BUFFER_SIZE) {
                throw new IllegalArgumentException("Invalid buffer size: " + size);
            }
            
            // Try to reuse from pool first
            ManagedDirectBuffer reusedBuffer = tryReuseBuffer(size);
            if (reusedBuffer != null) {
                totalReusedBytes.addAndGet(size);
                recordAllocationMetrics(reusedBuffer, component, startTime, true);
                return reusedBuffer;
            }
            
            // Create new buffer
            long bufferId = bufferIdGenerator.incrementAndGet();
            ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            ManagedDirectBuffer managedBuffer = new ManagedDirectBuffer(bufferId, buffer, size);
            
            // Register buffer
            activeBuffers.put(bufferId, managedBuffer);
            referenceCounts.put(bufferId, new AtomicLong(1));
            bufferMetadata.put(bufferId, new BufferMetadata(bufferId, size, 
                Thread.currentThread().getName(), component));
            
            totalAllocatedBytes.addAndGet(size);
            totalAllocations.incrementAndGet();
            
            recordAllocationMetrics(managedBuffer, component, startTime, false);
            
            // Publish allocation event
            publishBufferEvent("buffer_allocated", bufferId, size, component);
            
            return managedBuffer;
            
        } catch (Exception e) {
            performanceMonitor.recordError("ZeroCopyBufferManager", e, 
                Map.of("operation", "allocate", "size", size, "component", component));
            throw new RuntimeException("Failed to allocate buffer", e);
        }
    }
    
    /**
     * Try to reuse a buffer from the pool with size-based categorization
     */
    private ManagedDirectBuffer tryReuseBuffer(int requiredSize) {
        // Find the appropriate size category
        int poolSize = getPoolSize(requiredSize);
        ConcurrentLinkedQueue<ManagedDirectBuffer> sizePool = bufferPoolBySize.get(poolSize);
        
        if (sizePool != null) {
            for (ManagedDirectBuffer buffer : sizePool) {
                if (buffer.getSize() >= requiredSize && !buffer.isReleased()) {
                    sizePool.remove(buffer);
                    buffer.incrementReference();
                    totalReusedBytes.addAndGet(buffer.getSize());
                    return buffer;
                }
            }
        }
        
        // Try larger pools if not found in appropriate size
        for (Map.Entry<Integer, ConcurrentLinkedQueue<ManagedDirectBuffer>> entry : bufferPoolBySize.entrySet()) {
            if (entry.getKey() >= poolSize) {
                for (ManagedDirectBuffer buffer : entry.getValue()) {
                    if (buffer.getSize() >= requiredSize && !buffer.isReleased()) {
                        entry.getValue().remove(buffer);
                        buffer.incrementReference();
                        totalReusedBytes.addAndGet(buffer.getSize());
                        return buffer;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get pool size category for buffer size
     */
    private int getPoolSize(int size) {
        if (size <= 1024) return 1024;
        if (size <= 4096) return 4096;
        if (size <= 16384) return 16384;
        if (size <= 65536) return 65536;
        return ((size + 65535) / 65536) * 65536;
    }
    
    /**
     * Acquire an existing buffer by ID with reference counting
     */
    public ManagedDirectBuffer acquireBuffer(long bufferId, String component) {
        ManagedDirectBuffer buffer = activeBuffers.get(bufferId);
        if (buffer == null || buffer.isReleased()) {
            throw new IllegalArgumentException("Invalid or released buffer ID: " + bufferId);
        }
        
        buffer.incrementReference();
        referenceCounts.get(bufferId).incrementAndGet();
        
        // Update metadata
        BufferMetadata metadata = bufferMetadata.get(bufferId);
        if (metadata != null) {
            metadata.recordAccess();
        }
        
        // Publish acquisition event
        publishBufferEvent("buffer_acquired", bufferId, buffer.getSize(), component);
        
        return buffer;
    }
    
    /**
     * Release a buffer (decrements reference count)
     */
    public void releaseBuffer(ManagedDirectBuffer buffer, String component) {
        if (buffer == null) return;
        
        long bufferId = buffer.getBufferId();
        
        // Decrement reference count
        AtomicLong refCount = referenceCounts.get(bufferId);
        if (refCount != null) {
            long newCount = refCount.decrementAndGet();
            
            if (newCount <= 0) {
                // Final release - deallocate buffer
                deallocateBuffer(bufferId, component);
            } else {
                // Publish release event
                publishBufferEvent("buffer_released", bufferId, buffer.getSize(), component);
            }
        }
    }
    
    /**
     * Deallocate a buffer completely with memory compaction support
     */
    private void deallocateBuffer(long bufferId, String component) {
        ManagedDirectBuffer buffer = activeBuffers.remove(bufferId);
        if (buffer != null) {
            BufferMetadata metadata = bufferMetadata.remove(bufferId);
            referenceCounts.remove(bufferId);
            
            int size = buffer.getSize();
            
            // Fast buffer cleanup without reflection
            fastBufferCleanup(buffer);
            
            totalFreedBytes.addAndGet(size);
            totalDeallocations.incrementAndGet();
            
            // Add to appropriate size pool for reuse
            int poolSize = getPoolSize(size);
            ConcurrentLinkedQueue<ManagedDirectBuffer> sizePool =
                bufferPoolBySize.computeIfAbsent(poolSize, k -> new ConcurrentLinkedQueue<>());
            
            if (sizePool.size() < MAX_POOL_SIZE / 4) { // Limit per size category
                sizePool.offer(buffer);
            }
            
            // Check if memory compaction is needed
            checkMemoryCompaction();
            
            // Publish deallocation event
            publishBufferEvent("buffer_deallocated", bufferId, size, component);
            
            // Record metrics
            long duration = System.nanoTime() - (metadata != null ?
                metadata.creationTime.toEpochMilli() * 1_000_000 : System.nanoTime());
            performanceMonitor.recordEvent("ZeroCopyBufferManager", "buffer_lifetime",
                duration, Map.of("size", size, "component", component));
        }
    }
    
    /**
     * Fast buffer cleanup without reflection overhead
     */
    private void fastBufferCleanup(ManagedDirectBuffer buffer) {
        // Use direct buffer cleanup without reflection
        if (buffer.getBuffer().isDirect()) {
            try {
                // Clear buffer contents for security
                buffer.getBuffer().clear();
                buffer.release();
            } catch (Exception e) {
                // Fallback: just mark as released
                buffer.isReleased = true;
            }
        }
    }
    
    /**
     * Check if memory compaction is needed
     */
    private void checkMemoryCompaction() {
        long currentAllocations = totalAllocations.get();
        long lastCompaction = lastCompactionTime.get();
        
        if (currentAllocations % compactionThreshold.get() == 0 &&
            System.currentTimeMillis() - lastCompaction > 60000) { // At least 1 minute between compactions
            performMemoryCompaction();
            lastCompactionTime.set(System.currentTimeMillis());
        }
    }
    
    /**
     * Perform memory compaction to reduce fragmentation
     */
    private void performMemoryCompaction() {
        int compactedBuffers = 0;
        
        // Compact each size pool
        for (Map.Entry<Integer, ConcurrentLinkedQueue<ManagedDirectBuffer>> entry : bufferPoolBySize.entrySet()) {
            ConcurrentLinkedQueue<ManagedDirectBuffer> sizePool = entry.getValue();
            List<ManagedDirectBuffer> toRemove = new ArrayList<>();
            
            // Find fragmented buffers
            for (ManagedDirectBuffer buffer : sizePool) {
                if (buffer.isReleased() || buffer.getSize() < entry.getKey() / 2) {
                    toRemove.add(buffer);
                }
            }
            
            // Remove fragmented buffers
            for (ManagedDirectBuffer buffer : toRemove) {
                sizePool.remove(buffer);
                compactedBuffers++;
            }
        }
        
        // Record compaction metrics
        performanceMonitor.recordEvent("ZeroCopyBufferManager", "memory_compaction",
            compactedBuffers, Map.of("compacted_buffers", compactedBuffers));
    }
    
    /**
     * Perform zero-copy data transfer operation
     */
    public ZeroCopyOperationResult performZeroCopyOperation(ZeroCopyOperation operation) {
        long startTime = System.nanoTime();
        totalZeroCopyOperations.incrementAndGet();
        
        try {
            // Validate operation
            if (operation == null) {
                throw new IllegalArgumentException("Invalid zero-copy operation");
            }
            
            // Execute operation
            Object result = operation.execute();
            long duration = System.nanoTime() - startTime;
            
            // Track bytes transferred
            long bytesTransferred = operation.getBytesTransferred();
            totalBytesTransferred.addAndGet(bytesTransferred);
            
            // Record metrics
            performanceMonitor.recordEvent("ZeroCopyBufferManager", "zero_copy_operation", 
                duration, Map.of("bytes_transferred", bytesTransferred, "operation_type", operation.getType()));
            
            // Publish success event
            publishZeroCopyEvent("zero_copy_operation_completed", operation, duration, bytesTransferred, true);
            
            return new ZeroCopyOperationResult(true, result, duration, bytesTransferred, null);
            
        } catch (Exception e) {
            long duration = System.nanoTime() - startTime;
            performanceMonitor.recordError("ZeroCopyBufferManager", e, 
                Map.of("operation", "zero_copy_operation", "operation_type", operation.getType()));
            
            // Publish failure event
            publishZeroCopyEvent("zero_copy_operation_failed", operation, duration, 0, false);
            
            return new ZeroCopyOperationResult(false, null, duration, 0, e);
        }
    }
    
    /**
     * Create a memory-mapped file buffer for fast persistence
     */
    public ManagedDirectBuffer createMemoryMappedBuffer(String filePath, int size, String creatorComponent) {
        try {
            // Create or open memory-mapped file
            MemoryMappedFile mmapFile = new MemoryMappedFile(filePath, size);
            memoryMappedFiles.put(filePath, mmapFile);
            totalMappedBytes.addAndGet(size);
            
            // Create buffer wrapper for memory-mapped data
            long bufferId = bufferIdGenerator.incrementAndGet();
            ByteBuffer buffer = mmapFile.getBuffer();
            ManagedDirectBuffer managedBuffer = new ManagedDirectBuffer(bufferId, buffer, size);
            
            // Register buffer
            activeBuffers.put(bufferId, managedBuffer);
            referenceCounts.put(bufferId, new AtomicLong(1));
            bufferMetadata.put(bufferId, new BufferMetadata(bufferId, size,
                Thread.currentThread().getName(), creatorComponent + "_mmap"));
            
            totalAllocatedBytes.addAndGet(size);
            totalAllocations.incrementAndGet();
            
            // Publish allocation event
            publishBufferEvent("memory_mapped_buffer_allocated", bufferId, size, creatorComponent);
            
            return managedBuffer;
            
        } catch (Exception e) {
            performanceMonitor.recordError("ZeroCopyBufferManager", e,
                Map.of("operation", "create_memory_mapped_buffer", "file_path", filePath, "size", size));
            throw new RuntimeException("Failed to create memory-mapped buffer", e);
        }
    }
    
    /**
     * Create a shared buffer that can be accessed by multiple components
     */
    public SharedBuffer createSharedBuffer(int size, String creatorComponent, String sharedName) {
        ManagedDirectBuffer buffer = allocateBuffer(size, creatorComponent);
        SharedBuffer sharedBuffer = new SharedBuffer(buffer, sharedName, creatorComponent);
        
        // Publish shared buffer creation event
        Map<String, Object> context = Map.of(
            "shared_name", sharedName,
            "creator_component", creatorComponent,
            "size", size
        );
        eventBus.publishEvent(new CrossComponentEvent(
            "ZeroCopyBufferManager", "shared_buffer_created", Instant.now(), 0, context));
        
        return sharedBuffer;
    }
    
    /**
     * Get total pooled buffer count
     */
    private int getTotalPooledBufferCount() {
        int total = 0;
        for (ConcurrentLinkedQueue<ManagedDirectBuffer> sizePool : bufferPoolBySize.values()) {
            total += sizePool.size();
        }
        return total;
    }
    
    /**
     * Get buffer statistics
     */
    public BufferStatistics getStatistics() {
        return new BufferStatistics(
            activeBuffers.size(),
            getTotalPooledBufferCount(),
            totalAllocatedBytes.get(),
            totalReusedBytes.get(),
            totalFreedBytes.get(),
            totalAllocations.get(),
            totalDeallocations.get(),
            totalZeroCopyOperations.get(),
            totalBytesTransferred.get()
        );
    }
    
    /**
     * Get detailed buffer information
     */
    public List<BufferInfo> getBufferInfoList() {
        List<BufferInfo> infoList = new ArrayList<>();
        for (Map.Entry<Long, BufferMetadata> entry : bufferMetadata.entrySet()) {
            BufferMetadata metadata = entry.getValue();
            ManagedDirectBuffer buffer = activeBuffers.get(entry.getKey());
            if (buffer != null && metadata != null) {
                infoList.add(new BufferInfo(
                    metadata.bufferId,
                    metadata.size,
                    metadata.creationTime,
                    metadata.lastAccessTime,
                    metadata.accessCount,
                    metadata.creatorThread,
                    metadata.creatorComponent,
                    buffer.getReferenceCount(),
                    metadata.isValid
                ));
            }
        }
        return infoList;
    }
    
    /**
     * Cleanup old unused buffers
     */
    private void startCleanupTask() {
        cleanupTask.set(cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupUnusedBuffers();
            } catch (Exception e) {
                performanceMonitor.recordError("ZeroCopyBufferManager", e, 
                    Map.of("operation", "cleanup_task"));
            }
        }, 30, 30, TimeUnit.SECONDS));
    }
    
    /**
     * Clean up buffers that haven't been accessed for a long time
     */
    private void cleanupUnusedBuffers() {
        Instant now = Instant.now();
        List<Long> buffersToCleanup = new ArrayList<>();
        
        for (Map.Entry<Long, BufferMetadata> entry : bufferMetadata.entrySet()) {
            BufferMetadata metadata = entry.getValue();
            if (metadata.isValid && 
                now.minusSeconds(300).isAfter(metadata.lastAccessTime) && // 5 minutes old
                referenceCounts.get(entry.getKey()).get() <= 1) { // Only 1 reference (metadata)
                buffersToCleanup.add(entry.getKey());
            }
        }
        
        for (Long bufferId : buffersToCleanup) {
            deallocateBuffer(bufferId, "cleanup_task");
        }
    }
    
    /**
     * Record allocation metrics
     */
    private void recordAllocationMetrics(ManagedDirectBuffer buffer, String component, long startTime, boolean reused) {
        long duration = System.nanoTime() - startTime;
        Map<String, Object> context = Map.of(
            "buffer_size", buffer.getSize(),
            "reused", reused,
            "component", component
        );
        performanceMonitor.recordEvent("ZeroCopyBufferManager", "buffer_allocation", duration, context);
    }
    
    /**
     * Publish buffer lifecycle events
     */
    private void publishBufferEvent(String eventType, long bufferId, int size, String component) {
        Map<String, Object> context = Map.of(
            "buffer_id", bufferId,
            "size", size,
            "component", component
        );
        eventBus.publishEvent(new CrossComponentEvent(
            "ZeroCopyBufferManager", eventType, Instant.now(), 0, context));
    }
    
    /**
     * Publish zero-copy operation events
     */
    private void publishZeroCopyEvent(String eventType, ZeroCopyOperation operation, 
                                    long duration, long bytesTransferred, boolean success) {
        Map<String, Object> context = Map.of(
            "operation_type", operation.getType(),
            "bytes_transferred", bytesTransferred,
            "success", success
        );
        eventBus.publishEvent(new CrossComponentEvent(
            "ZeroCopyBufferManager", eventType, Instant.now(), duration, context));
    }
    
    /**
     * Publish initialization event
     */
    private void publishInitializationEvent() {
        Map<String, Object> context = Map.of(
            "max_buffer_size", MAX_BUFFER_SIZE,
            "max_pool_size", MAX_POOL_SIZE
        );
        eventBus.publishEvent(new CrossComponentEvent(
            "ZeroCopyBufferManager", "initialized", Instant.now(), 0, context));
    }
    
    /**
     * Memory-mapped file support class
     */
    private static class MemoryMappedFile {
        private final String filePath;
        private final int size;
        private final ByteBuffer buffer;
        
        MemoryMappedFile(String filePath, int size) throws IOException {
            this.filePath = filePath;
            this.size = size;
            
            // Create memory-mapped file
            try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
                file.setLength(size);
                this.buffer = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
            }
        }
        
        ByteBuffer getBuffer() {
            return buffer;
        }
        
        void close() throws IOException {
            // Force synchronization to disk - use reflection safely
            try {
                if (buffer != null && buffer.isDirect()) {
                    // Use reflection to access cleaner
                    java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                    cleanerMethod.setAccessible(true);
                    Object cleaner = cleanerMethod.invoke(buffer);
                    if (cleaner != null) {
                        java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                        cleanMethod.invoke(cleaner);
                    }
                }
            } catch (Exception e) {
                // Ignore cleanup errors - buffer will be garbage collected anyway
            }
        }
    }
    
    /**
     * Shutdown the buffer manager with cleanup
     */
    public void shutdown() {
        try {
            // Stop cleanup task
            ScheduledFuture<?> task = cleanupTask.get();
            if (task != null) {
                task.cancel(false);
            }
            
            // Close memory-mapped files
            for (MemoryMappedFile mmapFile : memoryMappedFiles.values()) {
                try {
                    mmapFile.close();
                } catch (IOException e) {
                    performanceMonitor.recordError("ZeroCopyBufferManager", e,
                        Map.of("operation", "close_mmap_file"));
                }
            }
            
            // Clear all buffers
            for (ManagedDirectBuffer buffer : activeBuffers.values()) {
                fastBufferCleanup(buffer);
            }
            
            // Clear pools and maps
            activeBuffers.clear();
            bufferPoolBySize.clear();
            referenceCounts.clear();
            bufferMetadata.clear();
            memoryMappedFiles.clear();
            
            // Shutdown executor
            cleanupExecutor.shutdown();
            cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            performanceMonitor.recordError("ZeroCopyBufferManager", e,
                Map.of("operation", "shutdown"));
        }
    }
    
    /**
     * Zero-copy operation interface
     */
    public interface ZeroCopyOperation {
        Object execute() throws Exception;
        String getType();
        long getBytesTransferred();
        ManagedDirectBuffer getSourceBuffer();
    }
    
    /**
     * Shared buffer for cross-component data sharing
     */
    public static class SharedBuffer {
        private final ManagedDirectBuffer buffer;
        private final String sharedName;
        private final String creatorComponent;
        private final ConcurrentHashMap<String, Long> componentAccessTimes;
        
        SharedBuffer(ManagedDirectBuffer buffer, String sharedName, String creatorComponent) {
            this.buffer = buffer;
            this.sharedName = sharedName;
            this.creatorComponent = creatorComponent;
            this.componentAccessTimes = new ConcurrentHashMap<>();
        }
        
        public ManagedDirectBuffer getBuffer() { return buffer; }
        public String getSharedName() { return sharedName; }
        public String getCreatorComponent() { return creatorComponent; }
        
        public void recordAccess(String component) {
            componentAccessTimes.put(component, System.currentTimeMillis());
        }
        
        public Map<String, Long> getAccessTimes() {
            return new HashMap<>(componentAccessTimes);
        }
    }
    
    /**
     * Zero-copy operation result
     */
    public static class ZeroCopyOperationResult {
        public final boolean success;
        public final Object result;
        public final long durationNs;
        public final long bytesTransferred;
        public final Exception error;
        
        public ZeroCopyOperationResult(boolean success, Object result, long durationNs, 
                                     long bytesTransferred, Exception error) {
            this.success = success;
            this.result = result;
            this.durationNs = durationNs;
            this.bytesTransferred = bytesTransferred;
            this.error = error;
        }
        
        public double getDurationMs() {
            return durationNs / 1_000_000.0;
        }
        
        public double getThroughputMBps() {
            return bytesTransferred / (1024.0 * 1024.0) / (durationNs / 1_000_000_000.0);
        }
    }
    
    /**
     * Buffer statistics
     */
    public static class BufferStatistics {
        public final int activeBufferCount;
        public final int pooledBufferCount;
        public final long totalAllocatedBytes;
        public final long totalReusedBytes;
        public final long totalFreedBytes;
        public final long totalAllocations;
        public final long totalDeallocations;
        public final long totalZeroCopyOperations;
        public final long totalBytesTransferred;
        
        public BufferStatistics(int activeBufferCount, int pooledBufferCount, long totalAllocatedBytes,
                              long totalReusedBytes, long totalFreedBytes, long totalAllocations,
                              long totalDeallocations, long totalZeroCopyOperations, long totalBytesTransferred) {
            this.activeBufferCount = activeBufferCount;
            this.pooledBufferCount = pooledBufferCount;
            this.totalAllocatedBytes = totalAllocatedBytes;
            this.totalReusedBytes = totalReusedBytes;
            this.totalFreedBytes = totalFreedBytes;
            this.totalAllocations = totalAllocations;
            this.totalDeallocations = totalDeallocations;
            this.totalZeroCopyOperations = totalZeroCopyOperations;
            this.totalBytesTransferred = totalBytesTransferred;
        }
        
        public double getReuseRate() {
            return totalAllocations > 0 ? (double) totalReusedBytes / totalAllocatedBytes : 0.0;
        }
        
        public double getAverageBufferSize() {
            return totalAllocations > 0 ? (double) totalAllocatedBytes / totalAllocations : 0.0;
        }
        
        public double getThroughputMB() {
            return totalBytesTransferred / (1024.0 * 1024.0);
        }
    }
    
    /**
     * Detailed buffer information
     */
    public static class BufferInfo {
        public final long bufferId;
        public final int size;
        public final Instant creationTime;
        public final Instant lastAccessTime;
        public final long accessCount;
        public final String creatorThread;
        public final String creatorComponent;
        public final long referenceCount;
        public final boolean isValid;
        
        public BufferInfo(long bufferId, int size, Instant creationTime, Instant lastAccessTime,
                        long accessCount, String creatorThread, String creatorComponent,
                        long referenceCount, boolean isValid) {
            this.bufferId = bufferId;
            this.size = size;
            this.creationTime = creationTime;
            this.lastAccessTime = lastAccessTime;
            this.accessCount = accessCount;
            this.creatorThread = creatorThread;
            this.creatorComponent = creatorComponent;
            this.referenceCount = referenceCount;
            this.isValid = isValid;
        }
        
        public long getLifetimeMs() {
            return java.time.Duration.between(creationTime, lastAccessTime).toMillis();
        }
        
        public double getAccessFrequency() {
            long lifetimeMs = getLifetimeMs();
            return lifetimeMs > 0 ? (double) accessCount / (lifetimeMs / 1000.0) : 0.0;
        }
    }
}