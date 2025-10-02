package com.kneaf.core.performance;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * AutoCloseable wrapper for ByteBuffers allocated by native code with buffer pooling.
 * Implements size-based buffer pooling to reduce native alloc/dealloc cycles.
 * Ensures the corresponding native free call is invoked when closed or
 * when the wrapper is reclaimed by the Cleaner.
 */
public final class NativeFloatBuffer implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    
    // Static initializer to ensure native library is loaded
    static {
        try {
            // This will trigger RustPerformance static initializer if not already loaded
            Class.forName("com.kneaf.core.performance.RustPerformance");
        } catch (ClassNotFoundException e) {
            System.err.println("RustPerformance class not found, native float buffer operations may fail");
        }
    }
    
    // Buffer pooling configuration
    private static final int MAX_POOL_SIZE_PER_BUCKET = 10;
    private static final int BUCKET_SIZE_POWER = 12; // 4096 byte buckets
    private static final int MAX_BUCKET_SIZE = 20; // Maximum bucket index (2^20 = 1MB)
    private static final long CLEANUP_INTERVAL_MS = 30000; // 30 seconds
    
    // Pool management
    private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<PooledBuffer>> bufferPools = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicInteger> poolSizes = new ConcurrentHashMap<>();
    private static final AtomicLong lastCleanupTime = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong totalAllocations = new AtomicLong(0);
    private static final AtomicLong totalReuses = new AtomicLong(0);
    private static final AtomicLong totalFrees = new AtomicLong(0);

    /**
     * Internal class to hold pooled buffer information
     */
    private static final class PooledBuffer {
        final ByteBuffer buffer;
        final int bucketIndex;
        
        PooledBuffer(ByteBuffer buffer, int bucketIndex) {
            this.buffer = buffer;
            this.bucketIndex = bucketIndex;
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
    @SuppressWarnings("unused")
    private final boolean isPooled;
    @SuppressWarnings("unused")
    private final int bucketIndex;

    private static final class State implements Runnable {
        private final ByteBuffer buf;
        private final boolean isPooled;
        private final int bucketIndex;
        
        State(ByteBuffer buf, boolean isPooled, int bucketIndex) {
            this.buf = buf;
            this.isPooled = isPooled;
            this.bucketIndex = bucketIndex;
        }
        
        private void returnToPool() {
            // Ensure bucket exists
            bufferPools.computeIfAbsent(bucketIndex, k -> new ConcurrentLinkedQueue<>());
            poolSizes.computeIfAbsent(bucketIndex, k -> new AtomicInteger(0));
            
            int currentSize = poolSizes.get(bucketIndex).get();
            if (currentSize < MAX_POOL_SIZE_PER_BUCKET) {
                PooledBuffer pooled = new PooledBuffer(buf, bucketIndex);
                bufferPools.get(bucketIndex).offer(pooled);
                poolSizes.get(bucketIndex).incrementAndGet();
            } else {
                // Pool is full, free the buffer
                freeBuffer();
            }
            
            // Periodic cleanup
            long now = System.currentTimeMillis();
            long lastCleanup = lastCleanupTime.get();
            
            if (now - lastCleanup > CLEANUP_INTERVAL_MS && lastCleanupTime.compareAndSet(lastCleanup, now)) {
                performCleanup();
            }
        }
        
        private void freeBuffer() {
            try {
                RustPerformance.freeFloatBufferNative(buf);
                totalFrees.incrementAndGet();
            } catch (Exception e) {
                System.err.println("Error freeing native buffer: " + e.getMessage());
            }
        }
        
        @Override
        public void run() {
            try {
                if (buf != null) {
                    if (isPooled && bucketIndex >= 0) {
                        // Return to pool instead of freeing
                        returnToPool();
                    } else {
                        // Free non-pooled buffer
                        freeBuffer();
                    }
                }
            } catch (Exception t) {
                // Avoid throwing during cleanup
                System.err.println("Error during buffer cleanup: " + t.getMessage());
            }
        }
    }

    private NativeFloatBuffer(ByteBuffer buf, long rows, long cols, boolean isPooled, int bucketIndex) {
        if (buf == null) throw new IllegalArgumentException("buf must not be null");
        if (rows < 0 || cols < 0) throw new IllegalArgumentException("rows/cols must be non-negative");
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        this.rows = rows;
        this.cols = cols;
        this.elementCount = rows * cols;
        this.byteCapacity = this.buf.capacity();
        // Create a FloatBuffer view for convenient float access
        this.view = this.buf.asFloatBuffer();
        this.cleanable = CLEANER.register(this, new State(this.buf, isPooled, bucketIndex));
        this.isPooled = isPooled;
        this.bucketIndex = bucketIndex;
    }

    /**
     * Calculate bucket index for given byte capacity
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
     * Get buffer from pool or return null if not available
     */
    private static PooledBuffer getFromPool(int byteCapacity) {
        int bucketIndex = getBucketIndex(byteCapacity);
        
        // Try to get from the exact bucket first
        ConcurrentLinkedQueue<PooledBuffer> pool = bufferPools.get(bucketIndex);
        if (pool != null) {
            PooledBuffer pooled = pool.poll();
            if (pooled != null) {
                poolSizes.get(bucketIndex).decrementAndGet();
                totalReuses.incrementAndGet();
                return pooled;
            }
        }
        
        // Try larger buckets if exact match not available
        for (int i = bucketIndex + 1; i <= MAX_BUCKET_SIZE; i++) {
            pool = bufferPools.get(i);
            if (pool != null) {
                PooledBuffer pooled = pool.poll();
                if (pooled != null) {
                    poolSizes.get(i).decrementAndGet();
                    totalReuses.incrementAndGet();
                    return pooled;
                }
            }
        }
        
        return null;
    }

    /**
     * Clean up excess buffers from pools
     */
    private static void performCleanup() {
        List<Integer> emptyBuckets = new ArrayList<>();
        
        for (var entry : bufferPools.entrySet()) {
            int bucketIndex = entry.getKey();
            ConcurrentLinkedQueue<PooledBuffer> pool = entry.getValue();
            AtomicInteger size = poolSizes.get(bucketIndex);
            
            if (pool != null && size != null) {
                int remaining = cleanupBucket(pool, size);
                // Mark empty buckets for removal
                if (remaining == 0) {
                    emptyBuckets.add(bucketIndex);
                }
            }
        }
        
        // Remove empty buckets
        for (Integer bucketIndex : emptyBuckets) {
            bufferPools.remove(bucketIndex);
            poolSizes.remove(bucketIndex);
        }
    }

    private static int cleanupBucket(ConcurrentLinkedQueue<PooledBuffer> pool, AtomicInteger size) {
        int targetSize = MAX_POOL_SIZE_PER_BUCKET / 2;
        int currentSize = size.get();
        
        while (currentSize > targetSize) {
            PooledBuffer pooled = pool.poll();
            if (pooled != null) {
                try {
                    RustPerformance.freeFloatBufferNative(pooled.buffer);
                    totalFrees.incrementAndGet();
                    size.decrementAndGet();
                    currentSize--;
                } catch (Exception e) {
                    System.err.println("Error freeing native buffer during cleanup: " + e.getMessage());
                }
            } else {
                break;
            }
        }
        return currentSize;
    }

    /**
     * Allocate a native float buffer via the Rust native helper and wrap it.
     * Returns null if the native allocation failed or native library missing.
     */
    /**
     * Allocate via native helper that returns both buffer and shape. Falls back
     * to older generateFloatBufferNative if the new native is unavailable.
     */
    public static NativeFloatBuffer allocateFromNative(long rows, long cols) {
        return allocateFromNative(rows, cols, true); // Use pooling by default
    }

    /**
     * Allocate with optional pooling
     */
    public static NativeFloatBuffer allocateFromNative(long rows, long cols, boolean usePool) {
        try {
            int requiredBytes = (int)(rows * cols * 4); // 4 bytes per float
            
            if (usePool) {
                // Try to get from pool first
                PooledBuffer pooled = getFromPool(requiredBytes);
                if (pooled != null) {
                    // Clear the buffer before reuse
                    pooled.buffer.clear();
                    return new NativeFloatBuffer(pooled.buffer, rows, cols, true, pooled.bucketIndex);
                }
            }
            
            // Allocate new buffer
            NativeFloatBufferAllocation alloc = RustPerformance.generateFloatBufferWithShapeNative(rows, cols);
            if (alloc == null) return null;
            
            totalAllocations.incrementAndGet();
            int bucketIndex = usePool ? getBucketIndex(alloc.getBuffer().capacity()) : -1;
            return new NativeFloatBuffer(alloc.getBuffer(), alloc.getRows(), alloc.getCols(), usePool, bucketIndex);
            
        } catch (UnsatisfiedLinkError e) {
            // Fallback to older API
            ByteBuffer b = RustPerformance.generateFloatBufferNative(rows, cols);
            if (b == null) return null;
            
            totalAllocations.incrementAndGet();
            int bucketIndex = usePool ? getBucketIndex(b.capacity()) : -1;
            return new NativeFloatBuffer(b, rows, cols, usePool, bucketIndex);
        }
    }

    /** Return the underlying ByteBuffer for read/write access. */
    public ByteBuffer buffer() {
        if (closed) throw new IllegalStateException("NativeFloatBuffer is closed");
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

    /**
     * Return a FloatBuffer view to the whole underlying buffer. The returned
     * buffer is a view; operations on it affect the native memory.
     */
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

    /** Return a FloatBuffer representing a single column (0-based). This creates
     * a new buffer with the column values copied into it (non-direct), since
     * the native memory layout is row-major.
     */
    public java.nio.FloatBuffer colBuffer(int col) {
        checkOpen();
        if (col < 0 || col >= cols) throw new IndexOutOfBoundsException("col out of range");
        // Bulk copy column into a new non-direct FloatBuffer to minimize per-element Java calls
        int rCount = (int) rows;
        float[] tmp = new float[rCount];
        int base = col; // starting index into row-major float array
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
        
        // Use bulk fill for better performance on large buffers
        if (cap > 1000) {
            // Fill in chunks to leverage CPU cache
            int chunkSize = Math.min(cap, 10000);
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

    /** Copy the contents into another NativeFloatBuffer (must match shape). */
    public void copyTo(NativeFloatBuffer dest) {
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
        if (closed) throw new IllegalStateException("NativeFloatBuffer is closed");
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
     * Get pool statistics for monitoring
     */
    public static String getPoolStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Buffer Pool Statistics:\n");
        stats.append("Total Allocations: ").append(totalAllocations.get()).append("\n");
        stats.append("Total Reuses: ").append(totalReuses.get()).append("\n");
        stats.append("Total Frees: ").append(totalFrees.get()).append("\n");
        stats.append("Reuse Rate: ").append(String.format("%.2f%%",
            totalAllocations.get() > 0 ? (100.0 * totalReuses.get() / totalAllocations.get()) : 0.0)).append("\n");
        stats.append("Pool Buckets:\n");
        
        for (Integer bucketIndex : bufferPools.keySet()) {
            AtomicInteger size = poolSizes.get(bucketIndex);
            int bucketSize = size != null ? size.get() : 0;
            int bucketByteSize = (1 << (BUCKET_SIZE_POWER + bucketIndex));
            stats.append("  Bucket ").append(bucketIndex)
               .append(" (").append(bucketByteSize).append(" bytes): ")
               .append(bucketSize).append(" buffers\n");
        }
        
        return stats.toString();
    }

    /**
     * Manually trigger pool cleanup
     */
    public static void forceCleanup() {
        performCleanup();
    }

    /**
     * Clear all pools and free all buffers
     */
    public static void clearPools() {
        for (var entry : bufferPools.entrySet()) {
            ConcurrentLinkedQueue<PooledBuffer> pool = entry.getValue();
            if (pool != null) {
                PooledBuffer pooled;
                while ((pooled = pool.poll()) != null) {
                    try {
                        RustPerformance.freeFloatBufferNative(pooled.buffer);
                        totalFrees.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Error freeing native buffer during pool clear: " + e.getMessage());
                    }
                }
            }
        }
        bufferPools.clear();
        poolSizes.clear();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // run cleanup now and unregister
            cleanable.clean();
        }
    }
}
