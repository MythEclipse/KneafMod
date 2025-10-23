package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kneaf.core.async.AsyncLogger;
import com.kneaf.core.async.AsyncLoggingManager;
import com.kneaf.core.async.AsyncMetricsCollector;
import com.kneaf.core.mock.TestMockEntity;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Entity priority levels for CPU resource allocation
 */
enum EntityPriority {
    CRITICAL(0), HIGH(1), MEDIUM(2), LOW(3);
    
    private final int level;
    
    EntityPriority(int level) {
        this.level = level;
    }
    
    public int getLevel() {
        return level;
    }
}

/**
 * Lock-free spatial grid for efficient collision detection and entity queries
 * Uses atomic operations and lock-free data structures to eliminate ConcurrentHashMap overhead
 */
class SpatialGrid {
    private final double cellSize;
    private final LockFreeHashMap<String, LockFreeHashSet<Long>> grid;
    private final LockFreeHashMap<Long, String> entityCells;
    
    public SpatialGrid(double cellSize) {
        this.cellSize = cellSize;
        this.grid = new LockFreeHashMap<>();
        this.entityCells = new LockFreeHashMap<>();
    }
    
    public void updateEntity(long entityId, double x, double y, double z) {
        String newCell = getCellKey(x, y, z);
        
        // Remove from old cell if exists using lock-free operations
        String oldCell = entityCells.remove(entityId);
        if (oldCell != null && !oldCell.equals(newCell)) {
            LockFreeHashSet<Long> entities = grid.get(oldCell);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) {
                    grid.remove(oldCell);
                }
            }
        }
        
        // Add to new cell using lock-free operations
        if (!newCell.equals(oldCell)) {
            entityCells.put(entityId, newCell);
            grid.computeIfAbsent(newCell, k -> new LockFreeHashSet<>()).add(entityId);
        }
    }
    
    public void removeEntity(long entityId) {
        String cell = entityCells.remove(entityId);
        if (cell != null) {
            LockFreeHashSet<Long> entities = grid.get(cell);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) {
                    grid.remove(cell);
                }
            }
        }
    }
    
    public Set<Long> getNearbyEntities(double x, double y, double z, double radius) {
        String centerCell = getCellKey(x, y, z);
        int radiusCells = (int) (radius / cellSize) + 1;
        Set<Long> nearby = new HashSet<>();
        
        // Parse center cell coordinates
        String[] parts = centerCell.split(",");
        int cx = Integer.parseInt(parts[0]);
        int cy = Integer.parseInt(parts[1]);
        int cz = Integer.parseInt(parts[2]);
        
        // Check surrounding cells using lock-free access
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    String cell = (cx + dx) + "," + (cy + dy) + "," + (cz + dz);
                    LockFreeHashSet<Long> entities = grid.get(cell);
                    if (entities != null) {
                        nearby.addAll(entities.toSet());
                    }
                }
            }
        }
        
        return nearby;
    }
    
    private String getCellKey(double x, double y, double z) {
        int cellX = (int) (x / cellSize);
        int cellY = (int) (y / cellSize);
        int cellZ = (int) (z / cellSize);
        return cellX + "," + cellY + "," + cellZ;
    }
    
    public void clear() {
        grid.clear();
        entityCells.clear();
    }
    
    public int getGridSize() {
        return grid.size.get();
    }
}

/**
 * Lock-free hash map implementation using atomic references and compare-and-swap operations
 * Eliminates lock contention compared to ConcurrentHashMap
 */
class LockFreeHashMap<K, V> {
    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    
    private AtomicReferenceArray<Node<K, V>> table;
    public AtomicInteger size;
    private int threshold;
    
    public LockFreeHashMap() {
        this.table = new AtomicReferenceArray<>(INITIAL_CAPACITY);
        this.size = new AtomicInteger(0);
        this.threshold = (int)(INITIAL_CAPACITY * LOAD_FACTOR);
    }
    
    public V put(K key, V value) {
        int hash = hash(key);
        int index = indexFor(hash, table.length());
        
        // OPTIMIZED: Limit retry attempts to prevent infinite loops under high contention
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
                threshold = (int)(newCapacity * LOAD_FACTOR);
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

/**
 * Lock-free hash set implementation using atomic operations
 * Eliminates lock contention compared to ConcurrentHashSet
 */
class LockFreeHashSet<E> {
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
        // Note: Size calculation in lock-free structures is approximate due to concurrent modifications
        return map.size.get();
    }
    
    public Set<E> toSet() {
        // FIXED: Properly implement toSet() using the map's getAllKeys method
        return map.getAllKeys();
    }
}


/**
 * Async entity processing service with thread-safe queue and parallel processing capabilities.
 * Handles entity physics calculations asynchronously to prevent main thread blocking.
 * Uses AsyncLogger untuk non-blocking logging dan AsyncMetricsCollector untuk metrics.
 */
public final class EntityProcessingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityProcessingService.class);
    private static final AsyncLogger ASYNC_LOGGER = AsyncLoggingManager.getAsyncLogger(EntityProcessingService.class);
    private static final AsyncMetricsCollector METRICS = AsyncLoggingManager.getMetricsCollector();
    private static final EntityProcessingService INSTANCE = new EntityProcessingService();
    
    // Thread pool for async entity processing
    private final ExecutorService entityProcessor;
    private final ForkJoinPool forkJoinPool;
    private final ScheduledExecutorService maintenanceExecutor;
    
    // Thread-safe entity processing queue
    private final ConcurrentLinkedQueue<EntityProcessingTask> processingQueue;
    private final ConcurrentHashMap<Long, CompletableFuture<EntityProcessingResult>> activeFutures;
    
    // Spatial grid for efficient collision detection
    private final SpatialGrid spatialGrid;
    
    // Entity priority tracking
    private final ConcurrentHashMap<Long, EntityPriority> entityPriorities;
    
    // Object pool for entity physics data
    private final Queue<EntityPhysicsData> physicsDataPool;
    
    // Performance metrics
    private final AtomicLong processedEntities = new AtomicLong(0);
    private final AtomicLong queuedEntities = new AtomicLong(0);
    private final AtomicInteger activeProcessors = new AtomicInteger(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    // Enhanced load monitoring metrics
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final AtomicLong totalProcessingTimeNs = new AtomicLong(0);
    private final AtomicLong processingTimeSamples = new AtomicLong(0);
    private volatile double averageProcessingTimeMs = 0.0;
    private final Queue<Double> recentLoadSamples = new ConcurrentLinkedQueue<>();
    private static final int MAX_LOAD_SAMPLES = 10;
    
    // Adaptive thread pool controller
    private final AdaptiveThreadPoolController adaptiveThreadPoolController;

    // Configuration
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final int PROCESSING_TIMEOUT_MS = 1000; // 50ms timeout for entity processing
    private static final int MAX_PHYSICS_DATA_POOL_SIZE = 1000;
    private static final double SPATIAL_GRID_CELL_SIZE = 16.0;

    /**
     * Get dynamic thread pool size based on available processors
     */
    private static int getMaxThreadPoolSize() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // Use available processors but cap at reasonable maximum to avoid excessive resource usage
        return Math.max(2, Math.min(availableProcessors, 16)); // Increased for adaptive pooling
    }

    /**
     * Get current CPU usage percentage (0.0 to 100.0)
     */
    private double getCpuUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                return sunOsBean.getProcessCpuLoad() * 100.0;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get CPU usage: {}", e.getMessage());
        }
        return 0.0;
    }

    /**
     * Update average processing time with new sample
     */
    private void updateAverageProcessingTime(long processingTimeNs) {
        long totalTime = totalProcessingTimeNs.addAndGet(processingTimeNs);
        long samples = processingTimeSamples.incrementAndGet();

        // Update moving average
        double currentAvgMs = (totalTime / (double) samples) / 1_000_000.0;
        averageProcessingTimeMs = currentAvgMs;

        // Keep only recent samples for moving average
        if (samples > 1000) {
            totalProcessingTimeNs.set((long)(averageProcessingTimeMs * 1_000_000.0 * 500));
            processingTimeSamples.set(500);
        }
    }

    /**
     * Add load sample to recent history for smoothing
     */
    private void addLoadSample(double load) {
        recentLoadSamples.offer(load);
        if (recentLoadSamples.size() > MAX_LOAD_SAMPLES) {
            recentLoadSamples.poll();
        }
    }

    /**
     * Get smoothed load average from recent samples
     */
    private double getSmoothedLoadAverage() {
        if (recentLoadSamples.isEmpty()) return 0.0;

        double sum = 0.0;
        for (double sample : recentLoadSamples) {
            sum += sample;
        }
        return sum / recentLoadSamples.size();
    }
    
    /**
     * Adaptive thread pool controller that dynamically adjusts size based on load
     */
    private class AdaptiveThreadPoolController {
        private static final int MIN_THREADS = 2;
        private static final int MAX_THREADS = 16;
        private static final int LOAD_THRESHOLD_HIGH = 80;  // %
        private static final int LOAD_THRESHOLD_LOW = 30;   // %
        private static final int ADJUSTMENT_INTERVAL_MS = 5000; // 5 seconds
        
        private final AtomicInteger currentThreadCount;
        private final ScheduledExecutorService monitorExecutor;
        private final EntityProcessingService service;
        
        // OPTIMIZED: Reduce logging frequency
        private volatile long lastLogTime = 0;
        private volatile double lastLoggedLoad = 0.0;
        
        public AdaptiveThreadPoolController(EntityProcessingService service) {
            this.currentThreadCount = new AtomicInteger(getMaxThreadPoolSize());
            this.service = service;
            this.monitorExecutor = Executors.newSingleThreadScheduledExecutor();
            
            // Start monitoring task
            startLoadMonitoring();
        }
        
        private void startLoadMonitoring() {
            monitorExecutor.scheduleAtFixedRate(() -> {
                try {
                    adjustThreadPoolSizeBasedOnLoad();
                } catch (Exception e) {
                    LOGGER.error("Error in adaptive thread pool monitoring: {}", e.getMessage());
                }
            }, 10, ADJUSTMENT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        
        private void adjustThreadPoolSizeBasedOnLoad() {
            EntityProcessingStatistics stats = service.getStatistics();
            double loadPercentage = calculateLoadPercentage(stats);
            double cpuUsage = getCpuUsage();

            // OPTIMIZED: Only log on significant changes or every 60 seconds
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 60000 || Math.abs(loadPercentage - lastLoggedLoad) > 20.0) {
                LOGGER.debug("Adaptive thread pool - Load: {}%, CPU: {}%, AvgProcTime: {}ms, Threads: {}",
                            String.format("%.2f", loadPercentage), String.format("%.2f", cpuUsage),
                            String.format("%.3f", service.averageProcessingTimeMs), currentThreadCount.get());
                lastLogTime = now;
                lastLoggedLoad = loadPercentage;
            }

            // Adjust thread count based on load
            if (loadPercentage > LOAD_THRESHOLD_HIGH) {
                increaseThreadCount();
            } else if (loadPercentage < LOAD_THRESHOLD_LOW) {
                decreaseThreadCount();
            }
        }
        
        private double calculateLoadPercentage(EntityProcessingStatistics stats) {
            long queued = stats.queuedEntities;
            int active = stats.activeProcessors;
            int currentThreads = currentThreadCount.get();

            if (currentThreads == 0) return 0.0;

            // Get CPU usage and processing time metrics
            double cpuUsage = getCpuUsage();
            double avgProcessingTimeMs = averageProcessingTimeMs;

            // Calculate load components
            double queueLoad = (queued > 0) ? Math.min(100.0 * queued / MAX_QUEUE_SIZE, 100.0) : 0.0;
            double activeLoad = (active > 0) ? Math.min(100.0 * active / currentThreads, 100.0) : 0.0;

            // CPU-based load (normalized to 0-100 scale)
            double cpuLoad = Math.min(cpuUsage, 100.0);

            // Processing time load - higher time indicates higher load
            double timeLoad = Math.min(avgProcessingTimeMs / 10.0, 100.0); // 10ms baseline

            // Enhanced weighted average: Queue(20%) + Active(20%) + CPU(40%) + Time(20%)
            double finalLoad = (queueLoad * 0.2 + activeLoad * 0.2 + cpuLoad * 0.4 + timeLoad * 0.2);

            // Apply smoothing with recent samples
            addLoadSample(finalLoad);
            double smoothedLoad = getSmoothedLoadAverage();

            // Return smoothed load to prevent erratic thread pool adjustments
            return smoothedLoad;
        }
        
        private void increaseThreadCount() {
            int current = currentThreadCount.get();
            if (current < MAX_THREADS) {
                int newCount = Math.min(current + 2, MAX_THREADS); // Increase by 2 threads
                if (currentThreadCount.compareAndSet(current, newCount)) {
                    LOGGER.info("Adaptive thread pool increasing from {} to {} threads (high load)",
                               current, newCount);
                    // Update thread pool sizes
                    ((ThreadPoolExecutor) service.entityProcessor).setCorePoolSize(newCount);
                    ((ThreadPoolExecutor) service.entityProcessor).setMaximumPoolSize(newCount);
                    // ForkJoinPool cannot be dynamically resized - create new instance for simplicity
                }
            }
        }
        
        private void decreaseThreadCount() {
            int current = currentThreadCount.get();
            if (current > MIN_THREADS) {
                int newCount = Math.max(current - 1, MIN_THREADS); // Decrease by 1 thread
                if (currentThreadCount.compareAndSet(current, newCount)) {
                    LOGGER.info("Adaptive thread pool decreasing from {} to {} threads (low load)",
                               current, newCount);
                    // Update thread pool sizes
                    ((ThreadPoolExecutor) service.entityProcessor).setCorePoolSize(newCount);
                    ((ThreadPoolExecutor) service.entityProcessor).setMaximumPoolSize(newCount);
                    // ForkJoinPool cannot be dynamically resized - create new instance for simplicity
                }
            }
        }
        
        public int getCurrentThreadCount() {
            return currentThreadCount.get();
        }
        
        public void shutdown() {
            monitorExecutor.shutdown();
        }
    }
    
    private EntityProcessingService() {
        int maxThreadPoolSize = getMaxThreadPoolSize();
        this.entityProcessor = new ThreadPoolExecutor(
            maxThreadPoolSize / 2,
            maxThreadPoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "EntityProcessor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        this.forkJoinPool = new ForkJoinPool(maxThreadPoolSize);
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        this.processingQueue = new ConcurrentLinkedQueue<>();
        this.activeFutures = new ConcurrentHashMap<>();
        this.spatialGrid = new SpatialGrid(SPATIAL_GRID_CELL_SIZE);
        this.entityPriorities = new ConcurrentHashMap<>();
        this.physicsDataPool = new ConcurrentLinkedQueue<>();
        
        // Pre-populate physics data pool
        for (int i = 0; i < MAX_PHYSICS_DATA_POOL_SIZE / 2; i++) {
            physicsDataPool.offer(new EntityPhysicsData(0.0, 0.0, 0.0));
        }
        
        // Start maintenance task
        startMaintenanceTask();
        
        // Use async logger untuk avoid blocking main thread
        ASYNC_LOGGER.info("EntityProcessingService initialized with {} max threads", maxThreadPoolSize);
        METRICS.incrementCounter("entity_processing.service.initialized");
        
        // Initialize adaptive thread pool controller
        this.adaptiveThreadPoolController = new AdaptiveThreadPoolController(this);
    }
    
    public static EntityProcessingService getInstance() {
        return INSTANCE;
    }
    
    /**
     * Submit entity for async processing with priority
     */
    /**
     * Submit entity for async processing with default priority (MEDIUM)
     * @param entity Entity to process (must implement EntityInterface or be adaptable)
     * @param physicsData Physics data for the entity
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData) {
        return processEntityAsync(entity, physicsData, EntityPriority.MEDIUM);
    }

    /**
     * Submit entity for async processing with custom priority
     * @param entity Entity to process (must implement EntityInterface or be adaptable)
     * @param physicsData Physics data for the entity
     * @param priority Processing priority
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData, EntityPriority priority) {
        if (!isRunning.get()) {
            return CompletableFuture.completedFuture(
                new EntityProcessingResult(false, "Service is shutdown", physicsData)
            );
        }
        
        if (entity == null) {
            return CompletableFuture.completedFuture(
                new EntityProcessingResult(false, "Entity is null", physicsData)
            );
        }
        
        // Perform entity validation appropriate for current runtime mode
        if (ModeDetector.isTestMode()) {
            // Silent in test mode
        } else {
            // Perform strict validation for production entities
            if (!isValidProductionEntity(entity)) {
                return CompletableFuture.completedFuture(
                    new EntityProcessingResult(false, "Entity failed production validation", physicsData)
                );
            }
            // Silent success - no logging for valid entities
        }
        
        // Get entity adapter (handles both EntityInterface and reflection for other objects)
        EntityInterface entityAdapter = getEntityAdapter(entity);
        
        // Update spatial grid
        spatialGrid.updateEntity(entityAdapter.getId(), entityAdapter.getX(), entityAdapter.getY(), entityAdapter.getZ());
        
        // Store entity priority
        entityPriorities.put(entityAdapter.getId(), priority);
        
        // Try to get physics data from pool
        EntityPhysicsData pooledData = physicsDataPool.poll();
        EntityPhysicsData finalData = pooledData != null ?
            new EntityPhysicsData(physicsData.motionX, physicsData.motionY, physicsData.motionZ) :
            physicsData;
        
        EntityProcessingTask task = new EntityProcessingTask(entityAdapter, finalData, priority);
        queuedEntities.incrementAndGet();

        CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
        activeFutures.put(entityAdapter.getId(), future);

        // Submit to thread pool for processing
        entityProcessor.submit(() -> processEntityTask(task, future));
        
        return future;
    }
    
    /**
     * Process entities in batches by priority for better performance
     */
    public List<CompletableFuture<EntityProcessingResult>> processEntityBatch(List<Object> entities, List<EntityPhysicsData> physicsDataList) {
        if (!isRunning.get() || entities.size() != physicsDataList.size()) {
            return Collections.emptyList();
        }
        
        // Group entities by priority
        Map<EntityPriority, List<EntityProcessingTask>> priorityGroups = new HashMap<>();
        
        for (int i = 0; i < entities.size(); i++) {
            EntityInterface entity = getEntityAdapter(entities.get(i));
            EntityPhysicsData data = physicsDataList.get(i);
            
            if (entity == null) {
                continue;
            }
            
            // Perform validation appropriate for current runtime mode
            if (!ModeDetector.isTestMode() && !isValidProductionEntity(entity)) {
                ASYNC_LOGGER.debug(() -> "Batch entity failed production validation, skipping: " + entity.getId());
                METRICS.incrementCounter("entity_processing.validation_failed");
                continue;
            }
            
            // Update spatial grid
            // Already using EntityInterface, no change needed
            spatialGrid.updateEntity(entity.getId(), entity.getX(), entity.getY(), entity.getZ());
            
            // Default to medium priority
            EntityPriority priority = entityPriorities.getOrDefault((long) entity.getId(), EntityPriority.MEDIUM);
            
            // Try to get physics data from pool
            EntityPhysicsData pooledData = physicsDataPool.poll();
            EntityPhysicsData finalData = pooledData != null ?
                new EntityPhysicsData(data.motionX, data.motionY, data.motionZ) :
                data;
            
            EntityProcessingTask task = new EntityProcessingTask(entity, finalData, priority);
            priorityGroups.computeIfAbsent(priority, k -> new ArrayList<>()).add(task);
        }
        
        // Process batches in priority order
        List<CompletableFuture<EntityProcessingResult>> futures = new ArrayList<>();
        
        // Process CRITICAL priority first
        processPriorityBatch(priorityGroups.get(EntityPriority.CRITICAL), futures);
        
        // Process HIGH priority
        processPriorityBatch(priorityGroups.get(EntityPriority.HIGH), futures);
        
        // Process MEDIUM priority
        processPriorityBatch(priorityGroups.get(EntityPriority.MEDIUM), futures);
        
        // Process LOW priority last
        processPriorityBatch(priorityGroups.get(EntityPriority.LOW), futures);
        
        return futures;
    }
    
    /**
     * Process a batch of entities with the same priority
     * OPTIMIZED: Use Rust batch processing for distance calculations
     */
    private void processPriorityBatch(List<EntityProcessingTask> tasks, List<CompletableFuture<EntityProcessingResult>> futures) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        
        // Create batches of 32 entities for efficient processing
        for (int i = 0; i < tasks.size(); i += 32) {
            List<EntityProcessingTask> batch = tasks.subList(i, Math.min(i + 32, tasks.size()));
            
            // OPTIMIZATION: Use Rust batch distance calculation if entities need distance checks
            try {
                // Use zero-copy buffer sharing for position data transfer
                long bufferHandle = RustNativeLoader.allocateNativeBuffer(batch.size() * 3 * 4); // 3 floats * 4 bytes each
                try {
                    float[] positions = new float[batch.size() * 3];
                    for (int j = 0; j < batch.size(); j++) {
                        EntityProcessingTask task = batch.get(j);
                        positions[j * 3] = (float) task.physicsData.motionX;
                        positions[j * 3 + 1] = (float) task.physicsData.motionY;
                        positions[j * 3 + 2] = (float) task.physicsData.motionZ;
                    }
                    
                    // Zero-copy transfer to native buffer
                    RustNativeLoader.copyToNativeBuffer(bufferHandle, positions, 0, positions.length);
                    
                    // Batch distance calculation from origin (0,0,0) - useful for magnitude checks
                    double[] distances = RustNativeLoader.batchDistanceCalculationWithZeroCopy(bufferHandle, batch.size(), 0.0, 0.0, 0.0);
                    
                    // Process each entity with precomputed distance
                    for (int j = 0; j < batch.size(); j++) {
                        EntityProcessingTask task = batch.get(j);
                        queuedEntities.incrementAndGet();
                        
                        CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
                        activeFutures.put(task.entity.getId(), future);
                        futures.add(future);
                        
                        // Submit batch processing with precomputed distance
                        final double entityVelocityMagnitude = distances[j];
                        entityProcessor.submit(() -> processEntityTaskOptimized(task, future, entityVelocityMagnitude));
                    }
                } finally {
                    // Always release native buffer to prevent memory leaks
                    RustNativeLoader.releaseNativeBuffer(bufferHandle);
                }
            } catch (Exception e) {
                // Fallback to regular processing if Rust batch fails
                LOGGER.warn("Rust batch processing failed, falling back to individual processing: {}", e.getMessage());
                for (EntityProcessingTask task : batch) {
                    queuedEntities.incrementAndGet();
                    
                    CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
                    activeFutures.put(task.entity.getId(), future);
                    futures.add(future);
                    
                    // Submit batch processing
                    entityProcessor.submit(() -> processEntityTask(task, future));
                }
            }
        }
    }
    
    /**
     * Process entity task with precomputed velocity magnitude (OPTIMIZED)
     */
    private void processEntityTaskOptimized(EntityProcessingTask task, CompletableFuture<EntityProcessingResult> future, double velocityMagnitude) {
        activeProcessors.incrementAndGet();
        long startTime = System.nanoTime();

        try {
            // Perform entity physics calculation with optimization hint
            EntityProcessingResult result = calculateEntityPhysicsOptimized(task, velocityMagnitude);

            long processingTime = System.nanoTime() - startTime;
            updateAverageProcessingTime(processingTime);

            if (processingTime > PROCESSING_TIMEOUT_MS * 1_000_000L) {
                LOGGER.warn("Entity processing took {}ms for entity {}",
                    processingTime / 1_000_000L, task.entity.getId());
            }

            future.complete(result);
            processedEntities.incrementAndGet();

        } catch (Exception e) {
            LOGGER.error("Error processing entity {}: {}", task.entity.getId(), e.getMessage(), e);
            future.completeExceptionally(e);
        } finally {
            activeProcessors.decrementAndGet();
            queuedEntities.decrementAndGet();
            activeFutures.remove(task.entity.getId());

            // Return physics data to pool if it's not the original data
            if (task.physicsData != null) {
                // Reset data before returning to pool
                task.physicsData.reset();
                physicsDataPool.offer(task.physicsData);
            }
        }
    }
    
    /**
     * Process entity task asynchronously
     */
    private void processEntityTask(EntityProcessingTask task, CompletableFuture<EntityProcessingResult> future) {
        activeProcessors.incrementAndGet();
        long startTime = System.nanoTime();

        try {
            // Perform entity physics calculation
            EntityProcessingResult result = calculateEntityPhysics(task);

            long processingTime = System.nanoTime() - startTime;
            updateAverageProcessingTime(processingTime);

            if (processingTime > PROCESSING_TIMEOUT_MS * 1_000_000L) {
                LOGGER.warn("Entity processing took {}ms for entity {}",
                    processingTime / 1_000_000L, task.entity.getId());
            }

            future.complete(result);
            processedEntities.incrementAndGet();

        } catch (Exception e) {
            LOGGER.error("Error processing entity {}: {}", task.entity.getId(), e.getMessage(), e);
            future.completeExceptionally(e);
        } finally {
            activeProcessors.decrementAndGet();
            queuedEntities.decrementAndGet();
            activeFutures.remove(task.entity.getId());

            // Return physics data to pool if it's not the original data
            if (task.physicsData != null) {
                // Reset data before returning to pool
                task.physicsData.reset();
                physicsDataPool.offer(task.physicsData);
            }
        }
    }
    
    /**
     * Calculate entity physics using optimized algorithms with precomputed velocity magnitude
     * OPTIMIZED VERSION: Uses Rust-precomputed distance to skip magnitude calculation
     */
    private EntityProcessingResult calculateEntityPhysicsOptimized(EntityProcessingTask task, double velocityMagnitude) {
        EntityInterface entity = task.entity;
        EntityPhysicsData data = task.physicsData;
        
        try {
            // Validate input data
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return new EntityProcessingResult(false, "Invalid motion values", data);
            }
            
            // Get entity type for optimized damping calculation
            EntityTypeEnum entityType = EntityTypeEnum.fromEntity(entity);
            if (entityType == null) {
                entityType = EntityTypeEnum.DEFAULT;
                LOGGER.debug("Using default entity type for processing");
            }
            double dampingFactor = entityType.getDampingFactor();
            
            // OPTIMIZATION: Skip magnitude check if velocity is too low (precomputed)
            if (velocityMagnitude < 0.005) {
                // Entity is nearly stationary, apply simple damping
                EntityPhysicsData processedData = new EntityPhysicsData(
                    data.motionX * dampingFactor,
                    data.motionY * dampingFactor,
                    data.motionZ * dampingFactor
                );
                return new EntityProcessingResult(true, "Low velocity optimization applied", processedData);
            }
            
            // Perform physics calculation - use native Rust optimization
            double[] result = OptimizationInjector.rustperf_vector_damp(
                data.motionX, data.motionY, data.motionZ, dampingFactor
            );
            
            if (isValidPhysicsResult(result, data)) {
                double verticalDamping = 0.02;
                double processedY = result[1];
                
                EntityPhysicsData processedData = new EntityPhysicsData(
                    result[0],
                    processedY * (1 - verticalDamping),
                    result[2]
                );
                
                return new EntityProcessingResult(true, "Optimized physics calculation successful", processedData);
            } else {
                // Fallback to Java calculation
                EntityPhysicsData fallbackData = new EntityPhysicsData(
                    data.motionX * dampingFactor,
                    data.motionY,
                    data.motionZ * dampingFactor
                );
                return new EntityProcessingResult(true, "Fallback to Java physics", fallbackData);
            }
            
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native library not available for entity {}, using fallback", entity.getId());
            EntityPhysicsData fallbackData = new EntityPhysicsData(
                data.motionX * 0.98,
                data.motionY,
                data.motionZ * 0.98
            );
            return new EntityProcessingResult(true, "Native library fallback", fallbackData);
        } catch (Exception e) {
            LOGGER.error("Physics calculation failed for entity {}: {}", entity.getId(), e.getMessage());
            return new EntityProcessingResult(false, "Physics calculation error: " + e.getMessage(), data);
        }
    }
    
    /**
     * Calculate entity physics using optimized algorithms
     */
    private EntityProcessingResult calculateEntityPhysics(EntityProcessingTask task) {
        EntityInterface entity = task.entity;
        EntityPhysicsData data = task.physicsData;
        
        try {
            // Validate input data
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return new EntityProcessingResult(false, "Invalid motion values", data);
            }
            
            // Get entity type for optimized damping calculation
            EntityTypeEnum entityType = EntityTypeEnum.fromEntity(entity);
            if (entityType == null) {
                entityType = EntityTypeEnum.DEFAULT;
                LOGGER.debug("Using default entity type for processing");
            }
            double dampingFactor = entityType.getDampingFactor();
            
            // Perform physics calculation - ALWAYS use native Rust optimization for valid entities in production
            double[] result = OptimizationInjector.rustperf_vector_damp(
                data.motionX, data.motionY, data.motionZ, dampingFactor
            );
            
            if (result != null && result.length == 3 && isValidPhysicsResult(result, data)) {
                // Improved gravity handling - allow natural gravity while preserving external effects
                double processedY = data.motionY;
                
                // Only preserve external gravity modifications if they are significant (knockback, explosions)
                // Allow natural gravity to work normally
                if (Math.abs(result[1] - data.motionY) > 0.5) {
                    // Significant external effect detected (explosion, strong knockback)
                    processedY = result[1];
                } else if (data.motionY < -0.1) {
                    // Natural falling - apply enhanced gravity for better feel
                    processedY = Math.min(data.motionY * 1.1, result[1]);
                }
                
                // TRUE vanilla knockback - NO damping for horizontal movement
                // Only apply damping to vertical (gravity) for stability
                double verticalDamping = 0.015;   // Standard damping for vertical movement only
                
                EntityPhysicsData processedData = new EntityPhysicsData(
                    result[0], // NO damping for horizontal X - pure vanilla knockback
                    processedY * (1 - verticalDamping), // Apply damping only to gravity (Y)
                    result[2]  // NO damping for horizontal Z - pure vanilla knockback
                );
                
                return new EntityProcessingResult(true, "Physics calculation successful", processedData);
            } else {
                // Fallback to Java calculation only if native fails
                EntityPhysicsData fallbackData = new EntityPhysicsData(
                    data.motionX * dampingFactor,
                    data.motionY,
                    data.motionZ * dampingFactor
                );
                return new EntityProcessingResult(true, "Fallback to Java physics", fallbackData);
            }
            
        } catch (UnsatisfiedLinkError e) {
            LOGGER.warn("Native library not available for entity {}, using fallback", entity.getId());
            EntityPhysicsData fallbackData = new EntityPhysicsData(
                data.motionX * 0.98, // Default damping
                data.motionY,
                data.motionZ * 0.98
            );
            return new EntityProcessingResult(true, "Native library fallback", fallbackData);
        } catch (Exception e) {
            LOGGER.error("Physics calculation failed for entity {}: {}", entity.getId(), e.getMessage());
            return new EntityProcessingResult(false, "Physics calculation error: " + e.getMessage(), data);
        }
    }
    
    /**
     * Validate physics calculation result with enhanced direction change support
     */
    private boolean isValidPhysicsResult(double[] result, EntityPhysicsData original) {
        if (result == null || result.length != 3) return false;
        
        // Check for invalid values
        for (double val : result) {
            if (Double.isNaN(val) || Double.isInfinite(val)) return false;
        }
        
        // Enhanced validation for direction changes and external forces
        // Allow for 180° direction changes and external forces (explosions, water, knockback)
        
        // Check for direction reversals (180° turns)
        boolean xDirectionReversed = (original.motionX > 0 && result[0] < 0) ||
                                     (original.motionX < 0 && result[0] > 0);
        boolean zDirectionReversed = (original.motionZ > 0 && result[2] < 0) ||
                                     (original.motionZ < 0 && result[2] > 0);
        
        // Use different thresholds for direction reversals vs normal movement
        final double HORIZONTAL_THRESHOLD_NORMAL = 5.0;   // For normal movements (increased from 3.0)
        final double HORIZONTAL_THRESHOLD_REVERSED = 12.0; // For direction reversals (180° turns) (increased from 8.0)
        final double VERTICAL_THRESHOLD = 8.0;            // For falling and jumping (increased from 5.0)
        
        double horizontalThreshold = (xDirectionReversed || zDirectionReversed) ?
            HORIZONTAL_THRESHOLD_REVERSED : HORIZONTAL_THRESHOLD_NORMAL;
        
        // Apply enhanced thresholds with direction change consideration
        if (Math.abs(result[0]) > Math.abs(original.motionX) * horizontalThreshold) return false;
        if (Math.abs(result[2]) > Math.abs(original.motionZ) * horizontalThreshold) return false;
        if (Math.abs(result[1]) > Math.abs(original.motionY) * VERTICAL_THRESHOLD) return false;
        
        return true;
    }
    
    /**
     * Start maintenance task for cleanup and monitoring
     */
    private void startMaintenanceTask() {
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                // Clean up completed futures
                activeFutures.entrySet().removeIf(entry -> entry.getValue().isDone());
                
                // Log performance metrics
                if (processedEntities.get() % 1000 == 0) {
                    logPerformanceMetrics();
                }
            } catch (Exception e) {
                LOGGER.error("Error in maintenance task: {}", e.getMessage(), e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Log performance metrics
     */
    private void logPerformanceMetrics() {
        EntityProcessingStatistics stats = getStatistics();
        LOGGER.info("EntityProcessingService Metrics - Processed: {}, Queued: {}, Active: {}, QueueSize: {}, GridCells: {}, PoolSize: {}, CPU: {}%, AvgProcTime: {}ms",
            stats.processedEntities,
            stats.queuedEntities,
            stats.activeProcessors,
            stats.queueSize,
            spatialGrid.getGridSize(),
            physicsDataPool.size(),
            String.format("%.2f", stats.cpuUsage),
            String.format("%.3f", stats.averageProcessingTimeMs)
        );
    }
    
    /**
     * Set entity priority for CPU resource allocation
     */
    public void setEntityPriority(long entityId, EntityPriority priority) {
        entityPriorities.put(entityId, priority);
    }
    
    /**
     * Get entity priority
     */
    public EntityPriority getEntityPriority(long entityId) {
        return entityPriorities.getOrDefault(entityId, EntityPriority.MEDIUM);
    }
    
    /**
     * Get nearby entities using spatial grid
     */
    public Set<Long> getNearbyEntities(double x, double y, double z, double radius) {
        return spatialGrid.getNearbyEntities(x, y, z, radius);
    }
    
    /**
     * Get service statistics
     */
    public EntityProcessingStatistics getStatistics() {
        return new EntityProcessingStatistics(
            processedEntities.get(),
            queuedEntities.get(),
            activeProcessors.get(),
            processingQueue.size(),
            activeFutures.size(),
            isRunning.get(),
            spatialGrid.getGridSize(),
            physicsDataPool.size(),
            getCpuUsage(),
            averageProcessingTimeMs
        );
    }
    
    /**
     * Shutdown service gracefully
     */
    public void shutdown() {
        isRunning.set(false);
        
        // Cancel all pending operations
        activeFutures.values().forEach(future ->
            future.complete(new EntityProcessingResult(false, "Service shutdown", null))
        );
        
        // Clear spatial grid
        spatialGrid.clear();
        
        // Clear entity priorities
        entityPriorities.clear();
        
        // Clear physics data pool
        physicsDataPool.clear();
        
        // Shutdown executors
        entityProcessor.shutdown();
        forkJoinPool.shutdown();
        maintenanceExecutor.shutdown();
        
        try {
            if (!entityProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                entityProcessor.shutdownNow();
            }
            if (!forkJoinPool.awaitTermination(5, TimeUnit.SECONDS)) {
                forkJoinPool.shutdownNow();
            }
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            entityProcessor.shutdownNow();
            forkJoinPool.shutdownNow();
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("EntityProcessingService shutdown completed");
    }
    
    /**
     * Entity processing task
     */
    public static class EntityProcessingTask {
        public final EntityInterface entity;
        public final EntityPhysicsData physicsData;
        public final EntityPriority priority;
        
        public EntityProcessingTask(EntityInterface entity, EntityPhysicsData physicsData) {
            this.entity = entity;
            this.physicsData = physicsData;
            this.priority = EntityPriority.MEDIUM; // Default priority
        }
        
        public EntityProcessingTask(EntityInterface entity, EntityPhysicsData physicsData, EntityPriority priority) {
            this.entity = entity;
            this.physicsData = physicsData;
            this.priority = priority;
        }
    }

    /**
     * Entity batch for efficient bulk processing
     */
    class EntityBatch {
        private final List<EntityProcessingTask> tasks;
        private final EntityPriority priority;
        
        public EntityBatch(List<EntityProcessingTask> tasks, EntityPriority priority) {
            this.tasks = tasks;
            this.priority = priority;
        }
        
        public List<EntityProcessingTask> getTasks() {
            return tasks;
        }
        
        public EntityPriority getPriority() {
            return priority;
        }
    }
    
    /**
     * Entity physics data
     */
    public static class EntityPhysicsData {
        public double motionX;
        public double motionY;
        public double motionZ;
        
        public EntityPhysicsData(double motionX, double motionY, double motionZ) {
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
        }
        
        public void reset() {
            this.motionX = 0.0;
            this.motionY = 0.0;
            this.motionZ = 0.0;
        }
    }
    
    /**
     * Entity processing result
     */
    public static class EntityProcessingResult {
        public final boolean success;
        public final String message;
        public final EntityPhysicsData processedData;
        
        public EntityProcessingResult(boolean success, String message, EntityPhysicsData processedData) {
            this.success = success;
            this.message = message;
            this.processedData = processedData;
        }
    }
    
    /**
     * Entity processing statistics
     */
    /**
     * Get entity adapter for processing - handles both EntityInterface and reflection for other objects
     */
    private EntityInterface getEntityAdapter(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }
        
        // First, try direct interface implementation
        if (entity instanceof EntityInterface) {
            return (EntityInterface) entity;
        }
        
        // For TestMockEntity, we can use reflection to get the necessary methods
        if (entity instanceof TestMockEntity) {
            return new TestMockEntityAdapter((TestMockEntity) entity);
        }
        
        // For other objects, use reflection adapter
        return new ReflectionEntityAdapter(entity);
    }
    
    /**
     * Adapter for TestMockEntity to EntityInterface
     */
    private static class TestMockEntityAdapter implements EntityInterface {
        private final TestMockEntity mockEntity;
        
        public TestMockEntityAdapter(TestMockEntity mockEntity) {
            this.mockEntity = mockEntity;
        }
        
        @Override
        public long getId() {
            return mockEntity.getId();
        }
        
        @Override
        public double getX() {
            return mockEntity.getX();
        }
        
        @Override
        public double getY() {
            return mockEntity.getY();
        }
        
        @Override
        public double getZ() {
            return mockEntity.getZ();
        }
    }
    
    /**
     * Reflection-based adapter for any object with getX(), getY(), getZ(), getId() methods
     */
    private static class ReflectionEntityAdapter implements EntityInterface {
        private final Object entity;
        private java.lang.reflect.Method getIdMethod;
        private java.lang.reflect.Method getXMethod;
        private java.lang.reflect.Method getYMethod;
        private java.lang.reflect.Method getZMethod;
        
        public ReflectionEntityAdapter(Object entity) {
            this.entity = entity;
            
            try {
                // Try to find the methods using reflection
                this.getIdMethod = findMethod(entity.getClass(), "getId");
                this.getXMethod = findMethod(entity.getClass(), "getX");
                this.getYMethod = findMethod(entity.getClass(), "getY");
                this.getZMethod = findMethod(entity.getClass(), "getZ");
            } catch (Exception e) {
                throw new IllegalArgumentException("Entity does not implement EntityInterface and missing required methods: getId(), getX(), getY(), getZ()", e);
            }
        }
        
        private java.lang.reflect.Method findMethod(Class<?> clazz, String methodName) {
            try {
                return clazz.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                // Check superclasses
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null && !superClass.equals(Object.class)) {
                    return findMethod(superClass, methodName);
                }
                return null;
            }
        }
        
        @Override
        public long getId() {
            try {
                Object result = getIdMethod.invoke(entity);
                if (result instanceof Integer) {
                    return ((Integer) result).longValue();
                } else if (result instanceof Long) {
                    return (Long) result;
                }
                throw new IllegalArgumentException("getId() must return int or long");
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke getId()", e);
            }
        }
        
        @Override
        public double getX() {
            try {
                return ((Number) getXMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke getX()", e);
            }
        }
        
        @Override
        public double getY() {
            try {
                return ((Number) getYMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke getY()", e);
            }
        }
        
        @Override
        public double getZ() {
            try {
                return ((Number) getZMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke getZ()", e);
            }
        }
    }
    
    /**
     * Validate that an entity is suitable for production processing
     * @param entity Entity to validate
     * @return true if entity is valid for production use
     */
    private boolean isValidProductionEntity(Object entity) {
        if (entity == null) return false;
        
        try {
            // Check if entity is a valid Minecraft entity (production-only check)
            String entityClassName = entity.getClass().getName();
            
            // In test mode, accept test mock entities
            if (ModeDetector.isTestMode() && entityClassName.startsWith("com.kneaf.core.mock.")) {
                return true;
            }
            
            // Accept Minecraft entities from various packages:
            // - net.minecraft.world.entity.* (standard entities)
            // - net.minecraft.client.player.* (client-side player)
            // - net.minecraft.server.level.* (server-side entities)
            // - com.kneaf.entities.* (custom mod entities)
            boolean isValidMinecraftEntity =
                entityClassName.startsWith("net.minecraft.world.entity.") ||
                entityClassName.startsWith("net.minecraft.client.player.") ||
                entityClassName.startsWith("net.minecraft.server.level.") ||
                entityClassName.startsWith("com.kneaf.entities.");
            
            if (!isValidMinecraftEntity) {
                LOGGER.warn("Rejected non-Minecraft entity in production: {}", entityClassName);
                return false;
            }
            
            // Allow specific Minecraft entity types that benefit from optimization
            // LocalPlayer, EntityLiving, EntityMob, etc. should all be allowed
            // Block entities and other non-physical entities can be filtered out if needed
            
            // Additional validation can be added here for specific entity types
            // For example: check if entity is a player, mob, etc.
            
            return true;
        } catch (Exception e) {
            LOGGER.debug("Entity validation failed due to exception", e);
            return false;
        }
    }

    public static class EntityProcessingStatistics {
        public final long processedEntities;
        public final long queuedEntities;
        public final int activeProcessors;
        public final int queueSize;
        public final int activeFutures;
        public final boolean isRunning;
        public final int spatialGridCells;
        public final int physicsDataPoolSize;
        public final double cpuUsage;
        public final double averageProcessingTimeMs;

        public EntityProcessingStatistics(long processedEntities, long queuedEntities, int activeProcessors,
                                        int queueSize, int activeFutures, boolean isRunning, int spatialGridCells,
                                        int physicsDataPoolSize, double cpuUsage, double averageProcessingTimeMs) {
            this.processedEntities = processedEntities;
            this.queuedEntities = queuedEntities;
            this.activeProcessors = activeProcessors;
            this.queueSize = queueSize;
            this.activeFutures = activeFutures;
            this.isRunning = isRunning;
            this.spatialGridCells = spatialGridCells;
            this.physicsDataPoolSize = physicsDataPoolSize;
            this.cpuUsage = cpuUsage;
            this.averageProcessingTimeMs = averageProcessingTimeMs;
        }
    }
}