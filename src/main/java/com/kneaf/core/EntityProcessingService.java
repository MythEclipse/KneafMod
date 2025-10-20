package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
 * Spatial grid for efficient collision detection and entity queries
 */
class SpatialGrid {
    private final double cellSize;
    final Map<String, Set<Long>> grid;
    private final Map<Long, String> entityCells;
    
    public SpatialGrid(double cellSize) {
        this.cellSize = cellSize;
        this.grid = new ConcurrentHashMap<>();
        this.entityCells = new ConcurrentHashMap<>();
    }
    
    public void updateEntity(long entityId, double x, double y, double z) {
        String newCell = getCellKey(x, y, z);
        
        // Remove from old cell if exists
        String oldCell = entityCells.remove(entityId);
        if (oldCell != null && !oldCell.equals(newCell)) {
            Set<Long> entities = grid.get(oldCell);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) {
                    grid.remove(oldCell);
                }
            }
        }
        
        // Add to new cell
        if (!newCell.equals(oldCell)) {
            entityCells.put(entityId, newCell);
            grid.computeIfAbsent(newCell, k -> new HashSet<>()).add(entityId);
        }
    }
    
    public void removeEntity(long entityId) {
        String cell = entityCells.remove(entityId);
        if (cell != null) {
            Set<Long> entities = grid.get(cell);
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
        
        // Check surrounding cells
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dy = -radiusCells; dy <= radiusCells; dy++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    String cell = (cx + dx) + "," + (cy + dy) + "," + (cz + dz);
                    Set<Long> entities = grid.get(cell);
                    if (entities != null) {
                        nearby.addAll(entities);
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
}


/**
 * Async entity processing service with thread-safe queue and parallel processing capabilities.
 * Handles entity physics calculations asynchronously to prevent main thread blocking.
 */
public final class EntityProcessingService {
    private static final Logger LOGGER = LogUtils.getLogger();
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
        return Math.max(2, Math.min(availableProcessors, 8));
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
        
        LOGGER.info("EntityProcessingService initialized with {} max threads", maxThreadPoolSize);
    }
    
    public static EntityProcessingService getInstance() {
        return INSTANCE;
    }
    
    /**
     * Submit entity for async processing with priority
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Entity entity, EntityPhysicsData physicsData) {
        return processEntityAsync(entity, physicsData, EntityPriority.MEDIUM);
    }
    
    /**
     * Submit entity for async processing with custom priority
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Entity entity, EntityPhysicsData physicsData, EntityPriority priority) {
        if (!isRunning.get()) {
            return CompletableFuture.completedFuture(
                new EntityProcessingResult(false, "Service is shutdown", physicsData)
            );
        }
        
        if (entity == null || entity.level().isClientSide() || entity instanceof Player) {
            return CompletableFuture.completedFuture(
                new EntityProcessingResult(false, "Invalid entity or client-side", physicsData)
            );
        }
        
        // Update spatial grid
        spatialGrid.updateEntity(entity.getId(), entity.getX(), entity.getY(), entity.getZ());
        
        // Store entity priority
        entityPriorities.put((long) entity.getId(), priority);
        
        // Try to get physics data from pool
        EntityPhysicsData pooledData = physicsDataPool.poll();
        EntityPhysicsData finalData = pooledData != null ?
            new EntityPhysicsData(physicsData.motionX, physicsData.motionY, physicsData.motionZ) :
            physicsData;
        
        EntityProcessingTask task = new EntityProcessingTask(entity, finalData, priority);
        queuedEntities.incrementAndGet();
        
        CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
        activeFutures.put((long) entity.getId(), future);
        
        // Submit to thread pool for processing
        entityProcessor.submit(() -> processEntityTask(task, future));
        
        return future;
    }
    
    /**
     * Process entities in batches by priority for better performance
     */
    public List<CompletableFuture<EntityProcessingResult>> processEntityBatch(List<Entity> entities, List<EntityPhysicsData> physicsDataList) {
        if (!isRunning.get() || entities.size() != physicsDataList.size()) {
            return Collections.emptyList();
        }
        
        // Group entities by priority
        Map<EntityPriority, List<EntityProcessingTask>> priorityGroups = new HashMap<>();
        
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            EntityPhysicsData data = physicsDataList.get(i);
            
            if (entity == null || entity.level().isClientSide() || entity instanceof Player) {
                continue;
            }
            
            // Update spatial grid
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
     */
    private void processPriorityBatch(List<EntityProcessingTask> tasks, List<CompletableFuture<EntityProcessingResult>> futures) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        
        // Create batches of 32 entities for efficient processing
        for (int i = 0; i < tasks.size(); i += 32) {
            List<EntityProcessingTask> batch = tasks.subList(i, Math.min(i + 32, tasks.size()));
            
            for (EntityProcessingTask task : batch) {
                queuedEntities.incrementAndGet();
                
                CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
                activeFutures.put((long) task.entity.getId(), future);
                futures.add(future);
                
                // Submit batch processing
                entityProcessor.submit(() -> processEntityTask(task, future));
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
            activeFutures.remove((long) task.entity.getId());
            
            // Return physics data to pool if it's not the original data
            if (task.physicsData != null) {
                // Reset data before returning to pool
                task.physicsData.reset();
                physicsDataPool.offer(task.physicsData);
            }
        }
    }
    
    /**
     * Calculate entity physics using optimized algorithms
     */
    private EntityProcessingResult calculateEntityPhysics(EntityProcessingTask task) {
        Entity entity = task.entity;
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
            double dampingFactor = entityType.getDampingFactor();
            
            // Perform physics calculation
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
        LOGGER.info("EntityProcessingService Metrics - Processed: {}, Queued: {}, Active: {}, QueueSize: {}, GridCells: {}, PoolSize: {}",
            stats.processedEntities,
            stats.queuedEntities,
            stats.activeProcessors,
            stats.queueSize,
            stats.spatialGridCells,
            stats.physicsDataPoolSize
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
            spatialGrid.grid.size(),
            physicsDataPool.size()
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
        public final Entity entity;
        public final EntityPhysicsData physicsData;
        public final EntityPriority priority;
        
        public EntityProcessingTask(Entity entity, EntityPhysicsData physicsData) {
            this.entity = entity;
            this.physicsData = physicsData;
            this.priority = EntityPriority.MEDIUM; // Default priority
        }
        
        public EntityProcessingTask(Entity entity, EntityPhysicsData physicsData, EntityPriority priority) {
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
    public static class EntityProcessingStatistics {
        public final long processedEntities;
        public final long queuedEntities;
        public final int activeProcessors;
        public final int queueSize;
        public final int activeFutures;
        public final boolean isRunning;
        public final int spatialGridCells;
        public final int physicsDataPoolSize;
        
        public EntityProcessingStatistics(long processedEntities, long queuedEntities, int activeProcessors,
                                        int queueSize, int activeFutures, boolean isRunning, int spatialGridCells, int physicsDataPoolSize) {
            this.processedEntities = processedEntities;
            this.queuedEntities = queuedEntities;
            this.activeProcessors = activeProcessors;
            this.queueSize = queueSize;
            this.activeFutures = activeFutures;
            this.isRunning = isRunning;
            this.spatialGridCells = spatialGridCells;
            this.physicsDataPoolSize = physicsDataPoolSize;
        }
    }
}