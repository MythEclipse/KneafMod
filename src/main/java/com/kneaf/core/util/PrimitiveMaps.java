/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import java.util.Arrays;

/**
 * PrimitiveMaps - Collection of primitive open-addressing hash maps.
 * Optimized for low memory footprint and high performance (no boxing).
 * NOT thread-safe - requires external synchronization.
 */
public class PrimitiveMaps {

    /**
     * Long -> Object map using linear probing.
     */
    public static class Long2ObjectOpenHashMap<V> {
        private long[] keys;
        private V[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        @SuppressWarnings("unchecked")
        public Long2ObjectOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new long[initialCapacity];
            this.values = (V[]) new Object[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(long key, V value) {
            if (size >= capacity * loadFactor) {
                // Resize or clear? For caching purposes in this mod, we clear to keep it simple
                // and bounded.
                // A production general purpose map would resize.
                // Given the use cases (caches usually have limits), we'll add a simple resize
                // or clear strategy.
                // Let's implement resize for correctness, but the mixins control size limits.
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return; // Should not happen
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public V get(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return null;
            }
            return null;
        }

        public boolean containsKey(long key) {
            return get(key) != null;
        }

        public void remove(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    used[idx] = false;
                    values[idx] = null;
                    size--;
                    closeGap(idx);
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }
        }

        private void closeGap(int gapIdx) {
            int curr = (gapIdx + 1) % capacity;
            while (used[curr]) {
                int ideal = hash(keys[curr]);
                if ((gapIdx < curr ? (ideal <= gapIdx || ideal > curr) : (ideal <= gapIdx && ideal > curr))) {
                    keys[gapIdx] = keys[curr];
                    values[gapIdx] = values[curr];
                    used[gapIdx] = true;
                    used[curr] = false;
                    // Also move values
                    values[gapIdx] = values[curr];
                    values[curr] = null;
                    gapIdx = curr;
                }
                curr = (curr + 1) % capacity;
            }
        }

        public void clear() {
            Arrays.fill(used, false);
            Arrays.fill(values, null); // Help GC
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(long key) {
            return (int) ((it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity);
        }

        @SuppressWarnings("unchecked")
        private void resize(int newCapacity) {
            long[] oldKeys = keys;
            V[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new long[newCapacity];
            values = (V[]) new Object[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
        public void forEach(java.util.function.BiConsumer<Long, V> consumer) {
            for (int i = 0; i < capacity; i++) {
                if (used[i]) {
                    consumer.accept(keys[i], values[i]);
                }
            }
        }
    }

    /**
     * Long -> Boolean map using linear probing.
     */
    public static class Long2BooleanOpenHashMap {
        private long[] keys;
        private boolean[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        public Long2BooleanOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new long[initialCapacity];
            this.values = new boolean[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(long key, boolean value) {
            if (size >= capacity * loadFactor) {
                // Resize logic
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public Boolean get(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return null;
            }
            return null;
        }

        public void clear() {
            Arrays.fill(used, false);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(long key) {
            return (int) ((it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity);
        }

        private void resize(int newCapacity) {
            long[] oldKeys = keys;
            boolean[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new long[newCapacity];
            values = new boolean[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /**
     * Long -> Long map using linear probing.
     * Values are primitives. Returns -1L if key not found.
     */
    public static class Long2LongOpenHashMap {
        private long[] keys;
        private long[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        public Long2LongOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new long[initialCapacity];
            this.values = new long[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(long key, long value) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public long get(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return -1L;
            }
            return -1L;
        }

        public boolean containsKey(long key) {
            return get(key) != -1L;
        }

        public void remove(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    used[idx] = false;
                    size--;
                    closeGap(idx);
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }
        }

        /**
         * Iterates and removes entries matching the predicate.
         * Predicate receives (key, value). Return true to remove.
         */
        public void removeIf(java.util.function.BiPredicate<Long, Long> predicate) {
            // Note: BiPredicate uses boxed Longs, but for bulk operations ok.
            // Ideally custom interface but keeping it standard for now.
            for (int i = 0; i < capacity; i++) {
                if (used[i]) {
                    if (predicate.test(keys[i], values[i])) {
                        remove(keys[i]);
                        // Note: remove() might shift elements, so we must be careful iterating.
                        // But remove() uses closeGap which shifts elements into the current slot if
                        // needed.
                        // However, iterating usually expects stable array.
                        // Since we are iterating strictly by index, if remove() shifts an element
                        // FROM i+k TO i, we might process it again or miss it?
                        // Actually, my remove/closeGap implementation shifts elements BACK.
                        // If we iterate 0..N, and remove at i, we fill i with something from i+1.
                        // So we should re-check i.
                        i--;
                    }
                }
            }
        }

        private void closeGap(int gapIdx) {
            int curr = (gapIdx + 1) % capacity;
            while (used[curr]) {
                int ideal = hash(keys[curr]);
                if ((gapIdx < curr ? (ideal <= gapIdx || ideal > curr) : (ideal <= gapIdx && ideal > curr))) {
                    keys[gapIdx] = keys[curr];
                    values[gapIdx] = values[curr];
                    used[gapIdx] = true;
                    used[curr] = false;
                    gapIdx = curr;
                }
                curr = (curr + 1) % capacity;
            }
        }

        public void clear() {
            Arrays.fill(used, false);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(long key) {
            return (int) ((it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity);
        }

        private void resize(int newCapacity) {
            long[] oldKeys = keys;
            long[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new long[newCapacity];
            values = new long[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }

        public void forEach(java.util.function.LongBinaryOperator consumer) {
            for (int i = 0; i < capacity; i++) {
                if (used[i]) {
                    consumer.applyAsLong(keys[i], values[i]);
                }
            }
        }
    }

    /**
     * Long -> Int map using linear probing.
     * Values are primitives. Returns -1 if key not found.
     */
    public static class Long2IntOpenHashMap {
        private long[] keys;
        private int[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        public Long2IntOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new long[initialCapacity];
            this.values = new int[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(long key, int value) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public int get(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return -1;
            }
            return -1;
        }

        public boolean containsKey(long key) {
            return get(key) != -1;
        }

        public void remove(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    used[idx] = false;
                    size--;
                    closeGap(idx);
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }
        }

        private void closeGap(int gapIdx) {
            int curr = (gapIdx + 1) % capacity;
            while (used[curr]) {
                int ideal = hash(keys[curr]);
                if ((gapIdx < curr ? (ideal <= gapIdx || ideal > curr) : (ideal <= gapIdx && ideal > curr))) {
                    keys[gapIdx] = keys[curr];
                    values[gapIdx] = values[curr];
                    used[gapIdx] = true;
                    used[curr] = false;
                    gapIdx = curr;
                }
                curr = (curr + 1) % capacity;
            }
        }

        public void clear() {
            Arrays.fill(used, false);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(long key) {
            return (int) ((it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity);
        }

        private void resize(int newCapacity) {
            long[] oldKeys = keys;
            int[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new long[newCapacity];
            values = new int[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /**
     * Int -> Object map using linear probing.
     */
    public static class Int2ObjectOpenHashMap<V> {
        private int[] keys;
        private V[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        @SuppressWarnings("unchecked")
        public Int2ObjectOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new int[initialCapacity];
            this.values = (V[]) new Object[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(int key, V value) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public V get(int key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return null;
            }
            return null;
        }

        public void clear() {
            Arrays.fill(used, false);
            Arrays.fill(values, null);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(int key) {
            return (it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity;
        }

        @SuppressWarnings("unchecked")
        private void resize(int newCapacity) {
            int[] oldKeys = keys;
            V[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new int[newCapacity];
            values = (V[]) new Object[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /**
     * Int -> Boolean map using linear probing.
     */
    public static class Int2BooleanOpenHashMap {
        private int[] keys;
        private boolean[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        public Int2BooleanOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new int[initialCapacity];
            this.values = new boolean[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(int key, boolean value) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public Boolean get(int key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return null;
            }
            return null;
        }

        public int size() {
            return size;
        }

        public void clear() {
            Arrays.fill(used, false);
            size = 0;
        }

        private int hash(int key) {
            return (it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity;
        }

        private void resize(int newCapacity) {
            int[] oldKeys = keys;
            boolean[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new int[newCapacity];
            values = new boolean[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }

    /**
     * Long Set using linear probing.
     */
    public static class LongOpenHashSet {
        private long[] keys;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        public LongOpenHashSet(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = new long[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public boolean add(long key) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return false; // Already present
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return false;
            }

            keys[idx] = key;
            used[idx] = true;
            size++;
            return true;
        }

        public boolean contains(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    return true;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return false;
            }
            return false;
        }

        public boolean remove(long key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx] == key) {
                    used[idx] = false;
                    size--;
                    closeGap(idx);
                    return true;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return false;
            }
            return false;
        }

        private void closeGap(int gapIdx) {
            int curr = (gapIdx + 1) % capacity;
            while (used[curr]) {
                int ideal = hash(keys[curr]);
                if ((gapIdx < curr ? (ideal <= gapIdx || ideal > curr) : (ideal <= gapIdx && ideal > curr))) {
                    keys[gapIdx] = keys[curr];
                    used[gapIdx] = true;
                    used[curr] = false;
                    gapIdx = curr;
                }
                curr = (curr + 1) % capacity;
            }
        }

        public void clear() {
            Arrays.fill(used, false);
            size = 0;
        }

        public int size() {
            return size;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        private int hash(long key) {
            return (int) ((it.unimi.dsi.fastutil.HashCommon.mix(key) & 0x7FFFFFFF) % capacity);
        }

        private void resize(int newCapacity) {
            long[] oldKeys = keys;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = new long[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    add(oldKeys[i]);
                }
            }
        }

        /**
         * Iterates over all keys.
         * Consumer receives (key).
         * Note: Primitives consumer would be better but standard Consumer<Long> used
         * for compatibility if needed.
         * For zero GCs we would use a custom interface, but here we can just expose a
         * forEach check.
         */
        public void forEach(java.util.function.LongConsumer consumer) {
            for (int i = 0; i < capacity; i++) {
                if (used[i]) {
                    consumer.accept(keys[i]);
                }
            }
        }
    }

    /**
     * Object -> Object map using linear probing.
     */
    public static class Object2ObjectOpenHashMap<K, V> {
        private K[] keys;
        private V[] values;
        private boolean[] used;
        private int capacity;
        private int size;
        private final float loadFactor = 0.75f;

        @SuppressWarnings("unchecked")
        public Object2ObjectOpenHashMap(int initialCapacity) {
            this.capacity = initialCapacity;
            this.keys = (K[]) new Object[initialCapacity];
            this.values = (V[]) new Object[initialCapacity];
            this.used = new boolean[initialCapacity];
            this.size = 0;
        }

        public void put(K key, V value) {
            if (size >= capacity * loadFactor) {
                resize(capacity * 2);
            }

            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx].equals(key)) {
                    values[idx] = value;
                    return;
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return;
            }

            keys[idx] = key;
            values[idx] = value;
            used[idx] = true;
            size++;
        }

        public V get(K key) {
            int idx = hash(key);
            int startIdx = idx;

            while (used[idx]) {
                if (keys[idx].equals(key)) {
                    return values[idx];
                }
                idx = (idx + 1) % capacity;
                if (idx == startIdx)
                    return null;
            }
            return null;
        }

        public V getOrDefault(K key, V defaultValue) {
            V val = get(key);
            return val != null ? val : defaultValue;
        }

        public boolean containsKey(K key) {
            return get(key) != null;
        }

        public void clear() {
            Arrays.fill(used, false);
            Arrays.fill(keys, null);
            Arrays.fill(values, null);
            size = 0;
        }

        public int size() {
            return size;
        }

        private int hash(Object key) {
            return (it.unimi.dsi.fastutil.HashCommon.mix(key.hashCode()) & 0x7FFFFFFF) % capacity;
        }

        @SuppressWarnings("unchecked")
        private void resize(int newCapacity) {
            K[] oldKeys = keys;
            V[] oldValues = values;
            boolean[] oldUsed = used;
            int oldCapacity = capacity;

            capacity = newCapacity;
            keys = (K[]) new Object[newCapacity];
            values = (V[]) new Object[newCapacity];
            used = new boolean[newCapacity];
            size = 0;

            for (int i = 0; i < oldCapacity; i++) {
                if (oldUsed[i]) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
    }
}
