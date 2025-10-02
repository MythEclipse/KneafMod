package com.kneaf.core.chunkstorage;

import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory cache for LevelChunk objects with configurable capacity and eviction policies.
 * Tracks chunk state (Hot/Cold/Dirty/Serialized) for intelligent caching decisions.
 */
public class ChunkCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkCache.class);
    
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
    
    // Swap statistics
    private final AtomicInteger swapOutCount = new AtomicInteger(0);
    private final AtomicInteger swapInCount = new AtomicInteger(0);
    private final AtomicInteger swapOutFailureCount = new AtomicInteger(0);
    private final AtomicInteger swapInFailureCount = new AtomicInteger(0);
    private final AtomicLong totalSwapOutTime = new AtomicLong(0);
    private final AtomicLong totalSwapInTime = new AtomicLong(0);
    
    // Memory pressure tracking
    private volatile MemoryPressureLevel memoryPressureLevel = MemoryPressureLevel.NORMAL;
    
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
        private volatile long swapStartTime;
        private volatile long swapCompleteTime;
        private volatile String swapError;
        
        public CachedChunk(LevelChunk chunk) {
            this.chunk = chunk;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessTime = creationTime;
            this.accessCount = new AtomicInteger(1);
            this.state = ChunkState.HOT;
            this.dirty = false;
            this.swapStartTime = 0;
            this.swapCompleteTime = 0;
            this.swapError = null;
        }
        
        public LevelChunk getChunk() { return chunk; }
        public long getLastAccessTime() { return lastAccessTime; }
        public long getCreationTime() { return creationTime; }
        public int getAccessCount() { return accessCount.get(); }
        public ChunkState getState() { return state; }
        public boolean isDirty() { return dirty; }
        public long getSwapStartTime() { return swapStartTime; }
        public long getSwapCompleteTime() { return swapCompleteTime; }
        public String getSwapError() { return swapError; }
        
        public void markAccessed() {
            // Note: lastAccessTime is intentionally not updated to maintain immutability
            accessCount.incrementAndGet();
        }
        
        public void markDirty() { this.dirty = true; }
        public void markClean() { this.dirty = false; }
        public void setState(ChunkState state) { this.state = state; }
        public void setSwapStartTime(long time) { this.swapStartTime = time; }
        public void setSwapCompleteTime(long time) { this.swapCompleteTime = time; }
        public void setSwapError(String error) { this.swapError = error; }
        
        /**
         * Check if this chunk is currently being swapped
         */
        public boolean isSwapping() {
            return state == ChunkState.SWAPPING_OUT || state == ChunkState.SWAPPING_IN;
        }
        
        /**
         * Check if this chunk is swapped out
         */
        public boolean isSwapped() {
            return state == ChunkState.SWAPPED;
        }
        
        /**
         * Get swap duration in milliseconds
         */
        public long getSwapDuration() {
            if (swapStartTime > 0 && swapCompleteTime > swapStartTime) {
                return swapCompleteTime - swapStartTime;
            }
            return 0;
        }
    }
    
    /**
     * Chunk states for intelligent caching decisions.
     */
    public enum ChunkState {
        HOT,            // Recently accessed, keep in cache
        COLD,           // Less recently accessed, candidate for eviction
        DIRTY,          // Modified, needs to be saved before eviction
        SERIALIZED,     // Recently serialized, can be evicted if needed
        SWAPPING_OUT,   // Currently being swapped out to secondary storage
        SWAPPED,        // Swapped out to secondary storage
        SWAPPING_IN     // Currently being swapped in from secondary storage
    }
    
    /**
     * Memory pressure levels for swap-aware eviction decisions.
     */
    public enum MemoryPressureLevel {
        NORMAL,     // Normal memory usage, no swapping needed
        ELEVATED,   // Elevated memory usage, consider swapping cold chunks
        HIGH,       // High memory pressure, swap out cold chunks
        CRITICAL    // Critical memory pressure, aggressive swapping
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
                CachedChunk cached = entry.getValue();
                // Skip chunks that are currently being swapped
                if (cached.isSwapping()) {
                    continue;
                }
                if (cached.getLastAccessTime() < oldestTime) {
                    oldestTime = cached.getLastAccessTime();
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
                
                // Skip chunks that are currently being swapped
                if (cached.isSwapping()) {
                    continue;
                }
                
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
    
    /**
     * Swap-aware eviction policy that prioritizes chunks for swapping based on state and memory pressure.
     */
    public static class SwapAwareEvictionPolicy implements EvictionPolicy {
        private final MemoryPressureLevel memoryPressure;
        
        public SwapAwareEvictionPolicy(MemoryPressureLevel memoryPressure) {
            this.memoryPressure = memoryPressure;
        }
        
        @Override
        public String selectChunkToEvict(Map<String, CachedChunk> cache) {
            String bestKey = null;
            double bestScore = Double.MAX_VALUE;
            long currentTime = System.currentTimeMillis();
            
            for (Map.Entry<String, CachedChunk> entry : cache.entrySet()) {
                CachedChunk cached = entry.getValue();
                
                // Skip chunks that are currently being swapped
                if (cached.isSwapping()) {
                    continue;
                }
                
                // Calculate swap priority score (lower is better for eviction)
                double score = calculateSwapPriorityScore(cached, currentTime);
                
                if (score < bestScore) {
                    bestScore = score;
                    bestKey = entry.getKey();
                }
            }
            
            return bestKey;
        }
        
        private double calculateSwapPriorityScore(CachedChunk cached, long currentTime) {
            double baseScore = 0.0;
            
            // State-based scoring
            switch (cached.getState()) {
                case COLD:
                    baseScore += 10.0;
                    break;
                case SERIALIZED:
                    baseScore += 5.0;
                    break;
                case HOT:
                    baseScore += 50.0;
                    break;
                case DIRTY:
                    baseScore += 100.0;
                    break;
                case SWAPPED:
                    baseScore += 1000.0; // Already swapped, shouldn't be selected
                    break;
                default:
                    baseScore += 25.0;
            }
            
            // Time-based scoring
            double timeScore = (currentTime - cached.getLastAccessTime()) / 1000.0; // seconds
            baseScore += timeScore * 0.1;
            
            // Access count scoring
            double accessScore = 1.0 / Math.max(1, cached.getAccessCount());
            baseScore *= accessScore;
            
            // Memory pressure multiplier
            double pressureMultiplier = getMemoryPressureMultiplier();
            baseScore *= pressureMultiplier;
            
            // Dirty penalty (dirty chunks are expensive to swap)
            if (cached.isDirty()) {
                baseScore *= 2.0;
            }
            
            return baseScore;
        }
        
        private double getMemoryPressureMultiplier() {
            switch (memoryPressure) {
                case CRITICAL:
                    return 0.1; // Strongly prioritize swapping
                case HIGH:
                    return 0.3;
                case ELEVATED:
                    return 0.7;
                case NORMAL:
                default:
                    return 1.0; // Normal priority
            }
        }
        
        @Override
        public String getPolicyName() {
            return "SwapAware";
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
            
            // Swap statistics
            int currentSwapOuts = swapOutCount.get();
            int currentSwapIns = swapInCount.get();
            int currentSwapOutFailures = swapOutFailureCount.get();
            int currentSwapInFailures = swapInFailureCount.get();
            long totalSwapOutDuration = totalSwapOutTime.get();
            long totalSwapInDuration = totalSwapInTime.get();
            
            return new CacheStats(currentHits, currentMisses, currentEvictions,
                                cacheSize, maxCapacity, hitRate, evictionPolicy.getPolicyName(),
                                currentSwapOuts, currentSwapIns, currentSwapOutFailures,
                                currentSwapInFailures, totalSwapOutDuration, totalSwapInDuration);
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
        swapOutCount.set(0);
        swapInCount.set(0);
        swapOutFailureCount.set(0);
        swapInFailureCount.set(0);
        totalSwapOutTime.set(0);
        totalSwapInTime.set(0);
    }
    
    /**
     * Set memory pressure level.
     *
     * @param pressureLevel The memory pressure level
     */
    public void setMemoryPressureLevel(MemoryPressureLevel pressureLevel) {
        if (pressureLevel != null) {
            this.memoryPressureLevel = pressureLevel;
            LOGGER.debug("Memory pressure level set to {}", pressureLevel);
        }
    }
    
    /**
     * Get current memory pressure level.
     *
     * @return The current memory pressure level
     */
    public MemoryPressureLevel getMemoryPressureLevel() {
        return memoryPressureLevel;
    }
    
    /**
     * Initiate swap-out operation for a chunk.
     *
     * @param key The chunk key
     * @param swapCallback Callback for swap completion
     * @return CompletableFuture that completes when swap-out is done
     */
    public CompletableFuture<Boolean> initiateSwapOut(String key, Consumer<Boolean> swapCallback) {
        if (key == null || key.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            cacheLock.writeLock().lock();
            try {
                CachedChunk cached = cache.get(key);
                if (cached == null) {
                    LOGGER.warn("Cannot swap out non-existent chunk: {}", key);
                    return false;
                }
                
                if (cached.isSwapping() || cached.isSwapped()) {
                    LOGGER.debug("Chunk {} is already swapping or swapped", key);
                    return false;
                }
                
                // Start swap operation
                long startTime = System.currentTimeMillis();
                cached.setState(ChunkState.SWAPPING_OUT);
                cached.setSwapStartTime(startTime);
                
                LOGGER.debug("Initiated swap-out for chunk {}", key);
                
                // Simulate swap operation (in real implementation, this would be async)
                try {
                    Thread.sleep(100); // Simulate swap time
                    
                    // Complete swap
                    cached.setState(ChunkState.SWAPPED);
                    cached.setSwapCompleteTime(System.currentTimeMillis());
                    
                    long duration = cached.getSwapDuration();
                    totalSwapOutTime.addAndGet(duration);
                    swapOutCount.incrementAndGet();
                    
                    LOGGER.debug("Completed swap-out for chunk {} in {}ms", key, duration);
                    
                    if (swapCallback != null) {
                        swapCallback.accept(true);
                    }
                    
                    return true;
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cached.setState(ChunkState.COLD); // Revert state
                    cached.setSwapError("Swap interrupted");
                    swapOutFailureCount.incrementAndGet();
                    
                    LOGGER.error("Swap-out interrupted for chunk {}", key, e);
                    
                    if (swapCallback != null) {
                        swapCallback.accept(false);
                    }
                    
                    return false;
                }
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * Initiate swap-in operation for a chunk.
     *
     * @param key The chunk key
     * @param swapCallback Callback for swap completion
     * @return CompletableFuture that completes when swap-in is done
     */
    public CompletableFuture<Boolean> initiateSwapIn(String key, Consumer<Boolean> swapCallback) {
        if (key == null || key.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            cacheLock.writeLock().lock();
            try {
                CachedChunk cached = cache.get(key);
                if (cached == null) {
                    LOGGER.warn("Cannot swap in non-existent chunk: {}", key);
                    return false;
                }
                
                if (!cached.isSwapped()) {
                    LOGGER.debug("Chunk {} is not swapped out", key);
                    return false;
                }
                
                // Start swap-in operation
                long startTime = System.currentTimeMillis();
                cached.setState(ChunkState.SWAPPING_IN);
                cached.setSwapStartTime(startTime);
                
                LOGGER.debug("Initiated swap-in for chunk {}", key);
                
                // Simulate swap operation (in real implementation, this would be async)
                try {
                    Thread.sleep(150); // Simulate swap time (slightly longer for swap-in)
                    
                    // Complete swap
                    cached.setState(ChunkState.HOT);
                    cached.setSwapCompleteTime(System.currentTimeMillis());
                    
                    long duration = cached.getSwapDuration();
                    totalSwapInTime.addAndGet(duration);
                    swapInCount.incrementAndGet();
                    
                    LOGGER.debug("Completed swap-in for chunk {} in {}ms", key, duration);
                    
                    if (swapCallback != null) {
                        swapCallback.accept(true);
                    }
                    
                    return true;
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cached.setState(ChunkState.SWAPPED); // Revert state
                    cached.setSwapError("Swap-in interrupted");
                    swapInFailureCount.incrementAndGet();
                    
                    LOGGER.error("Swap-in interrupted for chunk {}", key, e);
                    
                    if (swapCallback != null) {
                        swapCallback.accept(false);
                    }
                    
                    return false;
                }
                
            } finally {
                cacheLock.writeLock().unlock();
            }
        });
    }
    
    /**
     * Perform swap-aware eviction based on memory pressure.
     *
     * @param targetChunks Number of chunks to evict
     * @return Number of chunks successfully marked for swapping
     */
    public int performSwapAwareEviction(int targetChunks) {
        if (targetChunks <= 0) {
            return 0;
        }
        
        int swappedCount = 0;
        cacheLock.writeLock().lock();
        try {
            // Use swap-aware eviction policy based on current memory pressure
            SwapAwareEvictionPolicy swapPolicy = new SwapAwareEvictionPolicy(memoryPressureLevel);
            
            for (int i = 0; i < targetChunks; i++) {
                String keyToSwap = swapPolicy.selectChunkToEvict(cache);
                if (keyToSwap != null) {
                    CachedChunk cached = cache.get(keyToSwap);
                    if (cached != null && !cached.isSwapping() && !cached.isSwapped()) {
                        // Initiate swap-out without removing from cache (lazy eviction)
                        initiateSwapOut(keyToSwap, success -> {
                            if (success) {
                                LOGGER.debug("Lazy swap-out completed for chunk {}", keyToSwap);
                            }
                        });
                        swappedCount++;
                    }
                }
            }
            
            LOGGER.info("Initiated swap-aware eviction for {} chunks (pressure: {})",
                       swappedCount, memoryPressureLevel);
            
        } finally {
            cacheLock.writeLock().unlock();
        }
        
        return swappedCount;
    }
    
    /**
     * Check if a chunk can be evicted (not currently swapping).
     *
     * @param key The chunk key
     * @return true if the chunk can be evicted, false otherwise
     */
    public boolean canEvict(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        
        cacheLock.readLock().lock();
        try {
            CachedChunk cached = cache.get(key);
            return cached != null && !cached.isSwapping();
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Get swap statistics.
     *
     * @return SwapStats object containing swap metrics
     */
    public SwapStats getSwapStats() {
        cacheLock.readLock().lock();
        try {
            int outs = swapOutCount.get();
            int ins = swapInCount.get();
            int outFailures = swapOutFailureCount.get();
            int inFailures = swapInFailureCount.get();
            long totalOutTime = totalSwapOutTime.get();
            long totalInTime = totalSwapInTime.get();
            
            double avgOutTime = outs > 0 ? (double) totalOutTime / outs : 0.0;
            double avgInTime = ins > 0 ? (double) totalInTime / ins : 0.0;
            int totalSwaps = outs + ins;
            int totalFailures = outFailures + inFailures;
            double failureRate = totalSwaps > 0 ? (double) totalFailures / totalSwaps : 0.0;
            
            return new SwapStats(outs, ins, outFailures, inFailures,
                               avgOutTime, avgInTime, failureRate);
        } finally {
            cacheLock.readLock().unlock();
        }
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
        private final int swapOuts;
        private final int swapIns;
        private final int swapOutFailures;
        private final int swapInFailures;
        private final long totalSwapOutTime;
        private final long totalSwapInTime;
        
        public CacheStats(int hits, int misses, int evictions, int cacheSize,
                         int maxCapacity, double hitRate, String evictionPolicy,
                         int swapOuts, int swapIns, int swapOutFailures,
                         int swapInFailures, long totalSwapOutTime, long totalSwapInTime) {
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.cacheSize = cacheSize;
            this.maxCapacity = maxCapacity;
            this.hitRate = hitRate;
            this.evictionPolicy = evictionPolicy;
            this.swapOuts = swapOuts;
            this.swapIns = swapIns;
            this.swapOutFailures = swapOutFailures;
            this.swapInFailures = swapInFailures;
            this.totalSwapOutTime = totalSwapOutTime;
            this.totalSwapInTime = totalSwapInTime;
        }
        
        public int getHits() { return hits; }
        public int getMisses() { return misses; }
        public int getEvictions() { return evictions; }
        public int getCacheSize() { return cacheSize; }
        public int getMaxCapacity() { return maxCapacity; }
        public double getHitRate() { return hitRate; }
        public String getEvictionPolicy() { return evictionPolicy; }
        public int getSwapOuts() { return swapOuts; }
        public int getSwapIns() { return swapIns; }
        public int getSwapOutFailures() { return swapOutFailures; }
        public int getSwapInFailures() { return swapInFailures; }
        public long getTotalSwapOutTime() { return totalSwapOutTime; }
        public long getTotalSwapInTime() { return totalSwapInTime; }
        
        /**
         * Get average swap out time in milliseconds
         */
        public double getAverageSwapOutTime() {
            return swapOuts > 0 ? (double) totalSwapOutTime / swapOuts : 0.0;
        }
        
        /**
         * Get average swap in time in milliseconds
         */
        public double getAverageSwapInTime() {
            return swapIns > 0 ? (double) totalSwapInTime / swapIns : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, evictions=%d, " +
                               "size=%d/%d, hitRate=%.2f%%, policy=%s, " +
                               "swapOuts=%d, swapIns=%d, swapFailures=%d/%d, " +
                               "avgSwapOutTime=%.2fms, avgSwapInTime=%.2fms}",
                               hits, misses, evictions, cacheSize, maxCapacity,
                               hitRate * 100, evictionPolicy, swapOuts, swapIns,
                               swapOutFailures, swapInFailures,
                               getAverageSwapOutTime(), getAverageSwapInTime());
        }
    }
}
    /**
     * Swap statistics container.
     */
    class SwapStats {
        private final int swapOuts;
        private final int swapIns;
        private final int swapOutFailures;
        private final int swapInFailures;
        private final double avgSwapOutTime;
        private final double avgSwapInTime;
        private final double failureRate;
        
        public SwapStats(int swapOuts, int swapIns, int swapOutFailures, 
                        int swapInFailures, double avgSwapOutTime, 
                        double avgSwapInTime, double failureRate) {
            this.swapOuts = swapOuts;
            this.swapIns = swapIns;
            this.swapOutFailures = swapOutFailures;
            this.swapInFailures = swapInFailures;
            this.avgSwapOutTime = avgSwapOutTime;
            this.avgSwapInTime = avgSwapInTime;
            this.failureRate = failureRate;
        }
        
        public int getSwapOuts() { return swapOuts; }
        public int getSwapIns() { return swapIns; }
        public int getSwapOutFailures() { return swapOutFailures; }
        public int getSwapInFailures() { return swapInFailures; }
        public double getAvgSwapOutTime() { return avgSwapOutTime; }
        public double getAvgSwapInTime() { return avgSwapInTime; }
        public double getFailureRate() { return failureRate; }
        public int getTotalSwaps() { return swapOuts + swapIns; }
        public int getTotalFailures() { return swapOutFailures + swapInFailures; }
        
        @Override
        public String toString() {
            return String.format("SwapStats{swapOuts=%d, swapIns=%d, failures=%d/%d, " +
                               "failureRate=%.2f%%, avgSwapOutTime=%.2fms, avgSwapInTime=%.2fms}", 
                               swapOuts, swapIns, getTotalFailures(), getTotalSwaps(), 
                               failureRate * 100, avgSwapOutTime, avgSwapInTime);
        }
    }