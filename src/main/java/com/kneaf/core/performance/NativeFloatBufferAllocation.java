package com.kneaf.core.performance;

import java.nio.ByteBuffer;

/**
 * Simple holder returned from native allocation helper.
 * Contains the direct ByteBuffer and the matrix shape (rows, cols).
 */
public final class NativeFloatBufferAllocation {
    private final ByteBuffer buffer;
    private final long rows;
    private final long cols;

    public NativeFloatBufferAllocation(ByteBuffer buffer, long rows, long cols) {
        this.buffer = buffer;
        this.rows = rows;
        this.cols = cols;
    }

    public ByteBuffer getBuffer() { return buffer; }
    public long getRows() { return rows; }
    public long getCols() { return cols; }
}
