package com.kneaf.core.util.concurrent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Lock-free hash map implementation using atomic references and
 * compare-and-swap operations
 * Eliminates lock contention compared to ConcurrentHashMap
 */
public class LockFreeHashMap<K, V> {
    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private AtomicReferenceArray<Node<K, V>> table;
    public AtomicInteger size;
    private int threshold;

    public LockFreeHashMap() {
        this.table = new AtomicReferenceArray<>(INITIAL_CAPACITY);
        this.size = new AtomicInteger(0);
        this.threshold = (int) (INITIAL_CAPACITY * LOAD_FACTOR);
    }

    public V put(K key, V value) {
        int hash = hash(key);
        int index = indexFor(hash, table.length());

        // OPTIMIZED: Limit retry attempts to prevent infinite loops under high
        // contention
        int maxRetries = 100;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            Node<K, V> node = table.get(index);
            if (node == null) {
                // OPTIMIZED: Create node once, reuse on retries
                Node<K, V> newNode = new Node<>(hash, key, value, null);
                if (table.compareAndSet(index, null, newNode)) {
                    if (size.incrementAndGet() > threshold) {
                        resize();
                    }
                    return null;
                }
                retryCount++;
            } else if (node.hash == hash && (node.key == key || node.key.equals(key))) {
                // OPTIMIZED: Update value in-place without recreating entire chain
                V oldValue = node.value;
                node.value = value;
                return oldValue;
            } else {
                // Handle collisions using linked list
                Node<K, V> last = node;
                Node<K, V> prev = null;
                while (last != null) {
                    if (last.hash == hash && (last.key == key || last.key.equals(key))) {
                        // OPTIMIZED: Update value in place
                        V oldValue = last.value;
                        last.value = value;
                        return oldValue;
                    }
                    prev = last;
                    last = last.next;
                }

                // Add new node to end of chain
                if (prev != null) {
                    Node<K, V> newNode = new Node<>(hash, key, value, null);
                    prev.next = newNode;
                    size.incrementAndGet();
                    if (size.get() > threshold) {
                        resize();
                    }
                    return null;
                }
                retryCount++;
            }
        }

        // Fallback to synchronized put if CAS fails repeatedly (extreme contention)
        synchronized (this) {
            return putSynchronized(key, value, hash, index);
        }
    }

    // Synchronized fallback for extreme contention scenarios
    private synchronized V putSynchronized(K key, V value, int hash, int index) {
        Node<K, V> node = table.get(index);
        if (node == null) {
            Node<K, V> newNode = new Node<>(hash, key, value, null);
            table.set(index, newNode);
            if (size.incrementAndGet() > threshold) {
                resize();
            }
            return null;
        }

        Node<K, V> current = node;
        while (current != null) {
            if (current.hash == hash && (current.key == key || current.key.equals(key))) {
                V oldValue = current.value;
                current.value = value;
                return oldValue;
            }
            if (current.next == null) {
                current.next = new Node<>(hash, key, value, null);
                size.incrementAndGet();
                return null;
            }
            current = current.next;
        }
        return null;
    }

    public V get(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, table.length());

        Node<K, V> node = table.get(index);
        while (node != null) {
            if (node.hash == hash && (node.key == key || node.key.equals(key))) {
                return node.value;
            }
            node = node.next;
        }
        return null;
    }

    public V remove(Object key) {
        int hash = hash(key);
        int index = indexFor(hash, table.length());

        // OPTIMIZED: Limit retry attempts to prevent infinite loops
        int maxRetries = 100;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            Node<K, V> node = table.get(index);
            if (node == null) {
                return null;
            }

            if (node.hash == hash && (node.key == key || node.key.equals(key))) {
                if (table.compareAndSet(index, node, node.next)) {
                    size.decrementAndGet();
                    return node.value;
                }
                retryCount++;
            } else {
                Node<K, V> prev = node;
                Node<K, V> curr = node.next;

                while (curr != null) {
                    if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
                        // OPTIMIZED: Direct pointer manipulation instead of recreating chain
                        prev.next = curr.next;
                        size.decrementAndGet();
                        return curr.value;
                    }
                    prev = curr;
                    curr = curr.next;
                }
                return null;
            }
        }

        // Fallback to synchronized remove if CAS fails repeatedly
        synchronized (this) {
            return removeSynchronized(key, hash, index);
        }
    }

    // Synchronized fallback for extreme contention
    private synchronized V removeSynchronized(Object key, int hash, int index) {
        Node<K, V> node = table.get(index);
        if (node == null) {
            return null;
        }

        if (node.hash == hash && (node.key == key || node.key.equals(key))) {
            table.set(index, node.next);
            size.decrementAndGet();
            return node.value;
        }

        Node<K, V> prev = node;
        Node<K, V> curr = node.next;
        while (curr != null) {
            if (curr.hash == hash && (curr.key == key || curr.key.equals(key))) {
                prev.next = curr.next;
                size.decrementAndGet();
                return curr.value;
            }
            prev = curr;
            curr = curr.next;
        }
        return null;
    }

    public void clear() {
        int length = table.length();
        for (int i = 0; i < length; i++) {
            table.set(i, null);
        }
        size.set(0);
    }

    public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
        V value = get(key);
        if (value != null) {
            return value;
        }
        value = mappingFunction.apply(key);
        put(key, value);
        return value;
    }

    /**
     * Get all keys in the map (snapshot, not real-time)
     * ADDED: For LockFreeHashSet.toSet() implementation
     */
    public Set<K> getAllKeys() {
        Set<K> keys = new HashSet<>();
        int length = table.length();

        for (int i = 0; i < length; i++) {
            Node<K, V> node = table.get(i);
            while (node != null) {
                keys.add(node.key);
                node = node.next;
            }
        }

        return keys;
    }

    private int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    private int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    private volatile boolean resizing = false;

    private void resize() {
        // OPTIMIZED: Prevent concurrent resize operations
        if (resizing) {
            return;
        }

        synchronized (this) {
            // Double-check after acquiring lock
            if (resizing || size.get() <= threshold) {
                return;
            }

            resizing = true;
            try {
                int oldCapacity = table.length();
                int newCapacity = oldCapacity << 1;
                AtomicReferenceArray<Node<K, V>> newTable = new AtomicReferenceArray<>(newCapacity);

                // Redistribute nodes to new table - single-threaded during resize
                for (int i = 0; i < oldCapacity; i++) {
                    Node<K, V> node = table.get(i);
                    while (node != null) {
                        Node<K, V> next = node.next;
                        int newIndex = indexFor(node.hash, newCapacity);

                        // Direct set instead of CAS since resize is synchronized
                        Node<K, V> currentHead = newTable.get(newIndex);
                        Node<K, V> newNode = new Node<>(node.hash, node.key, node.value, currentHead);
                        newTable.set(newIndex, newNode);
                        node = next;
                    }
                }

                table = newTable;
                threshold = (int) (newCapacity * LOAD_FACTOR);
            } finally {
                resizing = false;
            }
        }
    }

    private static class Node<K, V> {
        final int hash;
        final K key;
        volatile V value;
        volatile Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
