package com.kneaf.core.chunkstorage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages virtual memory operations including swap-in/swap-out and memory pressure detection.
 * Coordinates between cache, database, and memory pools to optimize memory usage.
 */
public class SwapManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwapManager.class);
    
    // Memory pressure thresholds (as percentages of max heap)
    private static final double CRITICAL_MEMORY_THRESHOLD = 0.95; // 95% of max heap
    private static final double HIGH_MEMORY_THRESHOLD = 0.85;     // 85% of max heap
    private static final double ELEVATED_MEMORY_THRESHOLD = 0.75; // 75% of max heap
    private static final double NORMAL_MEMORY_THRESHOLD = 0.60;   // 60% of max heap
    
    // Swap configuration
    private final SwapConfig config;
    private final MemoryMXBean memoryBean;
    private final AtomicBoolean enabled;
    private final AtomicBoolean shutdown;
    
    // Component references
    private ChunkCache chunkCache;
    private RustDatabaseAdapter databaseAdapter;
    private ExecutorService swapExecutor;
    private ScheduledExecutorService monitorExecutor;
    
    // Memory pressure tracking
    private volatile MemoryPressureLevel currentPressureLevel;
    private final AtomicLong lastPressureCheck;
    private final AtomicInteger pressureTriggerCount;
    private final AtomicLong totalSwapOperations;
    private final AtomicLong failedSwapOperations;
    
    // Swap statistics
    private final SwapStatistics swapStats;
    private final Map<String, SwapOperation> activeSwaps;
    
    // Performance monitoring integration
    private final PerformanceMonitorAdapter performanceMonitor;
    
    /**
     * Memory pressure levels for intelligent swap decisions.
     */
    public enum MemoryPressureLevel {
        NORMAL,     // Normal memory usage, minimal swapping
        ELEVATED,   // Elevated memory usage, prepare for swapping
        HIGH,       // High memory pressure, active swapping
        CRITICAL    // Critical memory pressure, aggressive swapping
    }
    
    /**
     * Swap operation types.
     */
    public enum SwapOperationType {
        SWAP_OUT,   // Move chunk from memory to disk
        SWAP_IN,    // Move chunk from disk to memory
        BULK_SWAP   // Multiple swap operations
    }
    
    /**
     * Swap operation status.
     */
    public enum SwapStatus {
        PENDING,    // Operation queued
        IN_PROGRESS, // Operation currently executing
        COMPLETED,  // Operation completed successfully
        FAILED,     // Operation failed
        CANCELLED   // Operation cancelled
    }
    
    /**
     * Represents an active swap operation.
     */
    public static class SwapOperation {
        private final String chunkKey;
        private final SwapOperationType operationType;
        private final long startTime;
        private volatile SwapStatus status;
        private volatile long endTime;
        private volatile String errorMessage;
        private final CompletableFuture<Boolean> future;
        
        public SwapOperation(String chunkKey, SwapOperationType operationType) {
            this.chunkKey = chunkKey;
            this.operationType = operationType;
            this.startTime = System.currentTimeMillis();
            this.status = SwapStatus.PENDING;
            this.future = new CompletableFuture<>();
        }
        
        public String getChunkKey() { return chunkKey; }
        public SwapOperationType getOperationType() { return operationType; }
        public long getStartTime() { return startTime; }
        public SwapStatus getStatus() { return status; }
        public long getEndTime() { return endTime; }
        public String getErrorMessage() { return errorMessage; }
        public CompletableFuture<Boolean> getFuture() { return future; }
        
        public void setStatus(SwapStatus status) { this.status = status; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public long getDuration() {
            return (status == SwapStatus.COMPLETED || status == SwapStatus.FAILED) ? 
                   endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }
    
    /**
     * Swap configuration settings.
     */
    public static class SwapConfig {
        private boolean enabled = true;
        private long memoryCheckIntervalMs = 5000; // 5 seconds
        private int maxConcurrentSwaps = 10;
        private int swapBatchSize = 50;
        private long swapTimeoutMs = 30000; // 30 seconds
        private boolean enableAutomaticSwapping = true;
        private double criticalMemoryThreshold = CRITICAL_MEMORY_THRESHOLD;
        private double highMemoryThreshold = HIGH_MEMORY_THRESHOLD;
        private double elevatedMemoryThreshold = ELEVATED_MEMORY_THRESHOLD;
        private int minSwapChunkAgeMs = 60000; // 1 minute
        private boolean enableSwapStatistics = true;
        private boolean enablePerformanceMonitoring = true;
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public long getMemoryCheckIntervalMs() { return memoryCheckIntervalMs; }
        public void setMemoryCheckIntervalMs(long memoryCheckIntervalMs) { 
            this.memoryCheckIntervalMs = memoryCheckIntervalMs; 
        }
        
        public int getMaxConcurrentSwaps() { return maxConcurrentSwaps; }
        public void setMaxConcurrentSwaps(int maxConcurrentSwaps) { 
            this.maxConcurrentSwaps = maxConcurrentSwaps; 
        }
        
        public int getSwapBatchSize() { return swapBatchSize; }
        public void setSwapBatchSize(int swapBatchSize) { 
            this.swapBatchSize = swapBatchSize; 
        }
        
        public long getSwapTimeoutMs() { return swapTimeoutMs; }
        public void setSwapTimeoutMs(long swapTimeoutMs) { 
            this.swapTimeoutMs = swapTimeoutMs; 
        }
        
        public boolean isEnableAutomaticSwapping() { return enableAutomaticSwapping; }
        public void setEnableAutomaticSwapping(boolean enableAutomaticSwapping) { 
            this.enableAutomaticSwapping = enableAutomaticSwapping; 
        }
        
        public double getCriticalMemoryThreshold() { return criticalMemoryThreshold; }
        public void setCriticalMemoryThreshold(double criticalMemoryThreshold) { 
            this.criticalMemoryThreshold = criticalMemoryThreshold; 
        }
        
        public double getHighMemoryThreshold() { return highMemoryThreshold; }
        public void setHighMemoryThreshold(double highMemoryThreshold) { 
            this.highMemoryThreshold = highMemoryThreshold; 
        }
        
        public double getElevatedMemoryThreshold() { return elevatedMemoryThreshold; }
        public void setElevatedMemoryThreshold(double elevatedMemoryThreshold) { 
            this.elevatedMemoryThreshold = elevatedMemoryThreshold; 
        }
        
        public int getMinSwapChunkAgeMs() { return minSwapChunkAgeMs; }
        public void setMinSwapChunkAgeMs(int minSwapChunkAgeMs) { 
            this.minSwapChunkAgeMs = minSwapChunkAgeMs; 
        }
        
        public boolean isEnableSwapStatistics() { return enableSwapStatistics; }
        public void setEnableSwapStatistics(boolean enableSwapStatistics) { 
            this.enableSwapStatistics = enableSwapStatistics; 
        }
        
        public boolean isEnablePerformanceMonitoring() { return enablePerformanceMonitoring; }
        public void setEnablePerformanceMonitoring(boolean enablePerformanceMonitoring) { 
            this.enablePerformanceMonitoring = enablePerformanceMonitoring; 
        }
    }
    
    /**
     * Swap statistics container.
     */
    public static class SwapStatistics {
        private final AtomicLong totalSwapOuts = new AtomicLong(0);
        private final AtomicLong totalSwapIns = new AtomicLong(0);
        private final AtomicLong totalFailures = new AtomicLong(0);
        private final AtomicLong totalSwapOutTime = new AtomicLong(0);
        private final AtomicLong totalSwapInTime = new AtomicLong(0);
        private final AtomicLong totalBytesSwappedOut = new AtomicLong(0);
        private final AtomicLong totalBytesSwappedIn = new AtomicLong(0);
        
        public void recordSwapOut(long durationMs, long bytes) {
            totalSwapOuts.incrementAndGet();
            totalSwapOutTime.addAndGet(durationMs);
            totalBytesSwappedOut.addAndGet(bytes);
        }
        
        public void recordSwapIn(long durationMs, long bytes) {
            totalSwapIns.incrementAndGet();
            totalSwapInTime.addAndGet(durationMs);
            totalBytesSwappedIn.addAndGet(bytes);
        }
        
        public void recordFailure() {
            totalFailures.incrementAndGet();
        }
        
        public long getTotalSwapOuts() { return totalSwapOuts.get(); }
        public long getTotalSwapIns() { return totalSwapIns.get(); }
        public long getTotalFailures() { return totalFailures.get(); }
        public long getTotalSwapOutTime() { return totalSwapOutTime.get(); }
        public long getTotalSwapInTime() { return totalSwapInTime.get(); }
        public long getTotalBytesSwappedOut() { return totalBytesSwappedOut.get(); }
        public long getTotalBytesSwappedIn() { return totalBytesSwappedIn.get(); }
        
        public double getAverageSwapOutTime() {
            long outs = totalSwapOuts.get();
            return outs > 0 ? (double) totalSwapOutTime.get() / outs : 0.0;
        }
        
        public double getAverageSwapInTime() {
            long ins = totalSwapIns.get();
            return ins > 0 ? (double) totalSwapInTime.get() / ins : 0.0;
        }
        
        public double getFailureRate() {
            long total = totalSwapOuts.get() + totalSwapIns.get();
            return total > 0 ? (double) totalFailures.get() / total : 0.0;
        }
        
        public double getSwapThroughputMBps() {
            long totalBytes = totalBytesSwappedOut.get() + totalBytesSwappedIn.get();
            long totalTime = totalSwapOutTime.get() + totalSwapInTime.get();
            return totalTime > 0 ? (double) totalBytes / (totalTime * 1000.0) : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("SwapStatistics{swapOuts=%d, swapIns=%d, failures=%d, " +
                               "avgSwapOutTime=%.2fms, avgSwapInTime=%.2fms, failureRate=%.2f%%, " +
                               "throughput=%.2f MB/s, bytesOut=%d, bytesIn=%d}",
                               getTotalSwapOuts(), getTotalSwapIns(), getTotalFailures(),
                               getAverageSwapOutTime(), getAverageSwapInTime(), 
                               getFailureRate() * 100, getSwapThroughputMBps(),
                               getTotalBytesSwappedOut(), getTotalBytesSwappedIn());
        }
    }
    
    /**
     * Performance monitoring adapter for integration with Rust performance monitoring.
     */
    private static class PerformanceMonitorAdapter {
        private final boolean enabled;
        
        public PerformanceMonitorAdapter(boolean enabled) {
            this.enabled = enabled;
        }
        
        public void recordSwapIn(long bytes, long durationMs) {
            if (enabled) {
                // Integration with Rust performance monitoring would go here
                LOGGER.debug("Recording swap-in: {} bytes in {}ms", bytes, durationMs);
            }
        }
        
        public void recordSwapOut(long bytes, long durationMs) {
            if (enabled) {
                // Integration with Rust performance monitoring would go here
                LOGGER.debug("Recording swap-out: {} bytes in {}ms", bytes, durationMs);
            }
        }
        
        public void recordSwapFailure(String operationType, String error) {
            if (enabled) {
                // Integration with Rust performance monitoring would go here
                LOGGER.error("Recording swap failure: {} - {}", operationType, error);
            }
        }
        
        public void recordMemoryPressure(String level, boolean triggeredCleanup) {
            if (enabled) {
                // Integration with Rust performance monitoring would go here
                LOGGER.info("Recording memory pressure: {} (cleanup: {})", level, triggeredCleanup);
            }
        }
    }
    
    /**
     * Create a new SwapManager with the given configuration.
     * 
     * @param config The swap configuration
     */
    public SwapManager(SwapConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Swap configuration cannot be null");
        }
        
        this.config = config;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.enabled = new AtomicBoolean(config.isEnabled());
        this.shutdown = new AtomicBoolean(false);
        this.currentPressureLevel = MemoryPressureLevel.NORMAL;
        this.lastPressureCheck = new AtomicLong(System.currentTimeMillis());
        this.pressureTriggerCount = new AtomicInteger(0);
        this.totalSwapOperations = new AtomicLong(0);
        this.failedSwapOperations = new AtomicLong(0);
        this.swapStats = new SwapStatistics();
        this.activeSwaps = new ConcurrentHashMap<>();
        this.performanceMonitor = new PerformanceMonitorAdapter(config.isEnablePerformanceMonitoring());
        
        if (config.isEnabled()) {
            initializeExecutors();
            startMemoryMonitoring();
            LOGGER.info("SwapManager initialized with config: {}", config);
        } else {
            LOGGER.info("SwapManager disabled");
        }
    }
    
    /**
     * Initialize the swap manager with component references.
     * 
     * @param chunkCache The chunk cache
     * @param databaseAdapter The database adapter
     */
    public void initializeComponents(ChunkCache chunkCache, RustDatabaseAdapter databaseAdapter) {
        if (!enabled.get()) {
            return;
        }
        
        this.chunkCache = chunkCache;
        this.databaseAdapter = databaseAdapter;
        
        LOGGER.info("SwapManager components initialized");
    }
    
    /**
     * Check current memory pressure level.
     * 
     * @return The current memory pressure level
     */
    public MemoryPressureLevel getMemoryPressureLevel() {
        return currentPressureLevel;
    }
    
    /**
     * Get current memory usage statistics.
     * 
     * @return Memory usage information
     */
    public MemoryUsageInfo getMemoryUsage() {
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        
        return new MemoryUsageInfo(
            heapUsage.getUsed(),
            heapUsage.getMax(),
            heapUsage.getCommitted(),
            nonHeapUsage.getUsed(),
            calculateMemoryUsagePercentage(heapUsage)
        );
    }
    
    /**
     * Perform swap-out operation for a chunk.
     * 
     * @param chunkKey The chunk key to swap out
     * @return CompletableFuture that completes when swap-out is done
     */
    public CompletableFuture<Boolean> swapOutChunk(String chunkKey) {
        if (!enabled.get() || shutdown.get()) {
            return CompletableFuture.completedFuture(false);
        }
        
        if (chunkKey == null || chunkKey.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check if swap is already in progress
        if (activeSwaps.containsKey(chunkKey)) {
            LOGGER.debug("Swap already in progress for chunk: {}", chunkKey);
            return activeSwaps.get(chunkKey).getFuture();
        }
        
        SwapOperation operation = new SwapOperation(chunkKey, SwapOperationType.SWAP_OUT);
        activeSwaps.put(chunkKey, operation);
        totalSwapOperations.incrementAndGet();
        
        CompletableFuture<Boolean> swapFuture = CompletableFuture.supplyAsync(() -> {
            try {
                operation.setStatus(SwapStatus.IN_PROGRESS);
                long startTime = System.currentTimeMillis();
                
                // Perform the actual swap operation
                boolean success = performSwapOut(chunkKey);
                
                long duration = System.currentTimeMillis() - startTime;
                operation.setEndTime(System.currentTimeMillis());
                
                if (success) {
                    operation.setStatus(SwapStatus.COMPLETED);
                    swapStats.recordSwapOut(duration, estimateChunkSize(chunkKey));
                    performanceMonitor.recordSwapOut(estimateChunkSize(chunkKey), duration);
                    LOGGER.debug("Successfully swapped out chunk: {} in {}ms", chunkKey, duration);
                } else {
                    operation.setStatus(SwapStatus.FAILED);
                    operation.setErrorMessage("Swap-out operation failed");
                    swapStats.recordFailure();
                    failedSwapOperations.incrementAndGet();
                    performanceMonitor.recordSwapFailure("swap_out", "Operation failed");
                    LOGGER.warn("Failed to swap out chunk: {}", chunkKey);
                }
                
                return success;
                
            } catch (Exception e) {
                operation.setStatus(SwapStatus.FAILED);
                operation.setErrorMessage(e.getMessage());
                operation.setEndTime(System.currentTimeMillis());
                swapStats.recordFailure();
                failedSwapOperations.incrementAndGet();
                performanceMonitor.recordSwapFailure("swap_out", e.getMessage());
                LOGGER.error("Exception during swap-out for chunk: {}", chunkKey, e);
                return false;
                
            } finally {
                activeSwaps.remove(chunkKey);
            }
        }, swapExecutor);
        
        operation.getFuture().completeAsync(() -> swapFuture.join(), swapExecutor);
        return operation.getFuture();
    }
    
    /**
     * Perform swap-in operation for a chunk.
     * 
     * @param chunkKey The chunk key to swap in
     * @return CompletableFuture that completes when swap-in is done
     */
    public CompletableFuture<Boolean> swapInChunk(String chunkKey) {
        if (!enabled.get() || shutdown.get()) {
            return CompletableFuture.completedFuture(false);
        }
        
        if (chunkKey == null || chunkKey.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check if swap is already in progress
        if (activeSwaps.containsKey(chunkKey)) {
            LOGGER.debug("Swap already in progress for chunk: {}", chunkKey);
            return activeSwaps.get(chunkKey).getFuture();
        }
        
        SwapOperation operation = new SwapOperation(chunkKey, SwapOperationType.SWAP_IN);
        activeSwaps.put(chunkKey, operation);
        totalSwapOperations.incrementAndGet();
        
        CompletableFuture<Boolean> swapFuture = CompletableFuture.supplyAsync(() -> {
            try {
                operation.setStatus(SwapStatus.IN_PROGRESS);
                long startTime = System.currentTimeMillis();
                
                // Perform the actual swap operation
                boolean success = performSwapIn(chunkKey);
                
                long duration = System.currentTimeMillis() - startTime;
                operation.setEndTime(System.currentTimeMillis());
                
                if (success) {
                    operation.setStatus(SwapStatus.COMPLETED);
                    swapStats.recordSwapIn(duration, estimateChunkSize(chunkKey));
                    performanceMonitor.recordSwapIn(estimateChunkSize(chunkKey), duration);
                    LOGGER.debug("Successfully swapped in chunk: {} in {}ms", chunkKey, duration);
                } else {
                    operation.setStatus(SwapStatus.FAILED);
                    operation.setErrorMessage("Swap-in operation failed");
                    swapStats.recordFailure();
                    failedSwapOperations.incrementAndGet();
                    performanceMonitor.recordSwapFailure("swap_in", "Operation failed");
                    LOGGER.warn("Failed to swap in chunk: {}", chunkKey);
                }
                
                return success;
                
            } catch (Exception e) {
                operation.setStatus(SwapStatus.FAILED);
                operation.setErrorMessage(e.getMessage());
                operation.setEndTime(System.currentTimeMillis());
                swapStats.recordFailure();
                failedSwapOperations.incrementAndGet();
                performanceMonitor.recordSwapFailure("swap_in", e.getMessage());
                LOGGER.error("Exception during swap-in for chunk: {}", chunkKey, e);
                return false;
                
            } finally {
                activeSwaps.remove(chunkKey);
            }
        }, swapExecutor);
        
        operation.getFuture().completeAsync(() -> swapFuture.join(), swapExecutor);
        return operation.getFuture();
    }
    
    /**
     * Perform bulk swap operations for memory pressure relief.
     * 
     * @param chunkKeys List of chunk keys to swap
     * @param operationType The type of swap operation
     * @return CompletableFuture that completes when all swaps are done
     */
    public CompletableFuture<Integer> bulkSwapChunks(List<String> chunkKeys, SwapOperationType operationType) {
        if (!enabled.get() || shutdown.get()) {
            return CompletableFuture.completedFuture(0);
        }
        
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        
        List<CompletableFuture<Boolean>> swapFutures = new ArrayList<>();
        
        for (String chunkKey : chunkKeys) {
            CompletableFuture<Boolean> swapFuture;
            switch (operationType) {
                case SWAP_OUT:
                    swapFuture = swapOutChunk(chunkKey);
                    break;
                case SWAP_IN:
                    swapFuture = swapInChunk(chunkKey);
                    break;
                default:
                    continue;
            }
            swapFutures.add(swapFuture);
        }
        
        return CompletableFuture.allOf(swapFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                long successCount = swapFutures.stream()
                    .filter(future -> future.join())
                    .count();
                return (int) successCount;
            });
    }
    
    /**
     * Get swap statistics.
     * 
     * @return Current swap statistics
     */
    public SwapStatistics getSwapStatistics() {
        return swapStats;
    }
    
    /**
     * Get active swap operations.
     * 
     * @return Map of active swap operations
     */
    public Map<String, SwapOperation> getActiveSwaps() {
        return new HashMap<>(activeSwaps);
    }
    
    /**
     * Get overall swap manager statistics.
     * 
     * @return Swap manager statistics
     */
    public SwapManagerStats getStats() {
        return new SwapManagerStats(
            enabled.get(),
            currentPressureLevel,
            totalSwapOperations.get(),
            failedSwapOperations.get(),
            activeSwaps.size(),
            pressureTriggerCount.get(),
            getMemoryUsage(),
            swapStats
        );
    }
    
    /**
     * Shutdown the swap manager and release resources.
     */
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down SwapManager");
            
            try {
                // Cancel all active swaps
                for (SwapOperation operation : activeSwaps.values()) {
                    operation.setStatus(SwapStatus.CANCELLED);
                    operation.getFuture().complete(false);
                }
                activeSwaps.clear();
                
                // Shutdown executors
                if (swapExecutor != null) {
                    swapExecutor.shutdown();
                    if (!swapExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        swapExecutor.shutdownNow();
                    }
                }
                
                if (monitorExecutor != null) {
                    monitorExecutor.shutdown();
                    if (!monitorExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                        monitorExecutor.shutdownNow();
                    }
                }
                
                LOGGER.info("SwapManager shutdown completed");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("SwapManager shutdown interrupted", e);
            }
        }
    }
    
    // Private helper methods
    
    private void initializeExecutors() {
        this.swapExecutor = Executors.newFixedThreadPool(
            config.getMaxConcurrentSwaps(),
            r -> {
                Thread thread = new Thread(r, "swap-worker");
                thread.setDaemon(true);
                return thread;
            }
        );
        
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "swap-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }
    
    private void startMemoryMonitoring() {
        monitorExecutor.scheduleAtFixedRate(
            this::checkMemoryPressure,
            0,
            config.getMemoryCheckIntervalMs(),
            TimeUnit.MILLISECONDS
        );
    }
    
    private void checkMemoryPressure() {
        try {
            MemoryUsageInfo usage = getMemoryUsage();
            MemoryPressureLevel newLevel = determineMemoryPressureLevel(usage.getUsagePercentage());
            
            if (newLevel != currentPressureLevel) {
                LOGGER.info("Memory pressure level changed from {} to {} (usage: {:.2f}%)",
                           currentPressureLevel, newLevel, usage.getUsagePercentage() * 100);
                
                currentPressureLevel = newLevel;
                lastPressureCheck.set(System.currentTimeMillis());
                
                // Update cache memory pressure level
                if (chunkCache != null) {
                    chunkCache.setMemoryPressureLevel(mapToCachePressureLevel(newLevel));
                }
                
                // Trigger automatic swapping if enabled
                if (config.isEnableAutomaticSwapping() && 
                    (newLevel == MemoryPressureLevel.HIGH || newLevel == MemoryPressureLevel.CRITICAL)) {
                    triggerAutomaticSwap(newLevel);
                }
                
                performanceMonitor.recordMemoryPressure(newLevel.toString(), 
                    newLevel == MemoryPressureLevel.HIGH || newLevel == MemoryPressureLevel.CRITICAL);
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during memory pressure check", e);
        }
    }
    
    private MemoryPressureLevel determineMemoryPressureLevel(double usagePercentage) {
        if (usagePercentage >= config.getCriticalMemoryThreshold()) {
            return MemoryPressureLevel.CRITICAL;
        } else if (usagePercentage >= config.getHighMemoryThreshold()) {
            return MemoryPressureLevel.HIGH;
        } else if (usagePercentage >= config.getElevatedMemoryThreshold()) {
            return MemoryPressureLevel.ELEVATED;
        } else {
            return MemoryPressureLevel.NORMAL;
        }
    }
    
    private ChunkCache.MemoryPressureLevel mapToCachePressureLevel(MemoryPressureLevel level) {
        switch (level) {
            case CRITICAL:
                return ChunkCache.MemoryPressureLevel.CRITICAL;
            case HIGH:
                return ChunkCache.MemoryPressureLevel.HIGH;
            case ELEVATED:
                return ChunkCache.MemoryPressureLevel.ELEVATED;
            default:
                return ChunkCache.MemoryPressureLevel.NORMAL;
        }
    }
    
    private double calculateMemoryUsagePercentage(MemoryUsage usage) {
        long max = usage.getMax();
        long used = usage.getUsed();
        return max > 0 ? (double) used / max : 0.0;
    }
    
    private void triggerAutomaticSwap(MemoryPressureLevel level) {
        pressureTriggerCount.incrementAndGet();
        
        int targetSwaps = determineTargetSwapCount(level);
        if (targetSwaps > 0 && chunkCache != null) {
            LOGGER.info("Triggering automatic swap for {} chunks due to {} memory pressure", 
                       targetSwaps, level);
            
            int swapped = chunkCache.performSwapAwareEviction(targetSwaps);
            LOGGER.info("Automatically initiated swap for {} chunks", swapped);
        }
    }
    
    private int determineTargetSwapCount(MemoryPressureLevel level) {
        switch (level) {
            case CRITICAL:
                return config.getSwapBatchSize() * 2; // Aggressive swapping
            case HIGH:
                return config.getSwapBatchSize();
            case ELEVATED:
                return config.getSwapBatchSize() / 2; // Conservative swapping
            default:
                return 0;
        }
    }
    
    private boolean performSwapOut(String chunkKey) throws Exception {
        if (chunkCache == null || databaseAdapter == null) {
            throw new IllegalStateException("SwapManager not properly initialized");
        }
        
        // Get chunk from cache
        Optional<ChunkCache.CachedChunk> cached = chunkCache.getChunk(chunkKey);
        if (!cached.isPresent()) {
            LOGGER.debug("Chunk not found in cache for swap-out: {}", chunkKey);
            return false;
        }
        
        ChunkCache.CachedChunk cachedChunk = cached.get();
        
        // Check if chunk can be swapped (not currently swapping)
        if (cachedChunk.isSwapping() || cachedChunk.isSwapped()) {
            LOGGER.debug("Chunk is already swapping or swapped: {}", chunkKey);
            return false;
        }
        
        // Check chunk age (don't swap very recent chunks)
        long chunkAge = System.currentTimeMillis() - cachedChunk.getCreationTime();
        if (chunkAge < config.getMinSwapChunkAgeMs()) {
            LOGGER.debug("Chunk too young for swap-out: {} (age: {}ms)", chunkKey, chunkAge);
            return false;
        }
        
        // Serialize chunk data
        byte[] serializedData = serializeChunk(cachedChunk.getChunk());
        if (serializedData == null) {
            LOGGER.error("Failed to serialize chunk for swap-out: {}", chunkKey);
            return false;
        }
        
        // Use database adapter's swap functionality
        boolean success = databaseAdapter.swapOutChunk(chunkKey);
        
        if (success) {
            // Update chunk state in cache
            chunkCache.updateChunkState(chunkKey, ChunkCache.ChunkState.SWAPPED);
            LOGGER.debug("Successfully swapped out chunk: {}", chunkKey);
        } else {
            LOGGER.warn("Failed to swap out chunk via database adapter: {}", chunkKey);
        }
        
        return success;
    }
    
    private boolean performSwapIn(String chunkKey) throws Exception {
        if (chunkCache == null || databaseAdapter == null) {
            throw new IllegalStateException("SwapManager not properly initialized");
        }
        
        // Check if chunk is already in cache
        if (chunkCache.hasChunk(chunkKey)) {
            Optional<ChunkCache.CachedChunk> cached = chunkCache.getChunk(chunkKey);
            if (cached.isPresent() && !cached.get().isSwapped()) {
                LOGGER.debug("Chunk already in cache and not swapped: {}", chunkKey);
                return true;
            }
        }
        
        // Use database adapter's swap functionality
        Optional<byte[]> swappedData = databaseAdapter.swapInChunk(chunkKey);
        
        if (swappedData.isPresent()) {
            // Update chunk state in cache
            chunkCache.updateChunkState(chunkKey, ChunkCache.ChunkState.HOT);
            LOGGER.debug("Successfully swapped in chunk: {}", chunkKey);
            return true;
        } else {
            LOGGER.warn("Failed to swap in chunk via database adapter: {}", chunkKey);
            return false;
        }
    }
    
    private byte[] serializeChunk(LevelChunk chunk) {
        // This would use the chunk serializer from ChunkStorageManager
        // For now, return a dummy implementation
        try {
            // Simulate serialization
            return new byte[1024]; // Placeholder
        } catch (Exception e) {
            LOGGER.error("Failed to serialize chunk", e);
            return null;
        }
    }
    
    private long estimateChunkSize(String chunkKey) {
        // Rough estimate of chunk size in bytes
        // In a real implementation, this would be more accurate
        return 16384; // 16KB estimate
    }
    
    // Inner classes for data structures
    
    /**
     * Memory usage information.
     */
    public static class MemoryUsageInfo {
        private final long heapUsed;
        private final long heapMax;
        private final long heapCommitted;
        private final long nonHeapUsed;
        private final double usagePercentage;
        
        public MemoryUsageInfo(long heapUsed, long heapMax, long heapCommitted, 
                             long nonHeapUsed, double usagePercentage) {
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.heapCommitted = heapCommitted;
            this.nonHeapUsed = nonHeapUsed;
            this.usagePercentage = usagePercentage;
        }
        
        public long getHeapUsed() { return heapUsed; }
        public long getHeapMax() { return heapMax; }
        public long getHeapCommitted() { return heapCommitted; }
        public long getNonHeapUsed() { return nonHeapUsed; }
        public double getUsagePercentage() { return usagePercentage; }
        
        @Override
        public String toString() {
            return String.format("MemoryUsageInfo{heapUsed=%d MB, heapMax=%d MB, usage=%.2f%%}",
                               heapUsed / (1024 * 1024), heapMax / (1024 * 1024), 
                               usagePercentage * 100);
        }
    }
    
    /**
     * Swap manager statistics.
     */
    public static class SwapManagerStats {
        private final boolean enabled;
        private final MemoryPressureLevel pressureLevel;
        private final long totalOperations;
        private final long failedOperations;
        private final int activeSwaps;
        private final int pressureTriggers;
        private final MemoryUsageInfo memoryUsage;
        private final SwapStatistics swapStats;
        
        public SwapManagerStats(boolean enabled, MemoryPressureLevel pressureLevel,
                              long totalOperations, long failedOperations, int activeSwaps,
                              int pressureTriggers, MemoryUsageInfo memoryUsage,
                              SwapStatistics swapStats) {
            this.enabled = enabled;
            this.pressureLevel = pressureLevel;
            this.totalOperations = totalOperations;
            this.failedOperations = failedOperations;
            this.activeSwaps = activeSwaps;
            this.pressureTriggers = pressureTriggers;
            this.memoryUsage = memoryUsage;
            this.swapStats = swapStats;
        }
        
        public boolean isEnabled() { return enabled; }
        public MemoryPressureLevel getPressureLevel() { return pressureLevel; }
        public long getTotalOperations() { return totalOperations; }
        public long getFailedOperations() { return failedOperations; }
        public int getActiveSwaps() { return activeSwaps; }
        public int getPressureTriggers() { return pressureTriggers; }
        public MemoryUsageInfo getMemoryUsage() { return memoryUsage; }
        public SwapStatistics getSwapStats() { return swapStats; }
        
        public double getFailureRate() {
            return totalOperations > 0 ? (double) failedOperations / totalOperations : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("SwapManagerStats{enabled=%s, pressure=%s, operations=%d, " +
                               "failed=%d, activeSwaps=%d, pressureTriggers=%d, failureRate=%.2f%%, " +
                               "memory=%s, swapStats=%s}",
                               enabled, pressureLevel, totalOperations, failedOperations,
                               activeSwaps, pressureTriggers, getFailureRate() * 100,
                               memoryUsage, swapStats);
        }
    }
}