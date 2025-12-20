package com.kneaf.core;

import com.kneaf.core.async.AsyncLogger;
import com.kneaf.core.math.VectorMath;
import com.kneaf.core.model.*;

import com.kneaf.core.spatial.SpatialGrid;
import com.kneaf.core.performance.scheduler.AdaptiveThreadPoolController;
import net.minecraft.world.entity.Entity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

/**
 * Service for handling asynchronous entity processing
 * Integrates with Rust native library for performance critical operations
 * Uses a spatial grid for efficient entity lookups and collision detection
 */
public class EntityProcessingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityProcessingService.class);
    private static final AsyncLogger ASYNC_LOGGER = new AsyncLogger(EntityProcessingService.class);
    private static final com.kneaf.core.async.AsyncMetricsCollector METRICS = com.kneaf.core.async.AsyncMetricsCollector
            .getInstance();

    private static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final long PROCESSING_TIMEOUT_MS = 100;
    private static final double CELL_SIZE = 16.0; // Minecraft chunk size

    // Singleton instance
    private static volatile EntityProcessingService instance;

    // Service state
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ThreadPoolExecutor entityProcessor;
    private final ForkJoinPool forkJoinPool;
    private final ScheduledExecutorService maintenanceExecutor;
    private final SpatialGrid spatialGrid;

    // Entity queues and tracking
    private final ConcurrentLinkedQueue<EntityProcessingTask> processingQueue;
    private final ConcurrentHashMap<Long, CompletableFuture<EntityProcessingResult>> activeFutures;
    private final ConcurrentHashMap<Long, EntityPriority> entityPriorities;
    private final AtomicLong processedEntities = new AtomicLong(0);
    private final AtomicLong queuedEntities = new AtomicLong(0);
    private final AtomicInteger activeProcessors = new AtomicInteger(0);

    // Performance monitoring
    private final OperatingSystemMXBean osBean;
    private volatile double averageProcessingTimeMs = 0.0;
    private final AtomicLong lastProcessedCount = new AtomicLong(0);
    private final AtomicLong lastLogTime = new AtomicLong(System.currentTimeMillis());

    // Controller
    private final AdaptiveThreadPoolController threadPoolController;

    // Object Pooling for reduced GC pressure
    private final Queue<EntityPhysicsData> physicsDataPool = new ConcurrentLinkedQueue<>();

    public static EntityProcessingService getInstance() {
        if (instance == null) {
            synchronized (EntityProcessingService.class) {
                if (instance == null) {
                    instance = new EntityProcessingService();
                }
            }
        }
        return instance;
    }

    private EntityProcessingService() {
        // Initialize thread pools
        this.entityProcessor = new ThreadPoolExecutor(
                DEFAULT_THREAD_POOL_SIZE,
                DEFAULT_THREAD_POOL_SIZE,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "EntityProcessor-" + count.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY + 1); // Slightly higher priority
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure policy
        );

        this.forkJoinPool = new ForkJoinPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                null,
                true // Async mode
        );

        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EntityMaintenance");
            t.setDaemon(true);
            return t;
        });

        // Initialize components
        this.spatialGrid = new SpatialGrid(CELL_SIZE);
        this.processingQueue = new ConcurrentLinkedQueue<>();
        this.activeFutures = new ConcurrentHashMap<>();
        this.entityPriorities = new ConcurrentHashMap<>();
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.threadPoolController = new AdaptiveThreadPoolController(this);

        this.isRunning.set(true);

        // Start maintenance tasks
        startMaintenanceTask();
        maintenanceExecutor.scheduleAtFixedRate(threadPoolController, 5, 5, TimeUnit.SECONDS);

        LOGGER.info("EntityProcessingService initialized with {} threads", DEFAULT_THREAD_POOL_SIZE);
    }

    // ==========================================
    // Public API Logic
    // ==========================================

    public void updateThreadPoolSize(int newSize) {
        if (newSize > 0 && newSize != entityProcessor.getCorePoolSize()) {
            entityProcessor.setCorePoolSize(newSize);
            entityProcessor.setMaximumPoolSize(newSize);
        }
    }

    public int getCurrentThreadPoolSize() {
        return entityProcessor.getCorePoolSize();
    }

    public double getAverageProcessingTimeMs() {
        return averageProcessingTimeMs;
    }

    public double getCpuUsage() {
        // Returns CPU load between 0.0 and 100.0
        double load = osBean.getProcessCpuLoad();
        return load < 0 ? 0.0 : load * 100.0;
    }

    /**
     * Update running average of processing time
     */
    private void updateAverageProcessingTime(long durationNs) {
        double durationMs = durationNs / 1_000_000.0;
        // Exponential moving average with alpha = 0.05
        averageProcessingTimeMs = (averageProcessingTimeMs * 0.95) + (durationMs * 0.05);
    }

    public EntityProcessingResult processEntitySync(Object entity, EntityPhysicsData physicsData) {
        if (!isRunning.get()) {
            return new EntityProcessingResult(false, "Service not running", physicsData);
        }

        EntityInterface entityAdapter = getEntityAdapter(entity);

        // Update spatial grid
        spatialGrid.updateEntity(entityAdapter.getId(), entityAdapter.getX(), entityAdapter.getY(),
                entityAdapter.getZ());

        EntityProcessingTask task = new EntityProcessingTask(entityAdapter, physicsData);

        // Process directly
        try {
            long startTime = System.nanoTime();
            EntityProcessingResult result = calculateEntityPhysics(task);
            long duration = System.nanoTime() - startTime;
            updateAverageProcessingTime(duration);
            processedEntities.incrementAndGet();
            return result;
        } catch (Exception e) {
            LOGGER.error("Synchronous processing failed", e);
            return new EntityProcessingResult(false, e.getMessage(), physicsData);
        }
    }

    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData) {
        return processEntityAsync(entity, physicsData, EntityPriority.MEDIUM);
    }

    public CompletableFuture<EntityProcessingResult> processEntityAsync(Object entity, EntityPhysicsData physicsData,
            EntityPriority priority) {
        if (!isRunning.get()) {
            return CompletableFuture.completedFuture(
                    new EntityProcessingResult(false, "Service not running", physicsData));
        }

        EntityInterface entityAdapter = getEntityAdapter(entity);

        // Check cache/existing future logic if needed (omitted for brevity, using fresh
        // task)
        // ...

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

    public List<CompletableFuture<EntityProcessingResult>> processEntityBatch(List<Object> entities,
            List<EntityPhysicsData> physicsDataList) {
        if (!isRunning.get() || entities.size() != physicsDataList.size()) {
            return Collections.emptyList();
        }

        Map<EntityPriority, List<EntityProcessingTask>> priorityGroups = new HashMap<>();

        for (int i = 0; i < entities.size(); i++) {
            EntityInterface entity = getEntityAdapter(entities.get(i));
            EntityPhysicsData data = physicsDataList.get(i);

            if (entity == null) {
                continue;
            }

            if (!ModeDetector.isTestMode() && !isValidProductionEntity(entity)) {
                ASYNC_LOGGER.debug(() -> "Batch entity failed production validation, skipping: " + entity.getId());
                METRICS.incrementCounter("entity_processing.validation_failed");
                continue;
            }

            spatialGrid.updateEntity(entity.getId(), entity.getX(), entity.getY(), entity.getZ());

            EntityPriority priority = entityPriorities.getOrDefault((long) entity.getId(), EntityPriority.MEDIUM);

            EntityPhysicsData pooledData = physicsDataPool.poll();
            EntityPhysicsData finalData = pooledData != null
                    ? new EntityPhysicsData(data.motionX, data.motionY, data.motionZ)
                    : data;

            EntityProcessingTask task = new EntityProcessingTask(entity, finalData, priority);
            priorityGroups.computeIfAbsent(priority, k -> new ArrayList<>()).add(task);
        }

        List<CompletableFuture<EntityProcessingResult>> futures = new ArrayList<>();

        processPriorityBatch(priorityGroups.get(EntityPriority.CRITICAL), futures);
        processPriorityBatch(priorityGroups.get(EntityPriority.HIGH), futures);
        processPriorityBatch(priorityGroups.get(EntityPriority.MEDIUM), futures);
        processPriorityBatch(priorityGroups.get(EntityPriority.LOW), futures);

        return futures;
    }

    private void processPriorityBatch(List<EntityProcessingTask> tasks,
            List<CompletableFuture<EntityProcessingResult>> futures) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        for (int i = 0; i < tasks.size(); i += 32) {
            List<EntityProcessingTask> batch = tasks.subList(i, Math.min(i + 32, tasks.size()));
            int batchSize = batch.size();
            boolean rustSuccess = false;

            for (int j = 0; j < batchSize; j++) {
                EntityProcessingTask task = batch.get(j);
                queuedEntities.incrementAndGet();

                CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
                activeFutures.put(((EntityInterface) task.entity).getId(), future);
                futures.add(future);
            }

            if (OptimizationInjector.isNativeLibraryLoaded()) {
                try {
                    int inputSize = batchSize * 32;
                    int outputSize = batchSize * 20;

                    ByteBuffer inputBuf = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder());
                    ByteBuffer outputBuf = ByteBuffer.allocateDirect(outputSize).order(ByteOrder.nativeOrder());

                    for (int j = 0; j < batchSize; j++) {
                        EntityProcessingTask task = batch.get(j);
                        EntityInterface ent = (EntityInterface) task.entity;
                        EntityPhysicsData pd = task.physicsData;

                        inputBuf.putFloat((float) ent.getX());
                        inputBuf.putFloat((float) ent.getY());
                        inputBuf.putFloat((float) ent.getZ());
                        inputBuf.putFloat((float) pd.motionX);
                        inputBuf.putFloat((float) pd.motionY);
                        inputBuf.putFloat((float) pd.motionZ);
                        inputBuf.putInt((int) ent.getId());
                        inputBuf.putInt(0); // padding
                    }

                    RustNativeLoader.rustperf_batch_spatial_grid_zero_copy(inputBuf, outputBuf, batchSize);
                    outputBuf.position(0);

                    if (batchSize > 0) {
                        for (int j = 0; j < batchSize; j++) {
                            int neighborId = outputBuf.getInt();
                            float distSq = outputBuf.getFloat();
                            float density = outputBuf.getFloat();
                            @SuppressWarnings("unused")
                            float avoidX = outputBuf.getFloat();
                            @SuppressWarnings("unused")
                            float avoidZ = outputBuf.getFloat();

                            EntityProcessingTask task = batch.get(j);
                            CompletableFuture<EntityProcessingResult> future = activeFutures
                                    .get(((EntityInterface) task.entity).getId());

                            if (future != null) {
                                if (density > 5.0f)
                                    METRICS.incrementCounter("high_crowd_density");
                                if (neighborId != -1 && distSq < 4.0f)
                                    METRICS.incrementCounter("close_entity_pairs");

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

            if (!rustSuccess) {
                for (int j = 0; j < batchSize; j++) {
                    EntityProcessingTask task = batch.get(j);
                    long entityId = ((EntityInterface) task.entity).getId();
                    CompletableFuture<EntityProcessingResult> future = activeFutures.get(entityId);

                    if (future != null && !future.isDone()) {
                        EntityPhysicsData pd = task.physicsData;
                        double magnitude = Math
                                .sqrt(pd.motionX * pd.motionX + pd.motionY * pd.motionY + pd.motionZ * pd.motionZ);

                        entityProcessor.submit(() -> processEntityTaskOptimized(task, future, magnitude));
                    }
                }
            }
        }
    }

    private void processEntityTaskOptimized(EntityProcessingTask task, CompletableFuture<EntityProcessingResult> future,
            double velocityMagnitude) {
        activeProcessors.incrementAndGet();
        long startTime = System.nanoTime();

        try {
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

            if (task.physicsData != null) {
                task.physicsData.reset();
                physicsDataPool.offer(task.physicsData);
            }
        }
    }

    private void processEntityTask(EntityProcessingTask task, CompletableFuture<EntityProcessingResult> future) {
        activeProcessors.incrementAndGet();
        long startTime = System.nanoTime();

        try {
            EntityProcessingResult result = calculateEntityPhysics(task);
            long duration = System.nanoTime() - startTime;
            updateAverageProcessingTime(duration);

            if (duration > PROCESSING_TIMEOUT_MS * 1_000_000L) {
                LOGGER.warn("Entity processing took {}ms", duration / 1_000_000L);
            }

            future.complete(result);
            processedEntities.incrementAndGet();
        } catch (Exception e) {
            LOGGER.error("Processing error", e);
            future.completeExceptionally(e);
        } finally {
            activeProcessors.decrementAndGet();
            queuedEntities.decrementAndGet();
            activeFutures.remove(task.entity.getId());
            if (task.physicsData != null) {
                task.physicsData.reset();
                physicsDataPool.offer(task.physicsData);
            }
        }
    }

    private EntityProcessingResult calculateEntityPhysicsOptimized(EntityProcessingTask task,
            double velocityMagnitude) {
        EntityInterface entity = task.entity;
        EntityPhysicsData data = task.physicsData;

        try {
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                    Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                    Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return new EntityProcessingResult(false, "Invalid motion values", data);
            }

            PerformanceManager perfManager = PerformanceManager.getInstance();
            boolean useAdvancedPhysics = perfManager != null && perfManager.isAdvancedPhysicsOptimized();

            if (useAdvancedPhysics) {
                try {
                    double[] rustOptimized = VectorMath.normalize(data.motionX, data.motionY, data.motionZ);
                    double magnitude = velocityMagnitude;

                    double optimizedX = rustOptimized[0] * magnitude;
                    double optimizedY = rustOptimized[1] * magnitude;
                    double optimizedZ = rustOptimized[2] * magnitude;

                    EntityPhysicsData processedData = new EntityPhysicsData(optimizedX, optimizedY, optimizedZ);
                    return new EntityProcessingResult(true, "Advanced physics optimization", processedData);
                } catch (Exception e) {
                    LOGGER.debug("VectorMath calculation failed, using vanilla: {}", e.getMessage());
                    EntityPhysicsData processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                    return new EntityProcessingResult(true, "Vanilla physics (fallback)", processedData);
                }
            } else {
                EntityPhysicsData processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                return new EntityProcessingResult(true, "Pure vanilla physics", processedData);
            }
        } catch (Exception e) {
            LOGGER.error("Physics calculation failed", e);
            return new EntityProcessingResult(false, e.getMessage(), data);
        }
    }

    private EntityProcessingResult calculateEntityPhysics(EntityProcessingTask task) {
        EntityInterface entity = task.entity;
        EntityPhysicsData data = task.physicsData;

        try {
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                    Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                    Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                return new EntityProcessingResult(false, "Invalid motion values", data);
            }

            PerformanceManager perfManager = PerformanceManager.getInstance();
            boolean useAdvancedPhysics = perfManager != null && perfManager.isAdvancedPhysicsOptimized();

            if (useAdvancedPhysics) {
                try {
                    double[] rustOptimized = VectorMath.normalize(data.motionX, data.motionY, data.motionZ);
                    double magnitude = VectorMath.length(data.motionX, data.motionY, data.motionZ);

                    double optimizedX = rustOptimized[0] * magnitude;
                    double optimizedY = rustOptimized[1] * magnitude;
                    double optimizedZ = rustOptimized[2] * magnitude;

                    EntityPhysicsData processedData = new EntityPhysicsData(optimizedX, optimizedY, optimizedZ);
                    return new EntityProcessingResult(true, "Advanced physics", processedData);
                } catch (Exception e) {
                    LOGGER.debug("VectorMath failed", e);
                    EntityPhysicsData processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                    return new EntityProcessingResult(true, "Vanilla physics (fallback)", processedData);
                }
            } else {
                EntityPhysicsData processedData = new EntityPhysicsData(data.motionX, data.motionY, data.motionZ);
                return new EntityProcessingResult(true, "Vanilla physics", processedData);
            }
        } catch (Exception e) {
            LOGGER.error("Physics calculation failed", e);
            return new EntityProcessingResult(false, e.getMessage(), data);
        }
    }

    private void startMaintenanceTask() {
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                activeFutures.entrySet().removeIf(entry -> entry.getValue().isDone());
                logPerformanceMetrics();
            } catch (Exception e) {
                LOGGER.error("Maintenance task error", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void logPerformanceMetrics() {
        EntityProcessingStatistics stats = getStatistics();
        long currentCount = processedEntities.get();
        long currentTime = System.currentTimeMillis();
        long lastCount = lastProcessedCount.getAndSet(currentCount);
        long lastTime = lastLogTime.getAndSet(currentTime);

        long deltaCount = currentCount - lastCount;
        long deltaTime = currentTime - lastTime;
        double throughput = deltaTime > 0 ? (double) deltaCount / (deltaTime / 1000.0) : 0.0;

        LOGGER.debug(
                "Metrics - Processed: {} (Rate: {:.1f}/sec), Queued: {}, Active: {}, QueueSize: {}, Grid: {}, Pool: {}, CPU: {}%, Avg: {}ms",
                stats.processedEntities, throughput, stats.queuedEntities, stats.activeProcessors,
                stats.queueSize, spatialGrid.getGridSize(), physicsDataPool.size(),
                String.format("%.2f", stats.cpuUsage), String.format("%.3f", stats.averageProcessingTimeMs));
    }

    public void setEntityPriority(long entityId, EntityPriority priority) {
        entityPriorities.put(entityId, priority);
    }

    public EntityPriority getEntityPriority(long entityId) {
        return entityPriorities.getOrDefault(entityId, EntityPriority.MEDIUM);
    }

    public Set<Long> getNearbyEntities(double x, double y, double z, double radius) {
        return spatialGrid.getNearbyEntities(x, y, z, radius);
    }

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

    public void shutdown() {
        isRunning.set(false);
        activeFutures.values()
                .forEach(future -> future.complete(new EntityProcessingResult(false, "Service shutdown", null)));
        spatialGrid.clear();
        entityPriorities.clear();
        physicsDataPool.clear();
        entityProcessor.shutdown();
        forkJoinPool.shutdown();
        maintenanceExecutor.shutdown();

        try {
            if (!entityProcessor.awaitTermination(5, TimeUnit.SECONDS))
                entityProcessor.shutdownNow();
            if (!forkJoinPool.awaitTermination(5, TimeUnit.SECONDS))
                forkJoinPool.shutdownNow();
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS))
                maintenanceExecutor.shutdownNow();
        } catch (InterruptedException e) {
            entityProcessor.shutdownNow();
            forkJoinPool.shutdownNow();
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Shutdown completed");
    }

    private EntityInterface getEntityAdapter(Object entity) {
        if (entity == null)
            throw new IllegalArgumentException("Entity cannot be null");
        if (entity instanceof EntityInterface)
            return (EntityInterface) entity;
        return new ReflectionEntityAdapter(entity);
    }

    private static class ReflectionEntityAdapter implements EntityInterface {
        private final Object entity;
        private java.lang.reflect.Method getIdMethod;
        private java.lang.reflect.Method getXMethod;
        private java.lang.reflect.Method getYMethod;
        private java.lang.reflect.Method getZMethod;

        public ReflectionEntityAdapter(Object entity) {
            this.entity = entity;
            try {
                this.getIdMethod = findMethod(entity.getClass(), "getId");
                this.getXMethod = findMethod(entity.getClass(), "getX");
                this.getYMethod = findMethod(entity.getClass(), "getY");
                this.getZMethod = findMethod(entity.getClass(), "getZ");
            } catch (Exception e) {
                throw new IllegalArgumentException("Missing required methods", e);
            }
        }

        private java.lang.reflect.Method findMethod(Class<?> clazz, String methodName) {
            try {
                return clazz.getMethod(methodName);
            } catch (NoSuchMethodException e) {
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null && !superClass.equals(Object.class))
                    return findMethod(superClass, methodName);
                return null;
            }
        }

        @Override
        public long getId() {
            try {
                Object result = getIdMethod.invoke(entity);
                if (result instanceof Number)
                    return ((Number) result).longValue();
                throw new IllegalArgumentException("getId() invalid return type");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public double getX() {
            try {
                return ((Number) getXMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public double getY() {
            try {
                return ((Number) getYMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public double getZ() {
            try {
                return ((Number) getZMethod.invoke(entity)).doubleValue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private boolean isValidProductionEntity(Object entity) {
        return entity != null;
    }
}