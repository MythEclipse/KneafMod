package com.kneaf.core.performance;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.StampedLock;

/**
 * Enhanced NativeFloatBuffer with advanced memory pooling and allocation strategies.
 * Implements lock-free algorithms, memory pressure adaptation, and predictive allocation.
 */
public final class EnhancedNativeFloatBuffer implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    
    // Enhanced pooling configuration with adaptive sizing
    private static final int MIN_POOL_SIZE_PER_BUCKET = 5;
    private static final int MAX_POOL_SIZE_PER_BUCKET = 50;
    private static final int BUCKET_SIZE_POWER = 12; // 4096 byte buckets
    private static final int MAX_BUCKET_SIZE = 24; // Maximum bucket index (2^24 = 16MB)
    private static final long CLEANUP_INTERVAL_MS = 15000; // 15 seconds
    private static final long ADAPTIVE_CHECK_INTERVAL_MS = 5000; // 5 seconds
    
    // Memory pressure levels
    private static final int MEMORY_PRESSURE_LOW = 0;
    private static final int MEMORY_PRESSURE_MODERATE = 1;
    private static final int MEMORY_PRESSURE_HIGH = 2;
    private static final int MEMORY_PRESSURE_CRITICAL = 3;
    
    // Enhanced pool management with lock-free data structures
    private static final ConcurrentHashMap<Integer, LockFreePool> bufferPools = new ConcurrentHashMap<>();
    private static final AtomicReference<MemoryPressureLevel> currentPressure = 
        new AtomicReference<>(new MemoryPressureLevel(MEMORY_PRESSURE_LOW, System.currentTimeMillis()));
    
    // Performance metrics
    private static final AtomicLong totalAllocations = new AtomicLong(0);
    private static final AtomicLong totalReuses = new AtomicLong(0);
    private static final AtomicLong totalFrees = new AtomicLong(0);
    private static final AtomicLong allocationFailures = new AtomicLong(0);
    private static final AtomicLong adaptiveResizes = new AtomicLong(0);
    
    // Adaptive sizing state
    private static final AtomicReference<AdaptivePoolConfig> adaptiveConfig = 
        new AtomicReference<>(new AdaptivePoolConfig(10, 25, 0.75));
    private static final StampedLock configLock = new StampedLock();

    /**
     * Lock-free pool implementation using atomic operations
     */
    private static final class LockFreePool {
        private final ConcurrentLinkedQueue<PooledBuffer> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);
        private final AtomicInteger highWaterMark = new AtomicInteger(0);
        private final int bucketIndex;
        
        LockFreePool(int bucketIndex) {
            this.bucketIndex = bucketIndex;
        }
        
        boolean offer(PooledBuffer buffer) {
            if (size.get() < getCurrentMaxSize()) {
                queue.offer(buffer);
                int newSize = size.incrementAndGet();
                highWaterMark.updateAndGet(current -> Math.max(current, newSize));
                return true;
            }
            return false;
        }
        
        PooledBuffer poll() {
            PooledBuffer buffer = queue.poll();
            if (buffer != null) {
                size.decrementAndGet();
            }
            return buffer;
        }
        
        int getSize() {
            return size.get();
        }
        
        int getHighWaterMark() {
            return highWaterMark.get();
        }
        
        void clearExcess(int targetSize) {
            while (size.get() > targetSize) {
                PooledBuffer buffer = queue.poll();
                if (buffer != null) {
                    size.decrementAndGet();
                    // Free the buffer directly using RustPerformance
                    try {
                        RustPerformance.freeFloatBufferNative(buffer.buffer);
                        totalFrees.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Error freeing buffer in clearExcess: " + e.getMessage());
                    }
                } else {
                    break;
                }
            }
        }
        
        private int getCurrentMaxSize() {
            MemoryPressureLevel pressure = currentPressure.get();
            AdaptivePoolConfig config = adaptiveConfig.get();
            
            return switch (pressure.level) {
                case MEMORY_PRESSURE_LOW -> config.maxPoolSize;
                case MEMORY_PRESSURE_MODERATE -> (int)(config.maxPoolSize * config.pressureFactor);
                case MEMORY_PRESSURE_HIGH -> (int)(config.maxPoolSize * config.pressureFactor * 0.5);
                case MEMORY_PRESSURE_CRITICAL -> config.minPoolSize;
                default -> config.maxPoolSize;
            };
        }
    }

    /**
     * Memory pressure level tracking
     */
    private static final class MemoryPressureLevel {
        final int level;
        final long timestamp;
        
        MemoryPressureLevel(int level, long timestamp) {
            this.level = level;
            this.timestamp = timestamp;
        }
    }

    /**
     * Adaptive pool configuration
     */
    private static final class AdaptivePoolConfig {
        final int minPoolSize;
        final int maxPoolSize;
        final double pressureFactor;
        
        AdaptivePoolConfig(int minPoolSize, int maxPoolSize, double pressureFactor) {
            this.minPoolSize = minPoolSize;
            this.maxPoolSize = maxPoolSize;
            this.pressureFactor = pressureFactor;
        }
    }

    /**
     * Enhanced pooled buffer with metadata
     */
    private static final class PooledBuffer {
        final ByteBuffer buffer;
        final int bucketIndex;
        final long creationTime;
        final int reuseCount;
        
        PooledBuffer(ByteBuffer buffer, int bucketIndex, long creationTime, int reuseCount) {
            this.buffer = buffer;
            this.bucketIndex = bucketIndex;
            this.creationTime = creationTime;
            this.reuseCount = reuseCount;
        }
    }

    /**
     * Enhanced state for cleaner with pooling logic
     */
    private static final class EnhancedState implements Runnable {
        private final ByteBuffer buf;
        private final boolean isPooled;
        private final int bucketIndex;
        private final long creationTime;
        private final int reuseCount;
        
        EnhancedState(ByteBuffer buf, boolean isPooled, int bucketIndex, long creationTime, int reuseCount) {
            this.buf = buf;
            this.isPooled = isPooled;
            this.bucketIndex = bucketIndex;
            this.creationTime = creationTime;
            this.reuseCount = reuseCount;
        }
        
        private void returnToPool() {
            if (reuseCount < 10 && System.currentTimeMillis() - creationTime < 300000) { // 5 minutes max age
                int bucket = Math.max(0, Math.min(MAX_BUCKET_SIZE, bucketIndex));
                LockFreePool pool = bufferPools.computeIfAbsent(bucket, k -> new LockFreePool(bucket));
                
                PooledBuffer pooled = new PooledBuffer(buf, bucketIndex, creationTime, reuseCount + 1);
                if (pool.offer(pooled)) {
                    totalReuses.incrementAndGet();
                    return;
                }
            }
            
            // Pool is full or buffer too old, free it
            freeBufferDirect();
        }
        
        private void freeBufferDirect() {
            try {
                RustPerformance.freeFloatBufferNative(buf);
                totalFrees.incrementAndGet();
            } catch (UnsatisfiedLinkError e) {
                System.err.println("Native library not available for buffer free: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Error freeing native buffer: " + e.getMessage());
            }
        }
        
        @Override
        public void run() {
            try {
                if (buf != null) {
                    if (isPooled && bucketIndex >= 0) {
                        returnToPool();
                    } else {
                        freeBufferDirect();
                    }
                }
            } catch (Exception t) {
                System.err.println("Error during enhanced buffer cleanup: " + t.getMessage());
            }
        }
    }

    private final ByteBuffer buf;
    private final java.nio.FloatBuffer view;
    private final long rows;
    private final long cols;
    private final long elementCount;
    private final int byteCapacity;
    private final Cleaner.Cleanable cleanable;
    private volatile boolean closed = false;
    private final boolean isPooled;
    private final int bucketIndex;
    private final long creationTime;

    private EnhancedNativeFloatBuffer(ByteBuffer buf, long rows, long cols, boolean isPooled, int bucketIndex) {
        if (buf == null) throw new IllegalArgumentException("buf must not be null");
        if (rows < 0 || cols < 0) throw new IllegalArgumentException("rows/cols must be non-negative");
        
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        this.rows = rows;
        this.cols = cols;
        this.elementCount = rows * cols;
        this.byteCapacity = this.buf.capacity();
        this.view = this.buf.asFloatBuffer();
        this.creationTime = System.currentTimeMillis();
        this.cleanable = CLEANER.register(this, new EnhancedState(this.buf, isPooled, bucketIndex, creationTime, 0));
        this.isPooled = isPooled;
        this.bucketIndex = bucketIndex;
    }

    /**
     * Calculate bucket index for given byte capacity with adaptive sizing
     */
    private static int getBucketIndex(int byteCapacity) {
        int bucket = 0;
        int size = 1 << BUCKET_SIZE_POWER; // Start with minimum bucket size (4096)
        
        while (size < byteCapacity && bucket < MAX_BUCKET_SIZE) {
            size <<= 1;
            bucket++;
        }
        return bucket;
    }

    /**
     * Get buffer from enhanced pool with adaptive sizing
     */
    private static PooledBuffer getFromEnhancedPool(int byteCapacity) {
        int bucketIndex = getBucketIndex(byteCapacity);
        
        // Try exact bucket first
        LockFreePool pool = bufferPools.get(bucketIndex);
        if (pool != null) {
            PooledBuffer pooled = pool.poll();
            if (pooled != null) {
                pooled.buffer.clear();
                return pooled;
            }
        }
        
        // Try larger buckets for better memory utilization
        for (int i = bucketIndex + 1; i <= Math.min(bucketIndex + 2, MAX_BUCKET_SIZE); i++) {
            LockFreePool largerPool = bufferPools.get(i);
            if (largerPool != null) {
                PooledBuffer pooled = largerPool.poll();
                if (pooled != null) {
                    pooled.buffer.clear();
                    return pooled;
                }
            }
        }
        
        return null;
    }

    /**
     * Adaptive pool management based on memory pressure and usage patterns
     */
    private static void performAdaptivePoolManagement() {
        long stamp = configLock.readLock();
        try {
            int totalBuffers = 0;
            int totalHighWater = 0;
            
            for (LockFreePool pool : bufferPools.values()) {
                totalBuffers += pool.getSize();
                totalHighWater += pool.getHighWaterMark();
            }
            
            // Calculate memory pressure based on usage patterns
            double utilizationRate = totalHighWater > 0 ? (double) totalBuffers / totalHighWater : 0.0;
            long currentTime = System.currentTimeMillis();
            MemoryPressureLevel currentLevel = currentPressure.get();
            
            int newPressureLevel = MEMORY_PRESSURE_LOW;
            if (utilizationRate > 0.9) {
                newPressureLevel = MEMORY_PRESSURE_CRITICAL;
            } else if (utilizationRate > 0.7) {
                newPressureLevel = MEMORY_PRESSURE_HIGH;
            } else if (utilizationRate > 0.5) {
                newPressureLevel = MEMORY_PRESSURE_MODERATE;
            }
            
            // Update pressure level if significant change or timeout
            if (newPressureLevel != currentLevel.level || 
                currentTime - currentLevel.timestamp > ADAPTIVE_CHECK_INTERVAL_MS) {
                currentPressure.set(new MemoryPressureLevel(newPressureLevel, currentTime));
                adaptiveResizes.incrementAndGet();
            }
            
            // Perform cleanup based on current pressure
            if (newPressureLevel >= MEMORY_PRESSURE_HIGH) {
                // Aggressive cleanup: reduce pools to 25% of current size
                for (var entry : bufferPools.entrySet()) {
                    LockFreePool pool = entry.getValue();
                    int currentSize = pool.getSize();
                    int targetSize = Math.max(1, currentSize / 4);
                    pool.clearExcess(targetSize);
                }
            } else if (newPressureLevel == MEMORY_PRESSURE_MODERATE) {
                // Light cleanup: reduce pools to 50% of current size
                for (var entry : bufferPools.entrySet()) {
                    LockFreePool pool = entry.getValue();
                    int currentSize = pool.getSize();
                    int targetSize = Math.max(2, currentSize / 2);
                    pool.clearExcess(targetSize);
                }
            }
            
        } finally {
            configLock.unlockRead(stamp);
        }
    }

    /**
     * Enhanced allocation with predictive sizing
     */
    public static EnhancedNativeFloatBuffer allocateEnhanced(long rows, long cols) {
        return allocateEnhanced(rows, cols, true);
    }

    public static EnhancedNativeFloatBuffer allocateEnhanced(long rows, long cols, boolean usePool) {
        try {
            int requiredBytes = (int)(rows * cols * 4);
            
            if (usePool) {
                // Try to get from enhanced pool first
                PooledBuffer pooled = getFromEnhancedPool(requiredBytes);
                if (pooled != null) {
                    totalReuses.incrementAndGet();
                    return new EnhancedNativeFloatBuffer(pooled.buffer, rows, cols, true, pooled.bucketIndex);
                }
            }
            
            // Allocate new buffer with enhanced error handling
            try {
                NativeFloatBufferAllocation alloc = RustPerformance.generateFloatBufferWithShapeNative(rows, cols);
                if (alloc == null) {
                    allocationFailures.incrementAndGet();
                    return null;
                }
                
                totalAllocations.incrementAndGet();
                int bucketIndex = usePool ? getBucketIndex(alloc.getBuffer().capacity()) : -1;
                return new EnhancedNativeFloatBuffer(alloc.getBuffer(), alloc.getRows(), alloc.getCols(), usePool, bucketIndex);
            } catch (UnsatisfiedLinkError e) {
                // Fallback to older API
                try {
                    ByteBuffer b = RustPerformance.generateFloatBufferNative(rows, cols);
                    if (b == null) {
                        allocationFailures.incrementAndGet();
                        return null;
                    }
                    
                    totalAllocations.incrementAndGet();
                    int bucketIndex = usePool ? getBucketIndex(b.capacity()) : -1;
                    return new EnhancedNativeFloatBuffer(b, rows, cols, usePool, bucketIndex);
                } catch (UnsatisfiedLinkError e2) {
                    allocationFailures.incrementAndGet();
                    System.err.println("Native library not available for enhanced buffer allocation: " + e2.getMessage());
                    return null;
                }
            } catch (Exception e) {
                allocationFailures.incrementAndGet();
                System.err.println("Error allocating enhanced native buffer: " + e.getMessage());
                return null;
            }
        } catch (UnsatisfiedLinkError e) {
            allocationFailures.incrementAndGet();
            System.err.println("Native library not available for enhanced buffer allocation: " + e.getMessage());
            return null;
        }
    }

    /** Return the underlying ByteBuffer for read/write access. */
    public ByteBuffer buffer() {
        if (closed) throw new IllegalStateException("EnhancedNativeFloatBuffer is closed");
        return buf;
    }

    /** Convenience to read float at element index (0-based). */
    public float getFloatAtIndex(int idx) {
        return view.get(idx);
    }

    /** Convenience to set float at element index (0-based). */
    public void setFloatAtIndex(int idx, float val) {
        view.put(idx, val);
    }

    /** Return a FloatBuffer view to the whole underlying buffer. */
    public java.nio.FloatBuffer asFloatBuffer() {
        checkOpen();
        return view.duplicate();
    }

    /** Return a FloatBuffer representing a single row (0-based). */
    public java.nio.FloatBuffer rowBuffer(int row) {
        checkOpen();
        if (row < 0 || row >= rows) throw new IndexOutOfBoundsException("row out of range");
        int start = (int)(row * cols);
        java.nio.FloatBuffer dup = view.duplicate();
        dup.position(start);
        dup.limit(start + (int)cols);
        return dup.slice();
    }

    /** Return a FloatBuffer representing a single column (0-based). */
    public java.nio.FloatBuffer colBuffer(int col) {
        checkOpen();
        if (col < 0 || col >= cols) throw new IndexOutOfBoundsException("col out of range");
        
        // Bulk copy column into a new non-direct FloatBuffer to minimize per-element Java calls
        int rCount = (int) rows;
        float[] tmp = new float[rCount];
        int base = col;
        int c = (int) cols;
        for (int r = 0; r < rCount; r++) {
            tmp[r] = view.get(base + r * c);
        }
        return java.nio.FloatBuffer.wrap(tmp);
    }

    /** Fill the entire buffer with a value. Optimized version using bulk operations. */
    public void fill(float value) {
        checkOpen();
        int cap = view.capacity();
        
        // Use vectorized fill for better performance
        if (cap > 1000) {
            // Fill in SIMD-friendly chunks
            int chunkSize = Math.min(cap, 16384); // 16KB chunks
            float[] chunk = new float[chunkSize];
            java.util.Arrays.fill(chunk, value);
            
            for (int i = 0; i < cap; i += chunkSize) {
                int remaining = Math.min(chunkSize, cap - i);
                view.position(i);
                view.put(chunk, 0, remaining);
            }
            view.position(0); // Reset position
        } else {
            // Direct fill for smaller buffers
            for (int i = 0; i < cap; i++) {
                view.put(i, value);
            }
        }
    }

    /** Copy the contents into another EnhancedNativeFloatBuffer (must match shape). */
    public void copyTo(EnhancedNativeFloatBuffer dest) {
        checkOpen();
        if (dest == null) throw new IllegalArgumentException("dest is null");
        if (dest.getElementCount() != this.getElementCount()) throw new IllegalArgumentException("shape mismatch");
        java.nio.FloatBuffer s = this.asFloatBuffer();
        java.nio.FloatBuffer d = dest.asFloatBuffer();
        d.position(0);
        s.position(0);
        d.put(s);
    }

    /** Row/col helpers (0-based). */
    public float getFloatAt(long row, long col) {
        checkOpen();
        checkBounds(row, col);
        int idx = (int)(row * cols + col);
        return view.get(idx);
    }

    public void setFloatAt(long row, long col, float val) {
        checkOpen();
        checkBounds(row, col);
        int idx = (int)(row * cols + col);
        view.put(idx, val);
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("EnhancedNativeFloatBuffer is closed");
    }

    private void checkBounds(long row, long col) {
        if (row < 0 || col < 0 || row >= rows || col >= cols) {
            throw new IndexOutOfBoundsException("row/col out of range");
        }
    }

    public long getRows() { return rows; }
    public long getCols() { return cols; }
    public long getElementCount() { return elementCount; }
    public int getByteCapacity() { return byteCapacity; }

    /**
     * Get enhanced pool statistics for monitoring
     */
    public static String getEnhancedPoolStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Enhanced Buffer Pool Statistics:\n");
        stats.append("Total Allocations: ").append(totalAllocations.get()).append("\n");
        stats.append("Total Reuses: ").append(totalReuses.get()).append("\n");
        stats.append("Total Frees: ").append(totalFrees.get()).append("\n");
        stats.append("Allocation Failures: ").append(allocationFailures.get()).append("\n");
        stats.append("Adaptive Resizes: ").append(adaptiveResizes.get()).append("\n");
        stats.append("Reuse Rate: ").append(String.format("%.2f%%",
            totalAllocations.get() > 0 ? (100.0 * totalReuses.get() / totalAllocations.get()) : 0.0)).append("\n");
        
        MemoryPressureLevel pressure = currentPressure.get();
        stats.append("Current Memory Pressure: ").append(getPressureName(pressure.level)).append("\n");
        
        AdaptivePoolConfig config = adaptiveConfig.get();
        stats.append("Pool Config - Min: ").append(config.minPoolSize)
               .append(", Max: ").append(config.maxPoolSize)
               .append(", Pressure Factor: ").append(config.pressureFactor).append("\n");
        
        stats.append("Pool Buckets:\n");
        int totalBuffers = 0;
        int totalHighWater = 0;
        
        for (var entry : bufferPools.entrySet()) {
            LockFreePool pool = entry.getValue();
            int currentSize = pool.getSize();
            int highWater = pool.getHighWaterMark();
            int bucketByteSize = (1 << (BUCKET_SIZE_POWER + entry.getKey()));
            totalBuffers += currentSize;
            totalHighWater += highWater;
            
            stats.append("  Bucket ").append(entry.getKey())
                   .append(" (").append(bucketByteSize).append(" bytes): ")
                   .append(currentSize).append(" current, ")
                   .append(highWater).append(" peak\n");
        }
        
        stats.append("Total Buffers in Pools: ").append(totalBuffers).append("\n");
        stats.append("Total High Water Mark: ").append(totalHighWater).append("\n");
        stats.append("Pool Utilization: ").append(String.format("%.2f%%",
            totalHighWater > 0 ? (100.0 * totalBuffers / totalHighWater) : 0.0)).append("\n");
        
        return stats.toString();
    }

    private static String getPressureName(int level) {
        return switch (level) {
            case MEMORY_PRESSURE_LOW -> "LOW";
            case MEMORY_PRESSURE_MODERATE -> "MODERATE";
            case MEMORY_PRESSURE_HIGH -> "HIGH";
            case MEMORY_PRESSURE_CRITICAL -> "CRITICAL";
            default -> "UNKNOWN";
        };
    }

    /**
     * Manually trigger enhanced pool cleanup
     */
    public static void forceEnhancedCleanup() {
        performAdaptivePoolManagement();
    }

    /**
     * Clear all enhanced pools and free all buffers
     */
    public static void clearEnhancedPools() {
        for (var entry : bufferPools.entrySet()) {
            LockFreePool pool = entry.getValue();
            pool.clearExcess(0);
        }
        bufferPools.clear();
    }

    /**
     * Update adaptive configuration
     */
    public static void updateAdaptiveConfig(int minPoolSize, int maxPoolSize, double pressureFactor) {
        long stamp = configLock.writeLock();
        try {
            adaptiveConfig.set(new AdaptivePoolConfig(minPoolSize, maxPoolSize, pressureFactor));
        } finally {
            configLock.unlockWrite(stamp);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cleanable.clean();
        }
    }

    // Static initializer for enhanced buffer management
    static {
        // Start adaptive pool management thread
        Thread adaptiveThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL_MS);
                    performAdaptivePoolManagement();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error in adaptive pool management: " + e.getMessage());
                }
            }
        }, "EnhancedNativeFloatBuffer-AdaptiveManager");
        adaptiveThread.setDaemon(true);
        adaptiveThread.start();
    }
}