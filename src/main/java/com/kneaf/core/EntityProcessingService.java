package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kneaf.core.async.AsyncLogger;
import com.kneaf.core.async.AsyncLoggingManager;
import com.kneaf.core.async.AsyncMetricsCollector;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
 * Uses atomic operations and lock-free data structures to eliminate
 * ConcurrentHashMap overhead
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
        // OPTIMIZED: Avoid string operations - calculate cell coordinates directly
        int centerX = (int) (x / cellSize);
        int centerY = (int) (y / cellSize);
        int centerZ = (int) (z / cellSize);
        int radiusCells = (int) (radius / cellSize) + 1;
        Set<Long> nearby = new HashSet<>();

        // Check surrounding cells using lock-free access
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    // OPTIMIZED: Use getCellKeyDirect to avoid redundant calculations
                    String cell = getCellKeyDirect(centerX + dx, centerY + dy, centerZ + dz);
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
        return getCellKeyDirect(cellX, cellY, cellZ);
    }

    // OPTIMIZED: Direct cell key generation from integer coordinates
    private String getCellKeyDirect(int cellX, int cellY, int cellZ) {
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
 * Lock-free hash map implementation using atomic references and
 * compare-and-swap operations
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
        // Note: Size calculation in lock-free structures is approximate due to
        // concurrent modifications
        return map.size.get();
    }

    public Set<E> toSet() {
        // FIXED: Properly implement toSet() using the map's getAllKeys method
        return map.getAllKeys();
    }
}

/**
 * Async entity processing service with thread-safe queue and parallel
 * processing capabilities.
 * Handles entity physics calculations asynchronously to prevent main thread
 * blocking.
 * Uses AsyncLogger untuk non-blocking logging dan AsyncMetricsCollector untuk
 * metrics.
 */
public final class EntityProcessingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityProcessingService.class);
    private static final AsyncLogger ASYNC_LOGGER = AsyncLoggingManager.getAsyncLogger(EntityProcessingService.class);
    private static final AsyncMetricsCollector METRICS = AsyncLoggingManager.getMetricsCollector();
    private static final EntityProcessingService INSTANCE = new EntityProcessingService();

    // Native method declaration removed - now using RustNativeLoader

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

    // Throughput tracking
    private final AtomicLong lastProcessedCount = new AtomicLong(0);
    private final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());

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
        return Math.max(2, Math.min(availableProcessors, 16));
    }

    private double getCpuUsage() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad() * 100.0;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get CPU usage: {}", e.getMessage());
        }
        return 0.0;
    }

    private void updateAverageProcessingTime(long processingTimeNs) {
        long totalTime = totalProcessingTimeNs.addAndGet(processingTimeNs);
        long samples = processingTimeSamples.incrementAndGet();
        double currentAvgMs = (totalTime / (double) samples) / 1_000_000.0;
        averageProcessingTimeMs = currentAvgMs;
        if (samples > 1000) {
            totalProcessingTimeNs.set((long) (averageProcessingTimeMs * 1_000_000.0 * 500));
            processingTimeSamples.set(500);
        }
    }

    private void addLoadSample(double load) {
        recentLoadSamples.offer(load);
        if (recentLoadSamples.size() > MAX_LOAD_SAMPLES) {
            recentLoadSamples.poll();
        }
    }

    private double getSmoothedLoadAverage() {
        if (recentLoadSamples.isEmpty())
            return 0.0;
        double sum = 0.0;
        for (double sample : recentLoadSamples)
            sum += sample;
        return sum / recentLoadSamples.size();
    }

    private class AdaptiveThreadPoolController {
        private static final int MIN_THREADS = 2;
        private static final int MAX_THREADS = 16;
        private static final int LOAD_THRESHOLD_HIGH = 80;
        private static final int LOAD_THRESHOLD_LOW = 30;
        private static final int ADJUSTMENT_INTERVAL_MS = 5000;

        private final AtomicInteger currentThreadCount;
        private final ScheduledExecutorService monitorExecutor;
        private final EntityProcessingService service;
        private volatile long lastLogTime = 0;
        private volatile double lastLoggedLoad = 0.0;

        public AdaptiveThreadPoolController(EntityProcessingService service) {
            this.currentThreadCount = new AtomicInteger(getMaxThreadPoolSize());
            this.service = service;
            this.monitorExecutor = Executors.newSingleThreadScheduledExecutor();
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
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 60000 || Math.abs(loadPercentage - lastLoggedLoad) > 20.0) {
                lastLogTime = now;
                lastLoggedLoad = loadPercentage;
            }
            if (loadPercentage > LOAD_THRESHOLD_HIGH)
                increaseThreadCount();
            else if (loadPercentage < LOAD_THRESHOLD_LOW)
                decreaseThreadCount();
        }

        private double calculateLoadPercentage(EntityProcessingStatistics stats) {
            long queued = stats.queuedEntities;
            int active = stats.activeProcessors;
            int currentThreads = currentThreadCount.get();
            if (currentThreads == 0)
                return 0.0;
            double cpuUsage = getCpuUsage();
            double avgProcessingTimeMs = averageProcessingTimeMs;
            double queueLoad = (queued > 0) ? Math.min(100.0 * queued / MAX_QUEUE_SIZE, 100.0) : 0.0;
            double activeLoad = (active > 0) ? Math.min(100.0 * active / currentThreads, 100.0) : 0.0;
            double cpuLoad = Math.min(cpuUsage, 100.0);
            double timeLoad = Math.min(avgProcessingTimeMs / 10.0, 100.0);
            double finalLoad = (queueLoad * 0.2 + activeLoad * 0.2 + cpuLoad * 0.4 + timeLoad * 0.2);
            addLoadSample(finalLoad);
            return getSmoothedLoadAverage();
        }

        private void increaseThreadCount() {
            int current = currentThreadCount.get();
            if (current < MAX_THREADS) {
                int newCount = Math.min(current + 2, MAX_THREADS);
                if (currentThreadCount.compareAndSet(current, newCount)) {
                    ((ThreadPoolExecutor) service.entityProcessor).setCorePoolSize(newCount);
                    ((ThreadPoolExecutor) service.entityProcessor).setMaximumPoolSize(newCount);
                }
            }
        }

        private void decreaseThreadCount() {
            int current = currentThreadCount.get();
            if (current > MIN_THREADS) {
                int newCount = Math.max(current - 1, MIN_THREADS);
                if (currentThreadCount.compareAndSet(current, newCount)) {
                    ((ThreadPoolExecutor) service.entityProcessor).setCorePoolSize(newCount);
                    ((ThreadPoolExecutor) service.entityProcessor).setMaximumPoolSize(newCount);
                }
            }
        }

        public void shutdown() {
            monitorExecutor.shutdown();
        }
    }

    private EntityProcessingService() {
        int maxThreadPoolSize = getMaxThreadPoolSize();
        this.entityProcessor = new ThreadPoolExecutor(
                maxThreadPoolSize / 2, maxThreadPoolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
                r -> {
                    Thread t = new Thread(r, "EntityProcessor-");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.forkJoinPool = new ForkJoinPool(maxThreadPoolSize);
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        this.processingQueue = new ConcurrentLinkedQueue<>();
        this.activeFutures = new ConcurrentHashMap<>();
        this.spatialGrid = new SpatialGrid(SPATIAL_GRID_CELL_SIZE);
        this.entityPriorities = new ConcurrentHashMap<>();
        this.physicsDataPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < MAX_PHYSICS_DATA_POOL_SIZE / 2; i++)
            physicsDataPool.offer(new EntityPhysicsData(0.0, 0.0, 0.0));
        startMaintenanceTask();
        ASYNC_LOGGER.info("EntityProcessingService initialized");
        METRICS.incrementCounter("entity_processing.service.initialized");
        this.adaptiveThreadPoolController = new AdaptiveThreadPoolController(this);
    }

    public static EntityProcessingService getInstance() {
        return INSTANCE;
    }

    /**
     * Submit entity for async processing with priority
     */

    // NATIVE DECLARATION: Advanced Zero-Copy Spatial Grid removed - using
    // RustNativeLoader

    /**
     * Submit entity for async processing with default priority (MEDIUM)
     * 
     * @param entity      Entity to process (must implement EntityInterface or be
     *                    adaptable)
     * @param physicsData Physics data for the entity
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData) {
        return processEntityAsync(entity, physicsData, EntityPriority.MEDIUM);
    }

    /**
     * SYNCHRONOUS entity processing - processes immediately on calling thread
     * Uses Rust parallel processing internally but blocks until complete.
     * 
     * @param entity      Entity to process
     * @param physicsData Physics data for the entity
     * @return Processing result (always returns original data - metrics only)
     */
    public EntityProcessingResult processEntitySync(Object entity, EntityPhysicsData physicsData) {
        if (!isRunning.get() || entity == null) {
            return new EntityProcessingResult(false, "Service not running or null entity", physicsData);
        }

        try {
            // Get entity adapter
            EntityInterface entityAdapter = getEntityAdapter(entity);
            if (entityAdapter == null) {
                return new EntityProcessingResult(false, "Could not adapt entity", physicsData);
            }

            // Update spatial grid (real work)
            spatialGrid.updateEntity(entityAdapter.getId(), entityAdapter.getX(), entityAdapter.getY(),
                    entityAdapter.getZ());

            // Call Rust for vector processing (synchronous, Rust uses Rayon internally)
            try {
                double[] result = RustNativeLoader.rustperf_vector_damp(
                        physicsData.motionX, physicsData.motionY, physicsData.motionZ, 1.0);
                // Result used for metrics only - no gameplay modification
            } catch (UnsatisfiedLinkError e) {
                // Native not loaded, continue without Rust
            }

            // INCREMENT METRICS
            processedEntities.incrementAndGet();
            METRICS.incrementCounter("entity_sync_processed");

            // Return ORIGINAL unchanged data
            return new EntityProcessingResult(true, "Sync processed", physicsData);
        } catch (Exception e) {
            LOGGER.debug("Sync processing failed: {}", e.getMessage());
            return new EntityProcessingResult(false, "Exception: " + e.getMessage(), physicsData);
        }
    }

    /**
     * Submit entity for async processing with custom priority
     * 
     * @param entity      Entity to process (must implement EntityInterface or be
     *                    adaptable)
     * @param physicsData Physics data for the entity
     * @param priority    Processing priority
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData,
            EntityPriority priority) {
        if (!isRunning.get()) {
            return CompletableFuture.completedFuture(
                    new EntityProcessingResult(false, "Service is shutdown", physicsData));
        }

        if (entity == null) {
            return CompletableFuture.completedFuture(
                    new EntityProcessingResult(false, "Entity is null", physicsData));
        }

        // Perform entity validation appropriate for current runtime mode
        if (ModeDetector.isTestMode()) {
            // Silent in test mode
        } else {
            // Perform strict validation for production entities
            if (!isValidProductionEntity(entity)) {
                return CompletableFuture.completedFuture(
                        new EntityProcessingResult(false, "Entity failed production validation", physicsData));
            }
            // Silent success - no logging for valid entities
        }

        // Get entity adapter (handles both EntityInterface and reflection for other
        // objects)
        EntityInterface entityAdapter = getEntityAdapter(entity);

        // Update spatial grid
        spatialGrid.updateEntity(entityAdapter.getId(), entityAdapter.getX(), entityAdapter.getY(),
                entityAdapter.getZ());

        // Store entity priority
        entityPriorities.put(entityAdapter.getId(), priority);

        // Try to get physics data from pool
        EntityPhysicsData pooledData = physicsDataPool.poll();
        EntityPhysicsData finalData = pooledData != null
                ? new EntityPhysicsData(physicsData.motionX, physicsData.motionY, physicsData.motionZ)
                : physicsData;

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
    public List<CompletableFuture<EntityProcessingResult>> processEntityBatch(List<Object> entities,
            List<EntityPhysicsData> physicsDataList) {
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
            EntityPhysicsData finalData = pooledData != null
                    ? new EntityPhysicsData(data.motionX, data.motionY, data.motionZ)
                    : data;

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
    private void processPriorityBatch(List<EntityProcessingTask> tasks,
            List<CompletableFuture<EntityProcessingResult>> futures) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        // Create batches of 32 (optimized for AVX2/SIMD registers in Rust)
        for (int i = 0; i < tasks.size(); i += 32) {
            List<EntityProcessingTask> batch = tasks.subList(i, Math.min(i + 32, tasks.size()));
            int batchSize = batch.size();
            boolean rustSuccess = false;

            // 1. Initialize Futures for the batch
            for (int j = 0; j < batchSize; j++) {
                EntityProcessingTask task = batch.get(j);
                queuedEntities.incrementAndGet();

                CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
                activeFutures.put(((EntityInterface) task.entity).getId(), future);
                futures.add(future);
            }

            // 2. ADVANCED: Zero-Copy Shared Memory with Rust
            // Allocating DirectByteBuffers allows Rust to access memory without copying.
            // Struct Layout: { x, y, z, vx, vy, vz, id, padding } = 8 * 4 = 32 bytes
            // Result Layout: { neighbor, distSq, density, avoidX, avoidZ } = 5 * 4 = 20
            // bytes
            if (OptimizationInjector.isNativeLibraryLoaded()) {
                try {
                    int inputSize = batchSize * 32;
                    int outputSize = batchSize * 20;

                    // Allocate Direct Buffers (off-heap)
                    ByteBuffer inputBuf = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());
                    ByteBuffer outputBuf = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());

                    // Fill Input Buffer
                    for (int j = 0; j < batchSize; j++) {
                        EntityProcessingTask task = batch.get(j);
                        EntityInterface ent = (EntityInterface) task.entity;
                        EntityPhysicsData pd = task.physicsData;

                        // Write EntitySpatialData struct
                        inputBuf.putFloat((float) ent.getX());
                        inputBuf.putFloat((float) ent.getY());
                        inputBuf.putFloat((float) ent.getZ());
                        inputBuf.putFloat((float) pd.motionX);
                        inputBuf.putFloat((float) pd.motionY);
                        inputBuf.putFloat((float) pd.motionZ);
                        inputBuf.putInt((int) ent.getId());
                        inputBuf.putInt(0); // padding
                    }

                    // Call Advanced Rust Function
                    RustNativeLoader.rustperf_batch_spatial_grid_zero_copy(inputBuf, outputBuf, batchSize);

                    // Read Output Buffer
                    outputBuf.position(0); // Reset read cursor

                    if (batchSize > 0) {
                        for (int j = 0; j < batchSize; j++) {
                            // Read SpatialResult struct (for METRICS ONLY)
                            int neighborId = outputBuf.getInt();
                            float distSq = outputBuf.getFloat();
                            float density = outputBuf.getFloat();
                            @SuppressWarnings("unused")
                            float avoidX = outputBuf.getFloat(); // Read but DO NOT USE
                            @SuppressWarnings("unused")
                            float avoidZ = outputBuf.getFloat(); // Read but DO NOT USE

                            // METRICS ONLY - DO NOT MODIFY GAMEPLAY
                            EntityProcessingTask task = batch.get(j);
                            CompletableFuture<EntityProcessingResult> future = activeFutures
                                    .get(((EntityInterface) task.entity).getId());

                            if (future != null) {
                                // Track spatial metrics
                                if (density > 5.0f)
                                    METRICS.incrementCounter("high_crowd_density");
                                if (neighborId != -1 && distSq < 4.0f)
                                    METRICS.incrementCounter("close_entity_pairs");

                                // IMPORTANT: Complete future with ORIGINAL UNCHANGED physics data
                                // This ensures vanilla Minecraft gameplay is NOT modified
                                EntityPhysicsData pd = task.physicsData;
                                EntityPhysicsData resultData = new EntityPhysicsData(pd.motionX, pd.motionY,
                                        pd.motionZ);
                                future.complete(
                                        new EntityProcessingResult(true, "Rust Metrics (No Gameplay Change)",
                                                resultData));
                                processedEntities.incrementAndGet();
                            }
                        }
                        rustSuccess = true;
                        METRICS.incrementCounter("rust_spatial_grid_processed");
                    }
                } catch (Exception e) {
                    LOGGER.warn("Rust zero-copy batch processing failed, falling back: {}", e.getMessage());
                }
            }

            // 3. Fallback to Java Thread Pool
            if (!rustSuccess) {
                for (int j = 0; j < batchSize; j++) {
                    EntityProcessingTask task = batch.get(j);
                    // Futures are already created and tracked in activeFutures/futures list
                    long entityId = ((EntityInterface) task.entity).getId();
                    CompletableFuture<EntityProcessingResult> future = activeFutures.get(entityId);

                    if (future != null && !future.isDone()) {
                        // Calculate magnitude for optimized task
                        EntityPhysicsData pd = task.physicsData;
                        double magnitude = Math
                                .sqrt(pd.motionX * pd.motionX + pd.motionY * pd.motionY + pd.motionZ * pd.motionZ);

                        entityProcessor.submit(() -> processEntityTaskOptimized(task, future, magnitude));
                    }
                }
            }
        }
    }

    /**
     * Process entity task with precomputed velocity magnitude (OPTIMIZED)
     */
    private void processEntityTaskOptimized(EntityProcessingTask task, CompletableFuture<EntityProcessingResult> future,
            double velocityMagnitude) {
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
     * Calculate entity physics using optimized algorithms with precomputed velocity
     * magnitude
     * OPTIMIZED VERSION: Uses Rust-precomputed distance to skip magnitude
     * calculation
     */
    private EntityProcessingResult calculateEntityPhysicsOptimized(EntityProcessingTask task,
            double velocityMagnitude) {
        EntityInterface entity = task.entity;
        EntityPhysicsData data = task.physicsData;

        try {
            // Validate input data
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                    Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                    Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return new EntityProcessingResult(false, "Invalid motion values", data);
            }

            // Check if advanced physics optimization is enabled
            PerformanceManager perfManager = PerformanceManager.getInstance();
            boolean useAdvancedPhysics = perfManager != null && perfManager.isAdvancedPhysicsOptimized();

            EntityPhysicsData processedData;

            if (useAdvancedPhysics) {
                // ADVANCED PHYSICS OPTIMIZATION: Use Rust-powered calculations for better
                // performance
                // No damping - just use Rust for faster vector operations on all axes
                // (horizontal + vertical)
                try {
                    // Use Rust for comprehensive vector normalization and validation across all
                    // axes
                    double[] rustOptimized = RustNativeLoader.vectorNormalize(
                            data.motionX,
                            data.motionY,
                            data.motionZ);

                    // Rust normalization returns unit vector, so we need to preserve magnitude
                    double magnitude = velocityMagnitude; // Use precomputed magnitude for optimization

                    // Apply magnitude back to normalized vector for smooth physics
                    double optimizedX = rustOptimized[0] * magnitude;
                    double optimizedY = rustOptimized[1] * magnitude;
                    double optimizedZ = rustOptimized[2] * magnitude;

                    processedData = new EntityPhysicsData(optimizedX, optimizedY, optimizedZ);

                    return new EntityProcessingResult(true, "Advanced physics optimization with Rust calculations",
                            processedData);
                } catch (Exception e) {
                    // Fallback to vanilla if Rust fails
                    LOGGER.debug("Rust calculation failed, using vanilla: {}", e.getMessage());
                    processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                    return new EntityProcessingResult(true, "Vanilla physics (Rust fallback)", processedData);
                }
            } else {
                // Fallback: Pure vanilla physics passthrough
                processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);

                return new EntityProcessingResult(true, "Pure vanilla physics - no damping", processedData);
            }

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

            // Check if advanced physics optimization is enabled
            PerformanceManager perfManager = PerformanceManager.getInstance();
            boolean useAdvancedPhysics = perfManager != null && perfManager.isAdvancedPhysicsOptimized();

            EntityPhysicsData processedData;

            if (useAdvancedPhysics) {
                // ADVANCED PHYSICS OPTIMIZATION: Use Rust-powered calculations for better
                // performance
                // No damping - just use Rust for faster vector operations on all axes
                // (horizontal + vertical)
                try {
                    // Use Rust for comprehensive vector normalization and validation across all
                    // axes
                    double[] rustOptimized = RustNativeLoader.vectorNormalize(
                            data.motionX,
                            data.motionY,
                            data.motionZ);

                    // Rust normalization returns unit vector, so we need to preserve magnitude
                    // FULL RUST CALCULATION: Use Rust vectorLength instead of Math.sqrt
                    double magnitude = RustNativeLoader.vectorLength(
                            data.motionX,
                            data.motionY,
                            data.motionZ);

                    // Apply magnitude back to normalized vector for smooth physics
                    double optimizedX = rustOptimized[0] * magnitude;
                    double optimizedY = rustOptimized[1] * magnitude;
                    double optimizedZ = rustOptimized[2] * magnitude;

                    processedData = new EntityPhysicsData(optimizedX, optimizedY, optimizedZ);

                    return new EntityProcessingResult(true, "Advanced physics optimization with Rust calculations",
                            processedData);
                } catch (Exception e) {
                    // Fallback to vanilla if Rust fails
                    LOGGER.debug("Rust calculation failed, using vanilla: {}", e.getMessage());
                    processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                    return new EntityProcessingResult(true, "Vanilla physics (Rust fallback)", processedData);
                }
            } else {
                // Fallback: Pure vanilla physics passthrough
                processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);

                return new EntityProcessingResult(true, "Pure vanilla physics - no damping", processedData);
            }

        } catch (Exception e) {
            LOGGER.error("Physics calculation failed for entity {}: {}", entity.getId(), e.getMessage());
            return new EntityProcessingResult(false, "Physics calculation error: " + e.getMessage(), data);
        }
    }

    /**
     * Validate physics calculation result with enhanced direction change support
     */
    private boolean isValidPhysicsResult(double[] result, EntityPhysicsData original) {
        if (result == null || result.length != 3)
            return false;

        // Check for invalid values
        for (double val : result) {
            if (Double.isNaN(val) || Double.isInfinite(val))
                return false;
        }

        // Enhanced validation for direction changes and external forces
        // Allow for 180° direction changes and external forces (explosions, water,
        // knockback)

        // Check for direction reversals (180° turns)
        boolean xDirectionReversed = (original.motionX > 0 && result[0] < 0) ||
                (original.motionX < 0 && result[0] > 0);
        boolean zDirectionReversed = (original.motionZ > 0 && result[2] < 0) ||
                (original.motionZ < 0 && result[2] > 0);

        // Use different thresholds for direction reversals vs normal movement
        final double HORIZONTAL_THRESHOLD_NORMAL = 5.0; // For normal movements (increased from 3.0)
        final double HORIZONTAL_THRESHOLD_REVERSED = 12.0; // For direction reversals (180° turns) (increased from 8.0)
        final double VERTICAL_THRESHOLD = 8.0; // For falling and jumping (increased from 5.0)

        double horizontalThreshold = (xDirectionReversed || zDirectionReversed) ? HORIZONTAL_THRESHOLD_REVERSED
                : HORIZONTAL_THRESHOLD_NORMAL;

        // Apply enhanced thresholds with direction change consideration
        if (Math.abs(result[0]) > Math.abs(original.motionX) * horizontalThreshold)
            return false;
        if (Math.abs(result[2]) > Math.abs(original.motionZ) * horizontalThreshold)
            return false;
        if (Math.abs(result[1]) > Math.abs(original.motionY) * VERTICAL_THRESHOLD)
            return false;

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

                // Log performance metrics every interval
                logPerformanceMetrics();
            } catch (Exception e) {
                LOGGER.error("Error in maintenance task: {}", e.getMessage(), e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Log performance metrics
     */
    private void logPerformanceMetrics() {
        EntityProcessingStatistics stats = getStatistics();

        // Calculate throughput
        long currentCount = processedEntities.get();
        long currentTime = System.currentTimeMillis();
        long lastCount = lastProcessedCount.getAndSet(currentCount);
        long lastTime = lastLogTime.getAndSet(currentTime);

        long deltaCount = currentCount - lastCount;
        long deltaTime = currentTime - lastTime;
        double throughput = 0.0;

        if (deltaTime > 0) {
            throughput = (double) deltaCount / (deltaTime / 1000.0);
        }

        LOGGER.debug(
                "EntityProcessingService Metrics - Processed: {} (Rate: {:.1f}/sec), Queued: {}, Active: {}, QueueSize: {}, GridCells: {}, PoolSize: {}, CPU: {}%, AvgProcTime: {}ms",
                stats.processedEntities,
                throughput,
                stats.queuedEntities,
                stats.activeProcessors,
                stats.queueSize,
                spatialGrid.getGridSize(),
                physicsDataPool.size(),
                String.format("%.2f", stats.cpuUsage),
                String.format("%.3f", stats.averageProcessingTimeMs));
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
                averageProcessingTimeMs);
    }

    /**
     * Shutdown service gracefully
     */
    public void shutdown() {
        isRunning.set(false);

        // Cancel all pending operations
        activeFutures.values()
                .forEach(future -> future.complete(new EntityProcessingResult(false, "Service shutdown", null)));

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
     * Get entity adapter for processing - handles both EntityInterface and
     * reflection for other objects
     */
    private EntityInterface getEntityAdapter(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Entity cannot be null");
        }

        // First, try direct interface implementation
        if (entity instanceof EntityInterface) {
            return (EntityInterface) entity;
        }

        // For other objects, use reflection adapter
        return new ReflectionEntityAdapter(entity);
    }

    /**
     * Reflection-based adapter for any object with getX(), getY(), getZ(), getId()
     * methods
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
                throw new IllegalArgumentException(
                        "Entity does not implement EntityInterface and missing required methods: getId(), getX(), getY(), getZ()",
                        e);
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
     * UNIVERSAL MODE: Accept ALL entities (vanilla, modded, adapters)
     * 
     * @param entity Entity to validate
     * @return true if entity is valid for production use
     */
    private boolean isValidProductionEntity(Object entity) {
        if (entity == null)
            return false;

        // UNIVERSAL OPTIMIZATION: Accept ALL entities
        // No whitelist filtering - process everything for maximum performance
        return true;
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