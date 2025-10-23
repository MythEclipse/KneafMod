package com.kneaf.core;

import com.kneaf.core.performance.PerformanceMonitoringSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized entity processing injector with async processing, parallel library loading,
 * and hash-based entity type lookup for maximum performance.
 *
 * MOD COMPATIBILITY POLICY:
 * This optimization system ONLY applies to:
 * - Vanilla Minecraft entities (net.minecraft.world.entity.*)
 * - Kneaf Mod custom entities (com.kneaf.entities.*)
 * 
 * Entities from other mods are NEVER touched to ensure full compatibility.
 * This whitelist approach prevents conflicts with other mods' custom entity behaviors.
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
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizedOptimizationInjector.class);
    
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
    
    // Library loading state
    private static volatile boolean isLibraryLoading = false;
    private static CompletableFuture<ParallelLibraryLoader.LibraryLoadResult> libraryLoadFuture;
    
    // Configuration
    private static final int BASE_ASYNC_TIMEOUT_MS = 50; // Base timeout for async processing
    private static final int MAX_CONCURRENT_ENTITIES = 1000;
    private static final int MIN_ASYNC_TIMEOUT_MS = 25;   // Minimum timeout for critical entities
    private static final int MAX_ASYNC_TIMEOUT_MS = 200;  // Maximum timeout for complex entities
    
    // Knockback protection
    private static final Map<Integer, Integer> recentlyDamagedEntities = new HashMap<>();
    private static final int KNOCKBACK_PROTECTION_TICKS = 6;
    
    // Server Instance
    private static MinecraftServer serverInstance;
    
    // CPU Load Bean
    private static final OperatingSystemMXBean OS_BEAN = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    
    static {
        initializeAsyncLibraryLoading();
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
        
        LOGGER.trace("Entity {} marked for knockback protection after taking damage",
            event.getEntity().getId());
    }
    
    /**
     * Async entity tick handler - non-blocking main thread integration
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        long startTime = System.nanoTime();
        
        try {
            Entity entity = event.getEntity();
            if (entity == null) {
                return;
            }
            
            // Skip processing if throttling is disabled
            if (!performanceManager.isEntityThrottlingEnabled()) {
                return;
            }
            
            // --- Real Entity Validation ---
            if (entity.level().isClientSide() || entity.isRemoved()) {
                return;
            }
            
            // COMPATIBILITY CHECK: Only optimize vanilla and our mod entities
            if (!isEntityOptimizable(entity)) {
                return; // Skip other mod entities for compatibility
            }

            // Check for knockback protection
            Integer protectionTicks = recentlyDamagedEntities.get(entity.getId());
            if (protectionTicks != null) {
                if (protectionTicks > 0) {
                    recentlyDamagedEntities.put(entity.getId(), protectionTicks - 1);
                    return; // Skip async processing this tick
                } else {
                    recentlyDamagedEntities.remove(entity.getId()); // Protection expired
                }
            }
            // --- End Validation ---
            
            // Check if library is ready
            if (!OptimizationInjector.isNativeLibraryLoaded()) {
                return;
            }
            
            // Extract physics data
            EntityProcessingService.EntityPhysicsData physicsData = extractPhysicsData(entity);
            if (physicsData == null) {
                return;
            }
            
            // Calculate adaptive timeout
            int adaptiveTimeoutMs = calculateAdaptiveTimeout(entity);
            
            // Submit for async processing with adaptive timeout
            CompletableFuture<EntityProcessingService.EntityProcessingResult> future =
                entityProcessingService.processEntityAsync(entity, physicsData);
            
            // Handle result asynchronously (non-blocking)
            future.thenAccept(result -> {
                long processingTime = System.nanoTime() - startTime;
                if (result.success) {
                    applyPhysicsResult(entity, result);
                    // Record successful processing metrics silently
                    Map<String, Object> context = new HashMap<>();
                    context.put("operation", "entity_processing");
                    context.put("result", "success");
                    PerformanceMonitoringSystem.getInstance().recordEvent(
                        "OptimizedOptimizationInjector", "entity_process", processingTime, context);
                } else {
                    recordAsyncOptimizationMiss("Async processing failed: " + result.message);
                    
                    // Record failed processing metrics
                    Map<String, Object> errorContext = new HashMap<>();
                    errorContext.put("operation", "entity_processing");
                    errorContext.put("result", "failure");
                    errorContext.put("reason", result.message);
                    PerformanceMonitoringSystem.getInstance().recordError(
                        "OptimizedOptimizationInjector",
                        new RuntimeException("Entity processing failed: " + result.message), errorContext);
                }
            }).exceptionally(throwable -> {
                long processingTime = System.nanoTime() - startTime;
                recordAsyncOptimizationError("Async processing error: " + throwable.getMessage());
                
                // Record error metrics
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("operation", "entity_processing");
                errorContext.put("result", "error");
                errorContext.put("error_type", throwable.getClass().getSimpleName());
                PerformanceMonitoringSystem.getInstance().recordError(
                    "OptimizedOptimizationInjector", throwable, errorContext);
                
                return null;
            });
            
            // Timeout handling with adaptive timeout
            future.orTimeout(adaptiveTimeoutMs, TimeUnit.MILLISECONDS).exceptionally(throwable -> {
                // Fallback to synchronous processing for timeout
                performSynchronousFallback(entity, physicsData);
                return null;
            });
            
        } catch (Exception e) {
            // Record unexpected error
            Map<String, Object> errorContext = new HashMap<>();
            errorContext.put("operation", "entity_tick");
            errorContext.put("error_type", "unexpected");
            PerformanceMonitoringSystem.getInstance().recordError(
                "OptimizedOptimizationInjector", e, errorContext);
            
            LOGGER.error("Unexpected error in entity tick handler: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Check if entity should be optimized for compatibility with other mods
     * Only vanilla and Kneaf mod entities are optimized
     * Items are excluded as they often have custom behaviors from other mods
     * @param entity Entity to check
     * @return true if entity can be optimized
     */
    private static boolean isEntityOptimizable(Entity entity) {
        if (entity == null) return false;
        
        try {
            String entityClassName = entity.getClass().getName();
            
            // EXCLUDE ITEMS - they often have custom behaviors from mods
            if (entityClassName.contains(".item.") || 
                entityClassName.contains("ItemEntity") ||
                entityClassName.contains("ItemFrame") ||
                entityClassName.contains("ItemStack")) {
                return false; // Never optimize items
            }
            
            // WHITELIST APPROACH FOR MOD COMPATIBILITY:
            // Only optimize vanilla Minecraft entities and our custom mod entities
            // This ensures compatibility by not touching other mods' entities
            
            boolean isVanillaMinecraftEntity = 
                entityClassName.startsWith("net.minecraft.world.entity.") ||
                entityClassName.startsWith("net.minecraft.client.player.") ||
                entityClassName.startsWith("net.minecraft.server.level.");
            
            boolean isKneafModEntity = 
                entityClassName.startsWith("com.kneaf.entities.");
            
            // Only allow vanilla or our mod's entities
            if (!isVanillaMinecraftEntity && !isKneafModEntity) {
                return false; // Skip other mod entities
            }
            
            // Additional safety: Check inheritance hierarchy for other mod classes
            if (isVanillaMinecraftEntity) {
                Class<?> entityClass = entity.getClass();
                while (entityClass != null && !entityClass.getName().equals("java.lang.Object")) {
                    String className = entityClass.getName();
                    // If we find a class from another mod, skip it
                    if (!className.startsWith("net.minecraft.") && 
                        !className.startsWith("com.kneaf.") && 
                        !className.startsWith("java.")) {
                        return false; // From another mod
                    }
                    entityClass = entityClass.getSuperclass();
                }
            }
            
            return true;
        } catch (Exception e) {
            return false; // Skip on any error
        }
    }
    
    /**
     * Extract physics data from entity
     */
    private static EntityProcessingService.EntityPhysicsData extractPhysicsData(Entity entity) {
        try {
            Vec3 deltaMovement = entity.getDeltaMovement();
            double x = deltaMovement.x();
            double y = deltaMovement.y();
            double z = deltaMovement.z();
            
            // Validate motion values
            if (Double.isNaN(x) || Double.isInfinite(x) ||
                Double.isNaN(y) || Double.isInfinite(y) ||
                Double.isNaN(z) || Double.isInfinite(z)) {
                LOGGER.warn("Invalid delta movement for entity {}: [{}, {}, {}]", entity.getId(), x, y, z);
                return null;
            }
            
            return new EntityProcessingService.EntityPhysicsData(x, y, z);
        } catch (Exception e) {
            LOGGER.error("Error extracting physics data for entity {}: {}", entity.getId(), e.getMessage(), e);
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
            if (Double.isNaN(data.motionX) || Double.isInfinite(data.motionX) ||
                Double.isNaN(data.motionY) || Double.isInfinite(data.motionY) ||
                Double.isNaN(data.motionZ) || Double.isInfinite(data.motionZ)) {
                LOGGER.warn("Invalid processed physics data from async service for entity {}: [{}, {}, {}]",
                            entity.getId(), data.motionX, data.motionY, data.motionZ);
                return;
            }
            
            entity.setDeltaMovement(data.motionX, data.motionY, data.motionZ);
            
            totalEntitiesProcessedAsync.incrementAndGet();
            
        } catch (Exception e) {
            LOGGER.error("Error applying physics result to entity {}: {}", entity.getId(), e.getMessage(), e);
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
            
            double verticalDamping = 0.015; // Standard damping for vertical movement only
            
            double processedY = physicsData.motionY;
            
            // NOTE: Original logic `if (Math.abs(physicsData.motionY - physicsData.motionY) > 0.5)` is always false.
            // Preserving original logic as requested.
            if (Math.abs(physicsData.motionY - physicsData.motionY) > 0.5) {
                // Significant external effect detected
                processedY = physicsData.motionY;
            } else if (physicsData.motionY < -0.1) {
                // Natural falling
                processedY = Math.min(physicsData.motionY * 1.1, physicsData.motionY);
            }
            
            double newX = physicsData.motionX * dampingFactor;
            double newY = processedY * (1 - verticalDamping);
            double newZ = physicsData.motionZ * dampingFactor;

            // Apply physics with fallback
            if (Double.isNaN(newX) || Double.isInfinite(newX) ||
                Double.isNaN(newY) || Double.isInfinite(newY) ||
                Double.isNaN(newZ) || Double.isInfinite(newZ)) {
                LOGGER.warn("Invalid fallback physics data for entity {}: [{}, {}, {}]", entity.getId(), newX, newY, newZ);
                return;
            }

            entity.setDeltaMovement(newX, newY, newZ);
            
        } catch (Exception e) {
            recordAsyncOptimizationMiss("Synchronous fallback failed: " + e.getMessage());
        }
    }
    
    /**
     * Server tick handler - async processing statistics
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (serverInstance == null) {
            serverInstance = event.getServer(); // Cache server instance
        }

        if (performanceManager.isEntityThrottlingEnabled()) {
            try {
                // Silent success - no logging
                PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.server_tick");

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
                // Silent success - no logging, just metrics
                PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.level_tick");
                
            } catch (Exception e) {
                recordAsyncOptimizationError("Level tick async processing failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Record async optimization hit - silently without logging
     */
    private static void recordAsyncOptimizationHit(String details) {
        asyncOptimizationHits.incrementAndGet();
        // No logging for successful operations
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "async_optimization");
        context.put("result", "hit");
        context.put("details", details);
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.async_hits");
    }
    
    /**
     * Record async optimization miss - only log if it's a real error
     */
    private static void recordAsyncOptimizationMiss(String details) {
        asyncOptimizationMisses.incrementAndGet();
        
        Map<String, Object> context = new HashMap<>();
        context.put("operation", "async_optimization");
        context.put("result", "miss");
        context.put("details", details);
        
        PerformanceMonitoringSystem.getInstance().getMetricAggregator().incrementCounter("optimization_injector.async_misses");
        
        // Only log errors, not normal misses like client-side entities or throttling
        if (details.contains("error") || details.contains("failed") || details.contains("exception")) {
            PerformanceMonitoringSystem.getInstance().getErrorTracker().recordError("OptimizedOptimizationInjector",
                new RuntimeException("Async optimization miss: " + details), context);
        }
    }
    
    /**
     * Record async optimization error
     */
    private static void recordAsyncOptimizationError(String details) {
        asyncOptimizationErrors.incrementAndGet();
        
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
            baseTimeout = Math.min(baseTimeout * 3, MAX_ASYNC_TIMEOUT_MS);
        }
        // Player entities get moderate timeout
        else if (entityType.contains("player")) {
            baseTimeout = Math.min(baseTimeout * 2, MAX_ASYNC_TIMEOUT_MS);
        }
        // Simple entities get standard timeout
        else if (entityType.contains("zombie") ||
                 entityType.contains("skeleton") ||
                 entityType.contains("cow") ||
                 entityType.contains("sheep")) {
            // Standard: 50ms
        }
        // Very simple entities get reduced timeout
        else {
            baseTimeout = Math.max(baseTimeout / 2, MIN_ASYNC_TIMEOUT_MS);
        }
        
        // Adjust based on server performance (CPU load)
        double cpuLoad = getCurrentCpuLoad();
        if (cpuLoad > 0.8) {
            baseTimeout = Math.max(baseTimeout / 2, MIN_ASYNC_TIMEOUT_MS);
        } else if (cpuLoad < 0.3) {
            baseTimeout = Math.min(baseTimeout * 2, MAX_ASYNC_TIMEOUT_MS);
        }
        
        return baseTimeout;
    }
    
    /**
     * Get current CPU load estimate
     */
    private static double getCurrentCpuLoad() {
        if (OS_BEAN != null) {
            double load = OS_BEAN.getCpuLoad(); // Returns 0.0-1.0
            if (load >= 0) {
                return load;
            }
        }
        
        // Fallback to the original (less accurate) method
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int activeThreadCount = Thread.activeCount();
        
        // Estimate load as ratio of active threads to available processors
        // Adjusted heuristic to be slightly more realistic than original
        return Math.min(1.0, (double) activeThreadCount / (availableProcessors * 2.0));
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
            OptimizationInjector.isNativeLibraryLoaded()
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