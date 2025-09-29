package com.kneaf.core.performance;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Simple AutoCloseable wrapper for ByteBuffers allocated by native code.
 * Ensures the corresponding native free call is invoked when closed or
 * when the wrapper is reclaimed by the Cleaner.
 */
public final class NativeFloatBuffer implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final ByteBuffer buf;
    private final java.nio.FloatBuffer view;
    private final long rows;
    private final long cols;
    private final long elementCount;
    private final int byteCapacity;
    private final Cleaner.Cleanable cleanable;
    private volatile boolean closed = false;

    private static final class State implements Runnable {
        private final ByteBuffer buf;
        State(ByteBuffer buf) { this.buf = buf; }
        @Override
        public void run() {
            try {
                if (buf != null) {
                    RustPerformance.freeFloatBufferNative(buf);
                }
            } catch (Exception t) {
                // Avoid throwing during cleanup
                System.err.println("Error freeing native buffer: " + t.getMessage());
            }
        }
    }

    private NativeFloatBuffer(ByteBuffer buf, long rows, long cols) {
        if (buf == null) throw new IllegalArgumentException("buf must not be null");
        if (rows < 0 || cols < 0) throw new IllegalArgumentException("rows/cols must be non-negative");
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        this.rows = rows;
        this.cols = cols;
        this.elementCount = rows * cols;
        this.byteCapacity = this.buf.capacity();
        // Create a FloatBuffer view for convenient float access
        this.view = this.buf.asFloatBuffer();
        this.cleanable = CLEANER.register(this, new State(this.buf));
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
        try {
            NativeFloatBufferAllocation alloc = RustPerformance.generateFloatBufferWithShapeNative(rows, cols);
            if (alloc == null) return null;
            return new NativeFloatBuffer(alloc.getBuffer(), alloc.getRows(), alloc.getCols());
        } catch (UnsatisfiedLinkError e) {
            // Fallback to older API
            ByteBuffer b = RustPerformance.generateFloatBufferNative(rows, cols);
            if (b == null) return null;
            return new NativeFloatBuffer(b, rows, cols);
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

    /** Fill the entire buffer with a value. */
    public void fill(float value) {
        checkOpen();
        int cap = view.capacity();
        for (int i = 0; i < cap; i++) view.put(i, value);
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

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // run cleanup now and unregister
            cleanable.clean();
        }
    }
}
