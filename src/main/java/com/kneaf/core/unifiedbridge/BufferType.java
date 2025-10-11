package com.kneaf.core.unifiedbridge;

/**
 * Enum representing different types of buffers that can be allocated.
 */
public enum BufferType {
    /**
     * Direct ByteBuffer allocated outside the Java heap.
     */
    DIRECT,
    
    /**
     * Heap ByteBuffer allocated inside the Java heap.
     */
    HEAP,
    
    /**
     * Memory-mapped buffer for file operations.
     */
    MAPPED,
    
    /**
     * Zero-copy buffer for high-performance operations.
     */
    ZERO_COPY,
    
    /**
     * Pooled buffer managed by buffer pool.
     */
    POOLED
}