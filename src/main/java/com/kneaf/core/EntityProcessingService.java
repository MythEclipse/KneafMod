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
    
    // Performance metrics
    private final AtomicLong processedEntities = new AtomicLong(0);
    private final AtomicLong queuedEntities = new AtomicLong(0);
    private final AtomicInteger activeProcessors = new AtomicInteger(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    
    // Configuration
    private static final int MAX_THREAD_POOL_SIZE = 4; // Fixed safe value to avoid IllegalArgumentException
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final int PROCESSING_TIMEOUT_MS = 50; // 50ms timeout for entity processing
    
    private EntityProcessingService() {
        this.entityProcessor = new ThreadPoolExecutor(
            MAX_THREAD_POOL_SIZE / 2,
            MAX_THREAD_POOL_SIZE,
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
        
        this.forkJoinPool = new ForkJoinPool(MAX_THREAD_POOL_SIZE);
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        this.processingQueue = new ConcurrentLinkedQueue<>();
        this.activeFutures = new ConcurrentHashMap<>();
        
        // Start maintenance task
        startMaintenanceTask();
        
        LOGGER.info("EntityProcessingService initialized with {} max threads", MAX_THREAD_POOL_SIZE);
    }
    
    public static EntityProcessingService getInstance() {
        return INSTANCE;
    }
    
    /**
     * Submit entity for async processing
     */
    public CompletableFuture<EntityProcessingResult> processEntityAsync(Entity entity, EntityPhysicsData physicsData) {
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
        
        EntityProcessingTask task = new EntityProcessingTask(entity, physicsData);
        queuedEntities.incrementAndGet();
        
        CompletableFuture<EntityProcessingResult> future = new CompletableFuture<>();
        activeFutures.put((long) entity.getId(), future);
        
        // Submit to thread pool for processing
        entityProcessor.submit(() -> processEntityTask(task, future));
        
        return future;
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
                // Apply horizontal damping while preserving gravity
                double horizontalDamping = 0.015;
                EntityPhysicsData processedData = new EntityPhysicsData(
                    result[0] * (1 - horizontalDamping),
                    data.motionY, // Preserve original gravity
                    result[2] * (1 - horizontalDamping)
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
     * Validate physics calculation result
     */
    private boolean isValidPhysicsResult(double[] result, EntityPhysicsData original) {
        if (result == null || result.length != 3) return false;
        
        // Check for invalid values
        for (double val : result) {
            if (Double.isNaN(val) || Double.isInfinite(val)) return false;
        }
        
        // Prevent extreme value changes
        final double HORIZONTAL_THRESHOLD = 1.5;
        final double VERTICAL_THRESHOLD = 2.0;
        
        if (Math.abs(result[0]) > Math.abs(original.motionX) * HORIZONTAL_THRESHOLD) return false;
        if (Math.abs(result[2]) > Math.abs(original.motionZ) * HORIZONTAL_THRESHOLD) return false;
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
        LOGGER.info("EntityProcessingService Metrics - Processed: {}, Queued: {}, Active: {}, QueueSize: {}", 
            processedEntities.get(), 
            queuedEntities.get(), 
            activeProcessors.get(),
            processingQueue.size()
        );
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
            isRunning.get()
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
        
        public EntityProcessingTask(Entity entity, EntityPhysicsData physicsData) {
            this.entity = entity;
            this.physicsData = physicsData;
        }
    }
    
    /**
     * Entity physics data
     */
    public static class EntityPhysicsData {
        public final double motionX;
        public final double motionY;
        public final double motionZ;
        
        public EntityPhysicsData(double motionX, double motionY, double motionZ) {
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
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
        
        public EntityProcessingStatistics(long processedEntities, long queuedEntities, int activeProcessors,
                                        int queueSize, int activeFutures, boolean isRunning) {
            this.processedEntities = processedEntities;
            this.queuedEntities = queuedEntities;
            this.activeProcessors = activeProcessors;
            this.queueSize = queueSize;
            this.activeFutures = activeFutures;
            this.isRunning = isRunning;
        }
    }
}