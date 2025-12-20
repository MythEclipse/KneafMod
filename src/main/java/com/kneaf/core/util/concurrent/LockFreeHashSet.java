package com.kneaf.core.util.concurrent;

import java.util.Set;

/**
 * Lock-free hash set implementation using atomic operations
 * Eliminates lock contention compared to ConcurrentHashSet
 */
public class LockFreeHashSet<E> {
    private final LockFreeHashMap<E, Boolean> map;

    public LockFreeHashSet() {
        this.map = new LockFreeHashMap<>();
    }

    public boolean add(E e) {
        return map.put(e, Boolean.TRUE) == null;
    }

    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    public boolean contains(Object o) {
        return map.get(o) != null;
    }

    public void clear() {
        map.clear();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public int size() {
        // Note: Size calculation in lock-free structures is approximate due to
        // concurrent modifications
        return map.size.get();
    }

    public Set<E> toSet() {
        // FIXED: Properly implement toSet() using the map's getAllKeys method
        return map.getAllKeys();
    }
}
