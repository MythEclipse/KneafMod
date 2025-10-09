package com.kneaf.core.performance;

/**
 * Java native bridge for Rust cache eviction system
 * Provides JNI interface for priority queue-based cache operations
 */
public final class CacheNativeBridge {
    static {
        System.loadLibrary("rustperf");
    }

    // Cache eviction policies
    public static final int POLICY_LRU = 0;
    public static final int POLICY_LFU = 1;
    public static final int POLICY_FIFO = 2;
    public static final int POLICY_PRIORITY = 3;

    /**
     * Initialize the cache with feature flags
     * @param enabled Whether to enable cache feature
     * @param policy Eviction policy (use POLICY_* constants)
     * @param capacity Maximum cache capacity
     * @param collectStats Whether to collect statistics
     * @return true if initialization succeeded
     */
    public native boolean nativeInitializeCache(
        boolean enabled,
        int policy,
        int capacity,
        boolean collectStats
    );

    /**
     * Put a value in the cache
     * @param key Cache key
     * @param value Value to cache (as byte array)
     * @param priority Priority level (higher = less likely to be evicted)
     * @return Evicted value (null if none)
     */
    public native byte[] nativeCachePut(String key, byte[] value, int priority);

    /**
     * Get a value from the cache
     * @param key Cache key
     * @return Cached value (null if not found or feature disabled)
     */
    public native byte[] nativeCacheGet(String key);

    /**
     * Remove a value from the cache
     * @param key Cache key
     * @return Removed value (null if not found or feature disabled)
     */
    public native byte[] nativeCacheRemove(String key);

    /**
     * Clear all entries from the cache
     * @return true if operation succeeded
     */
    public native boolean nativeCacheClear();

    /**
     * Get cache statistics as JSON string
     * @return JSON string containing cache statistics
     */
    public native String nativeGetCacheStats();

    /**
     * Check if cache feature is enabled
     * @return true if cache is enabled
     */
    public native boolean nativeIsCacheEnabled();

    /**
     * Java wrapper for cache operations with feature flag support
     */
    public static class CacheWrapper {
        private final CacheNativeBridge bridge = new CacheNativeBridge();
        private boolean isEnabled = false;

        /**
         * Create a new cache wrapper
         * @param enabled Whether to enable cache initially
         * @param policy Eviction policy
         * @param capacity Initial capacity
         */
        public CacheWrapper(boolean enabled, int policy, int capacity) {
            initialize(enabled, policy, capacity, true);
        }

        /**
         * Initialize or reinitialize the cache
         * @param enabled Whether to enable cache
         * @param policy Eviction policy
         * @param capacity Maximum capacity
         * @param collectStats Whether to collect statistics
         * @return true if successful
         */
        public boolean initialize(boolean enabled, int policy, int capacity, boolean collectStats) {
            this.isEnabled = enabled;
            return bridge.nativeInitializeCache(enabled, policy, capacity, collectStats);
        }

        /**
         * Put a value in the cache (thread-safe)
         * @param key Cache key
         * @param value Value to cache
         * @param priority Priority level
         * @return Evicted value or null
         */
        public byte[] put(String key, byte[] value, int priority) {
            if (!isEnabled) {
                return value; // Bypass cache when disabled
            }
            return bridge.nativeCachePut(key, value, priority);
        }

        /**
         * Get a value from the cache (thread-safe)
         * @param key Cache key
         * @return Cached value or null
         */
        public byte[] get(String key) {
            if (!isEnabled) {
                return null;
            }
            return bridge.nativeCacheGet(key);
        }

        /**
         * Remove a value from the cache (thread-safe)
         * @param key Cache key
         * @return Removed value or null
         */
        public byte[] remove(String key) {
            if (!isEnabled) {
                return null;
            }
            return bridge.nativeCacheRemove(key);
        }

        /**
         * Clear all cache entries (thread-safe)
         */
        public void clear() {
            if (isEnabled) {
                bridge.nativeCacheClear();
            }
        }

        /**
         * Get cache statistics
         * @return Cache statistics as JSON string
         */
        public String getStats() {
            if (!isEnabled) {
                return "{\"cacheEnabled\":false}";
            }
            return bridge.nativeGetCacheStats();
        }

        /**
         * Check if cache is enabled
         * @return true if cache is enabled
         */
        public boolean isEnabled() {
            return isEnabled;
        }

        /**
         * Enable/disable cache feature
         * @param enabled Whether to enable cache
         */
        public void setEnabled(boolean enabled) {
            if (this.isEnabled != enabled) {
                this.isEnabled = enabled;
                initialize(enabled, POLICY_LRU, 1000, true);
            }
        }
    }
}