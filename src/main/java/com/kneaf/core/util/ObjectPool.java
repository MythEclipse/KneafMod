/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Object pool for reducing GC pressure during high-frequency operations.
 */
package com.kneaf.core.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * High-performance object pool for reducing garbage collection pressure.
 * 
 * Objects are pooled and reused instead of being garbage collected.
 * Ideal for frequently allocated objects like Vec3, BlockPos.MutableBlockPos,
 * and other tick-scoped temporary objects.
 *
 * Features:
 * 1. Lock-free concurrent access via ConcurrentLinkedQueue
 * 2. Configurable max pool size to limit memory
 * 3. Statistics for monitoring pool effectiveness
 * 4. Thread-safe borrow/return operations
 *
 * @param <T> Type of object to pool
 */
public class ObjectPool<T> {

    private final ConcurrentLinkedQueue<T> pool;
    private final Supplier<T> factory;
    private final int maxSize;

    // Statistics
    private final AtomicInteger borrows = new AtomicInteger(0);
    private final AtomicInteger returns = new AtomicInteger(0);
    private final AtomicInteger misses = new AtomicInteger(0); // Factory calls
    private final AtomicInteger poolSize = new AtomicInteger(0);

    /**
     * Create object pool with factory and max size.
     *
     * @param factory Creates new instances when pool is empty
     * @param maxSize Maximum number of objects to pool
     */
    public ObjectPool(Supplier<T> factory, int maxSize) {
        this.pool = new ConcurrentLinkedQueue<>();
        this.factory = factory;
        this.maxSize = maxSize;
    }

    /**
     * Create object pool with default max size of 64.
     *
     * @param factory Creates new instances
     */
    public ObjectPool(Supplier<T> factory) {
        this(factory, 64);
    }

    /**
     * Borrow an object from the pool.
     * If pool is empty, creates a new instance.
     *
     * @return Pooled or new object instance
     */
    public T borrow() {
        borrows.incrementAndGet();

        T obj = pool.poll();
        if (obj != null) {
            poolSize.decrementAndGet();
            return obj;
        }

        // Pool empty, create new
        misses.incrementAndGet();
        return factory.get();
    }

    /**
     * Return an object to the pool for reuse.
     * Object is discarded if pool is at max capacity.
     *
     * @param obj Object to return
     */
    public void release(T obj) {
        if (obj == null)
            return;

        returns.incrementAndGet();

        if (poolSize.get() < maxSize) {
            pool.offer(obj);
            poolSize.incrementAndGet();
        }
        // Else: discard, let GC handle it
    }

    /**
     * Get current pool size.
     */
    public int size() {
        return poolSize.get();
    }

    /**
     * Clear the pool.
     */
    public void clear() {
        pool.clear();
        poolSize.set(0);
    }

    /**
     * Get hit rate (borrows that didn't require factory call).
     */
    public double getHitRate() {
        int totalBorrows = borrows.get();
        int totalMisses = misses.get();
        if (totalBorrows == 0)
            return 0;
        return (totalBorrows - totalMisses) * 100.0 / totalBorrows;
    }

    /**
     * Get statistics string.
     */
    public String getStatistics() {
        return String.format(
                "ObjectPool{borrows=%d, returns=%d, misses=%d, size=%d, hitRate=%.1f%%}",
                borrows.get(), returns.get(), misses.get(), poolSize.get(), getHitRate());
    }

    /**
     * Reset all statistics.
     */
    public void resetStatistics() {
        borrows.set(0);
        returns.set(0);
        misses.set(0);
    }
}
