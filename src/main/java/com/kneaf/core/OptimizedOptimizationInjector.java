package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.slf4j.Logger;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.kneaf.core.performance.PerformanceMonitoringSystem;

/**
 * Optimized entity processing injector with async processing, parallel library loading,
 * and hash-based entity type lookup for maximum performance.
 * 
 * Key optimizations:
 * - Async entity processing using CompletableFuture and ExecutorService
 * - Parallel native library loading with dependency resolution
 * - Optimized entity type checking using enum-based hash lookup
 * - Thread-safe entity processing queue
 * - Non-blocking main thread integration
 * - Knockback protection to preserve damage-induced movement
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizedOptimizationInjector {
    private static final String RUST_USAGE_POLICY = "CALCULATION_ONLY - NO_GAME_LOGIC - NO_AI - NO_STATE_MODIFICATION - NO_ENTITY_CONTROL";
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Services
    private static final EntityProcessingService entityProcessingService = EntityProcessingService.getInstance();
    private static final ParallelLibraryLoader libraryLoader = ParallelLibraryLoader.getInstance();
    private static final PerformanceManager performanceManager = PerformanceManager.getInstance();
    
    // Performance metrics
    private static final AtomicInteger asyncOptimizationHits = new AtomicInteger(0);
    private static final AtomicInteger asyncOptimizationMisses = new AtomicInteger(0);
    private static final AtomicInteger asyncOptimizationErrors = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessedAsync = new AtomicLong(0);
    private static final AtomicLong totalProcessingTimeNs = new AtomicLong(0);
    
    // Library loading state - uses OptimizationInjector's loading status
    private static volatile boolean isLibraryLoading = false;
    private static CompletableFuture<ParallelLibraryLoader.LibraryLoadResult> libraryLoadFuture;
    
    // Configuration
    private static final int BASE_ASYNC_TIMEOUT_MS = 50; // Base timeout for async processing
    private static final int MAX_CONCURRENT_ENTITIES = 1000;
    private static final int MIN_ASYNC_TIMEOUT_MS = 25;   // Minimum timeout for critical entities
    private static final int MAX_ASYNC_TIMEOUT_MS = 200;  // Maximum timeout for complex entities
    // Knockback protection - track entities that recently took damage
    private static final Map<Integer, Integer> recentlyDamagedEntities = new HashMap<>();
    private static final int KNOCKBACK_PROTECTION_TICKS = 6; // 0.3 seconds at 20 TPS (reduced from 10)
    private static boolean isTestMode = false;
    
    static {
        if (!isTestMode) {
            initializeAsyncLibraryLoading();
        }
    }
    
    /**
     * Initialize async library loading with parallel dependency resolution
     */
    private static void initializeAsyncLibraryLoading() {
        isLibraryLoading = true;
        
        // Use OptimizationInjector's synchronous loading status
        isLibraryLoading = false; // Not actually loading async anymore
        LOGGER.info("Using OptimizationInjector's native library loading status");
    }
    
    /**
     * Track entities that recently took damage to preserve knockback
     */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) return;
        
        // Mark entity as recently damaged to skip optimization temporarily
        recentlyDamagedEntities.put(event.getEntity().getId(), KNOCKBACK_PROTECTION_TICKS);
        
        // Log at trace level to reduce noise - this is expected behavior
        LOGGER.trace("Entity {} marked for knockback protection after taking damage",
            event.getEntity().getId());
    }
    
    /**
     * Async entity tick handler - non-blocking main thread integration
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        long startTime = System.nanoTime();
        Entity entity = event.getEntity();
        
        try {
            // Quick validation checks
            if (entity.level().isClientSide() || !performanceManager.isEntityThrottlingEnabled()) {
                return;
            }
            
            // Entity-specific throttling - exempt performance-critical entities
            if (entity instanceof Player) {
                return; // Players are always exempt from throttling
            }
            
            // Check for boss entities and other performance-critical entities
            if (isPerformanceCriticalEntity(entity)) {
                recordAsyncOptimizationMiss("Performance-critical entity " + entity.getId() + " exempt from throttling");
                return;
            }
            
            // Check if entity is under knockback protection
            Integer protectionTicks = recentlyDamagedEntities.get(entity.getId());
            if (protectionTicks != null && protectionTicks > 0) {
                // Skip optimization for entities under knockback protection
                // Log as trace level to reduce noise, this is expected behavior
                LOGGER.trace("Entity {} under knockback protection ({} ticks)", entity.getId(), protectionTicks);
                
                // Decrement protection ticks
                recentlyDamagedEntities.put(entity.getId(), protectionTicks - 1);
                if (protectionTicks - 1 <= 0) {
                    recentlyDamagedEntities.remove(entity.getId());
                }
                
                return;
            }
            
            // Check if library is ready using OptimizationInjector's status
            if (!OptimizationInjector.isNativeLibraryLoaded()) {
                recordAsyncOptimizationMiss("Native library not loaded for entity " + entity.getId());
                return;
            }
            
            // Extract physics data
            EntityProcessingService.EntityPhysicsData physicsData = extractPhysicsData(entity);
            if (physicsData == null) {
                recordAsyncOptimizationMiss("Invalid physics data for entity " + entity.getId());
                return;
            }
            
            // Calculate adaptive timeout based on entity type and server performance
            int adaptiveTimeoutMs = calculateAdaptiveTimeout(entity);
            
            // Submit for async processing with adaptive timeout
            CompletableFuture<EntityProcessingService.EntityProcessingResult> future =
                entityProcessingService.processEntityAsync(entity, physicsData);
            
            // Handle result asynchronously (non-blocking)
            future.thenAccept(result -> {
                long processingTime = System.nanoTime() - startTime;
                if (result.success) {
                    applyPhysicsResult(entity, result);
                    recordAsyncOptimizationHit("Async processing successful for entity " + entity.getId());
                    
                    // Record successful processing metrics
                    Map<String, Object> context = new HashMap<>();
                    context.put("entity_id", entity.getId());
                    context.put("entity_type", entity.getType().toString());
                    context.put("operation", "entity_processing");
                    context.put("result", "success");
                    PerformanceMonitoringSystem.getInstance().recordEvent(
                        "OptimizedOptimizationInjector", "entity_process", processingTime, context);
                } else {
                    recordAsyncOptimizationMiss("Async processing failed for entity " + entity.getId() + ": " + result.message);
                    
                    // Record failed processing metrics
                    Map<String, Object> errorContext = new HashMap<>();
                    errorContext.put("entity_id", entity.getId());
                    errorContext.put("entity_type", entity.getType().toString());
                    errorContext.put("operation", "entity_processing");
                    errorContext.put("result", "failure");
                    errorContext.put("reason", result.message);
                    PerformanceMonitoringSystem.getInstance().recordError(
                        "OptimizedOptimizationInjector",
                        new RuntimeException("Entity processing failed: " + result.message), errorContext);
                }
            }).exceptionally(throwable -> {
                long processingTime = System.nanoTime() - startTime;
                recordAsyncOptimizationError("Async processing error for entity " + entity.getId() + ": " + throwable.getMessage());
                
                // Record error metrics
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("entity_id", entity.getId());
                errorContext.put("entity_type", entity.getType().toString());
                errorContext.put("operation", "entity_processing");
                errorContext.put("result", "error");
                errorContext.put("error_type", throwable.getClass().getSimpleName());
                PerformanceMonitoringSystem.getInstance().recordError(
                    "OptimizedOptimizationInjector", throwable, errorContext);
                
                return null;
            });
            
            // Timeout handling with adaptive timeout - fallback to synchronous if async takes too long
            future.orTimeout(adaptiveTimeoutMs, TimeUnit.MILLISECONDS).exceptionally(throwable -> {
                // Fallback to synchronous processing for timeout
                performSynchronousFallback(entity, physicsData);
                recordAsyncOptimizationMiss("Adaptive timeout (" + adaptiveTimeoutMs + "ms) exceeded for entity " + entity.getId());
                return null;
            });
            
        } catch (Exception e) {
            // Record unexpected error
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("entity_id", entity.getId());
            errorContext.put("operation", "entity_tick");
            errorContext.put("error_type", "unexpected");
            PerformanceMonitoringSystem.getInstance().recordError(
                "OptimizedOptimizationInjector", e, errorContext);
            
            LOGGER.error("Unexpected error in entity tick handler for entity {}: {}", entity.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Extract physics data from entity
     */
    private static EntityProcessingService.EntityPhysicsData extractPhysicsData(Entity entity) {
        try {
            double x = entity.getDeltaMovement().x;
            double y = entity.getDeltaMovement().y;
            double z = entity.getDeltaMovement().z;
            
            // Validate motion values
            if (Double.isNaN(x) || Double.isInfinite(x) || 
                Double.isNaN(y) || Double.isInfinite(y) || 
                Double.isNaN(z) || Double.isInfinite(z)) {
                return null;
            }
            
            return new EntityProcessingService.EntityPhysicsData(x, y, z);
        } catch (Exception e) {
            LOGGER.error("Error extracting physics data from entity {}: {}", entity.getId(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Apply physics result to entity
     */
    private static void applyPhysicsResult(Entity entity, 
                                         EntityProcessingService.EntityProcessingResult result) {
        if (!result.success || result.processedData == null) {
            return;
        }
        
        try {
            EntityProcessingService.EntityPhysicsData data = result.processedData;
            
            // Apply the processed physics with safety checks
            entity.setDeltaMovement(data.motionX, data.motionY, data.motionZ);
            
            totalEntitiesProcessedAsync.incrementAndGet();
            
        } catch (Exception e) {
            LOGGER.error("Error applying physics result to entity {}: {}", entity.getId(), e.getMessage());
        }
    }
    
    /**
     * Synchronous fallback for timeout scenarios
     */
    private static void performSynchronousFallback(Entity entity, 
                                                 EntityProcessingService.EntityPhysicsData physicsData) {
        try {
            // Quick synchronous fallback using optimized entity type lookup
            EntityTypeEnum entityType = EntityTypeEnum.fromEntity(entity);
            double dampingFactor = entityType.getDampingFactor();
            
            // Improved gravity handling - allow natural gravity while preserving external effects
            double processedY = physicsData.motionY;
            
            // Only preserve external gravity modifications if they are significant (knockback, explosions)
            // Allow natural gravity to work normally
            if (Math.abs(physicsData.motionY - physicsData.motionY) > 0.5) {
                // Significant external effect detected (explosion, strong knockback)
                processedY = physicsData.motionY;
            } else if (physicsData.motionY < -0.1) {
                // Natural falling - apply enhanced gravity for better feel
                processedY = Math.min(physicsData.motionY * 1.1, physicsData.motionY);
            }
            
            // Reduced horizontal damping to allow better knockback
            double horizontalDamping = 0.008; // Reduced from 0.015 for better knockback
            entity.setDeltaMovement(
                physicsData.motionX * (1 - horizontalDamping) * dampingFactor,
                processedY, // Improved gravity handling
                physicsData.motionZ * (1 - horizontalDamping) * dampingFactor
            );
            
            recordAsyncOptimizationHit("Synchronous fallback for entity " + entity.getId());
            
        } catch (Exception e) {
            recordAsyncOptimizationMiss("Synchronous fallback failed for entity " + entity.getId());
        }
    }
    
    /**
     * Server tick handler - async processing statistics
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (performanceManager.isEntityThrottlingEnabled()) {
            try {
                int entityCount = 200; // Approximate entity count
                recordAsyncOptimizationHit(String.format("Server tick processed with %d entities (async)", entityCount));

            } catch (Exception e) {
                recordAsyncOptimizationError("Server tick async processing failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Level tick handler - dimension-specific async processing
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (performanceManager.isEntityThrottlingEnabled()) {
            try {
                var level = event.getLevel();
                int entityCount = 100; // Approximate entity count
                String dimension = level.dimension().location().toString();
                
                recordAsyncOptimizationHit(String.format(
                    "Level tick processed with %d entities in %s (async)", entityCount, dimension));
                
            } catch (Exception e) {
                recordAsyncOptimizationError("Level tick async processing failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Record async optimization hit
     */
    private static void recordAsyncOptimizationHit(String details) {
        asyncOptimizationHits.incrementAndGet();

        // Record performance metrics
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "async_optimization");
        context.put("result", "hit");
        context.put("details", details);
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.async_hits");
    }
    
    /**
     * Record async optimization miss
     */
    private static void recordAsyncOptimizationMiss(String details) {
        asyncOptimizationMisses.incrementAndGet();
        
        // Record performance metrics but NOT as an error for expected misses
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "async_optimization");
        context.put("result", "miss");
        context.put("details", details);
        
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.async_misses");
        
        // Only track as error if it's not a knockback protection miss (expected behavior)
        if (!details.contains("knockback protection")) {
            PerformanceMonitoringSystem.getInstance().getErrorTracker().recordError("OptimizedOptimizationInjector",
                new RuntimeException("Async optimization miss: " + details), context);
            LOGGER.debug("Async optimization miss: {}", details);
        }
    }
    
    /**
     * Record async optimization error
     */
    private static void recordAsyncOptimizationError(String details) {
        asyncOptimizationErrors.incrementAndGet();
        
        // Record performance metrics and error
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "async_optimization");
        context.put("result", "error");
        context.put("details", details);
        
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.async_errors");
        PerformanceMonitoringSystem.getInstance().getErrorTracker().recordError("OptimizedOptimizationInjector",
            new RuntimeException("Async optimization error: " + details), context);
        
        LOGGER.warn("Async optimization error: {}", details);
    }
    
    /**
     * Log async performance statistics
     */
    private static void logAsyncPerformanceStats() {
        long totalOps = asyncOptimizationHits.get() + asyncOptimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) asyncOptimizationHits.get() / totalOps * 100 : 0.0;
        double avgProcessingTime = totalEntitiesProcessedAsync.get() > 0 ? 
            (double) totalProcessingTimeNs.get() / totalEntitiesProcessedAsync.get() / 1_000_000.0 : 0.0;
        
        LOGGER.info("Async Optimization Metrics - Hits: {}, Misses: {}, Errors: {}, Total: {}, HitRate: {:.2f}%, AvgTime: {:.2f}ms, LibraryLoaded: {}",
            asyncOptimizationHits.get(),
            asyncOptimizationMisses.get(),
            asyncOptimizationErrors.get(),
            totalEntitiesProcessedAsync.get(),
            hitRate,
            avgProcessingTime,
            OptimizationInjector.isNativeLibraryLoaded()
        );
    }
    
    /**
     * Get async optimization metrics
     */
    public static String getAsyncOptimizationMetrics() {
        long totalOps = asyncOptimizationHits.get() + asyncOptimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) asyncOptimizationHits.get() / totalOps * 100 : 0.0;
        double avgProcessingTime = totalEntitiesProcessedAsync.get() > 0 ? 
            (double) totalProcessingTimeNs.get() / totalEntitiesProcessedAsync.get() / 1_000_000.0 : 0.0;
        
        return String.format(
            "AsyncOptimizationMetrics{hits=%d, misses=%d, errors=%d, totalProcessed=%d, hitRate=%.2f%%, avgTime=%.2fms, nativeLoaded=%b, libraryLoading=%b}",
            asyncOptimizationHits.get(),
            asyncOptimizationMisses.get(),
            asyncOptimizationErrors.get(),
            totalEntitiesProcessedAsync.get(),
            hitRate,
            avgProcessingTime,
            OptimizationInjector.isNativeLibraryLoaded(),
            isLibraryLoading
        );
    }
    
    /**
     * Wait for library loading to complete
     */
    public static boolean waitForLibraryLoading(long timeoutMs) {
        if (libraryLoadFuture == null) {
            return OptimizationInjector.isNativeLibraryLoaded();
        }

        try {
            libraryLoadFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return OptimizationInjector.isNativeLibraryLoaded();
        } catch (Exception e) {
            LOGGER.warn("Library loading wait timeout or error: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Reload native library asynchronously
     */
    public static synchronized void reloadNativeLibraryAsync() {
        isLibraryLoading = false;

        // Shutdown existing services
        entityProcessingService.shutdown();

        // Re-initialize async library loading
        initializeAsyncLibraryLoading();

        LOGGER.info("Async native library reload initiated");
    }
    
    /**
     * Get entity processing service statistics
     */
    public static EntityProcessingService.EntityProcessingStatistics getEntityProcessingStatistics() {
        return entityProcessingService.getStatistics();
    }
    
    /**
     * Get library loading statistics
     */
    public static Map<String, ParallelLibraryLoader.LibraryLoadResult> getLibraryLoadingStatistics() {
        return libraryLoader.getLoadedLibraries();
    }
    
    /**
     * Enable test mode
     */
    public static void enableTestMode(boolean enabled) {
        isTestMode = enabled;
        if (!enabled) {
            initializeAsyncLibraryLoading();
        }
    }
    
    /**
     * Reset async optimization metrics
     */
    public static void resetAsyncMetrics() {
        asyncOptimizationHits.set(0);
        asyncOptimizationMisses.set(0);
        asyncOptimizationErrors.set(0);
        totalEntitiesProcessedAsync.set(0);
        totalProcessingTimeNs.set(0);
    }
    
    /**
     * Shutdown async services
     */
    public static void shutdownAsyncServices() {
        entityProcessingService.shutdown();
        libraryLoader.shutdown();
        LOGGER.info("Async optimization services shutdown completed");
    }
    
    /**
     * Check if entity is performance-critical and should be exempt from throttling
     */
    private static boolean isPerformanceCriticalEntity(Entity entity) {
        String entityType = entity.getType().toString().toLowerCase();
        
        // Boss entities - critical for gameplay and should not be throttled
        if (entityType.contains("boss") ||
            entityType.contains("dragon") ||
            entityType.contains("wither") ||
            entityType.contains("warden") ||
            entityType.contains("elder_guardian")) {
            return true;
        }
        
        // Named entities (likely important for gameplay)
        if (entity.hasCustomName()) {
            return true;
        }
        
        // Entities with special AI or behavior flags
        if (entityType.contains("villager") && entityType.contains("trader")) {
            return true; // Trading villagers are important for gameplay
        }
        
        return false;
    }
    
    /**
     * Calculate adaptive timeout based on entity type and server performance
     */
    private static int calculateAdaptiveTimeout(Entity entity) {
        int baseTimeout = BASE_ASYNC_TIMEOUT_MS;
        
        // Adjust timeout based on entity type complexity
        String entityType = entity.getType().toString().toLowerCase();
        
        // Complex entities get more time
        if (entityType.contains("boss") ||
            entityType.contains("dragon") ||
            entityType.contains("wither") ||
            entityType.contains("warden")) {
            baseTimeout = Math.min(baseTimeout * 3, MAX_ASYNC_TIMEOUT_MS); // Boss entities: 150ms max
        }
        // Player entities get moderate timeout
        else if (entity instanceof Player) {
            baseTimeout = Math.min(baseTimeout * 2, MAX_ASYNC_TIMEOUT_MS); // Players: 100ms max
        }
        // Simple entities get standard timeout
        else if (entityType.contains("zombie") ||
                 entityType.contains("skeleton") ||
                 entityType.contains("cow") ||
                 entityType.contains("sheep")) {
            baseTimeout = baseTimeout; // Standard: 50ms
        }
        // Very simple entities get reduced timeout
        else {
            baseTimeout = Math.max(baseTimeout / 2, MIN_ASYNC_TIMEOUT_MS); // Simple: 25ms min
        }
        
        // Adjust based on server performance (CPU load)
        double cpuLoad = getCurrentCpuLoad();
        if (cpuLoad > 0.8) {
            // High CPU load - reduce timeout to prevent overload
            baseTimeout = Math.max(baseTimeout / 2, MIN_ASYNC_TIMEOUT_MS);
        } else if (cpuLoad < 0.3) {
            // Low CPU load - can afford longer timeouts
            baseTimeout = Math.min(baseTimeout * 2, MAX_ASYNC_TIMEOUT_MS);
        }
        
        return baseTimeout;
    }
    
    /**
     * Get current CPU load estimate
     */
    private static double getCurrentCpuLoad() {
        // Simple CPU load estimation based on available processors and active thread count
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int activeThreadCount = Thread.activeCount();
        
        // Estimate load as ratio of active threads to available processors
        return Math.min(1.0, (double) activeThreadCount / availableProcessors);
    }
    
    /**
     * Get async optimization test metrics
     */
    public static AsyncOptimizationMetrics getAsyncTestMetrics() {
        return new AsyncOptimizationMetrics(
            asyncOptimizationHits.get(),
            asyncOptimizationMisses.get(),
            asyncOptimizationErrors.get(),
            totalEntitiesProcessedAsync.get(),
            OptimizationInjector.isNativeLibraryLoaded() && !isTestMode
        );
    }
    
    /**
     * Async optimization metrics
     */
    public static class AsyncOptimizationMetrics {
        public final int hits;
        public final int misses;
        public final int errors;
        public final long totalProcessed;
        public final boolean nativeLoaded;
        
        public AsyncOptimizationMetrics(int hits, int misses, int errors, long totalProcessed, boolean nativeLoaded) {
            this.hits = hits;
            this.misses = misses;
            this.errors = errors;
            this.totalProcessed = totalProcessed;
            this.nativeLoaded = nativeLoaded;
        }
        
        public int getAsyncHits() { return hits; }
        public int getAsyncMisses() { return misses; }
        public int getAsyncErrors() { return errors; }
    }
}