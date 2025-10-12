package com.kneaf.core.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory pool manager for sharing memory resources across bridge implementations.
 * Provides lazy initialization and pooling for heavy components.
 */
public final class MemoryPoolManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryPoolManager.class);
    
    // Singleton instance
    private static final MemoryPoolManager INSTANCE = new MemoryPoolManager();
    
    // Memory pools for different bridge implementations
    private final ConcurrentMap<String, ObjectPool<?>> objectPools = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Supplier<Object>> lazyInitializers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> initializedComponents = new ConcurrentHashMap<>();
    
    // Performance metrics
    private final AtomicLong totalPoolHits = new AtomicLong(0);
    private final AtomicLong totalPoolMisses = new AtomicLong(0);
    private final AtomicLong totalLazyInitializations = new AtomicLong(0);
    
    private MemoryPoolManager() {
        // Private constructor for singleton
    }
    
    /**
     * Get the singleton instance of MemoryPoolManager.
     * @return MemoryPoolManager instance
     */
    public static MemoryPoolManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Register a memory pool for a specific bridge implementation.
     * @param poolName Unique name for the pool
     * @param poolSize Maximum size of the pool
     * @param objectFactory Factory for creating new objects
     * @param <T> Type of objects in the pool
     */
    public <T> void registerPool(String poolName, int poolSize, Supplier<T> objectFactory) {
        ValidationUtils.notEmptyString(poolName, "Pool name cannot be null or empty");
        ValidationUtils.notNull(objectFactory, "Object factory cannot be null");
        
        ObjectPool<T> pool = new ObjectPool<>(poolSize, objectFactory);
        objectPools.put(poolName, pool);
        
        LOGGER.info("Registered memory pool '{}' with size {}", poolName, poolSize);
    }
    
    /**
     * Acquire an object from the specified pool.
     * @param poolName Name of the pool
     * @return Object from the pool, or null if pool doesn't exist
     */
    public <T> T acquireFromPool(String poolName) {
        ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(poolName);
        if (pool == null) {
            LOGGER.warn("Memory pool '{}' not found", poolName);
            totalPoolMisses.incrementAndGet();
            return null;
        }
        
        T object = pool.acquire();
        if (object != null) {
            totalPoolHits.incrementAndGet();
            LOGGER.debug("Acquired object from pool '{}'", poolName);
        } else {
            totalPoolMisses.incrementAndGet();
            LOGGER.debug("Pool '{}' empty, creating new object", poolName);
        }
        
        return object;
    }
    
    /**
     * Release an object back to the specified pool.
     * @param poolName Name of the pool
     * @param object Object to release
     */
    public <T> void releaseToPool(String poolName, T object) {
        ObjectPool<T> pool = (ObjectPool<T>) objectPools.get(poolName);
        if (pool == null) {
            LOGGER.warn("Memory pool '{}' not found, cannot release object", poolName);
            return;
        }
        
        if (pool.release(object)) {
            LOGGER.debug("Released object to pool '{}'", poolName);
        } else {
            LOGGER.debug("Pool '{}' full, discarding object", poolName);
        }
    }
    
    /**
     * Register a lazy initializer for a heavy component.
     * @param componentName Unique name for the component
     * @param initializer Supplier that creates the component
     */
    public void registerLazyInitializer(String componentName, Supplier<Object> initializer) {
        ValidationUtils.notEmptyString(componentName, "Component name cannot be null or empty");
        ValidationUtils.notNull(initializer, "Initializer cannot be null");
        
        lazyInitializers.put(componentName, initializer);
        LOGGER.info("Registered lazy initializer for component '{}'", componentName);
    }
    
    /**
     * Get a component with lazy initialization.
     * @param componentName Name of the component
     * @return Initialized component, or null if not found
     */
    public Object getLazyComponent(String componentName) {
        // Check if already initialized
        Object component = initializedComponents.get(componentName);
        if (component != null) {
                // If the cached component is a SwapManager and it's been shut down,
                // remove it and re-run the initializer so callers get a fresh instance.
                try {
                    if (component instanceof com.kneaf.core.chunkstorage.swap.SwapManager) {
                        com.kneaf.core.chunkstorage.swap.SwapManager sm = (com.kneaf.core.chunkstorage.swap.SwapManager) component;
                        if (sm.isShutdown()) {
                            LOGGER.info("Cached SwapManager '{}' is shutdown - reinitializing", componentName);
                            initializedComponents.remove(componentName);
                            totalLazyInitializations.decrementAndGet();
                            component = null;
                        }
                    }
                } catch (Throwable t) {
                    // Ignore and return the component if we cannot introspect it
                }
                if (component != null) {
                    LOGGER.debug("Returning cached component '{}'", componentName);
                    return component;
                }
        }
        
        // Check if initializer exists
        Supplier<Object> initializer = lazyInitializers.get(componentName);
        if (initializer == null) {
            LOGGER.warn("Lazy initializer for component '{}' not found", componentName);
            return null;
        }
        
        // Initialize the component
        try {
            component = initializer.get();
            initializedComponents.put(componentName, component);
            totalLazyInitializations.incrementAndGet();
            
            LOGGER.info("Lazy initialized component '{}'", componentName);
            return component;
        } catch (Exception e) {
            LOGGER.error("Failed to lazy initialize component '{}'", componentName, e);
            return null;
        }
    }
    
    /**
     * Clear all memory pools and initialized components.
     */
    public void clearAll() {
        objectPools.clear();
        lazyInitializers.clear();
        initializedComponents.clear();
        totalPoolHits.set(0);
        totalPoolMisses.set(0);
        totalLazyInitializations.set(0);
        
        LOGGER.info("Cleared all memory pools and initialized components");
    }
    
    /**
     * Get memory pool statistics.
     * @return Map containing performance metrics
     */
    public java.util.Map<String, Object> getStatistics() {
        long totalPools = objectPools.size();
        long totalLazyComponents = lazyInitializers.size();
        long totalInitialized = initializedComponents.size();
        
        double hitRate = totalPoolHits.get() + totalPoolMisses.get() > 0 
            ? (double) totalPoolHits.get() / (totalPoolHits.get() + totalPoolMisses.get()) 
            : 0.0;
        
        return java.util.Map.of(
            "totalPools", totalPools,
            "totalLazyComponents", totalLazyComponents,
            "totalInitialized", totalInitialized,
            "totalPoolHits", totalPoolHits.get(),
            "totalPoolMisses", totalPoolMisses.get(),
            "totalLazyInitializations", totalLazyInitializations.get(),
            "poolHitRate", hitRate
        );
    }
    
    /**
     * Simple object pool implementation.
     */
    private static class ObjectPool<T> {
        private final int maxSize;
        private final java.util.Queue<T> objects;
        private final Supplier<T> objectFactory;
        private final AtomicLong createdCount = new AtomicLong(0);
        private final AtomicLong reusedCount = new AtomicLong(0);
        
        public ObjectPool(int maxSize, Supplier<T> objectFactory) {
            this.maxSize = maxSize;
            this.objects = new java.util.concurrent.ConcurrentLinkedQueue<>();
            this.objectFactory = objectFactory;
        }
        
        public T acquire() {
            T object = objects.poll();
            if (object != null) {
                reusedCount.incrementAndGet();
                return object;
            }
            
            // Create new object if pool is empty
            object = objectFactory.get();
            createdCount.incrementAndGet();
            return object;
        }
        
        public boolean release(T object) {
            if (objects.size() < maxSize) {
                objects.offer(object);
                return true;
            }
            return false;
        }
        
        public int size() {
            return objects.size();
        }
        
        public long getCreatedCount() {
            return createdCount.get();
        }
        
        public long getReusedCount() {
            return reusedCount.get();
        }
    }
}