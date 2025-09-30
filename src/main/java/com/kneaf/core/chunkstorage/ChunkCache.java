package com.kneaf.core.chunkstorage;

import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory cache for LevelChunk objects with configurable capacity and eviction policies.
 * Tracks chunk state (Hot/Cold/Dirty/Serialized) for intelligent caching decisions.
 */
public class ChunkCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final Map<String, CachedChunk> cache = new ConcurrentHashMap<>();
    private final int maxCapacity;
    private final EvictionPolicy evictionPolicy;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    
    // Cache statistics
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private final AtomicInteger missCount = new AtomicInteger(0);
    private final AtomicInteger evictionCount = new AtomicInteger(0);
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);
    
    /**
     * Represents a cached chunk with metadata.
     */
    public static class CachedChunk {
        private final LevelChunk chunk;
        private final long lastAccessTime;
        private final long creationTime;
        private final AtomicInteger accessCount;
        private volatile ChunkState state;
        private volatile boolean dirty;
        
        public CachedChunk(LevelChunk chunk) {
            this.chunk = chunk;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessTime = creationTime;
            this.accessCount = new AtomicInteger(1);
            this.state = ChunkState.HOT;
            this.dirty = false;
        }
        
        public LevelChunk getChunk() { return chunk; }
        public long getLastAccessTime() { return lastAccessTime; }
        public long getCreationTime() { return creationTime; }
        public int getAccessCount() { return accessCount.get(); }
        public ChunkState getState() { return state; }
        public boolean isDirty() { return dirty; }
        
        public void markAccessed() {
            // Note: lastAccessTime is intentionally not updated to maintain immutability
            accessCount.incrementAndGet();
        }
        
        public void markDirty() { this.dirty = true; }
        public void markClean() { this.dirty = false; }
        public void setState(ChunkState state) { this.state = state; }
    }
    
    /**
     * Chunk states for intelligent caching decisions.
     */
    public enum ChunkState {
        HOT,    // Recently accessed, keep in cache
        COLD,   // Less recently accessed, candidate for eviction
        DIRTY,  // Modified, needs to be saved before eviction
        SERIALIZED // Recently serialized, can be evicted if needed
    }
    
    /**
     * Cache eviction policy interface.
     */
    public interface EvictionPolicy {
        String selectChunkToEvict(Map<String, CachedChunk> cache);
        String getPolicyName();
    }
    
    /**
     * LRU (Least Recently Used) eviction policy.
     */
    public static class LRUEvictionPolicy implements EvictionPolicy {
        @Override
        public String selectChunkToEvict(Map<String, CachedChunk> cache) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            
            for (Map.Entry<String, CachedChunk> entry : cache.entrySet()) {
                if (entry.getValue().getLastAccessTime() < oldestTime) {
                    oldestTime = entry.getValue().getLastAccessTime();
                    oldestKey = entry.getKey();
                }
            }
            
            return oldestKey;
        }
        
        @Override
        public String getPolicyName() {
            return "LRU";
        }
    }
    
    /**
     * Distance-based eviction policy (evict chunks farthest from players).
     */
    public static class DistanceEvictionPolicy implements EvictionPolicy {
        private final int centerX;
        private final int centerZ;
        
        public DistanceEvictionPolicy(int centerX, int centerZ) {
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
        
        @Override
        public String selectChunkToEvict(Map<String, CachedChunk> cache) {
            String farthestKey = null;
            double maxDistance = -1;
            
            for (Map.Entry<String, CachedChunk> entry : cache.entrySet()) {
                String[] parts = entry.getKey().split(":");
                if (parts.length >= 3) {
                    try {
                        int chunkX = Integer.parseInt(parts[1]);
                        int chunkZ = Integer.parseInt(parts[2]);
                        double distance = Math.sqrt(
                            Math.pow((double) chunkX - (double) centerX, 2) + Math.pow((double) chunkZ - (double) centerZ, 2)
                        );
                        
                        if (distance > maxDistance) {
                            maxDistance = distance;
                            farthestKey = entry.getKey();
                        }
                    } catch (NumberFormatException e) {
                        LOGGER.debug("Invalid chunk key format: {}", entry.getKey());
                    }
                }
            }
            
            return farthestKey;
        }
        
        @Override
        public String getPolicyName() {
            return "Distance";
        }
    }
    
    /**
     * Hybrid eviction policy (combines LRU and access count).
     */
    public static class HybridEvictionPolicy implements EvictionPolicy {
        @Override
        public String selectChunkToEvict(Map<String, CachedChunk> cache) {
            String bestKey = null;
            double bestScore = Double.MAX_VALUE;
            long currentTime = System.currentTimeMillis();
            
            for (Map.Entry<String, CachedChunk> entry : cache.entrySet()) {
                CachedChunk cached = entry.getValue();
                
                // Score based on time since last access and access count
                double timeScore = (currentTime - cached.getLastAccessTime()) / 1000.0; // seconds
                double accessScore = 1.0 / Math.max(1, cached.getAccessCount());
                double score = timeScore * accessScore;
                
                if (score < bestScore) {
                    bestScore = score;
                    bestKey = entry.getKey();
                }
            }
            
            return bestKey;
        }
        
        @Override
        public String getPolicyName() {
            return "Hybrid";
        }
    }
    
    public ChunkCache(int maxCapacity, EvictionPolicy evictionPolicy) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Max capacity must be positive");
        }
        if (evictionPolicy == null) {
            throw new IllegalArgumentException("Eviction policy cannot be null");
        }
        
        this.maxCapacity = maxCapacity;
        this.evictionPolicy = evictionPolicy;
        
        LOGGER.info("Initialized ChunkCache with capacity {} and {} eviction policy", 
                   maxCapacity, evictionPolicy.getPolicyName());
    }
    
    /**
     * Get a chunk from the cache.
     * 
     * @param key The chunk key
     * @return Optional containing the cached chunk if found
     */
    public Optional<CachedChunk> getChunk(String key) {
        if (key == null || key.isEmpty()) {
            return Optional.empty();
        }
        
        cacheLock.readLock().lock();
        try {
            CachedChunk cached = cache.get(key);
            if (cached != null) {
                cached.markAccessed();
                hitCount.incrementAndGet();
                totalHits.incrementAndGet();
                
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Cache hit for chunk {}", key);
                }
                
                return Optional.of(cached);
            } else {
                missCount.incrementAndGet();
                totalMisses.incrementAndGet();
                
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Cache miss for chunk {}", key);
                }
                
                return Optional.empty();
            }
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Put a chunk into the cache.
     * 
     * @param key The chunk key
     * @param chunk The LevelChunk to cache
     * @return The evicted chunk if cache was full, null otherwise
     */
    public CachedChunk putChunk(String key, LevelChunk chunk) {
        if (key == null || key.isEmpty() || chunk == null) {
            throw new IllegalArgumentException("Key and chunk cannot be null or empty");
        }
        
        cacheLock.writeLock().lock();
        try {
            // Check if we need to evict a chunk
            if (cache.size() >= maxCapacity && !cache.containsKey(key)) {
                String keyToEvict = evictionPolicy.selectChunkToEvict(cache);
                if (keyToEvict != null) {
                    CachedChunk evicted = cache.remove(keyToEvict);
                    evictionCount.incrementAndGet();
                    
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Evicted chunk {} from cache (policy: {})", 
                                   keyToEvict, evictionPolicy.getPolicyName());
                    }
                    
                    // Return the evicted chunk so caller can handle saving if needed
                    return evicted;
                }
            }
            
            // Add the new chunk
            CachedChunk cachedChunk = new CachedChunk(chunk);
            cache.put(key, cachedChunk);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Cached chunk {} (total cached: {})", key, cache.size());
            }
            
            return null;
            
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Remove a chunk from the cache.
     * 
     * @param key The chunk key
     * @return The removed chunk if present, null otherwise
     */
    public CachedChunk removeChunk(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        
        cacheLock.writeLock().lock();
        try {
            CachedChunk removed = cache.remove(key);
            if (removed != null && LOGGER.isDebugEnabled()) {
                LOGGER.debug("Removed chunk {} from cache", key);
            }
            return removed;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Check if a chunk is in the cache.
     * 
     * @param key The chunk key
     * @return true if the chunk is cached, false otherwise
     */
    public boolean hasChunk(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        
        cacheLock.readLock().lock();
        try {
            return cache.containsKey(key);
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Get the number of cached chunks.
     * 
     * @return The cache size
     */
    public int getCacheSize() {
        cacheLock.readLock().lock();
        try {
            return cache.size();
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Get cache capacity.
     * 
     * @return The maximum number of chunks that can be cached
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    /**
     * Clear all chunks from the cache.
     */
    public void clear() {
        cacheLock.writeLock().lock();
        try {
            int sizeBefore = cache.size();
            cache.clear();
            
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Cleared {} chunks from cache", sizeBefore);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Get cache statistics.
     * 
     * @return CacheStats object containing performance metrics
     */
    public CacheStats getStats() {
        cacheLock.readLock().lock();
        try {
            int currentHits = hitCount.get();
            int currentMisses = missCount.get();
            int currentEvictions = evictionCount.get();
            long totalHitCount = totalHits.get();
            long totalMissCount = totalMisses.get();
            int cacheSize = cache.size();
            
            double hitRate = (totalHitCount + totalMissCount) > 0 ? 
                (double) totalHitCount / (totalHitCount + totalMissCount) : 0.0;
            
            return new CacheStats(currentHits, currentMisses, currentEvictions, 
                                cacheSize, maxCapacity, hitRate, evictionPolicy.getPolicyName());
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Reset cache statistics.
     */
    public void resetStats() {
        hitCount.set(0);
        missCount.set(0);
        evictionCount.set(0);
    }
    
    /**
     * Update chunk state.
     * 
     * @param key The chunk key
     * @param state The new state
     */
    public void updateChunkState(String key, ChunkState state) {
        if (key == null || key.isEmpty() || state == null) {
            return;
        }
        
        cacheLock.writeLock().lock();
        try {
            CachedChunk cached = cache.get(key);
            if (cached != null) {
                cached.setState(state);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Mark a chunk as dirty.
     * 
     * @param key The chunk key
     */
    public void markChunkDirty(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        
        cacheLock.writeLock().lock();
        try {
            CachedChunk cached = cache.get(key);
            if (cached != null) {
                cached.markDirty();
                cached.setState(ChunkState.DIRTY);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Cache statistics container.
     */
    public static class CacheStats {
        private final int hits;
        private final int misses;
        private final int evictions;
        private final int cacheSize;
        private final int maxCapacity;
        private final double hitRate;
        private final String evictionPolicy;
        
        public CacheStats(int hits, int misses, int evictions, int cacheSize, 
                         int maxCapacity, double hitRate, String evictionPolicy) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.cacheSize = cacheSize;
            this.maxCapacity = maxCapacity;
            this.hitRate = hitRate;
            this.evictionPolicy = evictionPolicy;
        }
        
        public int getHits() { return hits; }
        public int getMisses() { return misses; }
        public int getEvictions() { return evictions; }
        public int getCacheSize() { return cacheSize; }
        public int getMaxCapacity() { return maxCapacity; }
        public double getHitRate() { return hitRate; }
        public String getEvictionPolicy() { return evictionPolicy; }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, evictions=%d, " +
                               "size=%d/%d, hitRate=%.2f%%, policy=%s}", 
                               hits, misses, evictions, cacheSize, maxCapacity, 
                               hitRate * 100, evictionPolicy);
        }
    }
}